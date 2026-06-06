integer(c_int) function wsjtx3_openmp_probe() bind(C, name="wsjtx3_openmp_probe")
  use iso_c_binding, only: c_int
  implicit none
  integer(c_int) :: thread_count

  thread_count=0
  !$omp parallel reduction(+:thread_count) num_threads(2)
  thread_count=thread_count+1
  !$omp end parallel
  wsjtx3_openmp_probe=thread_count
end function wsjtx3_openmp_probe
