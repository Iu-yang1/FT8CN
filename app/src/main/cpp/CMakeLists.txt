cmake_minimum_required(VERSION 3.18.1)

project("ft8cn")

option(FT8CN_ENABLE_WSJTX3_BACKEND "Enable official WSJT-X 3.0 backend bridge" ON)

set(FT8CN_WSJTX3_NDK_ROOT "${ANDROID_NDK}" CACHE PATH "Android NDK root; empty lets the build script discover it")
set(FT8CN_WSJTX3_FLANG_PATH "$ENV{FT8CN_FLANG_PATH}" CACHE FILEPATH "Android-capable Flang; empty enables discovery")
set(FT8CN_WSJTX3_BOOST_HEADERS "$ENV{BOOST_ROOT}" CACHE PATH "Boost headers; empty enables discovery")
set(FT8CN_WSJTX3_LLVM_SOURCE_ROOT "$ENV{LLVM_SOURCE_ROOT}" CACHE PATH "LLVM source root used for isolated flang-rt build")
set(FT8CN_WSJTX3_BUILD_PROFILE "Auto" CACHE STRING "WSJT-X core profile: Auto, Debug, Profile, or Release")
set_property(CACHE FT8CN_WSJTX3_BUILD_PROFILE PROPERTY STRINGS Auto Debug Profile Release)
set(FT8CN_WSJTX3_OPTIMIZATION "O2" CACHE STRING "WSJT-X release optimization: O2 or O3")
set_property(CACHE FT8CN_WSJTX3_OPTIMIZATION PROPERTY STRINGS O2 O3)
if (NOT FT8CN_WSJTX3_OPTIMIZATION MATCHES "^O[23]$")
    message(FATAL_ERROR "FT8CN_WSJTX3_OPTIMIZATION must be O2 or O3")
endif ()

add_library(
        ftx_core
        STATIC

        ftx_core/ftx_decoder_api.c
        ftx_core/ftx_encoder_api.c
        ftx_core/ftx_selftest.c

        fft/kiss_fftr.c
        fft/kiss_fft.c

        ft8/decode.c
        ft8/crc.c
        ft8/constants.c
        ft8/ldpc.c
        ft8/unpack.c
        ft8/text.c
        ft8/hash22.c
        ft8/encode.c
        ft8/pack.c

        common/resampler.c
        common/wave.c
        monitor_opr.c
        ft8Decoder.c
        ft8Encoder.c
        spectrum_data.c
        wsjtx3/wsjtx3_backend.c
)

add_library(
        radio_experimental
        STATIC

        experimental/experimental_modem.c
)

add_library(
        ft8cn
        SHARED

        generate_ft8.cpp
        diagnostics/ap_regression.cpp
        diagnostics/sample_decode.cpp
        ft8Resample.cpp
        ft8Listener.cpp
        ft8Spectrum.cpp
        experimentalCodec.cpp
)

find_library(
        log-lib
        log
)

find_library(
        m-lib
        m
)

target_include_directories(
        ftx_core
        PUBLIC
        ${CMAKE_CURRENT_SOURCE_DIR}/ftx_core/include
        ${CMAKE_CURRENT_SOURCE_DIR}
)

set(FT8CN_WSJTX3_BACKEND_ACTIVE 0)
set(FT8CN_WSJTX3_BACKEND_REQUESTED ${FT8CN_ENABLE_WSJTX3_BACKEND})

