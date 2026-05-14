cmake_minimum_required(VERSION 3.20)

project(ft8cn_wsjtx3_host_probe LANGUAGES C CXX Fortran)

set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_Fortran_MODULE_DIRECTORY ${CMAKE_BINARY_DIR}/fortran_modules)

set(WSJTX3_VENDOR_ROOT ${CMAKE_CURRENT_LIST_DIR}/../vendor/wsjtx-3.0.0)
set(WSJTX3_VENDOR_LIB ${WSJTX3_VENDOR_ROOT}/lib)

find_path(WSJTX3_FFTW_INCLUDE_DIR
        NAMES fftw3.h fftw3.f03
        PATHS
        H:/tools/msys64/ucrt64/include
        ENV INCLUDE
)

if (NOT WSJTX3_FFTW_INCLUDE_DIR)
    message(FATAL_ERROR "未找到 FFTW 头文件或 Fortran include，请先安装 ucrt64 FFTW。")
endif ()

# 这里只保留 FT8/FT4 官方核心真正会走到的源码。
# 目标是先验证官方核心的最小可编译闭包，避免继续把 Qt、旧模式和测试程序编进来。
set(WSJTX3_HOST_SUPPORT_SOURCES
        ${CMAKE_CURRENT_LIST_DIR}/shmem_stub.c
        ${CMAKE_CURRENT_LIST_DIR}/wsjtx3_host_support.f90
        ${CMAKE_CURRENT_LIST_DIR}/wsjtx3_bridge.f90
)

set(WSJTX3_VENDOR_CPP_SOURCES
        ${WSJTX3_VENDOR_LIB}/crc10.cpp
        ${WSJTX3_VENDOR_LIB}/crc13.cpp
        ${WSJTX3_VENDOR_LIB}/crc14.cpp
)

set(WSJTX3_VENDOR_FORTRAN_SOURCES
        # 公共模块与基础工具
        ${WSJTX3_VENDOR_LIB}/shmem.f90
        ${WSJTX3_VENDOR_LIB}/crc.f90
        ${WSJTX3_VENDOR_LIB}/fftw3mod.f90
        ${WSJTX3_VENDOR_LIB}/timer_module.f90
        ${WSJTX3_VENDOR_LIB}/fmtmsg.f90
        ${WSJTX3_VENDOR_LIB}/packjt.f90
        ${WSJTX3_VENDOR_LIB}/chkcall.f90
        ${WSJTX3_VENDOR_LIB}/db.f90
        ${WSJTX3_VENDOR_LIB}/shell.f90
        ${WSJTX3_VENDOR_LIB}/pctile.f90
        ${WSJTX3_VENDOR_LIB}/determ.f90
        ${WSJTX3_VENDOR_LIB}/polyfit.f90
        ${WSJTX3_VENDOR_LIB}/indexx.f90
        ${WSJTX3_VENDOR_LIB}/nuttal_window.f90
        ${WSJTX3_VENDOR_LIB}/four2a.f90
        ${WSJTX3_VENDOR_LIB}/peakup.f90
        ${WSJTX3_VENDOR_LIB}/platanh.f90
        ${WSJTX3_VENDOR_LIB}/ft2/gfsk_pulse.f90
        ${WSJTX3_VENDOR_LIB}/77bit/packjt77.f90

        # FT8 官方解码主链
        ${WSJTX3_VENDOR_LIB}/ft8_decode.f90
        ${WSJTX3_VENDOR_LIB}/ft8/baseline.f90
        ${WSJTX3_VENDOR_LIB}/ft8/decode174_91.f90
        ${WSJTX3_VENDOR_LIB}/ft8/encode174_91.f90
        ${WSJTX3_VENDOR_LIB}/ft8/encode174_91_nocrc.f90
        ${WSJTX3_VENDOR_LIB}/ft8/ft8apset.f90
        ${WSJTX3_VENDOR_LIB}/ft8/ft8b.f90
        ${WSJTX3_VENDOR_LIB}/ft8/ft8_downsample.f90
        ${WSJTX3_VENDOR_LIB}/ft8/genft8.f90
        ${WSJTX3_VENDOR_LIB}/ft8/gen_ft8wave.f90
        ${WSJTX3_VENDOR_LIB}/ft8/get_crc14.f90
        ${WSJTX3_VENDOR_LIB}/ft8/get_spectrum_baseline.f90
        ${WSJTX3_VENDOR_LIB}/ft8/osd174_91.f90
        ${WSJTX3_VENDOR_LIB}/ft8/subtractft8.f90
        ${WSJTX3_VENDOR_LIB}/ft8/sync8.f90
        ${WSJTX3_VENDOR_LIB}/ft8/sync8d.f90
        ${WSJTX3_VENDOR_LIB}/ft8/twkfreq1.f90
        ${WSJTX3_VENDOR_LIB}/ft8/ft8_a7.f90
        ${WSJTX3_VENDOR_LIB}/ft8/ft8_a8d.f90
        ${WSJTX3_VENDOR_LIB}/smo121.f90

        # FT4 官方解码主链
        ${WSJTX3_VENDOR_LIB}/ft4_decode.f90
        ${WSJTX3_VENDOR_LIB}/ft4/ft4_baseline.f90
        ${WSJTX3_VENDOR_LIB}/ft4/ft4_downsample.f90
        ${WSJTX3_VENDOR_LIB}/ft4/genft4.f90
        ${WSJTX3_VENDOR_LIB}/ft4/gen_ft4wave.f90
        ${WSJTX3_VENDOR_LIB}/ft4/getcandidates4.f90
        ${WSJTX3_VENDOR_LIB}/ft4/get_ft4_bitmetrics.f90
        ${WSJTX3_VENDOR_LIB}/ft4/subtractft4.f90
        ${WSJTX3_VENDOR_LIB}/ft4/sync4d.f90
)

add_library(ft8cn_wsjtx3_host_core STATIC
        ${WSJTX3_HOST_SUPPORT_SOURCES}
        ${WSJTX3_VENDOR_CPP_SOURCES}
        ${WSJTX3_VENDOR_FORTRAN_SOURCES}
)

set_source_files_properties(
        ${WSJTX3_VENDOR_CPP_SOURCES}
        PROPERTIES
        COMPILE_FLAGS -fpermissive
)

target_include_directories(ft8cn_wsjtx3_host_core
        PUBLIC
        ${CMAKE_CURRENT_LIST_DIR}/..
        ${CMAKE_CURRENT_LIST_DIR}/../..
        ${WSJTX3_VENDOR_ROOT}
        ${WSJTX3_VENDOR_LIB}
        ${WSJTX3_FFTW_INCLUDE_DIR}
)

target_compile_definitions(ft8cn_wsjtx3_host_core
        PRIVATE
        BIGSYM=1
)

target_compile_options(ft8cn_wsjtx3_host_core
        PRIVATE
        $<$<COMPILE_LANGUAGE:Fortran>:-O0 -g -fcheck=all -fbacktrace>
)

add_executable(ft8cn_wsjtx3_bridge_smoke
        ${CMAKE_CURRENT_LIST_DIR}/wsjtx3_bridge_smoke.c
        ${CMAKE_CURRENT_LIST_DIR}/../../common/wave.c
)

target_include_directories(ft8cn_wsjtx3_bridge_smoke
        PRIVATE
        ${CMAKE_CURRENT_LIST_DIR}/..
        ${CMAKE_CURRENT_LIST_DIR}/../..
)

target_link_libraries(ft8cn_wsjtx3_bridge_smoke
        PRIVATE
        ft8cn_wsjtx3_host_core
        fftw3f
)
