#!/usr/bin/env bash
# Sanity checks of an Android library directory produced by the superbuild:
#  * every file is named lib*.so and its SONAME equals its file name
#    (Android packages/extracts only lib*.so, DT_NEEDED must match files)
#  * 64-bit libraries have 16 KB-aligned LOAD segments
#  * no library contains a static C++ runtime (c++_shared everywhere)
#  * every DT_NEEDED entry is either in the directory, libc++_shared.so
#    (packaged by Gradle) or an NDK system library
#  * every shared library was linked with -Wl,--no-undefined (the NDK
#    default), i.e. all its symbols resolved against its DT_NEEDED libraries
#    at link time -- checked in <libdir>/../build.ninja when present
# Usage: check-android-libs.sh <libdir> [ndk-dir]
set -euo pipefail
LIBDIR="${1:?usage: $0 <libdir> [ndk]}"
NDK="${2:-${ANDROID_NDK_ROOT:-/opt/android-sdk/ndk/28.2.13676358}}"
READELF="$(ls "$NDK"/toolchains/llvm/prebuilt/*/bin/llvm-readelf | head -1)"
NM="$(ls "$NDK"/toolchains/llvm/prebuilt/*/bin/llvm-nm | head -1)"
SYSTEM_LIBS="libc.so libm.so libdl.so liblog.so libz.so libandroid.so libaaudio.so libOpenSLES.so libmediandk.so libc++_shared.so"

fail=0
count=0
for f in "$LIBDIR"/*; do
   name="$(basename "$f")"
   if [[ -L "$f" ]]; then
      echo "ERROR: $name is a symlink (versioned shared library?)"; fail=1; continue
   fi
   [[ "$name" == lib*.so ]] || { echo "ERROR: $name is not named lib*.so"; fail=1; continue; }
   count=$((count+1))
   soname="$("$READELF" -d "$f" | sed -n 's/.*(SONAME).*\[\(.*\)\]/\1/p')"
   [[ "$soname" == "$name" ]] || { echo "ERROR: $name has SONAME '$soname'"; fail=1; }
   # 64-bit ABIs: LOAD segments must be aligned for 16 KB pages (required by
   # Google Play for apps targeting Android 15+; NDK r28 default)
   if "$READELF" -h "$f" | grep -q 'Class:.*ELF64'; then
      while read -r align; do
         if (( align < 0x4000 )); then
            echo "ERROR: $name has a LOAD segment aligned to $align (< 16 KB)"; fail=1; break
         fi
      done < <("$READELF" -lW "$f" | awk '$1=="LOAD"{print $NF}')
   fi
   # One C++ runtime for all libraries (ANDROID_STL=c++_shared): no library
   # may carry its own copy of libc++abi
   if "$NM" -D --defined-only "$f" | grep -qE ' T (__cxa_throw|__gxx_personality_v0)$'; then
      echo "ERROR: $name contains a static C++ runtime (built without ANDROID_STL=c++_shared?)"; fail=1
   fi
   while read -r dep; do
      [[ -z "$dep" ]] && continue
      if [[ -f "$LIBDIR/$dep" ]] || [[ " $SYSTEM_LIBS " == *" $dep "* ]]; then
         continue
      fi
      echo "ERROR: $name needs $dep, which is neither built nor an NDK system library"; fail=1
   done < <("$READELF" -d "$f" | sed -n 's/.*(NEEDED).*\[\(.*\)\]/\1/p')
done
NINJA_FILE="$(dirname "$LIBDIR")/build.ninja"
if [[ -f "$NINJA_FILE" ]]; then
   missing="$(awk '
      /^build .*_SHARED_LIBRARY_LINKER__/ { target=$2; sub(":$","",target); shared=1; next }
      /^build / { shared=0 }
      shared && /^  LINK_FLAGS = / { if ($0 !~ /--no-undefined/) print target; shared=0 }
   ' "$NINJA_FILE")"
   nshared="$(grep -c '^build .*_SHARED_LIBRARY_LINKER__' "$NINJA_FILE" || true)"
   if [[ -n "$missing" ]]; then
      echo "ERROR: linked without --no-undefined:"; echo "$missing"; fail=1
   else
      echo "all $nshared shared-library link commands use -Wl,--no-undefined"
   fi
fi
echo "checked $count shared libraries in $LIBDIR"
if [[ $fail != 0 ]]; then
   echo "check-android-libs: FAILED"; exit 1
fi
echo "check-android-libs: OK"
