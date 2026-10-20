cmake_minimum_required(VERSION 3.22)
project(avif_hdr_benchmark LANGUAGES C CXX)

# Supply the same libavif/libultrahdr sources as the app and a host dav1d via
# PKG_CONFIG_PATH. No downloads or Android device are needed.
set(AVIF_SOURCE_DIR "" CACHE PATH "libavif source directory")
set(UHDR_SOURCE_DIR "" CACHE PATH "libultrahdr source directory")
find_package(JNI REQUIRED COMPONENTS JVM)
set(AVIF_CODEC_DAV1D SYSTEM CACHE STRING "" FORCE)
set(AVIF_LIBYUV OFF CACHE STRING "" FORCE)
set(AVIF_BUILD_APPS OFF CACHE BOOL "" FORCE)
set(AVIF_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
add_subdirectory("${AVIF_SOURCE_DIR}" libavif)

add_executable(avif_hdr_benchmark avif_hdr_benchmark.cpp)
target_compile_features(avif_hdr_benchmark PRIVATE cxx_std_17)
target_include_directories(avif_hdr_benchmark PRIVATE include ${JNI_INCLUDE_DIRS} "${UHDR_SOURCE_DIR}")
# Test the private production functions directly. Discard unused JNI entrypoints
# so this executable does not need to link the Android encoder/bitmap libraries.
target_compile_options(avif_hdr_benchmark PRIVATE -ffunction-sections -fdata-sections)
target_link_options(avif_hdr_benchmark PRIVATE -Wl,--gc-sections)
target_link_libraries(avif_hdr_benchmark PRIVATE avif pthread)
