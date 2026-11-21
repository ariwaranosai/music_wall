#!/usr/bin/env bash
set -euo pipefail

if [[ $# -lt 2 ]]; then
    echo "Usage: $0 ANDROID_NDK_ROOT ffmpeg-6.1.4.tar.xz [ABI ...]" >&2
    exit 2
fi

extension_dir="$(cd "$(dirname "$0")" && pwd)"
ndk_root="$(cd "$1" && pwd)"
toolchain=$(find "$ndk_root/toolchains/llvm/prebuilt" -mindepth 1 -maxdepth 1 -type d -print -quit)
executables="$toolchain/bin"
export PATH="$executables:$PATH"
archive="$(cd "$(dirname "$2")" && pwd)/$(basename "$2")"
expected=a231e3d5742c44b1cdaebfb98ad7b6200d12763e0b6db9e1e2c5891f2c083a18
if command -v sha256sum >/dev/null; then
    digest=$(sha256sum "$archive" | cut -d ' ' -f 1)
else
    digest=$(shasum -a 256 "$archive" | cut -d ' ' -f 1)
fi
[[ "$digest" == "$expected" ]] || { echo "FFmpeg archive checksum did not match" >&2; exit 1; }
shift 2
if [[ $# -eq 0 ]]; then
    set -- arm64-v8a armeabi-v7a x86_64 x86
fi

mkdir -p "$extension_dir/build" "$extension_dir/jniLibs"
build_root=$(mktemp -d "$extension_dir/build/ffmpeg.XXXXXX")
trap 'rm -rf "$build_root"' EXIT
tar -xf "$archive" -C "$build_root"
ln -s "$toolchain/sysroot" "$build_root/ndk-sysroot"

for abi in "$@"; do
    case "$abi" in
        arm64-v8a) arch=aarch64; target=aarch64-linux-android ;;
        armeabi-v7a) arch=arm; target=armv7a-linux-androideabi ;;
        x86_64) arch=x86_64; target=x86_64-linux-android ;;
        x86) arch=x86; target=i686-linux-android ;;
        *) echo "Unsupported ABI: $abi" >&2; exit 2 ;;
    esac
    cc="${target}26-clang"
    cxx="${target}26-clang++"
    destination="$build_root/$abi/install"
    staging="$destination/usr/local"
    mkdir -p "$build_root/$abi"
    (
        cd "$build_root/$abi"
        "$build_root/ffmpeg-6.1.4/configure" \
            --prefix=/usr/local --target-os=android --arch="$arch" --enable-cross-compile \
            --sysroot=../ndk-sysroot --cc="$cc" --cxx="$cxx" \
            --ar=llvm-ar --nm=llvm-nm --ranlib=llvm-ranlib --strip=llvm-strip \
            --extra-ldflags="-Wl,-z,max-page-size=16384" \
            --enable-shared --disable-static --disable-autodetect --disable-asm \
            --disable-programs --disable-doc --disable-network --disable-everything \
            --disable-avdevice --disable-avformat --disable-swscale --disable-postproc --disable-avfilter \
            --enable-swresample \
            --enable-decoder=alac,flac,vorbis,opus,mp3,mp2,aac,ac3,eac3,dca,mlp,pcm_alaw,pcm_mulaw
        make -j "${FFMPEG_BUILD_JOBS:-4}" DESTDIR="$destination" install
    )
    "$cxx" -shared -fPIC -O2 -std=c++17 -fvisibility=hidden -static-libstdc++ \
        -Wl,-z,max-page-size=16384 -Wl,-soname,libffmpegJNI.so \
        -I"$staging/include" "$extension_dir/jni/ffmpeg_jni.cc" -L"$staging/lib" \
        -lswresample -lavcodec -lavutil -llog -o "$staging/lib/libffmpegJNI.so"
    "$executables/llvm-strip" --strip-unneeded "$staging/lib/libffmpegJNI.so"
    mkdir -p "$extension_dir/jniLibs/$abi"
    for library in libavcodec.so libavutil.so libswresample.so libffmpegJNI.so; do
        cp -L "$staging/lib/$library" "$extension_dir/jniLibs/$abi/$library"
    done
done
