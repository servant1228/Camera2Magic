#!/data/data/com.termux/files/usr/bin/bash
# On-device compile check for libcamera3.so (Termux).
#
# The real build goes through app/src/main/cpp/CMakeLists.txt with the Android
# NDK (see app/build.gradle's buildNative task). Termux has clang and the
# platform's android/* + jni.h headers, but no EGL/GLES headers — that is why
# the engine declares those entry points itself in gl_abi.h. This script builds
# the exact same sources directly, which catches compile/link errors long before
# an APK is assembled.
set -e
cd "$(dirname "$0")"

OUT="${1:-build/libcamera3.so}"
CXX="${CXX:-clang++}"
mkdir -p "$(dirname "$OUT")"

"$CXX" \
    --target=aarch64-linux-android33 \
    -std=c++20 -O2 -fPIC -shared \
    -fno-exceptions -fno-rtti -fvisibility=hidden -nostdlib++ \
    -Wall -Wextra \
    -I. \
    engine.cpp jpeg_encoder.cpp native_bridge.cpp \
    -o "$OUT" \
    -Wl,--no-undefined -Wl,-z,now -Wl,-soname,libcamera3.so \
    -L/system/lib64 \
    -landroid -llog -lEGL -lGLESv3 -ldl -lm

echo "built $OUT"
llvm-readelf -d "$OUT" | grep NEEDED
