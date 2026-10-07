/*  SPDX-License-Identifier: GPL-2.0-or-later */
/**********************************************************************

  Audacity Android port

  BridgeTestSupport.h

  Helpers for host tests that drive libaudacity-bridge through Bridge.h
  only: a thread-safe event sink, temporary app directories, a start
  configuration, Invoke() with JSON parsing, and tiny CHECK macros.

  Usage (see BridgeCoreTest.cpp):

     bridgetest::TempDirs dirs;                        // removed at exit
     auto sink = std::make_shared<bridgetest::Sink>();
     aubridge::Start(dirs.ConfigJson(), sink);
     auto ready = sink->WaitFor("engine.ready");      // or engine.failed
     auto r = bridgetest::Call("project.new");         // {"ok":..}
     CHECK(r["ok"].get<bool>());

**********************************************************************/
#pragma once

#include <chrono>
#include <condition_variable>
#include <cstdio>
#include <cstdlib>
#include <functional>
#include <mutex>
#include <optional>
#include <string>
#include <utility>
#include <vector>

#include <unistd.h>

#include <nlohmann/json.hpp>

#include "aubridge/Bridge.h"

namespace bridgetest {

using json = nlohmann::json;

inline int &Failures()
{
   static int failures = 0;
   return failures;
}

#define CHECK(cond) \
   do { \
      if (!(cond)) { \
         std::fprintf(stderr, "CHECK failed %s:%d: %s\n", __FILE__, __LINE__, #cond); \
         ++::bridgetest::Failures(); \
      } \
   } while (false)

#define CHECK_MSG(cond, msg) \
   do { \
      if (!(cond)) { \
         std::fprintf(stderr, "CHECK failed %s:%d: %s -- %s\n", __FILE__, __LINE__, \
            #cond, std::string(msg).c_str()); \
         ++::bridgetest::Failures(); \
      } \
   } while (false)

//! Records every event; lets a test wait for one
class Sink final : public aubridge::EventSink {
public:
   struct Event {
      std::string type;
      json payload;
   };

   //! Called for blocking dialogs (engine thread); may call ReplyDialog
   std::function<void(const json &dialog)> onBlockingDialog;
   //! Called for progress events (engine thread); may call CancelProgress
   std::function<void(const json &progress)> onProgress;

   void OnEvent(const std::string &type, const std::string &text) override
   {
      json payload = json::parse(text, nullptr, false);
      {
         std::lock_guard lock{ mMutex };
         mEvents.push_back({ type, payload });
      }
      mCv.notify_all();
      if (type == "dialog" && payload.value("blocking", false) && onBlockingDialog)
         onBlockingDialog(payload);
      if (type == "progress" && onProgress)
         onProgress(payload);
      if (std::getenv("BRIDGE_TEST_VERBOSE") && type != "log")
         std::fprintf(stderr, "[event %s] %s\n", type.c_str(),
            text.substr(0, 300).c_str());
      if (std::getenv("BRIDGE_TEST_VERBOSE") && type == "log")
         std::fprintf(stderr, "[log] %s\n", payload.value("message", "").c_str());
   }

   size_t Count() const
   {
      std::lock_guard lock{ mMutex };
      return mEvents.size();
   }

   std::vector<Event> Since(size_t index) const
   {
      std::lock_guard lock{ mMutex };
      if (index >= mEvents.size())
         return {};
      return { mEvents.begin() + index, mEvents.end() };
   }

   //! First event of `type` at position >= `from` matching `pred`
   std::optional<json> WaitFor(const std::string &type,
      std::chrono::milliseconds timeout = std::chrono::seconds(120),
      size_t from = 0,
      std::function<bool(const json &)> pred = {})
   {
      std::unique_lock lock{ mMutex };
      std::optional<json> found;
      mCv.wait_for(lock, timeout, [&] {
         for (size_t i = from; i < mEvents.size(); ++i)
            if (mEvents[i].type == type && (!pred || pred(mEvents[i].payload))) {
               found = mEvents[i].payload;
               return true;
            }
         return false;
      });
      return found;
   }

   //! Last event of `type` at position >= `from`
   std::optional<json> Last(const std::string &type, size_t from = 0) const
   {
      std::lock_guard lock{ mMutex };
      for (size_t i = mEvents.size(); i-- > from;)
         if (mEvents[i].type == type)
            return mEvents[i].payload;
      return std::nullopt;
   }

private:
   mutable std::mutex mMutex;
   std::condition_variable mCv;
   std::vector<Event> mEvents;
};

//! filesDir / noBackupDir / cacheDir under a fresh temporary root
struct TempDirs {
   std::string root, filesDir, noBackupDir, cacheDir;
   bool keep = false;

   explicit TempDirs(std::string existingRoot = {})
   {
      if (existingRoot.empty()) {
         const char *base = std::getenv("BRIDGE_TEST_TMP");
         std::string pattern = std::string(base ? base : "/tmp") +
            "/aubridge-test-XXXXXX";
         std::vector<char> buf(pattern.begin(), pattern.end());
         buf.push_back('\0');
         if (!mkdtemp(buf.data()))
            std::abort();
         root = buf.data();
      }
      else {
         root = existingRoot;
         keep = true;
      }
      filesDir = root + "/files";
      noBackupDir = root + "/no_backup";
      cacheDir = root + "/cache";
   }
   ~TempDirs()
   {
      if (!keep && !std::getenv("BRIDGE_TEST_KEEP"))
         if (std::system(("rm -rf '" + root + "'").c_str()) != 0) {}
   }

   std::string ConfigJson(const json &extra = json::object()) const
   {
      json config{ { "filesDir", filesDir }, { "noBackupDir", noBackupDir },
         { "cacheDir", cacheDir }, { "locale", "en_US" },
         { "deviceModel", "host-test" }, { "audioOutputSampleRate", 48000 },
         { "audioFramesPerBuffer", 192 }, { "recordPermission", false } };
      for (auto it = extra.begin(); it != extra.end(); ++it)
         config[it.key()] = *it;
      return config.dump();
   }
};

//! Invoke + parse the envelope
inline json Call(const std::string &command, const json &args = json::object())
{
   const auto text = aubridge::Invoke(command, args.dump());
   auto envelope = json::parse(text, nullptr, false);
   if (envelope.is_discarded())
      return json{ { "ok", false }, { "error", { { "code", "PARSE" },
         { "message", text } } } };
   return envelope;
}

inline bool Ok(const json &envelope)
{
   return envelope.value("ok", false);
}

inline std::string ErrorCodeOf(const json &envelope)
{
   if (envelope.contains("error") && envelope["error"].is_object())
      return envelope["error"].value("code", "");
   return {};
}

} // namespace bridgetest
