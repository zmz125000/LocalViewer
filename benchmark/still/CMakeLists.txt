cmake_minimum_required(VERSION 3.22)
project(still_export_test LANGUAGES C CXX)

# Linux host: requires libjxl/libjxl_threads/libjxl_cms and libjpeg development libraries.
# Reuse codec sources/headers from an existing Android native build.
# cmake -S benchmark/still -B /tmp/hdr-bench -DCMAKE_BUILD_TYPE=Release \
#   -DHDR_DEPS_DIR=/path/to/app/.cxx/RelWithDebInfo/<id>/arm64-v8a/_deps
# cmake --build /tmp/hdr-bench -j && /tmp/hdr-bench/still_export_test
set(HDR_DEPS_DIR "" CACHE PATH "Native build _deps directory")
# Android: link the production encoder against the same pinned libjxl sources.
if(ANDROID)
    set(BUILD_TESTING OFF CACHE BOOL "" FORCE)
    set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
    foreach(option TOOLS DEVTOOLS BENCHMARK EXAMPLES MANPAGES JNI OPENEXR SJPEG TCMALLOC FUZZERS DOXYGEN)
        set(JPEGXL_ENABLE_${option} OFF CACHE BOOL "" FORCE)
    endforeach()
    add_subdirectory("${HDR_DEPS_DIR}/libjxl-src" jxl)
    add_library(still_export_android SHARED ../../app/src/main/cpp/still_jxl.cpp)
    target_compile_features(still_export_android PRIVATE cxx_std_17)
    target_include_directories(still_export_android PRIVATE "${HDR_DEPS_DIR}/libultrahdr-src")
    target_link_libraries(still_export_android PRIVATE jxl jxl_threads)
    target_link_options(still_export_android PRIVATE -Wl,--no-undefined)
    return()
endif()
find_package(JNI REQUIRED COMPONENTS JVM)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(UHDR_BUILD_DEPS OFF CACHE BOOL "" FORCE)
set(UHDR_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(UHDR_ENABLE_INSTALL OFF CACHE BOOL "" FORCE)
add_subdirectory("${HDR_DEPS_DIR}/libultrahdr-src" uhdr)
find_library(HOST_JXL NAMES jxl libjxl.so.0.11 REQUIRED)
find_library(HOST_JXL_THREADS NAMES jxl_threads libjxl_threads.so.0.11 REQUIRED)
find_library(HOST_JXL_CMS NAMES jxl_cms libjxl_cms.so.0.11 REQUIRED)
add_executable(still_export_test still_export_test.cpp ../../app/src/main/cpp/still_jxl.cpp
    "${HDR_DEPS_DIR}/libjxl-src/third_party/skcms/skcms.cc")
target_compile_features(still_export_test PRIVATE cxx_std_17)
target_compile_definitions(still_export_test PRIVATE __ANSI__ DISABLE_PERF_MEASUREMENT)
target_include_directories(still_export_test PRIVATE
    ../avif/include ${JNI_INCLUDE_DIRS} ../../app/src/main/cpp
    "${HDR_DEPS_DIR}/libultrahdr-src"
    "${HDR_DEPS_DIR}/libultrahdr-src/lib/include"
    "${HDR_DEPS_DIR}/libultrahdr-src/third_party/turbojpeg/src"
    "${HDR_DEPS_DIR}/libultrahdr-build/turbojpeg/src/turbojpeg-build"
    "${HDR_DEPS_DIR}/libjxl-src/third_party/skcms"
    "${HDR_DEPS_DIR}/libjxl-src/lib/include"
    "${HDR_DEPS_DIR}/libjxl-build/lib/include"
    "${HDR_DEPS_DIR}/jpegxr_src-src/jxrlib"
    "${HDR_DEPS_DIR}/jpegxr_src-src/jxrlib/jxrgluelib"
    "${HDR_DEPS_DIR}/jpegxr_src-src/jxrlib/common/include"
    "${HDR_DEPS_DIR}/jpegxr_src-src/jxrlib/image/sys"
    "${HDR_DEPS_DIR}/openjpeg-src/thirdparty/include")
# Keep the actual production packer; discard unrelated codec/JNI entrypoints.
target_compile_options(still_export_test PRIVATE -ffunction-sections -fdata-sections)
target_link_options(still_export_test PRIVATE -Wl,--gc-sections)
target_link_libraries(still_export_test PRIVATE pthread uhdr ${HOST_JXL} ${HOST_JXL_THREADS} ${HOST_JXL_CMS})

add_executable(raw_export_test raw_export_test.cpp)
target_compile_features(raw_export_test PRIVATE cxx_std_17)
target_compile_definitions(raw_export_test PRIVATE EHVIEWER_HDR_CODECS)
target_include_directories(raw_export_test PRIVATE ../avif/include ${JNI_INCLUDE_DIRS}
    "${HDR_DEPS_DIR}/libraw-src"
    "${HDR_DEPS_DIR}/libultrahdr-src/third_party/turbojpeg/src"
    "${HDR_DEPS_DIR}/libultrahdr-build/turbojpeg/src/turbojpeg-build")
target_compile_options(raw_export_test PRIVATE -ffunction-sections -fdata-sections)
target_link_options(raw_export_test PRIVATE -Wl,--gc-sections)
target_link_libraries(raw_export_test PRIVATE pthread)
