cmake_minimum_required(VERSION 3.20)

project(ft8cn_wsjtx3_host_probe LANGUAGES C CXX Fortran)

option(FT8CN_ENABLE_SANITIZERS "Enable ASan/UBSan for host C/C++ code" OFF)
if (FT8CN_ENABLE_SANITIZERS AND NOT MSVC)
    execute_process(COMMAND ${CMAKE_CXX_COMPILER} -print-file-name=libasan.a
            OUTPUT_VARIABLE FT8CN_ASAN_LIBRARY OUTPUT_STRIP_TRAILING_WHITESPACE)
    execute_process(COMMAND ${CMAKE_CXX_COMPILER} -print-file-name=libubsan.a
            OUTPUT_VARIABLE FT8CN_UBSAN_LIBRARY OUTPUT_STRIP_TRAILING_WHITESPACE)
    if (NOT IS_ABSOLUTE "${FT8CN_ASAN_LIBRARY}" OR NOT EXISTS "${FT8CN_ASAN_LIBRARY}"
            OR NOT IS_ABSOLUTE "${FT8CN_UBSAN_LIBRARY}" OR NOT EXISTS "${FT8CN_UBSAN_LIBRARY}")
        message(FATAL_ERROR
                "FT8CN_ENABLE_SANITIZERS requires installed libasan and libubsan runtimes")
    endif ()
endif ()

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_Fortran_MODULE_DIRECTORY "${CMAKE_BINARY_DIR}/fortran_modules")
set_property(CACHE CMAKE_BUILD_TYPE PROPERTY STRINGS Debug Profile Release)
if (NOT CMAKE_BUILD_TYPE)
    set(CMAKE_BUILD_TYPE Debug CACHE STRING "Host build profile" FORCE)
endif ()

set(FT8CN_RELEASE_OPT_LEVEL "O2" CACHE STRING "Release optimization level: O2 or O3")
set_property(CACHE FT8CN_RELEASE_OPT_LEVEL PROPERTY STRINGS O2 O3)
if (NOT FT8CN_RELEASE_OPT_LEVEL MATCHES "^O[23]$")
    message(FATAL_ERROR "FT8CN_RELEASE_OPT_LEVEL must be O2 or O3")
endif ()

set(WSJTX3_CPP_ROOT "${CMAKE_CURRENT_LIST_DIR}/../..")
set(WSJTX3_VENDOR_ROOT "${WSJTX3_CPP_ROOT}/wsjtx3/vendor/wsjtx-3.0.0")
set(WSJTX3_VENDOR_LIB "${WSJTX3_VENDOR_ROOT}/lib")
set(WSJTX3_SOURCE_MANIFEST "${WSJTX3_CPP_ROOT}/wsjtx3/wsjtx3-sources.manifest")
set(WSJTX3_FFTW_ROOT "" CACHE PATH "Optional FFTW installation prefix")

find_path(WSJTX3_FFTW_INCLUDE_DIR
        NAMES fftw3.h fftw3.f03
        HINTS "${WSJTX3_FFTW_ROOT}/include"
)
find_library(WSJTX3_FFTW3F_LIBRARY
        NAMES fftw3f libfftw3f
        HINTS "${WSJTX3_FFTW_ROOT}/lib"
)
if (NOT WSJTX3_FFTW_INCLUDE_DIR OR NOT WSJTX3_FFTW3F_LIBRARY)
    message(FATAL_ERROR
            "FFTW3f headers/library were not found. Set WSJTX3_FFTW_ROOT or CMAKE_PREFIX_PATH.")
endif ()

set(WSJTX3_VENDOR_FORTRAN_SOURCES)
set(WSJTX3_VENDOR_C_SOURCES)
set(WSJTX3_VENDOR_CXX_SOURCES)
set(WSJTX3_BRIDGE_FORTRAN_SOURCES)
set(WSJTX3_BRIDGE_C_SOURCES)

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
    list(GET manifest_fields 0 source_kind)
    list(GET manifest_fields 1 source_relative_path)
    set(source_path "${WSJTX3_CPP_ROOT}/${source_relative_path}")
    if (NOT EXISTS "${source_path}")
        message(FATAL_ERROR "WSJT-X manifest source does not exist: ${source_path}")
    endif ()
    if (source_kind STREQUAL "vendor-fortran")
        list(APPEND WSJTX3_VENDOR_FORTRAN_SOURCES "${source_path}")
    elseif (source_kind STREQUAL "vendor-c")
        list(APPEND WSJTX3_VENDOR_C_SOURCES "${source_path}")
    elseif (source_kind STREQUAL "vendor-cxx")
        list(APPEND WSJTX3_VENDOR_CXX_SOURCES "${source_path}")
    elseif (source_kind STREQUAL "bridge-fortran")
        list(APPEND WSJTX3_BRIDGE_FORTRAN_SOURCES "${source_path}")
    elseif (source_kind STREQUAL "bridge-c")
        list(APPEND WSJTX3_BRIDGE_C_SOURCES "${source_path}")
    elseif (NOT source_kind STREQUAL "android-c")
        message(FATAL_ERROR "Unknown WSJT-X source kind: ${source_kind}")
    endif ()
