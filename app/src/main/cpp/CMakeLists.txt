cmake_minimum_required(VERSION 3.14)
project(ehviewer C CXX)
include(ExternalProject)
include(FetchContent)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

if (NOT CMAKE_BUILD_TYPE STREQUAL "Debug")
    string(CONCAT CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -O3 -ffast-math -fvisibility=hidden -fvisibility-inlines-hidden -funroll-loops -flto "
            "-ffile-prefix-map=${ANDROID_TOOLCHAIN_ROOT}= "
            "-ffile-prefix-map=${CMAKE_SOURCE_DIR}= "
            "-ffile-prefix-map=${CMAKE_BINARY_DIR}= "
            "-mllvm -polly "
            "-mllvm -polly-run-dce "
            "-mllvm -polly-run-inliner "
            "-mllvm -polly-isl-arg=--no-schedule-serialize-sccs "
            "-mllvm -polly-ast-use-context "
            "-mllvm -polly-detect-keep-going "
            "-mllvm -polly-position=before-vectorizer "
            "-mllvm -polly-vectorizer=stripmine "
            "-mllvm -polly-detect-profitability-min-per-loop-insts=40 "
            "-mllvm -polly-invariant-load-hoisting")
endif (NOT CMAKE_BUILD_TYPE STREQUAL "Debug")

option(BUILD_TESTING OFF)
option(XZ_DOC OFF)
option(XZ_LZIP_DECODER OFF)
option(XZ_MICROLZMA_DECODER OFF)
option(XZ_MICROLZMA_ENCODER OFF)
option(XZ_TOOL_LZMADEC OFF)
option(XZ_TOOL_LZMAINFO OFF)
option(XZ_TOOL_XZ OFF)
option(XZ_TOOL_XZDEC OFF)
FetchContent_Declare(
        liblzma
        GIT_REPOSITORY https://github.com/tukaani-project/xz.git
        GIT_TAG v5.8.3
        GIT_SHALLOW 1
)

FetchContent_MakeAvailable(liblzma)
include_directories(${liblzma_SOURCE_DIR}/src/liblzma/api)

# Build GNUTLS libnettle
if (CMAKE_HOST_SYSTEM_NAME STREQUAL "Windows")
    set(NDK_VERSION "${ANDROID_NDK_MAJOR}.${ANDROID_NDK_MINOR}.${ANDROID_NDK_BUILD}")
    set(TOOLCHAIN "$ANDROID_HOME/ndk/${NDK_VERSION}/toolchains/llvm/prebuilt/linux-x86_64/bin")
    set(WSL wsl)
else ()
    set(TOOLCHAIN "${ANDROID_TOOLCHAIN_ROOT}/bin")
endif ()
set(CONFIGURE_COMMAND ${WSL} ./.bootstrap && ${WSL} ./configure --disable-public-key --disable-dependency-tracking
        "--host=${CMAKE_C_COMPILER_TARGET}"
        "CFLAGS=${CMAKE_C_FLAGS} -Qunused-arguments"
        "AR=${TOOLCHAIN}/llvm-ar"
        "CC=${TOOLCHAIN}/clang --target=${CMAKE_C_COMPILER_TARGET}"
        "RANLIB=${TOOLCHAIN}/llvm-ranlib"
)
ExternalProject_Add(
        nettle
        GIT_REPOSITORY https://gitlab.com/gnutls/nettle.git
        GIT_TAG nettle_4.0_release_20260205
        GIT_SHALLOW 1
        GIT_CONFIG core.autocrlf=input
        CONFIGURE_COMMAND ${CONFIGURE_COMMAND}
        BUILD_COMMAND ${WSL} make -j4 libnettle.a
        INSTALL_COMMAND ""
        BUILD_IN_SOURCE 1
        BUILD_BYPRODUCTS <SOURCE_DIR>/libnettle.a
)

ExternalProject_Get_Property(nettle SOURCE_DIR)
include_directories(${SOURCE_DIR}/..)
add_library(libnettle STATIC IMPORTED)
set_property(TARGET libnettle PROPERTY IMPORTED_LOCATION ${SOURCE_DIR}/libnettle.a)
add_dependencies(libnettle nettle)

# Configure libnettle support for libarchive
set(HAVE_LIBNETTLE 1)
set(HAVE_NETTLE_AES_H 1)
set(HAVE_NETTLE_HMAC_H 1)
set(HAVE_NETTLE_MD5_H 1)
set(HAVE_NETTLE_PBKDF2_H 1)
set(HAVE_NETTLE_RIPEMD160_H 1)
set(HAVE_NETTLE_SHA_H 1)

