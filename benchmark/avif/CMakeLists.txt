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

# Optional PNG reader regression using the app's cached skcms headers.
set(SKCMS_SOURCE_DIR "${AVIF_SOURCE_DIR}/../libjxl-src/third_party/skcms" CACHE PATH "skcms headers from the app native dependency cache")
if(EXISTS "${SKCMS_SOURCE_DIR}/skcms.h")
    target_include_directories(avif_hdr_benchmark PRIVATE "${SKCMS_SOURCE_DIR}")
    add_executable(png_hdr_test png_hdr_test.cpp)
    target_compile_features(png_hdr_test PRIVATE cxx_std_17)
    target_include_directories(png_hdr_test PRIVATE include ${JNI_INCLUDE_DIRS} "${UHDR_SOURCE_DIR}"
        "${AVIF_SOURCE_DIR}/include" "${SKCMS_SOURCE_DIR}")
    target_compile_options(png_hdr_test PRIVATE -ffunction-sections -fdata-sections)
    target_link_options(png_hdr_test PRIVATE -Wl,--gc-sections)
    target_link_libraries(png_hdr_test PRIVATE pthread)
endif()
