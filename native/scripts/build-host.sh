#!/usr/bin/env bash
# Build the Audacity core libraries for the Linux host (unit tests, smoke test).
#
# Usage: native/scripts/build-host.sh [--test] [extra CMake -D arguments...]
#   --test        run ctest (smoke test + any tests in native/tests) afterwards
# Environment:
#   BUILD_DIR     build directory          (default: native/build-host)
#   BUILD_TYPE    CMake build type         (default: RelWithDebInfo)
#   JOBS          parallel jobs            (default: nproc)
#   CMAKE, NINJA  tools to use             (default: cmake, ninja from PATH)
set -euo pipefail

NATIVE_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BUILD_DIR="${BUILD_DIR:-$NATIVE_DIR/build-host}"
BUILD_TYPE="${BUILD_TYPE:-RelWithDebInfo}"
JOBS="${JOBS:-$(nproc)}"
CMAKE="${CMAKE:-cmake}"
NINJA="${NINJA:-ninja}"

RUN_TESTS=0
ARGS=()
for a in "$@"; do
   case "$a" in
      --test) RUN_TESTS=1 ;;
      *) ARGS+=("$a") ;;
   esac
done

# Archive extraction needs a UTF-8 locale (also handled inside CMake).
export LC_ALL="${LC_ALL:-C.UTF-8}"

"$CMAKE" -S "$NATIVE_DIR" -B "$BUILD_DIR" -G Ninja \
   -DCMAKE_MAKE_PROGRAM="$(command -v "$NINJA")" \
   -DCMAKE_BUILD_TYPE="$BUILD_TYPE" \
   "${ARGS[@]}"
"$NINJA" -C "$BUILD_DIR" -j"$JOBS"

echo "Libraries: $BUILD_DIR/lib"
if [[ $RUN_TESTS == 1 ]]; then
   ctest --test-dir "$BUILD_DIR" --output-on-failure
fi
