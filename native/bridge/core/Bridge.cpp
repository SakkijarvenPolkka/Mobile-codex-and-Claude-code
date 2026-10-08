/*  SPDX-License-Identifier: GPL-2.0-or-later */
/**********************************************************************

  Audacity Android port

  Bridge.cpp

  The public entry points of libaudacity-bridge (include/aubridge/Bridge.h).

**********************************************************************/
#include "aubridge/Bridge.h"

#include <algorithm>
#include <chrono>
#include <cmath>
#include <condition_variable>
#include <mutex>

#include "Engine.h"
#include "EngineThread.h"
#include "Hooks.h"
#include "UiServices.h"

namespace aubridge {

namespace {

//! How long a display call waits for the display lane to START serving it
//! (the engine may be busy with a long command, API.md §8); once started, the
//! call waits for completion
constexpr auto kDisplayStartTimeout = std::chrono::milliseconds(250);

//! Runs `fn` on the engine thread's display lane and waits for it.  `fn`
//! must only write into memory it owns (the caller may give up before `fn`
//! runs); copy results out in `deliver`, which runs on the calling thread.
template<typename T, typename Fn>
T OnDisplayLane(T notReady, Fn &&fn)
{
   if (EngineThread::IsCurrent() || !Engine::IsReady())
      return notReady;
   struct State {
      std::mutex mutex;
      std::condition_variable cv;
      bool started = false, done = false, abandoned = false;
      T result;
   };
   auto state = std::make_shared<State>();
   state->result = notReady;
   const bool posted = EngineThread::Get().PostDisplay(
      [state, fn = std::forward<Fn>(fn), notReady]() mutable {
         {
            std::lock_guard lock{ state->mutex };
            if (state->abandoned)
               return;
            state->started = true;
         }
         state->cv.notify_all();
         T result = notReady;
         try {
            result = fn();
         }
         catch (...) {
         }
         {
            std::lock_guard lock{ state->mutex };
            state->result = std::move(result);
            state->done = true;
         }
         state->cv.notify_all();
      });
   if (!posted)
      return notReady;
   std::unique_lock lock{ state->mutex };
   if (!state->cv.wait_for(lock, kDisplayStartTimeout,
          [&] { return state->started || state->done; })) {
      state->abandoned = true;
      return notReady;
   }
   // Started: wait for completion (or for the engine to drop the task when
   // it stops: then `done` never comes, so poll the ready flag)
   while (!state->cv.wait_for(lock, std::chrono::milliseconds(100),
             [&] { return state->done; }))
      if (!Engine::IsReady())
         return notReady;
   return std::move(state->result);
}

//! Column results: the provider writes into a buffer owned by the task
struct Columns {
   int64_t status;
   std::vector<float> floats;
   std::vector<uint8_t> bytes;
};

int64_t Deliver(const Columns &c, float *out, size_t n)
{
   if (c.status >= 0 || c.status == DisplayStatus::Partial)
      std::copy(c.floats.begin(), c.floats.begin() + std::min(n, c.floats.size()), out);
   return c.status;
}

int64_t Deliver(const Columns &c, uint8_t *out, size_t n)
{
   if (c.status >= 0 || c.status == DisplayStatus::Partial)
      std::copy(c.bytes.begin(), c.bytes.begin() + std::min(n, c.bytes.size()), out);
   return c.status;
}

} // namespace

bool Start(const std::string &configJson, std::shared_ptr<EventSink> sink)
{
   return Engine::Start(configJson, std::move(sink));
}

void Stop()
{
   Engine::Stop();
}

bool IsReady()
{
   return Engine::IsReady();
}

std::string Invoke(const std::string &command, const std::string &argsJson)
{
   return Engine::Invoke(command, argsJson);
}

void ReplyDialog(int dialogId, int button)
{
   Dialogs::Reply(dialogId, button);
}

void ReplyDialogChoices(int dialogId, const std::vector<int> &indices)
{
   Dialogs::ReplyChoices(dialogId, indices);
}

void CancelProgress(int progressId, bool stop)
{
   UiServices::CancelProgress(progressId, stop);
}

bool ReadTransport(double *out, size_t n)
{
   if (!out || n < 16 || !Engine::IsReady())
      return false;
   if (auto reader = GetTransportReader())
      return reader(out, n);
   return false;
}

bool ReadMeters(float *out, size_t n)
{
   if (!out || n < 14 || !Engine::IsReady())
      return false;
   if (auto reader = GetMetersReader())
      return reader(out, n);
   return false;
}

int64_t WaveColumns(int64_t trackId, int channel, int zoomLevel,
   int64_t firstColumn, int count, float *out, size_t outSize)
{
   if (!out || count <= 0 || outSize < size_t(count) * 3)
      return DisplayStatus::NoSuchTrack;
   const size_t n = size_t(count) * 3;
   auto result = OnDisplayLane<Columns>({ DisplayStatus::NotReady, {}, {} }, [=] {
      Columns c{ DisplayStatus::NotReady, std::vector<float>(n), {} };
      if (auto &fn = GetDisplayProviders().waveColumns)
         c.status = fn(trackId, channel, zoomLevel, firstColumn, count,
            c.floats.data(), n);
      return c;
   });
   return Deliver(result, out, n);
}

int64_t EnvelopeColumns(int64_t trackId, int zoomLevel, int64_t firstColumn,
   int count, float *out, size_t outSize)
{
   if (!out || count <= 0 || outSize < size_t(count))
      return DisplayStatus::NoSuchTrack;
   const size_t n = size_t(count);
   auto result = OnDisplayLane<Columns>({ DisplayStatus::NotReady, {}, {} }, [=] {
      Columns c{ DisplayStatus::NotReady, std::vector<float>(n), {} };
      if (auto &fn = GetDisplayProviders().envelopeColumns)
         c.status = fn(trackId, zoomLevel, firstColumn, count, c.floats.data(), n);
      return c;
   });
   return Deliver(result, out, n);
}

std::vector<uint8_t> WaveSamples(int64_t trackId, int channel, double t0,
   double t1)
{
   return OnDisplayLane<std::vector<uint8_t>>({}, [=] {
      auto &fn = GetDisplayProviders().waveSamples;
      return fn ? fn(trackId, channel, t0, t1) : std::vector<uint8_t>{};
   });
}

int64_t SpectrogramColumns(int64_t trackId, int channel, int zoomLevel,
   int64_t firstColumn, int count, int rows, uint8_t *out, size_t outSize)
{
   if (!out || count <= 0 || rows <= 0 ||
       outSize < size_t(count) * size_t(rows))
      return DisplayStatus::NoSuchTrack;
   const size_t n = size_t(count) * size_t(rows);
   auto result = OnDisplayLane<Columns>({ DisplayStatus::NotReady, {}, {} }, [=] {
      Columns c{ DisplayStatus::NotReady, {}, std::vector<uint8_t>(n) };
      if (auto &fn = GetDisplayProviders().spectrogramColumns)
         c.status = fn(trackId, channel, zoomLevel, firstColumn, count, rows,
            c.bytes.data(), n);
      return c;
   });
   return Deliver(result, out, n);
}

double PpsForLevel(int level)
{
   // API.md §7.1: the only way a zoom level becomes a pps
   return std::pow(2.0, level / 8.0);
}

} // namespace aubridge