endforeach ()

set(WSJTX3_ALL_CORE_SOURCES
        ${WSJTX3_VENDOR_FORTRAN_SOURCES}
        ${WSJTX3_VENDOR_C_SOURCES}
        ${WSJTX3_VENDOR_CXX_SOURCES}
        ${WSJTX3_BRIDGE_FORTRAN_SOURCES}
        ${WSJTX3_BRIDGE_C_SOURCES}
)
list(REMOVE_DUPLICATES WSJTX3_ALL_CORE_SOURCES)

if (CMAKE_BUILD_TYPE STREQUAL "Debug")
    set(FT8CN_OPT_FLAG -O0)
    set(FT8CN_FORTRAN_RUNTIME_CHECKS -g -fcheck=all -fbacktrace)
elseif (CMAKE_BUILD_TYPE STREQUAL "Profile")
    set(FT8CN_OPT_FLAG -O2)
    set(FT8CN_FORTRAN_RUNTIME_CHECKS -g)
else ()
    set(FT8CN_OPT_FLAG -${FT8CN_RELEASE_OPT_LEVEL})
    set(FT8CN_FORTRAN_RUNTIME_CHECKS)
endif ()

add_library(ft8cn_wsjtx3_host_core STATIC ${WSJTX3_ALL_CORE_SOURCES})
set_source_files_properties(${WSJTX3_VENDOR_CXX_SOURCES} PROPERTIES COMPILE_OPTIONS -fpermissive)
set_source_files_properties(
        ${WSJTX3_BRIDGE_C_SOURCES}
        PROPERTIES COMPILE_OPTIONS "-Wall;-Wextra;-Werror"
)
set_source_files_properties(
        ${WSJTX3_BRIDGE_FORTRAN_SOURCES}
        PROPERTIES COMPILE_OPTIONS "-Wall;-Wextra;-Werror"
)

target_include_directories(ft8cn_wsjtx3_host_core
        PUBLIC
        "${WSJTX3_CPP_ROOT}/wsjtx3"
        "${WSJTX3_CPP_ROOT}"
        "${WSJTX3_VENDOR_ROOT}"
        "${WSJTX3_VENDOR_LIB}"
        "${WSJTX3_VENDOR_LIB}/qra/q65"
        "${WSJTX3_FFTW_INCLUDE_DIR}"
)
target_compile_definitions(ft8cn_wsjtx3_host_core PRIVATE BIGSYM=1)
target_compile_options(ft8cn_wsjtx3_host_core PRIVATE
        ${FT8CN_OPT_FLAG}
        $<$<CONFIG:Release>:-DNDEBUG>
        $<$<CONFIG:Profile>:-DNDEBUG>
        $<$<COMPILE_LANGUAGE:Fortran>:${FT8CN_FORTRAN_RUNTIME_CHECKS}>
)
if (FT8CN_ENABLE_SANITIZERS AND NOT MSVC)
    target_compile_options(ft8cn_wsjtx3_host_core PRIVATE
            $<$<COMPILE_LANGUAGE:C,CXX>:-fsanitize=address,undefined;-fno-omit-frame-pointer>)
endif ()

add_executable(ft8cn_wsjtx3_bridge_smoke
        "${CMAKE_CURRENT_LIST_DIR}/wsjtx3_bridge_smoke.c"
        "${WSJTX3_CPP_ROOT}/common/wave.c"
)
target_include_directories(ft8cn_wsjtx3_bridge_smoke PRIVATE
        "${WSJTX3_CPP_ROOT}/wsjtx3"
        "${WSJTX3_CPP_ROOT}"
)
target_compile_options(ft8cn_wsjtx3_bridge_smoke PRIVATE
        ${FT8CN_OPT_FLAG} -Wall -Wextra -Werror
        $<$<CONFIG:Release>:-DNDEBUG>
        $<$<CONFIG:Profile>:-DNDEBUG>
)
target_link_libraries(ft8cn_wsjtx3_bridge_smoke PRIVATE
        ft8cn_wsjtx3_host_core
        "${WSJTX3_FFTW3F_LIBRARY}"
)

