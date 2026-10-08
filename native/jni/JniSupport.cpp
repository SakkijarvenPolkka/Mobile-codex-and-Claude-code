/*  SPDX-License-Identifier: GPL-2.0-or-later */
/**********************************************************************

  Audacity Android port

  JniSupport.cpp

**********************************************************************/
#include "JniSupport.h"

#include <atomic>
#include <climits>
#include <cstdarg>
#include <cstdio>
#include <cstring>
#include <new>

#include <pthread.h>

#if defined(__ANDROID__)
#include <android/log.h>
#endif

namespace aujni {

namespace {

std::atomic<JavaVM *> gVm{ nullptr };

//! Set while the thread_local destructors of an exiting thread run: from
//! then on the thread is never attached again (trivially destructible, so
//! it can be read even after ThreadAttachment was destroyed)
thread_local bool tExiting = false;

//! Detaches the calling thread at thread exit if AttachedEnv() attached it.
//! thread_local destructors run before the pthread-key destructors (glibc
//! and bionic), i.e. before ART's own exit check, so ART never sees a native
//! thread exit while attached.
struct ThreadAttachment {
   JavaVM *vm = nullptr;
   ~ThreadAttachment()
   {
      tExiting = true;
      if (vm)
         vm->DetachCurrentThread();
      vm = nullptr;
   }
};
thread_local ThreadAttachment tAttachment;

jint GetEnv(JavaVM *vm, JNIEnv **env) noexcept
{
   return vm->GetEnv(reinterpret_cast<void **>(env), JNI_VERSION_1_6);
}

} // namespace

JavaVM *Vm() noexcept
{
   return gVm.load(std::memory_order_acquire);
}

void SetVm(JavaVM *vm) noexcept
{
   gVm.store(vm, std::memory_order_release);
}

JNIEnv *CurrentEnv() noexcept
{
   JavaVM *vm = Vm();
   if (!vm)
      return nullptr;
   JNIEnv *env = nullptr;
   if (GetEnv(vm, &env) != JNI_OK)
      return nullptr;
   return env;
}

JNIEnv *AttachedEnv() noexcept
{
   JavaVM *vm = Vm();
   if (!vm)
      return nullptr;
   JNIEnv *env = nullptr;
   const jint status = GetEnv(vm, &env);
   if (status == JNI_OK)
      return env;
   if (status != JNI_EDETACHED || tExiting)
      return nullptr;

   char name[32] = "audacity-native";
   // pthread names are at most 16 bytes including the terminator
   char threadName[16] = {};
   if (pthread_getname_np(pthread_self(), threadName, sizeof threadName) == 0 &&
       threadName[0] != '\0')
      std::snprintf(name, sizeof name, "%s", threadName);
   JavaVMAttachArgs args{};
   args.version = JNI_VERSION_1_6;
   args.name = name;
   args.group = nullptr;
   // Daemon: an attached engine thread must never keep the JVM alive
#if defined(__ANDROID__)
   JNIEnv **penv = &env;
#else
   void **penv = reinterpret_cast<void **>(&env);
#endif
   if (vm->AttachCurrentThreadAsDaemon(penv, &args) != JNI_OK || !env) {
      Log(LogLevel::Error, "cannot attach thread '%s' to the JVM", name);
      return nullptr;
   }
   // First use constructs the thread_local and registers its destructor
   tAttachment.vm = vm;
   return env;
}

bool ToStdString(JNIEnv *env, jbyteArray array, std::string &out) noexcept
{
   out.clear();
   if (!array)
      return true;
   const jsize length = env->GetArrayLength(array);
   if (length <= 0)
      return true;
   try {
      out.resize(size_t(length));
   }
   catch (const std::bad_alloc &) {
      return false;
   }
   env->GetByteArrayRegion(array, 0, length, reinterpret_cast<jbyte *>(&out[0]));
   return !env->ExceptionCheck();
}

jbyteArray NewByteArray(JNIEnv *env, const void *data, size_t size) noexcept
{
   if (size > size_t(INT_MAX)) {
      // Never happens for the bridge's payloads; do not wrap around
      Log(LogLevel::Error, "byte array of %zu bytes is too large for Java", size);
      return nullptr;
   }
   jbyteArray array = env->NewByteArray(jsize(size));
   if (!array)
      return nullptr;
   if (size > 0)
      env->SetByteArrayRegion(array, 0, jsize(size),
         static_cast<const jbyte *>(data));
   return array;
}

jstring NewAsciiString(JNIEnv *env, const std::string &ascii) noexcept
{
   char buffer[64];
   std::string heap;
   char *text = buffer;
   if (ascii.size() >= sizeof buffer) {
      try {
         heap.assign(ascii.size() + 1, '\0');
      }
      catch (const std::bad_alloc &) {
         return nullptr;
      }
      text = &heap[0];
   }
   size_t i = 0;
   for (; i < ascii.size(); ++i) {
      const unsigned char c = static_cast<unsigned char>(ascii[i]);
      text[i] = (c == 0 || c >= 0x80) ? '?' : char(c);
   }
   text[i] = '\0';
   return env->NewStringUTF(text);
}

bool ClearException(JNIEnv *env, const char *where) noexcept
{
   if (!env->ExceptionCheck())
      return false;
   // Describe only the first few (a broken listener would flood the log)
   static std::atomic<int> described{ 0 };
   if (described.fetch_add(1, std::memory_order_relaxed) < 20) {
      Log(LogLevel::Error, "Java exception in %s", where);
      env->ExceptionDescribe();   // prints the stack trace and clears it
   }
   env->ExceptionClear();
   return true;
}

void Log(LogLevel level, const char *format, ...) noexcept
{
   va_list args;
   va_start(args, format);
#if defined(__ANDROID__)
   const int prio = level == LogLevel::Info ? ANDROID_LOG_INFO
      : level == LogLevel::Warning ? ANDROID_LOG_WARN
      : ANDROID_LOG_ERROR;
   __android_log_vprint(prio, "AudacityJni", format, args);
#else
   const char *name = level == LogLevel::Info ? "info"
      : level == LogLevel::Warning ? "warning" : "error";
   char line[1024];
   std::vsnprintf(line, sizeof line, format, args);
   std::fprintf(stderr, "[aujni %s] %s\n", name, line);
#endif
   va_end(args);
}

std::string ErrorEnvelope(const char *code, const std::string &message) noexcept
{
   try {
      std::string text = "{\"ok\":false,\"error\":{\"code\":\"";
      text += code;
      text += "\",\"message\":\"";
      for (const char ch : message) {
         const unsigned char c = static_cast<unsigned char>(ch);
         if (c == '"' || c == '\\') {
            text += '\\';
            text += char(c);
         }
         else if (c < 0x20 || c >= 0x7f)
            text += '?';   // keeps the envelope valid UTF-8 whatever the input
         else
            text += char(c);
      }
      text += "\"},\"generation\":0}";
      return text;
   }
   catch (...) {
      return {};
   }
}

} // namespace aujni
