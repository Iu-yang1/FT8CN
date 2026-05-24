module wsjtx3_bridge
  use iso_c_binding
  use ft8_decode, only: ft8_decoder
  use ft4_decode, only: ft4_decoder
  use q65_decode, only: q65_decoder
  use prog_args, only: temp_dir, data_dir
  implicit none

  integer, parameter :: WSJTX3_MAX_CONTEXTS = 4
  integer, parameter :: WSJTX3_MAX_RESULTS = 100
  integer, parameter :: WSJTX3_MODE_FT8 = 0
  integer, parameter :: WSJTX3_MODE_FT4 = 1
  integer, parameter :: WSJTX3_MODE_Q65 = 2
  integer, parameter :: FT8_BRIDGE_NPTS = 15 * 12000
  integer, parameter :: FT4_BRIDGE_NMAX = 21 * 3456
  integer, parameter :: Q65_BRIDGE_NMAX = 300 * 12000
  integer, parameter :: FT8_PHASE_EARLY = 41
  integer, parameter :: FT8_PHASE_LATE = 47
  integer, parameter :: FT8_PHASE_FULL = 50
  integer, parameter :: FTX_DECODE_MIN_HZ = 0
  integer, parameter :: FT8_DECODE_MAX_HZ = 3000
  integer, parameter :: FT4_DECODE_MAX_HZ = 3000
  integer, parameter :: Q65_DECODE_MAX_HZ = 5000
  integer, parameter :: Q65_DEFAULT_TR_PERIOD = 60
  integer, parameter :: Q65_DEFAULT_SUBMODE = 0

  type :: wsjtx3_result_t
     real(c_float) :: sync = 0.0
     integer(c_int) :: snr = 0
     real(c_float) :: dt = 0.0
     real(c_float) :: freq = 0.0
     character(len=37) :: decoded = ' '
     integer(c_int) :: nap = 0
     real(c_float) :: qual = 0.0
  end type wsjtx3_result_t

  type, bind(C) :: wsjtx3_bridge_result_c
     integer(c_int) :: snr
     integer(c_int) :: nap
     real(c_float) :: sync
     real(c_float) :: dt
     real(c_float) :: freq
     real(c_float) :: qual
     character(kind=c_char) :: decoded(38)
  end type wsjtx3_bridge_result_c

  type :: wsjtx3_context_t
     logical :: active = .false.
     integer(c_int) :: mode = WSJTX3_MODE_FT8
     integer(c_int) :: sample_rate = 12000
     integer(c_int) :: expected_samples = 0
     integer(c_long_long) :: utc_time = 0
     integer(c_int) :: decode_pass_count = 3
     integer(c_int) :: multi_decode_round_count = 3
     integer(c_int) :: qso_freq_sensitivity = 1
     integer(c_int) :: decode_sensitivity = 1
     integer(c_int) :: enable_early_decode = 1
     integer(c_int) :: enable_wideband_dx_search = 1
     integer(c_int) :: ldpc_iterations = 20
     integer(c_int) :: qso_frequency_hz = 1000
     integer(c_int) :: tx_frequency_hz = 1000
     integer(c_int) :: q65_submode = Q65_DEFAULT_SUBMODE
     integer(c_int) :: q65_tr_period = Q65_DEFAULT_TR_PERIOD
     character(len=12) :: my_call = ''
     character(len=12) :: his_call = ''
     character(len=6) :: his_grid = ''
     integer(c_int) :: result_count = 0
     type(wsjtx3_result_t) :: results(WSJTX3_MAX_RESULTS)
  end type wsjtx3_context_t

  type(wsjtx3_context_t), save :: g_contexts(WSJTX3_MAX_CONTEXTS)
  type(ft8_decoder), save :: g_ft8_decoders(WSJTX3_MAX_CONTEXTS)
  type(ft4_decoder), save :: g_ft4_decoders(WSJTX3_MAX_CONTEXTS)
  type(q65_decoder), save :: g_q65_decoders(WSJTX3_MAX_CONTEXTS)
  integer, save :: g_active_context = 0

  interface
     subroutine genq65(msg0, ichk, msgsent, itone, i3, n3)
       character(len=37), intent(in) :: msg0
       integer, intent(in) :: ichk
       character(len=37), intent(out) :: msgsent
       integer, intent(out) :: itone(85)
       integer, intent(out) :: i3
       integer, intent(out) :: n3
     end subroutine genq65

     subroutine genwave(itone, nsym, nsps, nwave, fsample, tonespacing, f0, icmplx, cwave, wave)
       integer, intent(in) :: nsym
       integer, intent(in) :: nsps
       integer, intent(in) :: nwave
       integer, intent(in) :: icmplx
       integer, intent(in) :: itone(nsym)
       real, intent(in) :: fsample
       real(8), intent(in) :: tonespacing
       real, intent(in) :: f0
       complex, intent(out) :: cwave(nwave)
       real, intent(out) :: wave(nwave)
     end subroutine genwave
  end interface

