/*  SPDX-License-Identifier: GPL-2.0-or-later */
/**********************************************************************

  Audacity Android port

  TransportSnapshot.cpp

  The transport snapshot of API.md §6.4: written by the engine thread (tick
  and transport commands), read lock free by ReadTransport on any thread
  (the UI thread every frame).

  Seqlock with one writer: the sequence number is odd while a write is in
  progress; every field is a relaxed std::atomic<double> so that a torn
  read is merely retried, never undefined behaviour.

**********************************************************************/
#include "AudioModule.h"

#include <cmath>
#include <ctime>

namespace aubridge {

namespace {

struct Record {
   std::atomic<uint32_t> seq{ 0 };
   std::atomic<double> fields[kTransportFields];
   std::atomic<bool> valid{ false };

   Record()
   {
      for (auto &f : fields)
         f.store(0.0, std::memory_order_relaxed);
   }
};

Record &TheRecord()
{
   static Record record;
   return record;
}

} // namespace

int64_t MonotonicNowNs()
{
   timespec ts{};
   clock_gettime(CLOCK_MONOTONIC, &ts);
   return int64_t(ts.tv_sec) * 1000000000LL + ts.tv_nsec;
}

void PublishTransport(const double (&fields)[kTransportFields])
{
   auto &r = TheRecord();
   const uint32_t s = r.seq.load(std::memory_order_relaxed);
   r.seq.store(s + 1, std::memory_order_relaxed);
   std::atomic_thread_fence(std::memory_order_release);
   for (size_t i = 0; i < kTransportFields; ++i)
      r.fields[i].store(fields[i], std::memory_order_relaxed);
   r.seq.store(s + 2, std::memory_order_release);
   r.valid.store(true, std::memory_order_release);
}

bool ReadTransportImpl(double *out, size_t n)
{
   if (!out || n < kTransportFields)
      return false;
   auto &r = TheRecord();
   if (!r.valid.load(std::memory_order_acquire))
      return false;
   double tmp[kTransportFields];
   // One writer updating at <= 50 Hz: a retry is rare; bound it anyway
   for (int attempt = 0; attempt < 10000; ++attempt) {
      const uint32_t s1 = r.seq.load(std::memory_order_acquire);
      if (s1 & 1u)
         continue;
      for (size_t i = 0; i < kTransportFields; ++i)
         tmp[i] = r.fields[i].load(std::memory_order_relaxed);
      std::atomic_thread_fence(std::memory_order_acquire);
      const uint32_t s2 = r.seq.load(std::memory_order_relaxed);
      if (s1 == s2) {
         for (size_t i = 0; i < kTransportFields; ++i)
            out[i] = tmp[i];
         for (size_t i = kTransportFields; i < n; ++i)
            out[i] = 0;
         return true;
      }
   }
   return false;
}

} // namespace aubridge
