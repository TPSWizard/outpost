#!/usr/bin/env bash
# Builds the on-device AI engine (llama.cpp + Outpost's JNI bridge) for arm64-v8a and x86_64
# and copies the stripped libraries into app/src/main/jniLibs/.
#
# Requirements: Android NDK r27+ and CMake 3.22+ (both installable with sdkmanager).
# Usage: ANDROID_HOME=/path/to/sdk scripts/build-native.sh [/path/to/llama.cpp]
# Without a llama.cpp path, CMake fetches the pinned commit from GitHub.
set -euo pipefail
SDK=${ANDROID_HOME:?set ANDROID_HOME}
NDK=${ANDROID_NDK:-$(ls -d "$SDK"/ndk/* | sort -V | tail -1)}
CMAKE_DIR=$(ls -d "$SDK"/cmake/* | sort -V | tail -1)
ROOT=$(cd "$(dirname "$0")/.." && pwd)
SRC_ARG=()
[[ $# -ge 1 ]] && SRC_ARG=(-DLLAMA_SRC="$1")
for ABI in arm64-v8a x86_64; do
  B="$ROOT/build-native/$ABI"
  "$CMAKE_DIR/bin/cmake" -S "$ROOT/app/src/main/cpp" -B "$B" -G Ninja -DCMAKE_MAKE_PROGRAM="$CMAKE_DIR/bin/ninja" \
    -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" -DANDROID_ABI=$ABI -DANDROID_PLATFORM=android-24 \
    -DANDROID_STL=c++_shared -DCMAKE_BUILD_TYPE=Release "${SRC_ARG[@]}"
  "$CMAKE_DIR/bin/cmake" --build "$B" -j"$(nproc)"
  OUT="$ROOT/app/src/main/jniLibs/$ABI"
  mkdir -p "$OUT"
  cp "$B"/bin/*.so "$B"/liboutpost_llm.so "$OUT"/
  # Shared C++ runtime (also shipped by libzim; Gradle keeps one copy via pickFirst).
  case $ABI in arm64-v8a) TRIPLE=aarch64-linux-android;; x86_64) TRIPLE=x86_64-linux-android;; esac
  cp "$NDK"/toolchains/llvm/prebuilt/*/sysroot/usr/lib/$TRIPLE/libc++_shared.so "$OUT"/
  "$NDK"/toolchains/llvm/prebuilt/*/bin/llvm-strip --strip-unneeded "$OUT"/*.so
done
echo "Native libraries written to app/src/main/jniLibs/"
