/*  SPDX-License-Identifier: GPL-2.0-or-later */
/**********************************************************************

  Audacity Android port

  AudacityJni.cpp

  libaudacity-jni.so: the natives of
  `object io.github.sakkijarvenpolkka.audacity.engine.NativeBridge`
  (API.md §2), registered with RegisterNatives from JNI_OnLoad (the only
  exported symbol), forwarding to include/aubridge/Bridge.h.

  Rules (see README.md):
   * Strings cross as standard UTF-8 byte[] (never NewStringUTF/
     GetStringUTFChars, which use modified UTF-8), except the ASCII event
     type.
   * No C++ exception escapes into the JVM: every native catches all.
   * No JNI call is made while the bridge blocks (Invoke, display lane):
     arguments are copied into native buffers first, results are copied
     into the Java arrays afterwards with Set<Type>ArrayRegion (one memcpy,
     no long critical sections that would stall the GC).
   * The `thiz` argument is the NativeBridge object instance (the externals
     are members of a Kotlin `object`, not @JvmStatic).

**********************************************************************/
#include <jni.h>

#include <cstdint>
#include <exception>
#include <memory>
#include <string>
#include <vector>

#include "aubridge/Bridge.h"
#include "JniEventSink.h"
#include "JniSupport.h"

using namespace aujni;

