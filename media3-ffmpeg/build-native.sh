#!/usr/bin/env bash
set -euo pipefail

# Build Media3 1.11.0's FFmpeg JNI wrapper against FFmpeg release/6.0.
# Usage: ./media3-ffmpeg/build-native.sh /path/to/FFmpeg /path/to/android-ndk
module_dir="$(cd "$(dirname "$0")" && pwd)"
ffmpeg_source="$(realpath "${1:?FFmpeg source directory required}")"
ndk_dir="$(realpath "${2:?Android NDK directory required}")"
jni_dir="$module_dir/src/main/jni"
ffmpeg_link="$jni_dir/ffmpeg"
expected_commit=573f2b81ebd97da81def2ba762e81b0328547004

if [[ "$(git -C "$ffmpeg_source" rev-parse HEAD)" != "$expected_commit" ]]; then
    echo "Expected FFmpeg commit $expected_commit" >&2
    exit 1
fi
if [[ -e "$ffmpeg_link" || -L "$ffmpeg_link" ]]; then
    echo "$ffmpeg_link already exists" >&2
    exit 1
fi
ln -s "$ffmpeg_source" "$ffmpeg_link"
trap 'rm -f "$ffmpeg_link"' EXIT

bash "$jni_dir/build_ffmpeg.sh" "$module_dir/src/main" "$ndk_dir" linux-x86_64 31 \
    vorbis opus flac alac pcm_mulaw pcm_alaw mp3 amrnb amrwb aac ac3 eac3 dca mlp truehd

for abi in arm64-v8a armeabi-v7a x86_64; do
    build_dir="$module_dir/build/native/$abi"
    cmake -S "$jni_dir" -B "$build_dir" -G Ninja \
        -DCMAKE_TOOLCHAIN_FILE="$ndk_dir/build/cmake/android.toolchain.cmake" \
        -DANDROID_ABI="$abi" -DANDROID_PLATFORM=android-31 \
        -DCMAKE_BUILD_TYPE=Release
    cmake --build "$build_dir"
    mkdir -p "$module_dir/src/main/jniLibs/$abi"
    cp "$build_dir/libffmpegJNI.so" "$module_dir/src/main/jniLibs/$abi/"
    "$ndk_dir/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip" --strip-debug \
        "$module_dir/src/main/jniLibs/$abi/libffmpegJNI.so"
done
