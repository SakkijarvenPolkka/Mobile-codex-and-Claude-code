/*  SPDX-License-Identifier: GPL-2.0-or-later */
/**********************************************************************

  Audacity Android port

  JniEventSink.cpp

**********************************************************************/
#include "JniEventSink.h"

#include <atomic>

#include "JniSupport.h"

namespace aujni {

namespace {
// Written once in JNI_OnLoad, before any sink exists
jclass gListenerClass = nullptr;     // global reference, keeps the method id valid
jmethodID gOnEvent = nullptr;        // EngineListener.onEvent(String, byte[])
} // namespace

bool InitEventSinkClass(JNIEnv *env) noexcept
{
   jclass local = env->FindClass(kEngineListenerClass);
   if (!local) {
      ClearException(env, "FindClass(EngineListener)");
      return false;
   }
   jmethodID onEvent = env->GetMethodID(local, "onEvent", "(Ljava/lang/String;[B)V");
   if (!onEvent) {
      ClearException(env, "GetMethodID(EngineListener.onEvent)");
      env->DeleteLocalRef(local);
      return false;
   }
   auto global = static_cast<jclass>(env->NewGlobalRef(local));
   env->DeleteLocalRef(local);
   if (!global) {
      ClearException(env, "NewGlobalRef(EngineListener)");
      return false;
   }
   if (gListenerClass)
      env->DeleteGlobalRef(gListenerClass);
   gListenerClass = global;
   gOnEvent = onEvent;
   return true;
}

JniEventSink::JniEventSink(JNIEnv *env, jobject listener) noexcept
{
   if (!listener || !gOnEvent)
      return;
   if (!env->IsInstanceOf(listener, gListenerClass)) {
      Log(LogLevel::Error, "start: the listener is not an EngineListener");
      return;
   }
   mListener = env->NewGlobalRef(listener);
   if (!mListener)
      ClearException(env, "NewGlobalRef(listener)");
}

JniEventSink::~JniEventSink()
{
   if (!mListener)
      return;
   // Normally the last reference is dropped by Stop() or a failed Start() on
   // a Java thread. Never attach a thread here: the destructor may also run
   // from static destruction at process exit, when attaching can block. On
   // an unattached thread the global reference is leaked (once per Start).
   if (JNIEnv *env = CurrentEnv())
      env->DeleteGlobalRef(mListener);
   mListener = nullptr;
}

void JniEventSink::OnEvent(const std::string &type, const std::string &json)
{
   if (!mListener)
      return;
   JNIEnv *env = AttachedEnv();
   if (!env)
      return;
   // A Java thread that is inside a native call with a Java exception
   // pending (should not happen): do not disturb its exception
   if (env->ExceptionCheck())
      return;
   if (env->PushLocalFrame(4) != JNI_OK) {
      ClearException(env, "PushLocalFrame");
      return;
   }
   jstring jtype = NewAsciiString(env, type);
   jbyteArray jpayload = jtype ? NewByteArray(env, json.data(), json.size()) : nullptr;
   if (jtype && jpayload)
      env->CallVoidMethod(mListener, gOnEvent, jtype, jpayload);
   // OutOfMemoryError from the allocations, or what onEvent threw: the
   // engine thread must go on (the listener contract forbids throwing)
   ClearException(env, "EngineListener.onEvent");
   env->PopLocalFrame(nullptr);
}

} // namespace aujni