namespace {

// Display status codes (API.md §7.2)
constexpr jlong kNoSuchTrack = -1;
constexpr jlong kPartialEmpty = -2;
constexpr jlong kNotReady = -3;

// Limits of the display calls (API.md §7.1, §7.5); checked before
// allocating the native buffers
constexpr jint kMaxColumns = 65536;
constexpr jint kMaxRows = 4096;

constexpr size_t kTransportSize = 16;   // API.md §6.4
constexpr size_t kMetersSize = 14;      // API.md §6.5

//! True when the bridge wrote the result buffer (Bridge.cpp `Deliver`)
bool Delivered(int64_t status)
{
   return status >= 0 || status == kPartialEmpty;
}

const char *What(const std::exception &e)
{
   return e.what() ? e.what() : "exception";
}

// ---------------------------------------------------------------------------
// Natives (same order as API.md §2)
// ---------------------------------------------------------------------------

jboolean JNICALL Start(JNIEnv *env, jobject, jbyteArray config, jobject listener)
{
   try {
      if (!config || !listener) {
         Log(LogLevel::Error, "start: null %s", config ? "listener" : "config");
         return JNI_FALSE;
      }
      std::string configJson;
      if (!ToStdString(env, config, configJson))
         return JNI_FALSE;
      auto sink = std::make_shared<JniEventSink>(env, listener);
      if (!sink->Valid())
         return JNI_FALSE;
      return aubridge::Start(configJson, std::move(sink)) ? JNI_TRUE : JNI_FALSE;
   }
   catch (const std::exception &e) {
      Log(LogLevel::Error, "start: %s", What(e));
   }
   catch (...) {
      Log(LogLevel::Error, "start: unknown exception");
   }
   return JNI_FALSE;
}

jbyteArray JNICALL Invoke(JNIEnv *env, jobject, jbyteArray command, jbyteArray args)
{
   std::string response;
   try {
      std::string name, argsJson;
      if (!command)
         response = ErrorEnvelope("INVALID_ARGS", "the command name is null");
      else if (!ToStdString(env, command, name) || !ToStdString(env, args, argsJson)) {
         if (env->ExceptionCheck())
            return nullptr;   // OutOfMemoryError etc. propagates to Kotlin
         response = ErrorEnvelope("INTERNAL", "out of memory copying the arguments");
      }
      else {
         if (!args)
            argsJson = "{}";
         response = aubridge::Invoke(name, argsJson);
      }
   }
   catch (const std::exception &e) {
      response = ErrorEnvelope("INTERNAL", std::string("invoke: ") + What(e));
   }
   catch (...) {
      response = ErrorEnvelope("INTERNAL", "invoke: unknown exception");
   }
   // null only with a pending OutOfMemoryError, which Kotlin receives
   return NewByteArray(env, response.data(), response.size());
}

void JNICALL ReplyDialog(JNIEnv *, jobject, jint dialogId, jint button)
{
   try {
      aubridge::ReplyDialog(dialogId, button);
   }
   catch (...) {
      Log(LogLevel::Error, "replyDialog: exception");
   }
}

void JNICALL ReplyDialogChoices(JNIEnv *env, jobject, jint dialogId, jintArray indices)
{
   try {
      if (!indices) {
         // Not reachable from Kotlin (non-null IntArray): treat as cancel
         aubridge::ReplyDialog(dialogId, -1);
         return;
      }
      const jsize n = env->GetArrayLength(indices);
      std::vector<jint> raw(size_t(n > 0 ? n : 0));
      if (n > 0)
         env->GetIntArrayRegion(indices, 0, n, raw.data());
      // The bridge sorts and drops duplicates / out-of-range values
      aubridge::ReplyDialogChoices(dialogId, std::vector<int>(raw.begin(), raw.end()));
   }
   catch (...) {
      Log(LogLevel::Error, "replyDialogChoices: exception");
   }
}

void JNICALL CancelProgress(JNIEnv *, jobject, jint progressId, jboolean stop)
{
   try {
      aubridge::CancelProgress(progressId, stop == JNI_TRUE);
   }
   catch (...) {
      Log(LogLevel::Error, "cancelProgress: exception");
   }
}

// Lock-free reads: called every frame from the UI thread, so no allocation
jboolean JNICALL ReadTransport(JNIEnv *env, jobject, jdoubleArray out)
{
   if (!out || size_t(env->GetArrayLength(out)) < kTransportSize)
      return JNI_FALSE;
   double values[kTransportSize] = {};
   try {
      if (!aubridge::ReadTransport(values, kTransportSize))
         return JNI_FALSE;
   }
   catch (...) {
      return JNI_FALSE;
   }
   env->SetDoubleArrayRegion(out, 0, jsize(kTransportSize), values);
   return JNI_TRUE;
}

jboolean JNICALL ReadMeters(JNIEnv *env, jobject, jfloatArray out)
{
   if (!out || size_t(env->GetArrayLength(out)) < kMetersSize)
      return JNI_FALSE;
   float values[kMetersSize] = {};
   try {
      if (!aubridge::ReadMeters(values, kMetersSize))
         return JNI_FALSE;
   }
   catch (...) {
      return JNI_FALSE;
   }
   env->SetFloatArrayRegion(out, 0, jsize(kMetersSize), values);
   return JNI_TRUE;
}

jlong JNICALL WaveColumns(JNIEnv *env, jobject, jlong trackId, jint channel,
   jint zoomLevel, jlong firstColumn, jint count, jfloatArray out)
{
   if (!out || count < 1 || count > kMaxColumns)
      return kNoSuchTrack;
   const size_t n = size_t(count) * 3;
   if (size_t(env->GetArrayLength(out)) < n)
      return kNoSuchTrack;
   try {
      std::vector<float> buffer(n);
      const int64_t status = aubridge::WaveColumns(trackId, channel, zoomLevel,
         firstColumn, count, buffer.data(), buffer.size());
      if (Delivered(status))
         env->SetFloatArrayRegion(out, 0, jsize(n), buffer.data());
      return status;
   }
   catch (...) {
      return kNotReady;
   }
}

jlong JNICALL EnvelopeColumns(JNIEnv *env, jobject, jlong trackId,
   jint zoomLevel, jlong firstColumn, jint count, jfloatArray out)
{
   if (!out || count < 1 || count > kMaxColumns)
      return kNoSuchTrack;
   const size_t n = size_t(count);
   if (size_t(env->GetArrayLength(out)) < n)
      return kNoSuchTrack;
   try {
      std::vector<float> buffer(n);
      const int64_t status = aubridge::EnvelopeColumns(trackId, zoomLevel,
         firstColumn, count, buffer.data(), buffer.size());
      if (Delivered(status))
         env->SetFloatArrayRegion(out, 0, jsize(n), buffer.data());
      return status;
   }
   catch (...) {
      return kNotReady;
   }
}

jbyteArray JNICALL WaveSamples(JNIEnv *env, jobject, jlong trackId,
   jint channel, jdouble t0, jdouble t1)
{
   std::vector<uint8_t> bytes;
   try {
      bytes = aubridge::WaveSamples(trackId, channel, t0, t1);
   }
   catch (...) {
      return nullptr;
   }
   if (bytes.empty())
      return nullptr;
   return NewByteArray(env, bytes.data(), bytes.size());
}

jlong JNICALL SpectrogramColumns(JNIEnv *env, jobject, jlong trackId,
   jint channel, jint zoomLevel, jlong firstColumn, jint count, jint rows,
   jbyteArray out)
{
   if (!out || count < 1 || count > kMaxColumns || rows < 1 || rows > kMaxRows)
      return kNoSuchTrack;
   const size_t n = size_t(count) * size_t(rows);
   if (size_t(env->GetArrayLength(out)) < n)
      return kNoSuchTrack;
   try {
      std::vector<uint8_t> buffer(n);
      const int64_t status = aubridge::SpectrogramColumns(trackId, channel,
         zoomLevel, firstColumn, count, rows, buffer.data(), buffer.size());
      if (Delivered(status))
         env->SetByteArrayRegion(out, 0, jsize(n),
            reinterpret_cast<const jbyte *>(buffer.data()));
      return status;
   }
   catch (...) {
      return kNotReady;
   }
}

//! Not in API.md's app surface: stops the engine thread and waits for it
//! (host tests; Android never stops the engine). Background threads only.
void JNICALL Stop(JNIEnv *, jobject)
{
   try {
      aubridge::Stop();
   }
   catch (...) {
      Log(LogLevel::Error, "stop: exception");
   }
}

struct NativeMethod {
   const char *name;
   const char *signature;
   void *function;
   bool required;
};

#define AUJNI_LISTENER "Lio/github/sakkijarvenpolkka/audacity/engine/EngineListener;"

const NativeMethod kMethods[] = {
   { "start", "([B" AUJNI_LISTENER ")Z", reinterpret_cast<void *>(Start), true },
   { "invoke", "([B[B)[B", reinterpret_cast<void *>(Invoke), true },
   { "replyDialog", "(II)V", reinterpret_cast<void *>(ReplyDialog), true },
   { "replyDialogChoices", "(I[I)V", reinterpret_cast<void *>(ReplyDialogChoices), true },
   { "cancelProgress", "(IZ)V", reinterpret_cast<void *>(CancelProgress), true },
   { "readTransport", "([D)Z", reinterpret_cast<void *>(ReadTransport), true },
   { "readMeters", "([F)Z", reinterpret_cast<void *>(ReadMeters), true },
   { "waveColumns", "(JIIJI[F)J", reinterpret_cast<void *>(WaveColumns), true },
   { "envelopeColumns", "(JIJI[F)J", reinterpret_cast<void *>(EnvelopeColumns), true },
   { "waveSamples", "(JIDD)[B", reinterpret_cast<void *>(WaveSamples), true },
   { "spectrogramColumns", "(JIIJII[B)J", reinterpret_cast<void *>(SpectrogramColumns), true },
   { "stop", "()V", reinterpret_cast<void *>(Stop), false },
};

#undef AUJNI_LISTENER

//! Registers the natives one by one so that a missing optional method does
//! not fail the others; false when a required one cannot be registered
bool RegisterBridgeNatives(JNIEnv *env)
{
   jclass bridge = env->FindClass(kNativeBridgeClass);
   if (!bridge) {
      ClearException(env, "FindClass(NativeBridge)");
      return false;
   }
   bool ok = true;
   for (const auto &m : kMethods) {
      // OpenJDK's jni.h declares the strings as char *, the NDK's as const
      JNINativeMethod method{};
      method.name = const_cast<char *>(m.name);
      method.signature = const_cast<char *>(m.signature);
      method.fnPtr = m.function;
      if (env->RegisterNatives(bridge, &method, 1) == JNI_OK)
         continue;
      // NoSuchMethodError: the Kotlin declaration does not match
      env->ExceptionClear();
      Log(m.required ? LogLevel::Error : LogLevel::Warning,
         "NativeBridge.%s%s is not declared; %s", m.name, m.signature, m.required ? "cannot load" : "skipped");
      if (m.required)
         ok = false;
   }
   env->DeleteLocalRef(bridge);
   return ok;
}

} // namespace

//! The only exported symbol of libaudacity-jni.so. Runs on the thread that
//! calls System.loadLibrary/System.load from NativeBridge, so FindClass uses
//! the app's class loader. Returning JNI_ERR makes the load throw
//! UnsatisfiedLinkError (NativeBridge.isLoaded = false: fake engine).
extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *)
{
   try {
      JNIEnv *env = nullptr;
      if (vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK || !env)
         return JNI_ERR;
      SetVm(vm);
      if (!InitEventSinkClass(env) || !RegisterBridgeNatives(env)) {
         Log(LogLevel::Error, "JNI_OnLoad failed: NativeBridge/EngineListener do not match libaudacity-jni");
         return JNI_ERR;
      }
      return JNI_VERSION_1_6;
   }
   catch (...) {
      return JNI_ERR;
   }
}
