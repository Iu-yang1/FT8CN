cmake_minimum_required(VERSION 3.18.1)

project("ft8cn")

add_library(
        ft8cn
        SHARED

        generate_ft8.cpp
        ft8Listener.cpp
        ft8Spectrum.cpp

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

        monitor_opr.c
        ft8Decoder.c
        ft8Encoder.c
        spectrum_data.c
)

find_library(
        log-lib
        log
)

find_library(
        m-lib
        m
)

target_link_libraries(
        ft8cn
        ${log-lib}
        ${m-lib}
)