set(FTX_CODEC_SOURCES
        "${WSJTX3_CPP_ROOT}/common/resampler.c"
        "${WSJTX3_CPP_ROOT}/ftx_core/ftx_encoder_api.c"
        "${WSJTX3_CPP_ROOT}/ftx_core/ftx_selftest.c"
        "${WSJTX3_CPP_ROOT}/ft8/crc.c"
        "${WSJTX3_CPP_ROOT}/ft8/constants.c"
        "${WSJTX3_CPP_ROOT}/ft8/encode.c"
        "${WSJTX3_CPP_ROOT}/ft8/hash22.c"
        "${WSJTX3_CPP_ROOT}/ft8/ldpc.c"
        "${WSJTX3_CPP_ROOT}/ft8/pack.c"
        "${WSJTX3_CPP_ROOT}/ft8/text.c"
        "${WSJTX3_CPP_ROOT}/ft8/unpack.c"
)
add_library(ftx_codec_selftest_support STATIC ${FTX_CODEC_SOURCES})
target_include_directories(ftx_codec_selftest_support PUBLIC
        "${WSJTX3_CPP_ROOT}/ftx_core/include"
        "${WSJTX3_CPP_ROOT}"
)
target_compile_options(ftx_codec_selftest_support PRIVATE
        ${FT8CN_OPT_FLAG} -Wall -Wextra -Werror
        $<$<CONFIG:Release>:-DNDEBUG>
        $<$<CONFIG:Profile>:-DNDEBUG>
)

add_executable(ftx_selftest
        "${WSJTX3_CPP_ROOT}/tests/ftx_core_selftest_main.c"
        "${WSJTX3_CPP_ROOT}/tests/ftx_resampler_test.c"
        "${WSJTX3_CPP_ROOT}/tests/ftx_synthetic_decode_test.c"
        "${WSJTX3_CPP_ROOT}/tests/ftx_channel_regression_test.c"
        "${WSJTX3_CPP_ROOT}/tests/ftx_request_context_test.c"
        "${WSJTX3_CPP_ROOT}/tests/q65_capacity_test.c"
        "${WSJTX3_CPP_ROOT}/tests/q65_averaging_test.c"
        "${WSJTX3_CPP_ROOT}/tests/q65_tx_rx_test.c"
        "${WSJTX3_CPP_ROOT}/tests/wsjtx3_osd_opt_test.c"
        "${WSJTX3_CPP_ROOT}/ft8Encoder.c"
)
target_compile_options(ftx_selftest PRIVATE
        ${FT8CN_OPT_FLAG} -Wall -Wextra -Werror
        $<$<CONFIG:Release>:-DNDEBUG>
        $<$<CONFIG:Profile>:-DNDEBUG>
)
target_link_libraries(ftx_selftest PRIVATE
        ftx_codec_selftest_support
        ft8cn_wsjtx3_host_core
        "${WSJTX3_FFTW3F_LIBRARY}"
)

if (FT8CN_ENABLE_SANITIZERS AND NOT MSVC)
    foreach (sanitized_target IN ITEMS ft8cn_wsjtx3_bridge_smoke ftx_codec_selftest_support ftx_selftest)
        target_compile_options(${sanitized_target} PRIVATE
                $<$<COMPILE_LANGUAGE:C,CXX>:-fsanitize=address,undefined;-fno-omit-frame-pointer>)
    endforeach ()
    target_link_options(ft8cn_wsjtx3_bridge_smoke PRIVATE -fsanitize=address,undefined)
    target_link_options(ftx_selftest PRIVATE -fsanitize=address,undefined)
endif ()

enable_testing()
add_test(NAME ftx_codec_selftest COMMAND ftx_selftest)
get_filename_component(FT8CN_HOST_RUNTIME_DIR "${CMAKE_Fortran_COMPILER}" DIRECTORY)
set_tests_properties(ftx_codec_selftest PROPERTIES
        ENVIRONMENT_MODIFICATION "PATH=path_list_prepend:${FT8CN_HOST_RUNTIME_DIR}"
)