# Configure lzma support for libarchive
SET(HAVE_LIBLZMA 1)
SET(HAVE_LZMA_H 1)
SET(HAVE_LZMA_STREAM_ENCODER_MT 1)
SET(HAVE_LZMADEC_H 1)
SET(HAVE_LIBLZMADEC 1)

option(ENABLE_OPENSSL OFF)
option(ENABLE_TAR OFF)
option(ENABLE_CPIO OFF)
option(ENABLE_CAT OFF)
option(ENABLE_UNZIP OFF)
option(ENABLE_TEST OFF)

set(LIBARCHIVE_PATCH
        ${CMAKE_CURRENT_LIST_DIR}/0001-Fix-zip_time-performance.patch
        ${CMAKE_CURRENT_LIST_DIR}/0002-Use-UTF-8-as-default-charset-on-bionic.patch
)
FetchContent_Declare(
        libarchive
        GIT_REPOSITORY https://github.com/libarchive/libarchive.git
        GIT_TAG v3.8.7
        GIT_SHALLOW 1
        PATCH_COMMAND git apply --check -R ${LIBARCHIVE_PATCH} || git apply ${LIBARCHIVE_PATCH}
)

FetchContent_MakeAvailable(libarchive)
target_link_libraries(archive_static liblzma libnettle)
include_directories(${libarchive_SOURCE_DIR}/libarchive)

FetchContent_Declare(
        libwebp
        GIT_REPOSITORY https://github.com/webmproject/libwebp.git
        GIT_TAG v1.6.0
        GIT_SHALLOW 1
)

option(WEBP_BUILD_ANIM_UTILS OFF)
option(WEBP_BUILD_CWEBP OFF)
option(WEBP_BUILD_DWEBP OFF)
option(WEBP_BUILD_VWEBP OFF)
option(WEBP_BUILD_WEBPINFO OFF)
option(WEBP_BUILD_LIBWEBPMUX OFF)
option(WEBP_BUILD_EXTRAS OFF)
option(WEBP_ENABLE_SWAP_16BIT_CSP ON)

# Disable runtime detection of NEON support as NEON is required since API level 23
# https://source.android.com/docs/compatibility/6.0/android-6.0-cdd#3_3_1_application_binary_interfaces
unset(ANDROID)
FetchContent_MakeAvailable(libwebp)
if (CMAKE_SYSTEM_NAME STREQUAL "Android")
    set(ANDROID 1)
endif ()

set_property(DIRECTORY APPEND PROPERTY CMAKE_CONFIGURE_DEPENDS ../../../../rust-toolchain.toml)
unset(Rust_TOOLCHAIN CACHE)
FetchContent_Declare(
        Corrosion
        GIT_REPOSITORY https://github.com/FooIbar/corrosion.git
        GIT_TAG v0.6.1
        GIT_SHALLOW 1
)

FetchContent_MakeAvailable(Corrosion)
corrosion_import_crate(
        MANIFEST_PATH ../rust/Cargo.toml
        LOCKED
        FEATURES $<IF:$<VERSION_GREATER_EQUAL:${ANDROID_NATIVE_API_LEVEL},26>,android-26,android>
        FLAGS $<$<NOT:$<CONFIG:Debug>>:-Z build-std -Z build-std-features=>
)
corrosion_add_target_rustflags(ehviewer_rust $<$<NOT:$<CONFIG:Debug>>:-Z unstable-options -C panic=immediate-abort -Z location-detail=none -Z fmt-debug=none>)
corrosion_link_libraries(ehviewer_rust android jnigraphics log webp webpdemux)

# ── jxrlib (Microsoft JPEG XR, packaged in brion/jpegxr) ─────────────────
FetchContent_Declare(
        jpegxr_src
        GIT_REPOSITORY https://github.com/bvibber/jpegxr.git
        GIT_TAG 0.3.1
        GIT_SHALLOW 1
)
FetchContent_GetProperties(jpegxr_src)
if (NOT jpegxr_src_POPULATED)
    FetchContent_Populate(jpegxr_src)
