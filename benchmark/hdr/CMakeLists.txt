cmake_minimum_required(VERSION 3.22)
project(hdr_pack_benchmark LANGUAGES CXX)

# Linux host: reuse codec headers from an existing Android native build.
# cmake -S benchmark/hdr -B /tmp/hdr-bench -DCMAKE_BUILD_TYPE=Release \
#   -DHDR_DEPS_DIR=/path/to/app/.cxx/RelWithDebInfo/<id>/arm64-v8a/_deps
# cmake --build /tmp/hdr-bench -j && /tmp/hdr-bench/hdr_pack_benchmark
set(HDR_DEPS_DIR "" CACHE PATH "Native build _deps directory")
find_package(JNI REQUIRED COMPONENTS JVM)
add_executable(hdr_pack_benchmark hdr_pack_benchmark.cpp ../../app/src/main/cpp/hdr_convert.cpp)
target_compile_features(hdr_pack_benchmark PRIVATE cxx_std_17)
target_compile_definitions(hdr_pack_benchmark PRIVATE __ANSI__ DISABLE_PERF_MEASUREMENT)
target_include_directories(hdr_pack_benchmark PRIVATE
    ../avif/include ${JNI_INCLUDE_DIRS} ../../app/src/main/cpp
    "${HDR_DEPS_DIR}/libultrahdr-src"
    "${HDR_DEPS_DIR}/libultrahdr-src/lib/include"
    "${HDR_DEPS_DIR}/libultrahdr-src/third_party/turbojpeg/src"
    "${HDR_DEPS_DIR}/libultrahdr-build/turbojpeg/src/turbojpeg-build"
    "${HDR_DEPS_DIR}/libjxl-src/third_party/skcms"
    "${HDR_DEPS_DIR}/jpegxr_src-src/jxrlib"
    "${HDR_DEPS_DIR}/jpegxr_src-src/jxrlib/jxrgluelib"
    "${HDR_DEPS_DIR}/jpegxr_src-src/jxrlib/common/include"
    "${HDR_DEPS_DIR}/jpegxr_src-src/jxrlib/image/sys"
    "${HDR_DEPS_DIR}/openjpeg-src/thirdparty/include")
# Keep the actual production packer; discard unrelated codec/JNI entrypoints.
target_compile_options(hdr_pack_benchmark PRIVATE -ffunction-sections -fdata-sections)
target_link_options(hdr_pack_benchmark PRIVATE -Wl,--gc-sections)
target_link_libraries(hdr_pack_benchmark PRIVATE pthread)