if (FT8CN_WSJTX3_BACKEND_REQUESTED
        AND ANDROID
        AND CMAKE_HOST_WIN32
        AND ANDROID_ABI STREQUAL "arm64-v8a")
    set(WSJTX3_ANDROID_OUT_DIR "${CMAKE_CURRENT_BINARY_DIR}/wsjtx3_android/${ANDROID_ABI}")
    set(WSJTX3_ANDROID_CORE_LIB "${WSJTX3_ANDROID_OUT_DIR}/libwsjtx3_official_core.a")
    set(WSJTX3_ANDROID_RUNTIME_LIB "${WSJTX3_ANDROID_OUT_DIR}/libflang_rt.runtime.a")
    execute_process(
            COMMAND "${CMAKE_C_COMPILER}" -target aarch64-linux-android21
            -fopenmp --print-file-name=libomp.a
            OUTPUT_VARIABLE WSJTX3_ANDROID_OPENMP_LIB
            OUTPUT_STRIP_TRAILING_WHITESPACE
            RESULT_VARIABLE WSJTX3_OPENMP_LOOKUP_RESULT)
    if (NOT WSJTX3_OPENMP_LOOKUP_RESULT EQUAL 0
            OR NOT IS_ABSOLUTE "${WSJTX3_ANDROID_OPENMP_LIB}"
            OR NOT EXISTS "${WSJTX3_ANDROID_OPENMP_LIB}")
        message(FATAL_ERROR "Unable to locate the NDK arm64 OpenMP runtime")
    endif ()
    set(WSJTX3_SOURCE_MANIFEST "${CMAKE_CURRENT_SOURCE_DIR}/wsjtx3/wsjtx3-sources.manifest")
    set(WSJTX3_MANIFEST_DEPENDS "${WSJTX3_SOURCE_MANIFEST}")
    file(STRINGS "${WSJTX3_SOURCE_MANIFEST}" WSJTX3_MANIFEST_LINES)
    foreach (manifest_line IN LISTS WSJTX3_MANIFEST_LINES)
        string(STRIP "${manifest_line}" manifest_line)
        if (manifest_line STREQUAL "" OR manifest_line MATCHES "^#")
            continue()
        endif ()
        string(REPLACE "|" ";" manifest_fields "${manifest_line}")
        list(LENGTH manifest_fields manifest_field_count)
        if (NOT manifest_field_count EQUAL 2)
            message(FATAL_ERROR "Invalid WSJT-X source manifest line: ${manifest_line}")
        endif ()
        list(GET manifest_fields 1 source_relative_path)
        set(manifest_source "${CMAKE_CURRENT_SOURCE_DIR}/${source_relative_path}")
        if (NOT EXISTS "${manifest_source}")
            message(FATAL_ERROR "WSJT-X manifest source does not exist: ${manifest_source}")
        endif ()
        list(APPEND WSJTX3_MANIFEST_DEPENDS "${manifest_source}")
    endforeach ()

    if (FT8CN_WSJTX3_BUILD_PROFILE STREQUAL "Auto")
        if (CMAKE_BUILD_TYPE STREQUAL "Debug")
            set(WSJTX3_EFFECTIVE_BUILD_PROFILE "Debug")
        else ()
            set(WSJTX3_EFFECTIVE_BUILD_PROFILE "Release")
        endif ()
    else ()
        set(WSJTX3_EFFECTIVE_BUILD_PROFILE "${FT8CN_WSJTX3_BUILD_PROFILE}")
    endif ()
    set(WSJTX3_TOOL_STAMP "${CMAKE_CURRENT_BINARY_DIR}/wsjtx3-tool-config.stamp")
    file(GENERATE OUTPUT "${WSJTX3_TOOL_STAMP}" CONTENT
            "profile=${WSJTX3_EFFECTIVE_BUILD_PROFILE}\noptimization=${FT8CN_WSJTX3_OPTIMIZATION}\nflang=${FT8CN_WSJTX3_FLANG_PATH}\nndk=${FT8CN_WSJTX3_NDK_ROOT}\nboost=${FT8CN_WSJTX3_BOOST_HEADERS}\nllvm=${FT8CN_WSJTX3_LLVM_SOURCE_ROOT}\n")

    set(WSJTX3_TOOL_DEPENDS "${WSJTX3_TOOL_STAMP}" "${WSJTX3_ANDROID_OPENMP_LIB}")
    foreach (tool_path IN ITEMS "${FT8CN_WSJTX3_FLANG_PATH}"
            "${FT8CN_WSJTX3_NDK_ROOT}/toolchains/llvm/prebuilt/windows-x86_64/bin/clang.exe")
        if (EXISTS "${tool_path}")
            list(APPEND WSJTX3_TOOL_DEPENDS "${tool_path}")
        endif ()
    endforeach ()

    add_custom_command(
            OUTPUT "${WSJTX3_ANDROID_CORE_LIB}" "${WSJTX3_ANDROID_RUNTIME_LIB}"
            COMMAND powershell.exe -ExecutionPolicy Bypass -File
            "${CMAKE_CURRENT_SOURCE_DIR}/wsjtx3/android/build_wsjtx3_android_core.ps1"
            -OutputDir "${WSJTX3_ANDROID_OUT_DIR}"
            -CMakePath "${CMAKE_COMMAND}"
            -NinjaPath "${CMAKE_MAKE_PROGRAM}"
            -NdkRoot "${FT8CN_WSJTX3_NDK_ROOT}"
            -FlangPath "${FT8CN_WSJTX3_FLANG_PATH}"
            -BoostHeaders "${FT8CN_WSJTX3_BOOST_HEADERS}"
            -LlvmSourceRoot "${FT8CN_WSJTX3_LLVM_SOURCE_ROOT}"
            -BuildProfile "${WSJTX3_EFFECTIVE_BUILD_PROFILE}"
            -Optimization "${FT8CN_WSJTX3_OPTIMIZATION}"
            DEPENDS
            ${WSJTX3_MANIFEST_DEPENDS}
            ${WSJTX3_TOOL_DEPENDS}
            "${CMAKE_CURRENT_SOURCE_DIR}/wsjtx3/android/build_flang_rt_android.ps1"
            "${CMAKE_CURRENT_SOURCE_DIR}/wsjtx3/android/build_wsjtx3_android_core.ps1"
            "${CMAKE_CURRENT_SOURCE_DIR}/wsjtx3/android/patches/flang-rt-android-time.patch"
            "${CMAKE_CURRENT_SOURCE_DIR}/../../../../scripts/toolchain-common.ps1"
            VERBATIM
    )

    add_custom_target(wsjtx3_android_core_build
            DEPENDS "${WSJTX3_ANDROID_CORE_LIB}" "${WSJTX3_ANDROID_RUNTIME_LIB}")

    add_library(wsjtx3_official_core STATIC IMPORTED GLOBAL)
    set_target_properties(wsjtx3_official_core PROPERTIES
            IMPORTED_LOCATION "${WSJTX3_ANDROID_CORE_LIB}")
    add_dependencies(wsjtx3_official_core wsjtx3_android_core_build)

    add_library(wsjtx3_flang_rt STATIC IMPORTED GLOBAL)
    set_target_properties(wsjtx3_flang_rt PROPERTIES
            IMPORTED_LOCATION "${WSJTX3_ANDROID_RUNTIME_LIB}")
    add_dependencies(wsjtx3_flang_rt wsjtx3_android_core_build)

    target_link_libraries(ftx_core PUBLIC wsjtx3_official_core wsjtx3_flang_rt)
    target_link_libraries(ftx_core PUBLIC "${WSJTX3_ANDROID_OPENMP_LIB}")
    set(FT8CN_WSJTX3_BACKEND_ACTIVE 1)
endif ()

if (FT8CN_WSJTX3_BACKEND_ACTIVE)
    target_compile_definitions(ftx_core PUBLIC FT8CN_ENABLE_WSJTX3_BACKEND=1)
else ()
    target_compile_definitions(ftx_core PUBLIC FT8CN_ENABLE_WSJTX3_BACKEND=0)
endif ()

if (ANDROID AND NOT FT8CN_WSJTX3_BACKEND_ACTIVE)
    message(FATAL_ERROR "FT8CN now requires the official WSJT-X 3.0 backend. Please build arm64-v8a with the configured Fortran toolchain.")
endif ()

target_include_directories(
        radio_experimental
        PUBLIC
        ${CMAKE_CURRENT_SOURCE_DIR}
)

target_include_directories(
        ft8cn
        PRIVATE
        ${CMAKE_CURRENT_SOURCE_DIR}
)

target_link_libraries(
        ft8cn
        ftx_core
        radio_experimental
        ${log-lib}
        ${m-lib}
)
