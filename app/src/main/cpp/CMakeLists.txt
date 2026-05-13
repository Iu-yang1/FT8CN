cmake_minimum_required(VERSION 3.18.1)

project("ft8cn")

option(FT8CN_ENABLE_WSJTX3_BACKEND "Enable official WSJT-X 3.0 backend bridge" OFF)

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

if (FT8CN_ENABLE_WSJTX3_BACKEND)
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
