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
    message(FATAL_ERROR "未找到 FFTW 头文件/Fortran include，请先把 ucrt64 的 FFTW 装好并放到 PATH/INCLUDE 可见位置。")
endif ()

file(GLOB_RECURSE WSJTX3_VENDOR_SOURCES CONFIGURE_DEPENDS
        ${WSJTX3_VENDOR_LIB}/*.f90
        ${WSJTX3_VENDOR_LIB}/*.f
        ${WSJTX3_VENDOR_LIB}/*.c
        ${WSJTX3_VENDOR_LIB}/*.cpp
)

list(FILTER WSJTX3_VENDOR_SOURCES EXCLUDE REGEX ".*\\.save$")
list(FILTER WSJTX3_VENDOR_SOURCES EXCLUDE REGEX ".*/lib/ft2/.*")
list(FILTER WSJTX3_VENDOR_SOURCES EXCLUDE REGEX ".*/lib/init_rs\\.c$")
list(FILTER WSJTX3_VENDOR_SOURCES EXCLUDE REGEX ".*/lib/decode_rs\\.c$")
list(FILTER WSJTX3_VENDOR_SOURCES EXCLUDE REGEX ".*/lib/encode_rs\\.c$")
list(FILTER WSJTX3_VENDOR_SOURCES EXCLUDE REGEX ".*/lib/wrapkarn\\.c$")
list(FILTER WSJTX3_VENDOR_SOURCES EXCLUDE REGEX ".*/lib/ftrsd/sfrsd\\.c$")
list(FILTER WSJTX3_VENDOR_SOURCES EXCLUDE REGEX ".*/lib/rig_control\\.c$")
list(FILTER WSJTX3_VENDOR_SOURCES EXCLUDE REGEX ".*/lib/shmem\\.cpp$")
list(FILTER WSJTX3_VENDOR_SOURCES EXCLUDE REGEX ".*/lib/qra/q65/q65test\\.c$")
list(FILTER WSJTX3_VENDOR_SOURCES EXCLUDE REGEX ".*/lib/qra/qracodes/main\\.c$")

add_library(ft8cn_wsjtx3_host_core STATIC ${WSJTX3_VENDOR_SOURCES})

target_include_directories(ft8cn_wsjtx3_host_core
        PUBLIC
        ${WSJTX3_VENDOR_ROOT}
        ${WSJTX3_VENDOR_LIB}
        ${WSJTX3_FFTW_INCLUDE_DIR}
)

target_compile_definitions(ft8cn_wsjtx3_host_core
        PRIVATE
        BIGSYM=1
)