contains

  logical function context_valid(handle)
    integer(c_int), intent(in) :: handle
    if (handle < 1 .or. handle > WSJTX3_MAX_CONTEXTS) then
       context_valid = .false.
       return
    end if
    context_valid = g_contexts(handle)%active
  end function context_valid

  logical function context_is_ft8(context)
    type(wsjtx3_context_t), intent(in) :: context
    context_is_ft8 = context%mode == WSJTX3_MODE_FT8
  end function context_is_ft8

  logical function context_is_ft4(context)
    type(wsjtx3_context_t), intent(in) :: context
    context_is_ft4 = context%mode == WSJTX3_MODE_FT4
  end function context_is_ft4

  logical function context_is_q65(context)
    type(wsjtx3_context_t), intent(in) :: context
    context_is_q65 = context%mode == WSJTX3_MODE_Q65
  end function context_is_q65

  integer(c_int) function mode_max_samples(context)
    type(wsjtx3_context_t), intent(in) :: context
    if (context_is_q65(context)) then
       mode_max_samples = min(max(context%expected_samples, context%q65_tr_period * 12000), Q65_BRIDGE_NMAX)
    else if (context_is_ft4(context)) then
       mode_max_samples = FT4_BRIDGE_NMAX
    else
       mode_max_samples = FT8_BRIDGE_NPTS
    end if
  end function mode_max_samples

  integer(c_int) function mode_max_frequency(context)
    type(wsjtx3_context_t), intent(in) :: context
    if (context_is_q65(context)) then
       mode_max_frequency = Q65_DECODE_MAX_HZ
    else if (context_is_ft4(context)) then
       mode_max_frequency = FT4_DECODE_MAX_HZ
    else
       mode_max_frequency = FT8_DECODE_MAX_HZ
    end if
  end function mode_max_frequency

  subroutine reset_context_results(handle)
    integer(c_int), intent(in) :: handle
    integer :: index
    if (.not. context_valid(handle)) then
       return
    end if
    g_contexts(handle)%result_count = 0
    do index = 1, WSJTX3_MAX_RESULTS
       g_contexts(handle)%results(index)%sync = 0.0
       g_contexts(handle)%results(index)%snr = 0
       g_contexts(handle)%results(index)%dt = 0.0
       g_contexts(handle)%results(index)%freq = 0.0
       g_contexts(handle)%results(index)%decoded = ' '
       g_contexts(handle)%results(index)%nap = 0
       g_contexts(handle)%results(index)%qual = 0.0
    end do
  end subroutine reset_context_results

  subroutine clear_context(handle)
    integer(c_int), intent(in) :: handle
    if (handle < 1 .or. handle > WSJTX3_MAX_CONTEXTS) then
       return
    end if
    g_contexts(handle)%active = .false.
    g_contexts(handle)%mode = WSJTX3_MODE_FT8
    g_contexts(handle)%sample_rate = 12000
    g_contexts(handle)%expected_samples = 0
    g_contexts(handle)%utc_time = 0
    g_contexts(handle)%decode_pass_count = 3
    g_contexts(handle)%multi_decode_round_count = 3
    g_contexts(handle)%qso_freq_sensitivity = 1
    g_contexts(handle)%decode_sensitivity = 1
    g_contexts(handle)%enable_early_decode = 1
    g_contexts(handle)%enable_wideband_dx_search = 1
    g_contexts(handle)%ldpc_iterations = 20
    g_contexts(handle)%qso_frequency_hz = 1000
    g_contexts(handle)%tx_frequency_hz = 1000
    g_contexts(handle)%q65_submode = Q65_DEFAULT_SUBMODE
    g_contexts(handle)%q65_tr_period = Q65_DEFAULT_TR_PERIOD
    g_contexts(handle)%my_call = ''
    g_contexts(handle)%his_call = ''
    g_contexts(handle)%his_grid = ''
    call reset_context_results(handle)
  end subroutine clear_context

  integer(c_int) function clamp_int(value, lower_bound, upper_bound)
    integer(c_int), intent(in) :: value
    integer(c_int), intent(in) :: lower_bound
    integer(c_int), intent(in) :: upper_bound
    clamp_int = min(max(value, lower_bound), upper_bound)
  end function clamp_int

  integer(c_int) function utc_millis_to_hhmmss(utc_time)
    integer(c_long_long), intent(in) :: utc_time
    integer(c_long_long) :: day_millis
    integer(c_int) :: total_seconds
    integer(c_int) :: hours
    integer(c_int) :: minutes
    integer(c_int) :: seconds
    day_millis = modulo(utc_time, 86400000_c_long_long)
    if (day_millis < 0_c_long_long) then
       day_millis = day_millis + 86400000_c_long_long
    end if
    total_seconds = int(day_millis / 1000_c_long_long, kind=c_int)
    hours = total_seconds / 3600
    minutes = mod(total_seconds, 3600) / 60
    seconds = mod(total_seconds, 60)
    utc_millis_to_hhmmss = hours * 10000 + minutes * 100 + seconds
  end function utc_millis_to_hhmmss

  integer(c_int) function qso_progress_from_context(context)
    type(wsjtx3_context_t), intent(in) :: context
    if (len_trim(context%his_call) >= 3 .and. len_trim(context%his_grid) >= 4) then
       qso_progress_from_context = 3
    elseif (len_trim(context%his_call) >= 3) then
       qso_progress_from_context = 1
    else
       qso_progress_from_context = 0
    end if
  end function qso_progress_from_context

  integer(c_int) function napwid_from_context(context)
    type(wsjtx3_context_t), intent(in) :: context
    select case (context%qso_freq_sensitivity)
    case (0)
       napwid_from_context = 24
    case (2)
       napwid_from_context = 52
    case default
       napwid_from_context = 35
    end select
  end function napwid_from_context

  integer(c_int) function ndepth_from_context(context, nagain)
    type(wsjtx3_context_t), intent(in) :: context
    logical, intent(in) :: nagain
    integer(c_int) :: depth
    depth = 1
    if (context%decode_pass_count > 1 .or. context%multi_decode_round_count > 1) then
       depth = 2
    end if
    if (context%decode_sensitivity == 2 .and. depth < 2) then
       depth = 2
    end if
    if (context%decode_sensitivity == 0 .and. depth > 1 .and. context%ldpc_iterations <= 20) then
       depth = depth - 1
    end if
    if (nagain .and. context%ldpc_iterations > 20) then
        depth = 3
    end if
    ndepth_from_context = clamp_int(depth, 1_c_int, 3_c_int)
  end function ndepth_from_context

  integer(c_int) function ft8_phase_ndepth_from_context(context, phase, nagain)
    type(wsjtx3_context_t), intent(in) :: context
    integer(c_int), intent(in) :: phase
    logical, intent(in) :: nagain

    ! early / late 预解只负责抢时隙和给 full slot 预热状态，
    ! 这里不能直接跟着外层 pass/round 提升到深层 ndepth=2/3，
    ! 否则官方 FT8 样本会在 partial-slot 阶段把 full slot 结果污染到 0 条。
    if (.not. nagain .and. phase /= FT8_PHASE_FULL) then
       ft8_phase_ndepth_from_context = 1_c_int
       return
    end if

    ft8_phase_ndepth_from_context = ndepth_from_context(context, nagain)
  end function ft8_phase_ndepth_from_context

  integer(c_int) function ft4_ndepth_from_context(context, sample_count)
    type(wsjtx3_context_t), intent(in) :: context
    integer(c_int), intent(in) :: sample_count
    integer(c_int) :: full_samples
    integer(c_int) :: depth

    full_samples = FT4_BRIDGE_NMAX
    if (context%expected_samples > 0) then
       full_samples = min(full_samples, context%expected_samples)
    end if

    if (sample_count < full_samples) then
       ft4_ndepth_from_context = 1_c_int
       return
    end if

    ! FT4 鐨勫畼鏂?ndepth 瑕佸敖閲忓拰鍓嶇 pass / round 璇箟瑙ｅ:
    ! 1. decode_pass_count 涓昏鍐冲畾鍗曟 official decode 鐨勬繁搴︽。浣?
    ! 2. multi_decode_round_count 鐢卞灞?C session 鎺у埗 AP / follow-up 杞锛?
    !    閬垮厤 FT4 鍙堝洖鍒?round count 澶ч儴鍒嗗彧鏄槧灏?ndepth 鐨勬棫鐘舵€併€?
    depth = 1_c_int

    if (context%decode_pass_count >= 2_c_int) then
       depth = 2_c_int
    end if
    if (context%decode_sensitivity == 2_c_int .and. depth < 2_c_int) then
       depth = 2_c_int
    end if
    if (context%decode_sensitivity == 0_c_int .and. context%ldpc_iterations <= 20_c_int) then
       depth = 1_c_int
    end if

    if (context%decode_pass_count >= 3_c_int .and. context%ldpc_iterations > 20_c_int) then
       depth = 3_c_int
    end if

    ft4_ndepth_from_context = clamp_int(depth, 1_c_int, 3_c_int)
  end function ft4_ndepth_from_context

  integer(c_int) function q65_ndepth_from_context(context)
    type(wsjtx3_context_t), intent(in) :: context
    integer(c_int) :: depth

    depth = 1_c_int
    if (context%decode_pass_count >= 2_c_int .or. context%decode_sensitivity >= 2_c_int) then
       depth = 2_c_int
    end if
    if (context%decode_pass_count >= 3_c_int .and. context%ldpc_iterations > 20_c_int) then
       depth = 3_c_int
    end if
    q65_ndepth_from_context = clamp_int(depth, 1_c_int, 3_c_int)
  end function q65_ndepth_from_context

  integer(c_int) function q65_ntol_from_context(context)
    type(wsjtx3_context_t), intent(in) :: context

    select case (context%qso_freq_sensitivity)
    case (0)
       q65_ntol_from_context = 2500_c_int
    case (2)
       q65_ntol_from_context = 1000_c_int
    case default
       q65_ntol_from_context = 2500_c_int
    end select
  end function q65_ntol_from_context

  integer(c_int) function q65_base_nsps_for_period_12k(tr_period)
    integer(c_int), intent(in) :: tr_period

    select case (tr_period)
    case (15_c_int)
       q65_base_nsps_for_period_12k = 1800_c_int
    case (30_c_int)
       q65_base_nsps_for_period_12k = 3600_c_int
    case (60_c_int)
       q65_base_nsps_for_period_12k = 7200_c_int
    case (120_c_int)
       q65_base_nsps_for_period_12k = 16000_c_int
    case (300_c_int)
       q65_base_nsps_for_period_12k = 41472_c_int
    case default
       q65_base_nsps_for_period_12k = 0_c_int
    end select
  end function q65_base_nsps_for_period_12k

  integer(c_int) function q65_mode_factor_from_submode(q65_submode)
    integer(c_int), intent(in) :: q65_submode

    if (q65_submode < 0_c_int .or. q65_submode > 5_c_int) then
       q65_mode_factor_from_submode = 0_c_int
       return
    end if
    q65_mode_factor_from_submode = 2_c_int ** q65_submode
  end function q65_mode_factor_from_submode

  real function q65_emedelay_from_context(context)
    type(wsjtx3_context_t), intent(in) :: context

    q65_emedelay_from_context = 0.0
    if (context%q65_tr_period == 60_c_int) then
       q65_emedelay_from_context = 2.5
    end if
  end function q65_emedelay_from_context

  integer(c_int) function ft8_phase_from_context(context, sample_count)
    type(wsjtx3_context_t), intent(in) :: context
    integer(c_int), intent(in) :: sample_count
    integer(c_int) :: full_samples
    integer(c_int) :: late_samples
    integer(c_int) :: early_samples
    full_samples = FT8_BRIDGE_NPTS
    if (context%expected_samples > 0) then
       full_samples = min(full_samples, context%expected_samples)
    end if
    late_samples = (full_samples * FT8_PHASE_LATE) / FT8_PHASE_FULL
    early_samples = (full_samples * FT8_PHASE_EARLY) / FT8_PHASE_FULL
    if (sample_count >= full_samples) then
       ft8_phase_from_context = FT8_PHASE_FULL
    elseif (context%enable_early_decode == 0) then
       ft8_phase_from_context = 0
    elseif (sample_count >= late_samples) then
       ft8_phase_from_context = FT8_PHASE_LATE
    elseif (sample_count >= early_samples) then
       ft8_phase_from_context = FT8_PHASE_EARLY
    else
       ft8_phase_from_context = 0
    end if
  end function ft8_phase_from_context

  logical function ft8_try_a8_from_context(context)
    type(wsjtx3_context_t), intent(in) :: context
    ft8_try_a8_from_context = len_trim(context%his_call) >= 3 .and. len_trim(context%his_grid) >= 4
  end function ft8_try_a8_from_context

  logical function ft8_ap_enabled_from_context(context)
    type(wsjtx3_context_t), intent(in) :: context
    ft8_ap_enabled_from_context = context%enable_wideband_dx_search /= 0 .and. &
         (len_trim(context%my_call) >= 3 .or. len_trim(context%his_call) >= 3)
  end function ft8_ap_enabled_from_context

  subroutine copy_c_string(src, dst)
    character(kind=c_char), dimension(*), intent(in) :: src
    character(len=*), intent(out) :: dst
    integer :: index
    dst = ''
    do index = 1, len(dst)
      if (src(index) == c_null_char) then
         exit
      end if
      dst(index:index) = achar(iachar(src(index)))
    end do
  end subroutine copy_c_string

  subroutine wsjtx3_bridge_set_runtime_dirs(temp_dir_path, data_dir_path) &
       bind(C, name="wsjtx3_bridge_set_runtime_dirs")
    character(kind=c_char), dimension(*), intent(in) :: temp_dir_path
    character(kind=c_char), dimension(*), intent(in) :: data_dir_path

    call copy_c_string(temp_dir_path, temp_dir)
    call copy_c_string(data_dir_path, data_dir)

    if (len_trim(temp_dir) == 0) then
       temp_dir = '.'
    end if
    if (len_trim(data_dir) == 0) then
       data_dir = temp_dir
    end if
  end subroutine wsjtx3_bridge_set_runtime_dirs

  subroutine copy_fortran_string(src, dst)
    character(len=*), intent(in) :: src
    character(kind=c_char), intent(out) :: dst(38)
    integer :: index
    do index = 1, 38
       dst(index) = c_null_char
    end do
    do index = 1, min(len_trim(src), 37)
       dst(index) = src(index:index)
    end do
  end subroutine copy_fortran_string

  subroutine append_active_result(sync, snr, dt, freq, decoded, nap, qual)
    real, intent(in) :: sync
    integer, intent(in) :: snr
    real, intent(in) :: dt
    real, intent(in) :: freq
    character(len=37), intent(in) :: decoded
    integer, intent(in) :: nap
    real, intent(in) :: qual
    integer :: next_index
    if (g_active_context < 1 .or. g_active_context > WSJTX3_MAX_CONTEXTS) then
       return
    end if
    if (.not. g_contexts(g_active_context)%active) then
       return
    end if
    next_index = g_contexts(g_active_context)%result_count + 1
    if (next_index > WSJTX3_MAX_RESULTS) then
       return
    end if
    g_contexts(g_active_context)%result_count = next_index
    g_contexts(g_active_context)%results(next_index)%sync = real(sync, kind=c_float)
    g_contexts(g_active_context)%results(next_index)%snr = int(snr, kind=c_int)
    g_contexts(g_active_context)%results(next_index)%dt = real(dt, kind=c_float)
    g_contexts(g_active_context)%results(next_index)%freq = real(freq, kind=c_float)
    g_contexts(g_active_context)%results(next_index)%decoded = decoded
    g_contexts(g_active_context)%results(next_index)%nap = int(nap, kind=c_int)
    g_contexts(g_active_context)%results(next_index)%qual = real(qual, kind=c_float)
  end subroutine append_active_result

  subroutine wsjtx3_ft8_callback(this, sync, snr, dt, freq, decoded, nap, qual)
    class(ft8_decoder), intent(inout) :: this
    real, intent(in) :: sync
    integer, intent(in) :: snr
    real, intent(in) :: dt
    real, intent(in) :: freq
    character(len=37), intent(in) :: decoded
    integer, intent(in) :: nap
    real, intent(in) :: qual
    call append_active_result(sync, snr, dt, freq, decoded, nap, qual)
  end subroutine wsjtx3_ft8_callback

  subroutine wsjtx3_ft4_callback(this, sync, snr, dt, freq, decoded, nap, qual)
    class(ft4_decoder), intent(inout) :: this
    real, intent(in) :: sync
    integer, intent(in) :: snr
    real, intent(in) :: dt
    real, intent(in) :: freq
    character(len=37), intent(in) :: decoded
    integer, intent(in) :: nap
    real, intent(in) :: qual
    call append_active_result(sync, snr, dt, freq, decoded, nap, qual)
  end subroutine wsjtx3_ft4_callback

  subroutine wsjtx3_q65_callback(this, nutc, snr1, nsnr, dt, freq, decoded, idec, nused, ntrperiod)
    class(q65_decoder), intent(inout) :: this
    integer, intent(in) :: nutc
    real, intent(in) :: snr1
    integer, intent(in) :: nsnr
    real, intent(in) :: dt
    real, intent(in) :: freq
    character(len=37), intent(in) :: decoded
    integer, intent(in) :: idec
    integer, intent(in) :: nused
    integer, intent(in) :: ntrperiod
    real :: qual

    qual = real(nused)
    call append_active_result(snr1, nsnr, dt, freq, decoded, idec, qual)
  end subroutine wsjtx3_q65_callback

  logical function ft8_allow_followup_rounds(context, phase)
    type(wsjtx3_context_t), intent(in) :: context
    integer(c_int), intent(in) :: phase
    ft8_allow_followup_rounds = phase == FT8_PHASE_FULL .and. context%ldpc_iterations > 20 .and. &
         (context%decode_pass_count > 1 .or. context%multi_decode_round_count > 1)
  end function ft8_allow_followup_rounds

  integer(c_int) function ft8_followup_budget_from_context(context)
    type(wsjtx3_context_t), intent(in) :: context
    integer(c_int) :: pass_budget
    integer(c_int) :: round_budget

    pass_budget = max(0_c_int, context%decode_pass_count - 1_c_int)
    round_budget = max(0_c_int, context%multi_decode_round_count - 1_c_int)
    ft8_followup_budget_from_context = clamp_int(max(pass_budget, round_budget), 0_c_int, 2_c_int)
  end function ft8_followup_budget_from_context

  subroutine call_ft8_decode_phase(handle, context, iwave, phase, capture_results, nagain)
    integer(c_int), intent(in) :: handle
    type(wsjtx3_context_t), intent(in) :: context
    integer(c_int16_t), intent(in) :: iwave(:)
    integer(c_int), intent(in) :: phase
    logical, intent(in) :: capture_results
    logical, intent(in) :: nagain
    integer(c_int) :: qso_progress
    integer(c_int) :: ndepth
    integer(c_int) :: nutc
    integer(c_int) :: napwid
    logical :: enable_ap
    logical :: try_a8
    logical :: newdat_flag
    logical(kind=1) :: disk_data_flag

    if (phase <= 0) then
       return
    end if

    qso_progress = qso_progress_from_context(context)
    ndepth = ft8_phase_ndepth_from_context(context, phase, nagain)
    napwid = napwid_from_context(context)
    nutc = utc_millis_to_hhmmss(context%utc_time)
    enable_ap = ft8_ap_enabled_from_context(context)
    try_a8 = ft8_try_a8_from_context(context)
    newdat_flag = .true.
    disk_data_flag = .true.

    if (capture_results) then
       g_active_context = handle
    else
       g_active_context = 0
    end if

    call g_ft8_decoders(handle)%decode(wsjtx3_ft8_callback, iwave, qso_progress, &
         context%qso_frequency_hz, context%tx_frequency_hz, newdat_flag, nutc, &
         FTX_DECODE_MIN_HZ, FT8_DECODE_MAX_HZ, &
         phase, ndepth, 0.0, 0, nagain, enable_ap, try_a8, .false., napwid, &
         context%my_call, context%his_call, context%his_grid, disk_data_flag)

    g_active_context = 0
  end subroutine call_ft8_decode_phase

  subroutine run_ft8_followup_rounds(handle, context, iwave, followup_budget)
    integer(c_int), intent(in) :: handle
    type(wsjtx3_context_t), intent(in) :: context
    integer(c_int16_t), intent(in) :: iwave(:)
    integer(c_int), intent(in) :: followup_budget
    integer(c_int) :: followup_index

    if (followup_budget <= 0) then
       return
    end if

    do followup_index = 1, followup_budget
       call call_ft8_decode_phase(handle, context, iwave, FT8_PHASE_FULL, .true., .true.)
    end do
  end subroutine run_ft8_followup_rounds

  subroutine run_ft8_decode_pipeline(handle, context, iwave, sample_count)
    integer(c_int), intent(in) :: handle
    type(wsjtx3_context_t), intent(in) :: context
    integer(c_int16_t), intent(in) :: iwave(:)
    integer(c_int), intent(in) :: sample_count
    integer(c_int) :: phase
    integer(c_int) :: followup_budget

    phase = ft8_phase_from_context(context, sample_count)
    if (phase <= 0) then
       return
    end if

    select case (phase)
    case (FT8_PHASE_FULL)
       call call_ft8_decode_phase(handle, context, iwave, FT8_PHASE_FULL, .true., .false.)
    case (FT8_PHASE_LATE)
       if (context%enable_early_decode == 0) then
          return
       end if
       call call_ft8_decode_phase(handle, context, iwave, FT8_PHASE_LATE, .true., .false.)
    case (FT8_PHASE_EARLY)
       if (context%enable_early_decode == 0) then
          return
       end if
       call call_ft8_decode_phase(handle, context, iwave, FT8_PHASE_EARLY, .true., .false.)
    end select

    if (.not. ft8_allow_followup_rounds(context, phase)) then
       return
    end if

    followup_budget = ft8_followup_budget_from_context(context)
    call run_ft8_followup_rounds(handle, context, iwave, followup_budget)
  end subroutine run_ft8_decode_pipeline

  subroutine run_q65_decode_pipeline(handle, context, iwave, sample_count)
    integer(c_int), intent(in) :: handle
    type(wsjtx3_context_t), intent(in) :: context
    integer(c_int16_t), intent(in) :: iwave(:)
    integer(c_int), intent(in) :: sample_count
    integer(c_int) :: full_samples
    integer(c_int) :: navg0
    integer(c_int) :: nqd
    integer(c_int) :: nutc
    integer(c_int) :: nqf(20)

    full_samples = context%q65_tr_period * 12000
    if (sample_count < full_samples) then
       return
    end if

    open(17, file=trim(temp_dir)//'/red.dat', status='unknown')
    open(14, file=trim(temp_dir)//'/avemsg.txt', status='unknown')

    navg0 = 0
    nqd = 1
    nqf = 0
    nutc = utc_millis_to_hhmmss(context%utc_time)
    g_active_context = handle
    call g_q65_decoders(handle)%decode(wsjtx3_q65_callback, iwave, nqd, nutc, context%q65_tr_period, &
         context%q65_submode, context%qso_frequency_hz, q65_ntol_from_context(context), &
         q65_ndepth_from_context(context), FTX_DECODE_MIN_HZ, Q65_DECODE_MAX_HZ, .true., .true., .true., &
         0_c_int, .true., q65_emedelay_from_context(context), context%my_call, context%his_call, &
         context%his_grid, qso_progress_from_context(context), 0_c_int, .false., navg0, nqf)
    g_active_context = 0

    close(17)
    close(14)
  end subroutine run_q65_decode_pipeline

  integer(c_int) function wsjtx3_bridge_generate_q65_wave(message, q65_submode, q65_tr_period, &
       sample_rate, base_frequency_hz, out_wave, out_capacity) &
       bind(C, name="wsjtx3_bridge_generate_q65_wave")
    character(kind=c_char), dimension(*), intent(in) :: message
    integer(c_int), value :: q65_submode
    integer(c_int), value :: q65_tr_period
    integer(c_int), value :: sample_rate
    real(c_float), value :: base_frequency_hz
    real(c_float), intent(out) :: out_wave(*)
    integer(c_int), value :: out_capacity

    integer(c_int) :: base_nsps
    integer(c_int) :: scaled_nsps
    integer(c_int) :: nwave
    integer(c_int) :: mode_factor
    integer :: index
    integer :: itone(85)
    integer :: i3
    integer :: n3
    character(len=37) :: message_text
    character(len=37) :: msgsent
    complex, allocatable :: cwave(:)
    real, allocatable :: wave(:)
    real :: fsample
    real(8) :: tonespacing
    real :: f0

    wsjtx3_bridge_generate_q65_wave = 0_c_int
    if (sample_rate <= 0_c_int .or. out_capacity <= 0_c_int) then
       return
    end if

    base_nsps = q65_base_nsps_for_period_12k(q65_tr_period)
    mode_factor = q65_mode_factor_from_submode(q65_submode)
    if (base_nsps <= 0_c_int .or. mode_factor <= 0_c_int) then
        return
    end if

    message_text = ''
    call copy_c_string(message, message_text)
    if (len_trim(message_text) == 0) then
       return
    end if

    scaled_nsps = max(1_c_int, nint(real(base_nsps, kind=8) * real(sample_rate, kind=8) / 12000.0_8))
    nwave = 85_c_int * scaled_nsps
    if (nwave > out_capacity) then
       return
    end if

    itone = 0
    i3 = -1
    n3 = -1
    call genq65(message_text, 0, msgsent, itone, i3, n3)

    allocate(cwave(nwave))
    allocate(wave(nwave))
    fsample = real(sample_rate)
    tonespacing = (real(fsample, kind=8) / real(scaled_nsps, kind=8)) * real(mode_factor, kind=8)
    f0 = real(base_frequency_hz)
    call genwave(itone, 85, scaled_nsps, nwave, fsample, tonespacing, f0, 0, cwave, wave)

    do index = 1, nwave
       out_wave(index) = real(wave(index), kind=c_float)
    end do
    deallocate(cwave)
    deallocate(wave)
    wsjtx3_bridge_generate_q65_wave = nwave
  end function wsjtx3_bridge_generate_q65_wave

  integer(c_int) function wsjtx3_bridge_create(mode, sample_rate, expected_samples, utc_time) &
       bind(C, name="wsjtx3_bridge_create")
    integer(c_int), value :: mode
    integer(c_int), value :: sample_rate
    integer(c_int), value :: expected_samples
    integer(c_long_long), value :: utc_time
    integer :: handle
    wsjtx3_bridge_create = 0
    if (mode /= WSJTX3_MODE_FT8 .and. mode /= WSJTX3_MODE_FT4 .and. mode /= WSJTX3_MODE_Q65) then
       return
    end if
    do handle = 1, WSJTX3_MAX_CONTEXTS
       if (.not. g_contexts(handle)%active) then
          call clear_context(handle)
          g_contexts(handle)%active = .true.
          g_contexts(handle)%mode = mode
          g_contexts(handle)%sample_rate = sample_rate
          g_contexts(handle)%expected_samples = expected_samples
          g_contexts(handle)%utc_time = utc_time
          if (mode == WSJTX3_MODE_Q65 .and. expected_samples > 0) then
             g_contexts(handle)%q65_tr_period = max(1_c_int, expected_samples / 12000)
          end if
          wsjtx3_bridge_create = handle
          return
       end if
    end do
  end function wsjtx3_bridge_create

  subroutine wsjtx3_bridge_destroy(handle) bind(C, name="wsjtx3_bridge_destroy")
    integer(c_int), value :: handle
    call clear_context(handle)
  end subroutine wsjtx3_bridge_destroy

  subroutine wsjtx3_bridge_reset(handle, utc_time, expected_samples) &
       bind(C, name="wsjtx3_bridge_reset")
    integer(c_int), value :: handle
    integer(c_long_long), value :: utc_time
    integer(c_int), value :: expected_samples
    if (.not. context_valid(handle)) then
       return
    end if
    g_contexts(handle)%utc_time = utc_time
    g_contexts(handle)%expected_samples = expected_samples
    if (g_contexts(handle)%mode == WSJTX3_MODE_Q65 .and. expected_samples > 0) then
       g_contexts(handle)%q65_tr_period = max(1_c_int, expected_samples / 12000)
    end if
    call reset_context_results(handle)
  end subroutine wsjtx3_bridge_reset

  subroutine wsjtx3_bridge_set_options(handle, decode_pass_count, multi_decode_round_count, &
       qso_freq_sensitivity, decode_sensitivity, enable_early_decode, &
       enable_wideband_dx_search, ldpc_iterations) bind(C, name="wsjtx3_bridge_set_options")
    integer(c_int), value :: handle
    integer(c_int), value :: decode_pass_count
    integer(c_int), value :: multi_decode_round_count
    integer(c_int), value :: qso_freq_sensitivity
    integer(c_int), value :: decode_sensitivity
    integer(c_int), value :: enable_early_decode
    integer(c_int), value :: enable_wideband_dx_search
    integer(c_int), value :: ldpc_iterations
    if (.not. context_valid(handle)) then
       return
    end if
    g_contexts(handle)%decode_pass_count = clamp_int(decode_pass_count, 1_c_int, 3_c_int)
    g_contexts(handle)%multi_decode_round_count = clamp_int(multi_decode_round_count, 1_c_int, 3_c_int)
    g_contexts(handle)%qso_freq_sensitivity = clamp_int(qso_freq_sensitivity, 0_c_int, 2_c_int)
    g_contexts(handle)%decode_sensitivity = clamp_int(decode_sensitivity, 0_c_int, 2_c_int)
    g_contexts(handle)%enable_early_decode = merge(1_c_int, 0_c_int, enable_early_decode /= 0)
    g_contexts(handle)%enable_wideband_dx_search = merge(1_c_int, 0_c_int, enable_wideband_dx_search /= 0)
    g_contexts(handle)%ldpc_iterations = max(1_c_int, ldpc_iterations)
  end subroutine wsjtx3_bridge_set_options

  subroutine wsjtx3_bridge_set_q65_params(handle, q65_submode, q65_tr_period) &
       bind(C, name="wsjtx3_bridge_set_q65_params")
    integer(c_int), value :: handle
    integer(c_int), value :: q65_submode
    integer(c_int), value :: q65_tr_period
    if (.not. context_valid(handle)) then
       return
    end if
    if (g_contexts(handle)%mode /= WSJTX3_MODE_Q65) then
       return
    end if
    if (q65_submode >= 0_c_int .and. q65_submode <= 5_c_int) then
       g_contexts(handle)%q65_submode = q65_submode
    else
       g_contexts(handle)%q65_submode = Q65_DEFAULT_SUBMODE
    end if

    select case (q65_tr_period)
    case (15_c_int, 30_c_int, 60_c_int, 120_c_int, 300_c_int)
       g_contexts(handle)%q65_tr_period = q65_tr_period
    case default
       g_contexts(handle)%q65_tr_period = Q65_DEFAULT_TR_PERIOD
    end select
  end subroutine wsjtx3_bridge_set_q65_params

  subroutine wsjtx3_bridge_set_ap_hints(handle, my_call, his_call, his_grid) &
       bind(C, name="wsjtx3_bridge_set_ap_hints")
    integer(c_int), value :: handle
    character(kind=c_char), dimension(*), intent(in) :: my_call
    character(kind=c_char), dimension(*), intent(in) :: his_call
    character(kind=c_char), dimension(*), intent(in) :: his_grid
    if (.not. context_valid(handle)) then
       return
    end if
    call copy_c_string(my_call, g_contexts(handle)%my_call)
    call copy_c_string(his_call, g_contexts(handle)%his_call)
    call copy_c_string(his_grid, g_contexts(handle)%his_grid)
  end subroutine wsjtx3_bridge_set_ap_hints

  subroutine wsjtx3_bridge_set_qso_frequencies(handle, qso_frequency_hz, tx_frequency_hz) &
       bind(C, name="wsjtx3_bridge_set_qso_frequencies")
    integer(c_int), value :: handle
    integer(c_int), value :: qso_frequency_hz
    integer(c_int), value :: tx_frequency_hz
    if (.not. context_valid(handle)) then
       return
    end if
    if (qso_frequency_hz >= FTX_DECODE_MIN_HZ) then
       g_contexts(handle)%qso_frequency_hz = clamp_int(qso_frequency_hz, FTX_DECODE_MIN_HZ, &
            mode_max_frequency(g_contexts(handle)))
    end if
    if (tx_frequency_hz >= FTX_DECODE_MIN_HZ) then
       g_contexts(handle)%tx_frequency_hz = clamp_int(tx_frequency_hz, FTX_DECODE_MIN_HZ, &
            mode_max_frequency(g_contexts(handle)))
    end if
  end subroutine wsjtx3_bridge_set_qso_frequencies

  integer(c_int) function wsjtx3_bridge_process_float(handle, samples, sample_count) &
       bind(C, name="wsjtx3_bridge_process_float")
    integer(c_int), value :: handle
    real(c_float), intent(in) :: samples(*)
    integer(c_int), value :: sample_count
    integer(c_int) :: copy_count
    integer :: index
    type(wsjtx3_context_t) :: context
    integer(c_int16_t), allocatable :: iwave(:)
    integer(c_int) :: max_samples

    wsjtx3_bridge_process_float = 0
    if (.not. context_valid(handle)) then
       return
    end if

    context = g_contexts(handle)
    call reset_context_results(handle)

    if (context%sample_rate /= 12000 .or. sample_count <= 0) then
       return
    end if

    max_samples = mode_max_samples(context)
    copy_count = min(sample_count, max_samples)
    if (copy_count <= 0) then
       return
    end if

    allocate(iwave(max_samples))
    iwave = 0
    do index = 1, copy_count
       iwave(index) = int(max(-32767.0_c_float, min(32767.0_c_float, samples(index) * 32767.0_c_float)), &
            kind=c_int16_t)
    end do

    if (context_is_ft8(context)) then
       call run_ft8_decode_pipeline(handle, context, iwave, sample_count)
    else if (context_is_ft4(context)) then
       g_active_context = handle
       call g_ft4_decoders(handle)%decode(wsjtx3_ft4_callback, iwave, qso_progress_from_context(context), &
            context%qso_frequency_hz, FTX_DECODE_MIN_HZ, FT4_DECODE_MAX_HZ, &
            ft4_ndepth_from_context(context, sample_count), .false., 0, &
            context%my_call, context%his_call)
       g_active_context = 0
    else if (context_is_q65(context)) then
       call run_q65_decode_pipeline(handle, context, iwave, sample_count)
    end if

    wsjtx3_bridge_process_float = g_contexts(handle)%result_count
    deallocate(iwave)
  end function wsjtx3_bridge_process_float

  integer(c_int) function wsjtx3_bridge_get_result_count(handle) bind(C, name="wsjtx3_bridge_get_result_count")
    integer(c_int), value :: handle
    if (.not. context_valid(handle)) then
       wsjtx3_bridge_get_result_count = 0
       return
    end if
    wsjtx3_bridge_get_result_count = g_contexts(handle)%result_count
  end function wsjtx3_bridge_get_result_count

  integer(c_int) function wsjtx3_bridge_get_result(handle, index, out_result) &
       bind(C, name="wsjtx3_bridge_get_result")
    integer(c_int), value :: handle
    integer(c_int), value :: index
    type(wsjtx3_bridge_result_c), intent(out) :: out_result
    integer :: result_index
    out_result%snr = 0
    out_result%nap = 0
    out_result%sync = 0.0
    out_result%dt = 0.0
    out_result%freq = 0.0
    out_result%qual = 0.0
    call copy_fortran_string('', out_result%decoded)

    if (.not. context_valid(handle)) then
      wsjtx3_bridge_get_result = 0
      return
    end if

    result_index = index + 1
    if (result_index < 1 .or. result_index > g_contexts(handle)%result_count) then
      wsjtx3_bridge_get_result = 0
      return
    end if

    out_result%snr = g_contexts(handle)%results(result_index)%snr
    out_result%nap = g_contexts(handle)%results(result_index)%nap
    out_result%sync = g_contexts(handle)%results(result_index)%sync
    out_result%dt = g_contexts(handle)%results(result_index)%dt
    out_result%freq = g_contexts(handle)%results(result_index)%freq
    out_result%qual = g_contexts(handle)%results(result_index)%qual
    call copy_fortran_string(trim(g_contexts(handle)%results(result_index)%decoded), out_result%decoded)
    wsjtx3_bridge_get_result = 1
  end function wsjtx3_bridge_get_result

end module wsjtx3_bridge