endif ()
set(JXRLIB_ROOT ${jpegxr_src_SOURCE_DIR}/jxrlib)
add_library(jxrlib STATIC
        ${JXRLIB_ROOT}/image/sys/adapthuff.c
        ${JXRLIB_ROOT}/image/sys/image.c
        ${JXRLIB_ROOT}/image/sys/strcodec.c
        ${JXRLIB_ROOT}/image/sys/strPredQuant.c
        ${JXRLIB_ROOT}/image/sys/strTransform.c
        ${JXRLIB_ROOT}/image/sys/perfTimerANSI.c
        ${JXRLIB_ROOT}/image/decode/decode.c
        ${JXRLIB_ROOT}/image/decode/postprocess.c
        ${JXRLIB_ROOT}/image/decode/segdec.c
        ${JXRLIB_ROOT}/image/decode/strdec.c
        ${JXRLIB_ROOT}/image/decode/strInvTransform.c
        ${JXRLIB_ROOT}/image/decode/strPredQuantDec.c
        ${JXRLIB_ROOT}/image/decode/JXRTranscode.c
        ${JXRLIB_ROOT}/image/encode/encode.c
        ${JXRLIB_ROOT}/image/encode/segenc.c
        ${JXRLIB_ROOT}/image/encode/strenc.c
        ${JXRLIB_ROOT}/image/encode/strFwdTransform.c
        ${JXRLIB_ROOT}/image/encode/strPredQuantEnc.c
        ${JXRLIB_ROOT}/jxrgluelib/JXRGlue.c
        ${JXRLIB_ROOT}/jxrgluelib/JXRGlueJxr.c
        ${JXRLIB_ROOT}/jxrgluelib/JXRGluePFC.c
        ${JXRLIB_ROOT}/jxrgluelib/JXRMeta.c
)
target_include_directories(jxrlib PUBLIC
        ${JXRLIB_ROOT}
        ${JXRLIB_ROOT}/common/include
        ${JXRLIB_ROOT}/image/sys
        ${JXRLIB_ROOT}/jxrgluelib
)
target_compile_definitions(jxrlib PUBLIC __ANSI__ DISABLE_PERF_MEASUREMENT)
target_compile_options(jxrlib PRIVATE
        -Wno-unused-function
        -Wno-unused-variable
        -Wno-sign-compare
        -Wno-shift-negative-value
        -Wno-extra-tokens
        -Wno-missing-field-initializers
        -Wno-constant-conversion
        -Wno-deprecated-declarations
)

# ── libultrahdr (Google Ultra HDR encode) ────────────────────────────────
set(UHDR_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(UHDR_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(UHDR_BUILD_BENCHMARK OFF CACHE BOOL "" FORCE)
set(UHDR_BUILD_FUZZERS OFF CACHE BOOL "" FORCE)
set(UHDR_BUILD_JAVA OFF CACHE BOOL "" FORCE)
set(UHDR_BUILD_DEPS ON CACHE BOOL "" FORCE)
set(UHDR_ENABLE_INSTALL OFF CACHE BOOL "" FORCE)
set(UHDR_ENABLE_GLES OFF CACHE BOOL "" FORCE)
set(UHDR_WRITE_XMP ON CACHE BOOL "" FORCE)
set(UHDR_WRITE_ISO ON CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)

FetchContent_Declare(
        libultrahdr
        GIT_REPOSITORY https://github.com/google/libultrahdr.git
        GIT_TAG v1.5.1
        GIT_SHALLOW 1
)
FetchContent_MakeAvailable(libultrahdr)

# Build and link our app's native lib
add_library(${PROJECT_NAME} SHARED archive.c gifutils.c hash.c natsort/strnatcmp.c hdr_convert.cpp)
target_include_directories(${PROJECT_NAME} PRIVATE
        ${JXRLIB_ROOT}/jxrgluelib
        ${JXRLIB_ROOT}/common/include
        ${JXRLIB_ROOT}/image/sys
)
# libultrahdr public header target name is typically "uhdr"
if (TARGET uhdr)
    set(UHDR_LIB uhdr)
elseif (TARGET libuhdr)
    set(UHDR_LIB libuhdr)
elseif (TARGET ultrahdr)
    set(UHDR_LIB ultrahdr)
else ()
    message(FATAL_ERROR "libultrahdr CMake target not found (expected uhdr)")
endif ()
target_link_libraries(
        ${PROJECT_NAME} archive_static ehviewer_rust z jxrlib ${UHDR_LIB} log
        -Wl,-u,JNI_OnLoad -Wl,--undefined-glob,Java_*
        -Wl,--version-script,${CMAKE_SOURCE_DIR}/libehviewer.map.txt
)
