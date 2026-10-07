/*  SPDX-License-Identifier: GPL-2.0-or-later */
/**********************************************************************

  Audacity Android port

  Bridge.h

  Public entry points of libaudacity-bridge. This is the only header the JNI
  glue (native/jni) and the host tests include. The protocol carried through
  these functions is specified in native/bridge/API.md.

**********************************************************************/
#pragma once

#include <cstdint>
#include <memory>
#include <string>
#include <vector>

#if defined(_WIN32)
#  define AUBRIDGE_API
#else
#  define AUBRIDGE_API __attribute__((visibility("default")))
#endif

namespace aubridge {

//! Receives engine events (API.md §4). Called on the engine thread; must not
//! block and must not call back into Invoke().
class AUBRIDGE_API EventSink {
public:
   virtual ~EventSink();
   //! @param type   event type, e.g. "snapshot", "progress", "dialog"
   //! @param json   UTF-8 JSON payload
   virtual void OnEvent(const std::string &type, const std::string &json) = 0;
};

//! Starts the engine thread and its asynchronous bootstrap.
//! @param configJson start configuration (API.md §2.1), UTF-8 JSON
//! @return false if already started or the thread could not be created
AUBRIDGE_API bool Start(const std::string &configJson,
   std::shared_ptr<EventSink> sink);

//! Stops the engine thread (closes the project without saving). Used by the
//! host tests; Android normally never calls it (process death is the exit).
//! Blocks until the engine thread has exited.
AUBRIDGE_API void Stop();

//! True after `engine.ready` was emitted (and before Stop()).
AUBRIDGE_API bool IsReady();

//! Runs a command (API.md §3) on the engine thread and waits for it.
//! Must not be called on the engine thread itself.
//! @return the response envelope (API.md §3.1), UTF-8 JSON
AUBRIDGE_API std::string Invoke(const std::string &command,
   const std::string &argsJson);

//! Answers a blocking `dialog` event. Any thread.
AUBRIDGE_API void ReplyDialog(int dialogId, int button);

//! Answers a blocking `multiChoice` dialog with the checked choice indices.
//! Any thread.
AUBRIDGE_API void ReplyDialogChoices(int dialogId, const std::vector<int> &indices);

//! Requests cancel (stop == false) or stop (stop == true) of a running
//! progress. Any thread; never blocks.
AUBRIDGE_API void CancelProgress(int progressId, bool stop);

//! Lock-free transport snapshot (API.md §6.4). Any thread.
//! @param out at least 16 doubles
//! @return false before the engine is ready
AUBRIDGE_API bool ReadTransport(double *out, size_t n);

//! Lock-free meters (API.md §6.5); resets the peak accumulators. Any thread.
//! @param out at least 14 floats
AUBRIDGE_API bool ReadMeters(float *out, size_t n);

// Display data (API.md §7). These post to the engine thread's display lane
// and wait; never call them on the engine thread.

//! @param out at least 3 * count floats: min[count], max[count], rms[count]
//! @return waveVersion (>= 0, bit 62 set when partial) or a negative status
AUBRIDGE_API int64_t WaveColumns(int64_t trackId, int channel, int zoomLevel,
   int64_t firstColumn, int count, float *out, size_t outSize);

//! @param out at least count floats
AUBRIDGE_API int64_t EnvelopeColumns(int64_t trackId, int zoomLevel,
   int64_t firstColumn, int count, float *out, size_t outSize);

//! @return the binary layout of API.md §7.4; empty vector on error
AUBRIDGE_API std::vector<uint8_t> WaveSamples(int64_t trackId, int channel,
   double t0, double t1);

//! @param out at least count * rows bytes, column-major
AUBRIDGE_API int64_t SpectrogramColumns(int64_t trackId, int channel,
   int zoomLevel, int64_t firstColumn, int count, int rows,
   uint8_t *out, size_t outSize);

//! pps(level) = 2^(level / 8.0); the only way a zoom level becomes a pps.
AUBRIDGE_API double PpsForLevel(int level);

} // namespace aubridge
