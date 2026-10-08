/*  SPDX-License-Identifier: GPL-2.0-or-later */
/**********************************************************************

  Audacity Android port

  JniEventSink.h

  aubridge::EventSink that forwards engine events to the Kotlin
  EngineListener.onEvent(String, byte[]) given to NativeBridge.start
  (API.md §2, §4).

**********************************************************************/
#pragma once

#include <jni.h>

#include <memory>
#include <string>

#include "aubridge/Bridge.h"

namespace aujni {

//! Caches the EngineListener class and its onEvent method id; called from
//! JNI_OnLoad (FindClass works there with the app's class loader, not on
//! native threads). False when the class or method is missing.
bool InitEventSinkClass(JNIEnv *env) noexcept;

class JniEventSink final : public aubridge::EventSink {
public:
   //! Holds a global reference to `listener`; check Valid()
   JniEventSink(JNIEnv *env, jobject listener) noexcept;
   ~JniEventSink() override;

   JniEventSink(const JniEventSink &) = delete;
   JniEventSink &operator=(const JniEventSink &) = delete;

   bool Valid() const noexcept { return mListener != nullptr; }

   //! Any thread (normally the engine thread): attaches it to the JVM once,
   //! calls onEvent, never throws, never leaves a Java exception pending
   void OnEvent(const std::string &type, const std::string &json) override;

private:
   jobject mListener = nullptr;   // global reference
};

} // namespace aujni
