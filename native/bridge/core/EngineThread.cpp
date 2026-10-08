/*  SPDX-License-Identifier: GPL-2.0-or-later */
/**********************************************************************

  Audacity Android port

  EngineThread.cpp

**********************************************************************/
#include "EngineThread.h"

#include <algorithm>
#include <cassert>
#include <exception>

#include "AudacityException.h"
#include "Events.h"
#include "Session.h"

namespace aubridge {

namespace {
thread_local bool tIsEngine = false;
constexpr size_t kStackSize = 8 * 1024 * 1024;   // like the desktop main thread
constexpr auto kTickPeriod = std::chrono::milliseconds(50);
//! Nothing going on (no task for kActiveWindow, the activity probe says no)
constexpr auto kIdleTickPeriod = std::chrono::milliseconds(2000);
//! A task keeps the fast tick this long (throttled commands, device changes,
//! states that modules publish a few ticks later)
constexpr auto kActiveWindow = std::chrono::milliseconds(2000);
}

EngineThread &EngineThread::Get()
{
   static EngineThread instance;
   return instance;
}

bool EngineThread::IsCurrent()
{
   return tIsEngine;
}

bool EngineThread::Start(Task bootstrap, Task shutdown)
{
   std::lock_guard lock{ mMutex };
   if (mRunning || mThreadValid)
      return false;
   mBootstrap = std::move(bootstrap);
   mShutdown = std::move(shutdown);
   mStop = false;
   mInternal.clear();
   mCommands.clear();
   mDisplay.clear();
   mTickHandlers.clear();
   mActivityProbe = {};

   pthread_attr_t attr;
   if (pthread_attr_init(&attr) != 0)
      return false;
   pthread_attr_setstacksize(&attr, kStackSize);
   mRunning = true;
   const int rc = pthread_create(&mThread, &attr, &EngineThread::ThreadMain, this);
   pthread_attr_destroy(&attr);
   if (rc != 0) {
      mRunning = false;
      return false;
   }
   mThreadValid = true;
   return true;
}

void *EngineThread::ThreadMain(void *self)
{
#if defined(__linux__) || defined(__ANDROID__)
   pthread_setname_np(pthread_self(), "AudacityEngine");
#endif
   static_cast<EngineThread *>(self)->Run();
   return nullptr;
}

void EngineThread::RequestStop()
{
   {
      std::lock_guard lock{ mMutex };
      mStop = true;
   }
   mCv.notify_all();
}

void EngineThread::Join()
{
   assert(!IsCurrent());
   pthread_t thread;
   {
      std::lock_guard lock{ mMutex };
      if (!mThreadValid)
         return;
      thread = mThread;
   }
   pthread_join(thread, nullptr);
   std::lock_guard lock{ mMutex };
   mThreadValid = false;
}

bool EngineThread::IsRunning() const
{
   std::lock_guard lock{ mMutex };
   return mRunning && !mStop;
}

bool EngineThread::IsStopping() const
{
   std::lock_guard lock{ mMutex };
   return mStop;
}

bool EngineThread::Post(std::deque<Task> &queue, Task task)
{
   {
      std::lock_guard lock{ mMutex };
      // Internal (CallAfter) work is still accepted while shutting down: the
      // shutdown sequence drains it (closing a project relies on it)
      if (!mRunning || (mStop && &queue != &mInternal))
         return false;
      queue.push_back(std::move(task));
   }
   mCv.notify_one();
   return true;
}

bool EngineThread::PostInternal(Task task) { return Post(mInternal, std::move(task)); }
bool EngineThread::PostCommand(Task task) { return Post(mCommands, std::move(task)); }
bool EngineThread::PostDisplay(Task task) { return Post(mDisplay, std::move(task)); }

size_t EngineThread::DrainInternal(size_t maxTasks)
{
   assert(IsCurrent());
   if (mDrainDepth > 1)
      return 0;
   ++mDrainDepth;
   size_t n = 0;
   while (n < maxTasks) {
      Task task;
      {
         std::lock_guard lock{ mMutex };
         if (mInternal.empty())
            break;
         task = std::move(mInternal.front());
         mInternal.pop_front();
      }
      RunGuarded(task);
      ++n;
   }
   --mDrainDepth;
   return n;
}

int EngineThread::AddTickHandler(std::function<void()> handler)
{
   const int id = mNextTickId++;
   mTickHandlers.emplace_back(id, std::move(handler));
   return id;
}

void EngineThread::SetActivityProbe(std::function<bool()> probe)
{
   mActivityProbe = std::move(probe);
}

void EngineThread::RemoveTickHandler(int id)
{
   auto &v = mTickHandlers;
   v.erase(std::remove_if(v.begin(), v.end(),
      [id](const auto &p) { return p.first == id; }), v.end());
}

void EngineThread::RunGuarded(const Task &task) noexcept
{
   try {
      task();
   }
   catch (const AudacityException &) {
      // AudacityApp::OnExceptionInMainLoop: restore the project to its last
      // state, then (later, not during unwinding) alert the user
      auto pException = std::current_exception();
      Get().PostInternal([pException] {
         RollbackCurrentProject();
         try {
            std::rethrow_exception(pException);
         }
         catch (AudacityException &e) {
            e.DelayedHandlerAction();
         }
         catch (...) {
         }
      });
   }
   catch (const std::exception &e) {
      Events::Log(Events::LogLevel::Error,
         std::string("Unhandled exception on the engine thread: ") + e.what());
   }
   catch (...) {
      Events::Log(Events::LogLevel::Error,
         "Unhandled unknown exception on the engine thread");
   }
}

void EngineThread::Tick()
{
   // Copy: handlers may add/remove handlers
   auto handlers = mTickHandlers;
   for (auto &[id, handler] : handlers)
      RunGuarded(handler);
}

void EngineThread::Run()
{
   using clock = std::chrono::steady_clock;
   tIsEngine = true;
   RunGuarded(mBootstrap);

   auto nextTick = clock::now() + kTickPeriod;
   auto lastTask = clock::now();
   for (;;) {
      Task task;
      {
         std::unique_lock lock{ mMutex };
         mCv.wait_until(lock, nextTick, [this] {
            return mStop || !mInternal.empty() || !mCommands.empty() ||
               !mDisplay.empty();
         });
         if (mStop)
            break;
         auto *queue = !mInternal.empty() ? &mInternal
            : !mCommands.empty() ? &mCommands
            : !mDisplay.empty() ? &mDisplay
            : nullptr;
         if (queue) {
            task = std::move(queue->front());
            queue->pop_front();
         }
      }
      auto now = clock::now();
      if (task) {
         RunGuarded(task);
         now = lastTask = clock::now();
         // Work arrived while idle: back to the fast tick
         if (nextTick > now + kTickPeriod)
            nextTick = now + kTickPeriod;
      }
      if (now >= nextTick) {
         Tick();
         now = clock::now();
         bool active = now - lastTask < kActiveWindow;
         if (!active && mActivityProbe) {
            try {
               active = mActivityProbe();
            }
            catch (...) {
               active = true;
            }
         }
         nextTick = now + (active ? kTickPeriod : kIdleTickPeriod);
      }
   }

   RunGuarded(mShutdown);

   // Drop what is left; pending Invoke()/display callers see a broken
   // promise and answer NOT_READY
   std::deque<Task> internal, commands, display;
   {
      std::lock_guard lock{ mMutex };
      internal.swap(mInternal);
      commands.swap(mCommands);
      display.swap(mDisplay);
      mTickHandlers.clear();
      mActivityProbe = {};
      mBootstrap = {};
      mShutdown = {};
   }
   internal.clear();
   commands.clear();
   display.clear();
   {
      std::lock_guard lock{ mMutex };
      mRunning = false;
   }
   tIsEngine = false;
}

} // namespace aubridge
