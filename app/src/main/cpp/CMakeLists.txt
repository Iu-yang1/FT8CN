cmake_minimum_required(VERSION 3.18.1)

project("ft8cn")

option(FT8CN_ENABLE_WSJTX3_BACKEND "Enable official WSJT-X 3.0 backend bridge" ON)

if (DEFINED ANDROID_NDK AND NOT ANDROID_NDK STREQUAL "")
    set(FT8CN_WSJTX3_NDK_ROOT_DEFAULT "${ANDROID_NDK}")
else ()
    set(FT8CN_WSJTX3_NDK_ROOT_DEFAULT "H:/iu_yang1/AndroidSDKLIB/ndk/23.1.7779620")
endif ()

set(FT8CN_WSJTX3_FLANG_PATH "H:/tools/build/llvm-flang-22.1.5-clangcl/bin/flang.exe" CACHE FILEPATH "Path to the Android-capable flang compiler")
set(FT8CN_WSJTX3_BOOST_HEADERS "H:/tools/boost_headers" CACHE PATH "Path to standalone Boost headers used by wsjtx3 Android bridge")
set(FT8CN_WSJTX3_NDK_ROOT "${FT8CN_WSJTX3_NDK_ROOT_DEFAULT}" CACHE PATH "Path to Android NDK root used by wsjtx3 Android bridge")

add_library(
        ftx_core
        STATIC

        ftx_core/ftx_decoder_api.c
        ftx_core/ftx_encoder_api.c
        ftx_core/ftx_selftest.c

        wsjtx/session_plan.cpp
        wsjtx/wsjtx_port.cpp

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

if (FT8CN_ENABLE_WSJTX3_BACKEND
        AND ANDROID
        AND WIN32
        AND ANDROID_ABI STREQUAL "arm64-v8a"
        AND EXISTS "${FT8CN_WSJTX3_FLANG_PATH}"
        AND EXISTS "${FT8CN_WSJTX3_BOOST_HEADERS}")
    set(WSJTX3_ANDROID_OUT_DIR "${CMAKE_CURRENT_BINARY_DIR}/wsjtx3_android/${ANDROID_ABI}")
    set(WSJTX3_ANDROID_CORE_LIB "${WSJTX3_ANDROID_OUT_DIR}/libwsjtx3_official_core.a")
    set(WSJTX3_ANDROID_RUNTIME_LIB "${WSJTX3_ANDROID_OUT_DIR}/libflang_rt.runtime.a")

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
            DEPENDS
            "${CMAKE_CURRENT_SOURCE_DIR}/wsjtx3/android/build_flang_rt_android.ps1"
            "${CMAKE_CURRENT_SOURCE_DIR}/wsjtx3/android/build_wsjtx3_android_core.ps1"
            "${CMAKE_CURRENT_SOURCE_DIR}/wsjtx3/android/fftw3f_kiss_shim.c"
            "${CMAKE_CURRENT_SOURCE_DIR}/wsjtx3/android/complex_math_shim.c"
            "${CMAKE_CURRENT_SOURCE_DIR}/fft/kiss_fft.c"
            "${CMAKE_CURRENT_SOURCE_DIR}/fft/kiss_fftr.c"
            "${CMAKE_CURRENT_SOURCE_DIR}/wsjtx3/host/CMakeLists.txt"
            "${CMAKE_CURRENT_SOURCE_DIR}/wsjtx3/host/wsjtx3_bridge.f90"
            "${CMAKE_CURRENT_SOURCE_DIR}/wsjtx3/host/wsjtx3_host_support.f90"
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
    set(FT8CN_WSJTX3_BACKEND_ACTIVE 1)
endif ()

if (FT8CN_WSJTX3_BACKEND_ACTIVE)
    target_compile_definitions(ftx_core PUBLIC FT8CN_ENABLE_WSJTX3_BACKEND=1)
else ()
    target_compile_definitions(ftx_core PUBLIC FT8CN_ENABLE_WSJTX3_BACKEND=0)
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
