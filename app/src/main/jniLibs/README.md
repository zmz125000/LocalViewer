# EasyTier native libraries

The `arm64-v8a` JNI library bundles **EasyTier 2.7.0**, built without source
modifications from the upstream [v2.7.0 tag](https://github.com/EasyTier/EasyTier/releases/tag/v2.7.0).
Upstream marks this version as a pre-release.

Source archive: https://codeload.github.com/EasyTier/EasyTier/tar.gz/refs/tags/v2.7.0

SHA-256 of the source archive used:
`2a911ef9661398a71f85e0336f786e7e6d8a8058b1c6061454cfafb18f9c3b83`

Build environment: Linux x86_64, Rust `nightly-2026-05-15`, Android NDK
`29.0.14206865`, Android API 31, protoc 33.0. Cargo uses the upstream
`Cargo.lock` with `--locked`. The library has 16 KiB ELF load alignment.

To rebuild, extract the source archive, install the Rust Android target and
protoc, then run from the extracted source directory (adjust the tool paths):

```bash
rustup target add --toolchain nightly-2026-05-15 aarch64-linux-android
export PROTOC=/path/to/protoc/bin/protoc
ndk_toolchain=/path/to/android-ndk/toolchains/llvm/prebuilt/linux-x86_64
export LIBCLANG_PATH="$ndk_toolchain/musl/lib"
export BINDGEN_EXTRA_CLANG_ARGS_aarch64_linux_android="--sysroot=$ndk_toolchain/sysroot -isystem $ndk_toolchain/lib/clang/21/include"
export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="$ndk_toolchain/bin/aarch64-linux-android31-clang"
export CC_aarch64_linux_android="$CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER"
export AR_aarch64_linux_android="$ndk_toolchain/bin/llvm-ar"
cargo +nightly-2026-05-15 build --locked --release \
  --target aarch64-linux-android -p easytier-android-jni
```

Copy `target/aarch64-linux-android/release/libeasytier_android_jni.so` into
`arm64-v8a/`. The 2.7 JNI library includes the FFI implementation; do not bundle
the redundant `libeasytier_ffi.so` library.

Bundled library SHA-256 checksum:

```text
37b6d6d135c0a92a1c6e9b4fffad3ce2287699f9c1263f8560ee7d31ef538098  libeasytier_android_jni.so
```

Integration requirements: `collectNetworkInfos` takes an explicit entry limit
and returns nullable JSON. Status JSON omits default values and empty arrays,
and serializes NAT types as protobuf enum names. This app runs one instance
and collects one entry.
