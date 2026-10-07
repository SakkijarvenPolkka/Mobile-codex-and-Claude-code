/*  SPDX-License-Identifier: GPL-2.0-or-later */
/**********************************************************************

  Audacity Android port

  Events.cpp

**********************************************************************/
#include "Events.h"

#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <mutex>

#include "aubridge/Bridge.h"

#if defined(__ANDROID__)
#include <android/log.h>
#endif

namespace aubridge {

EventSink::~EventSink() = default;

namespace Events {
namespace {
std::mutex &SinkMutex()
{
   static std::mutex mutex;
   return mutex;
}
std::shared_ptr<EventSink> &TheSink()
{
   static std::shared_ptr<EventSink> sink;
   return sink;
}

// Rate limiting of `log` events: at most kBurst events per second
constexpr int kBurst = 50;
struct LogLimiter {
   std::mutex mutex;
   std::chrono::steady_clock::time_point windowStart{};
   int count = 0;
   int dropped = 0;
};
LogLimiter &Limiter()
{
   static LogLimiter limiter;
   return limiter;
}

const char *LevelName(LogLevel level)
{
   switch (level) {
   case LogLevel::Debug: return "debug";
   case LogLevel::Info: return "info";
   case LogLevel::Warning: return "warning";
   default: return "error";
   }
}
} // namespace

void SetSink(std::shared_ptr<EventSink> sink)
{
   std::lock_guard lock{ SinkMutex() };
   TheSink() = std::move(sink);
}

std::shared_ptr<EventSink> GetSink()
{
   std::lock_guard lock{ SinkMutex() };
   return TheSink();
}

void Emit(const std::string &type, const json &payload)
{
   auto sink = GetSink();
   if (!sink)
      return;
   const auto text = Dump(payload);
   try {
      sink->OnEvent(type, text);
   }
   catch (...) {
      // A sink must not throw; never let it unwind into library code
   }
}

void Log(LogLevel level, const std::string &message)
{
#if defined(__ANDROID__)
   const int prio = level == LogLevel::Debug ? ANDROID_LOG_DEBUG
      : level == LogLevel::Info ? ANDROID_LOG_INFO
      : level == LogLevel::Warning ? ANDROID_LOG_WARN
      : ANDROID_LOG_ERROR;
   __android_log_print(prio, "Audacity", "%s", message.c_str());
#else
   static const bool toStderr = std::getenv("AUBRIDGE_LOG_STDERR") != nullptr;
   if (toStderr)
      std::fprintf(stderr, "[aubridge %s] %s\n", LevelName(level), message.c_str());
#endif

   int dropped = 0;
   {
      auto &limiter = Limiter();
      std::lock_guard lock{ limiter.mutex };
      const auto now = std::chrono::steady_clock::now();
      if (now - limiter.windowStart >= std::chrono::seconds(1)) {
         limiter.windowStart = now;
         limiter.count = 0;
         dropped = limiter.dropped;
         limiter.dropped = 0;
      }
      if (limiter.count >= kBurst) {
         ++limiter.dropped;
         return;
      }
      ++limiter.count;
   }
   if (dropped > 0)
      Emit("log", json{ { "level", "warning" },
         { "message", std::to_string(dropped) + " log lines dropped" } });
   Emit("log", json{ { "level", LevelName(level) }, { "message", message } });
}

} // namespace Events
} // namespace aubridge
