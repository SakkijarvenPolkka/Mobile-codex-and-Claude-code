# JNI glue (`native/jni`)

`libaudacity-jni.so` connects the Kotlin `object NativeBridge`
(`engine/src/main/java/io/github/sakkijarvenpolkka/audacity/engine/NativeBridge.kt`)
to the bridge's public header `bridge/include/aubridge/Bridge.h`. Contract:
`bridge/API.md` §2 (JNI surface), §4 (events), §6 (lock-free reads), §7
(display calls).

## Layout

```
libaudacity-jni.so ──DT_NEEDED──► libaudacity-bridge.so ──► lib-*.so, libmod-*.so, wxBase, ...
        │                                   (whole-archive of core + modules)
        └── liblog.so, libc++_shared.so
```

* A separate library rather than code inside `libaudacity-bridge.so`: the
  bridge (and its C++ host tests) stays free of JNI, and relinking the glue
  never relinks the bridge.
* `NativeBridge.isLoaded` calls `System.loadLibrary("audacity-jni")`; Android
  (API ≥ 23) loads the DT_NEEDED libraries from the APK's native library
  directory by itself. Gradle's `externalNativeBuild` builds the default
  targets of `native/CMakeLists.txt`, so `libaudacity-jni.so` is packaged
  with every other shared library (plus `libc++_shared.so`).
* The only exported symbol is `JNI_OnLoad` (`-fvisibility=hidden`); it
  caches the `JavaVM`, the `EngineListener` class (global ref) and its
  `onEvent(Ljava/lang/String;[B)V` method id, and binds every external of
  `NativeBridge` with `RegisterNatives` (one by one; `stop` is optional, the
  others are required — a mismatch makes the load fail with
  `UnsatisfiedLinkError`, and the app falls back to the fake engine).
  ProGuard/R8: `engine/consumer-rules.pro` keeps `NativeBridge`,
  `EngineListener` and its implementations (names are looked up at load
  time).
* Host: built when a JDK's `jni.h` is found (`$JAVA_HOME/include`, else the
  usual `/usr/lib/jvm/*` locations). It never links `libjvm`; RUNPATH
  `$ORIGIN` finds the bridge next to it, so `System.load(<abs path>)` works
  without `LD_LIBRARY_PATH`.

## Files

| file | content |
|---|---|
| `AudacityJni.cpp` | `JNI_OnLoad`, `RegisterNatives`, one native per API.md §2 row (+ `stop`) |
| `JniEventSink.{h,cpp}` | `aubridge::EventSink` → `EngineListener.onEvent(String, byte[])` |
| `JniSupport.{h,cpp}` | `JavaVM` cache, thread attach/detach, UTF-8 byte arrays, logging, error envelope |
| `tests/` | host ctest `bridge-jni` (label `jni`): Java mirrors of `NativeBridge`/`EngineListener` + a driver, run with `-Xcheck:jni` |

## Rules the code follows

* **Strings** cross as standard UTF-8 `byte[]` (`GetByteArrayRegion` /
  `NewByteArray`), never `NewStringUTF`/`GetStringUTFChars` (modified UTF-8),
  except the ASCII event type (non-ASCII bytes would become `?`).
* **No C++ exception escapes** into the JVM: every native catches
  everything and maps it to a result (`INTERNAL` envelope, `-3`, `false`,
  `null`). Java exceptions thrown by `onEvent` are logged
  (`ExceptionDescribe`, first 20) and cleared; the engine goes on.
* **No JNI while the bridge blocks.** `invoke` and the display calls copy
  their arguments into native buffers, call the bridge (which may wait for
  the engine thread for a long time), then copy the result into the Java
  array with `Set<Type>ArrayRegion` — a single memcpy, no
  `Get/ReleasePrimitiveArrayCritical` held across a blocking call. A display
  result is copied only when the bridge delivered it (status ≥ 0 or `-2`);
  otherwise the Java array is untouched. Sizes are validated before
  allocating (`count` 1…65536, `rows` 1…4096, the array must be large
  enough) and answered with `-1`.
* `readTransport`/`readMeters` (UI thread, every frame) use stack buffers:
  no allocation, no lock.
* **Threads.** Events come from the engine thread (and occasionally from
  helper threads that log). `AttachedEnv()` attaches such a native thread
  once, as a *daemon* thread named after its pthread name (`AudacityEngine`),
  and a `thread_local` guard detaches it when the thread exits — before
  ART's own pthread-key check, so no "thread exited while attached" abort.
  Threads that are already attached (Java threads inside a native call) are
  used as they are and never detached. During thread exit the glue never
  re-attaches.
* Each event runs in its own JNI local frame (`PushLocalFrame` /
  `PopLocalFrame`): the engine thread never returns to Java, so local
  references would otherwise accumulate.
* The sink keeps a global reference to the listener; it is deleted when the
  bridge drops the sink (`Stop()`, a refused second `start`) on an attached
  thread. The destructor never attaches (it may run during process exit);
  on an unattached thread the reference is leaked once.
* `stop()` (not part of the app surface) calls `aubridge::Stop()`: it joins
  the engine thread, which detaches itself on exit. Tests call it before the
  JVM exits so that no attached native thread outlives the VM.

## Building and testing

```sh
# host library + Java test classes, then the ctest (label jni)
flock /tmp/claude-0/locks/build-host.lock ninja -C native/build-host bridge-test-jni
flock /tmp/claude-0/locks/build-host.lock ctest --test-dir native/build-host -L jni --output-on-failure

# Kotlin → JNI → bridge → Audacity in the unit-test JVM (real NativeAudacityEngine)
flock /tmp/claude-0/locks/gradle.lock ./gradlew --no-daemon -Paudacity.buildNative=false \
    -Paudacity.hostJniLibrary=$PWD/native/build-host/lib/libaudacity-jni.so \
    :engine:testDebugUnitTest --tests '*NativeHostIntegrationTest*'
# (without -Paudacity.hostJniLibrary the test is skipped)

# Android
flock /tmp/claude-0/locks/build-android-arm64.lock /opt/android-sdk/cmake/3.31.6/bin/ninja \
    -C native/build-android-arm64 audacity-jni
native/scripts/check-android-libs.sh native/build-android-arm64/lib
```
