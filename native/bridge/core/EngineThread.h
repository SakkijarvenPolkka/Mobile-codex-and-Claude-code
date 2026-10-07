/*  SPDX-License-Identifier: GPL-2.0-or-later */
/**********************************************************************

  Audacity Android port

  EngineThread.h

  The single native thread that plays the role of Audacity's main (UI)
  thread (init-and-project.md §3).  Every library call runs here.

  Queues, in priority order:
   * internal -- BasicUI::CallAfter work (any thread may post)
   * command  -- Invoke() closures from JNI / tests
   * display  -- waveform/spectrogram requests; drained ONLY by the
                 outermost loop when the other two are empty
  plus a tick every ~50 ms (idle work, module tick handlers, deferred
  snapshots).

  Nested loops (BasicUI::Yield, blocking dialogs via WaitModal) drain the
  internal queue only, so a command never runs inside another command.

**********************************************************************/
#pragma once

#include <chrono>
#include <condition_variable>
#include <deque>
#include <functional>
#include <future>
#include <mutex>
#include <pthread.h>
#include <vector>

namespace aubridge {

class EngineThread final {
public:
   using Task = std::function<void()>;

   static EngineThread &Get();
   //! True on the engine thread (and only there)
   static bool IsCurrent();

   //! Creates the thread (8 MiB stack).  `bootstrap` runs first on the new
   //! thread, then the loop; `shutdown` runs on the thread after Stop().
   //! @return false if already running or the thread could not be created
   bool Start(Task bootstrap, Task shutdown);
   //! Asks the loop to exit (any thread); returns immediately
   void RequestStop();
   //! Waits for the thread to exit (never on the engine thread)
   void Join();
   bool IsRunning() const;
   bool IsStopping() const;

   //! Any thread.  @return false if the engine is not running (task dropped)
   bool PostInternal(Task task);
   bool PostCommand(Task task);
   bool PostDisplay(Task task);

   //! Engine thread: run up to `maxTasks` internal tasks; re-entrancy is
   //! bounded (returns 0 when nested too deeply)
   size_t DrainInternal(size_t maxTasks = 256);

   //! Engine thread: wait for `future`, draining internal tasks meanwhile
   //! (like a modal dialog's event loop).  Off the engine thread: plain get().
   template<typename T> T WaitModal(std::future<T> &future)
   {
      if (IsCurrent()) {
         while (future.wait_for(std::chrono::milliseconds(10)) !=
                std::future_status::ready)
            DrainInternal(16);
      }
      return future.get();
   }

   //! Engine thread.  Handlers run every ~50 ms on the outermost loop.
   //! @return an id for RemoveTickHandler
   int AddTickHandler(std::function<void()> handler);
   void RemoveTickHandler(int id);

   //! Runs a task, catching everything (AudacityException: rollback of the
   //! current project + DelayedHandlerAction, like
   //! AudacityApp::OnExceptionInMainLoop)
   static void RunGuarded(const Task &task) noexcept;

private:
   EngineThread() = default;
   static void *ThreadMain(void *self);
   void Run();
   void Tick();
   bool Post(std::deque<Task> &queue, Task task);

   mutable std::mutex mMutex;
   std::condition_variable mCv;
   std::deque<Task> mInternal, mCommands, mDisplay;
   std::vector<std::pair<int, std::function<void()>>> mTickHandlers;
   int mNextTickId = 1;
   Task mBootstrap, mShutdown;
   bool mRunning = false;
   bool mStop = false;
   bool mThreadValid = false;
   int mDrainDepth = 0;
   pthread_t mThread{};
};

} // namespace aubridge
