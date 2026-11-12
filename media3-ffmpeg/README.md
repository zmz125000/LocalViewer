# Media3 FFmpeg audio decoder

This module vendors the AndroidX Media3 FFmpeg decoder source from the
[`1.11.0` tag](https://github.com/androidx/media/tree/1.11.0/libraries/decoder_ffmpeg).
It is licensed under Apache 2.0. The native `libffmpegJNI.so` files are built
from [FFmpeg commit `573f2b81ebd97da81def2ba762e81b0328547004`](https://github.com/FFmpeg/FFmpeg/commit/573f2b81ebd97da81def2ba762e81b0328547004)
using the enabled decoders in `build-native.sh`. FFmpeg is licensed separately
under LGPL 2.1 or later; its license text is in `LICENSE-FFMPEG-LGPL-2.1`.

To rebuild on Linux with the Android NDK installed:

```sh
git init /tmp/ffmpeg-6.0
git -C /tmp/ffmpeg-6.0 remote add origin https://github.com/FFmpeg/FFmpeg.git
git -C /tmp/ffmpeg-6.0 fetch --depth 1 origin 573f2b81ebd97da81def2ba762e81b0328547004
git -C /tmp/ffmpeg-6.0 checkout FETCH_HEAD
./media3-ffmpeg/build-native.sh /tmp/ffmpeg-6.0 /path/to/Android/Sdk/ndk/29.0.14206865
```

The Media3 FFmpeg extension supplies an audio renderer. Its experimental video
renderer is unfinished and does not support any video format. Video continues
to use the Android platform decoders.
