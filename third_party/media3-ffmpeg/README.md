# Media3 FFmpeg decoder

The Java renderer and JNI adapter come from [AndroidX Media3 1.11.1](https://github.com/androidx/media/tree/1.11.1/libraries/decoder_ffmpeg) under the Apache 2.0 license. The app uses the native FFmpeg 6.1.4 libraries under LGPL 2.1 or later.

To rebuild the shared libraries, use Android NDK r28c and the [FFmpeg 6.1.4 source archive](https://ffmpeg.org/releases/ffmpeg-6.1.4.tar.xz).

```sh
./third_party/media3-ffmpeg/build-android.sh /path/to/android-ndk-r28c /path/to/ffmpeg-6.1.4.tar.xz
```

The script checks the source checksum and builds the codec libraries separately from the JNI adapter. The result in `jniLibs` can be rebuilt or replaced by compatible shared libraries before assembling the APK. The app includes the LGPL text and source information under `assets/licenses`.
