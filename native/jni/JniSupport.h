/*  SPDX-License-Identifier: GPL-2.0-or-later */
/**********************************************************************

  Audacity Android port

  JniSupport.h

  Helpers of the JNI glue (native/jni): the cached JavaVM, attaching
  native threads (the engine thread) to the JVM once and detaching them at
  thread exit, standard UTF-8 byte arrays, logging and a minimal JSON error
  envelope for failures that happen before the bridge is reached.

  Nothing here throws into JNI: every function is noexcept and reports
  failures through its return value (a pending Java exception, if any, is
  the JVM's own, e.g. OutOfMemoryError).

**********************************************************************/
#pragma once

#include <jni.h>

#include <cstddef>
#include <string>

namespace aujni {

//! Fully qualified (slash separated) names of the Kotlin classes
inline constexpr const char *kNativeBridgeClass =
   "io/github/sakkijarvenpolkka/audacity/engine/NativeBridge";
inline constexpr const char *kEngineListenerClass =
   "io/github/sakkijarvenpolkka/audacity/engine/EngineListener";

//! The JavaVM given to JNI_OnLoad (null before)
JavaVM *Vm() noexcept;
void SetVm(JavaVM *vm) noexcept;

//! JNIEnv of the calling thread. A native thread that is not attached yet
//! (the engine thread, helper threads that emit events) is attached as a
//! daemon thread named after its pthread name, once; it is detached
//! automatically when the thread exits. Returns null when the VM is gone or
//! the thread is already exiting.
JNIEnv *AttachedEnv() noexcept;

//! JNIEnv of the calling thread only if it is attached already (never
//! attaches), else null
JNIEnv *CurrentEnv() noexcept;

//! Copies a Java byte[] (standard UTF-8) into `out`. A null array yields an
//! empty string. False on failure (allocation).
bool ToStdString(JNIEnv *env, jbyteArray array, std::string &out) noexcept;

//! New byte[] with the given bytes; null (with a pending OutOfMemoryError)
//! when the JVM cannot allocate it
jbyteArray NewByteArray(JNIEnv *env, const void *data, size_t size) noexcept;

//! New java.lang.String from an ASCII event name; other bytes become '?'
//! (JNI's NewStringUTF expects *modified* UTF-8)
jstring NewAsciiString(JNIEnv *env, const std::string &ascii) noexcept;

//! Logs and clears a pending Java exception (rate limited); returns true if
//! there was one
bool ClearException(JNIEnv *env, const char *where) noexcept;

enum class LogLevel { Info, Warning, Error };
//! logcat (tag "AudacityJni") on Android, stderr on the host
void Log(LogLevel level, const char *format, ...) noexcept
#if defined(__GNUC__)
   __attribute__((format(printf, 2, 3)))
#endif
   ;

//! `{"ok":false,"error":{"code":..,"message":..},"generation":0}` (API.md
//! §3.1) for failures of the glue itself; the message is escaped and
//! reduced to printable ASCII
std::string ErrorEnvelope(const char *code, const std::string &message) noexcept;

} // namespace aujni
