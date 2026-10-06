#!/usr/bin/env bash
# Cross-compile the Audacity core libraries for Android with the NDK, the
# same way Gradle's externalNativeBuild does (minSdk 28, c++_shared).
#
# Usage: native/scripts/build-android.sh <abi> [extra CMake -D arguments...]
#   <abi>  arm64-v8a | x86_64 | armeabi-v7a | x86
# Environment:
#   ANDROID_SDK_ROOT / ANDROID_HOME   SDK (default: /opt/android-sdk)
#   ANDROID_NDK_ROOT                  NDK (default: $SDK/ndk/28.2.13676358)
#   CMAKE_VERSION                     SDK CMake to use (default: 3.31.6)
#   ANDROID_PLATFORM                  (default: android-28)
#   BUILD_TYPE                        (default: Release)
#   BUILD_DIR                         (default: native/build-android-<short abi>)
#   JOBS                              (default: nproc)
#   TARGETS                           ninja targets (default: all)
set -euo pipefail

ABI="${1:-}"
if [[ -z "$ABI" ]]; then
   echo "usage: $0 <arm64-v8a|x86_64|armeabi-v7a|x86> [cmake args...]" >&2
   exit 2
fi
shift

NATIVE_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-/opt/android-sdk}}"
NDK="${ANDROID_NDK_ROOT:-$SDK/ndk/28.2.13676358}"
CMAKE_BIN="$SDK/cmake/${CMAKE_VERSION:-3.31.6}/bin"
PLATFORM="${ANDROID_PLATFORM:-android-28}"
BUILD_TYPE="${BUILD_TYPE:-Release}"
JOBS="${JOBS:-$(nproc)}"

case "$ABI" in
   arm64-v8a) SHORT=arm64 ;;
   x86_64)    SHORT=x86_64 ;;
   armeabi-v7a) SHORT=armv7 ;;
   x86)       SHORT=x86 ;;
   *) echo "unknown ABI: $ABI" >&2; exit 2 ;;
esac
BUILD_DIR="${BUILD_DIR:-$NATIVE_DIR/build-android-$SHORT}"

[[ -f "$NDK/build/cmake/android.toolchain.cmake" ]] || { echo "NDK not found at $NDK" >&2; exit 1; }
[[ -x "$CMAKE_BIN/cmake" ]] || { echo "SDK CMake not found at $CMAKE_BIN" >&2; exit 1; }

export LC_ALL="${LC_ALL:-C.UTF-8}"

"$CMAKE_BIN/cmake" -S "$NATIVE_DIR" -B "$BUILD_DIR" -G Ninja \
   -DCMAKE_MAKE_PROGRAM="$CMAKE_BIN/ninja" \
   -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
   -DANDROID_ABI="$ABI" \
   -DANDROID_PLATFORM="$PLATFORM" \
   -DANDROID_STL=c++_shared \
   -DCMAKE_BUILD_TYPE="$BUILD_TYPE" \
   "$@"
# shellcheck disable=SC2086
"$CMAKE_BIN/ninja" -C "$BUILD_DIR" -j"$JOBS" ${TARGETS:-}

"$NATIVE_DIR/scripts/check-android-libs.sh" "$BUILD_DIR/lib" "$NDK"
