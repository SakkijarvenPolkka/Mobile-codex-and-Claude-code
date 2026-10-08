# PortAudio for Android (Audacity port): AAudio host API + host "Null" device

PortAudio upstream has no Android host API. The superbuild compiles PortAudio's
portable core (`src/common`, `src/os/unix`) from a pinned upstream commit (see
`native/cmake/Dependencies.cmake`) with `native/cmake/deps/portaudio/CMakeLists.txt`
and adds the host API in this directory. Design background: `notes/audio-io.md` §3–4.

| File | Purpose |
|---|---|
| `pa_android_hostapis.c` | Host-API table (replaces upstream `pa_unix_hostapis.c`). `PA_USE_AAUDIO=1` registers `PaAAudio_Initialize` first, so it is the default host API. |
| `src/pa_aaudio.c` | The AAudio host API (the only real implementation). |
| `include/pa_android_aaudio.h` | Extension API used by the bridge/JNI (device list, defaults, options, stats, error text, input gain). |
| `src/pa_aaudio_null.c` | Host only: simulated AAudio ("Null" device) implementing the AAudio subset `pa_aaudio.c` uses. |
| `src/null/aaudio/AAudio.h` | Host only: stand-in `<aaudio/AAudio.h>` (same names and values as NDK r28). |
| `include/pa_null.h` | Host only: test API of the simulated device. |
| `sources.cmake` | Lists the above for the PortAudio CMake. |
| `../tests/portaudio/` | Host tests (`ctest -L portaudio`). |

## Build

| Cache option | Default | Meaning |
|---|---|---|
| `PA_USE_AAUDIO` | ON on Android, OFF elsewhere | Compile `pa_aaudio.c`, link `libaaudio` + `liblog`. |
| `PA_USE_NULL` | ON on the Linux host, OFF on Android | Compile `pa_aaudio.c` against the simulator; host API name `"Null"`. |
| `PA_USE_SKELETON` | OFF | PortAudio's skeleton host API (template, no devices). |

Trees configured before this change have `PA_USE_AAUDIO:BOOL=OFF` cached; reconfigure them with
`-DPA_USE_AAUDIO=ON` (done for `build-android-arm64` and `build-android-x86_64`).

Targets: `portaudio` / `portaudio::portaudio` (`libportaudio.so`, one shared instance because
`lib-audio-devices` and `lib-audio-io` both call `Pa_*`), and the INTERFACE target
**`portaudio::android`** = portaudio + the include directory of `pa_android_aaudio.h`/`pa_null.h` +
`PA_HAS_AAUDIO_EXTENSIONS=1` (+ `PA_HAS_NULL_DEVICE=1` on the host). Code that calls `PaAAudio_*`
or `PaNull_*` links `portaudio::android`. (The headers are deliberately not PUBLIC on `portaudio`:
~400 objects see PortAudio through `lib-audio-devices` and would all recompile.)

Our sources compile with `-Wall -Wextra -Wshadow -Wstrict-prototypes` (warning-free with GCC 13
and NDK clang 19); upstream sources keep `-w`. `HAVE_CLOCK_GETTIME`/`HAVE_NANOSLEEP` are now
defined, so PortAudio's `PaUtil_GetTime` (CPU load) uses `CLOCK_MONOTONIC` and `Pa_Sleep` uses
`nanosleep`.

## Extension API (`pa_android_aaudio.h`)

| Function | Thread | Semantics |
|---|---|---|
| `PaAAudio_SetDeviceList(devs, n)` (alias `PaAAudio_SetDevices`) | any | Copies Java's `AudioDeviceInfo` list; skips empty names, zero-channel entries, duplicates and the two default names. Takes effect at the next `Pa_Initialize`. |
| `PaAAudio_SetDefaults(rate, burst)` | any | Native rate/burst of the default route (`AudioManager.getProperty`). Without it `Pa_Initialize` probes by opening (never starting) an output stream. `rate <= 0` re-enables probing. **Call it before `AudioIO::Init()`** – the probe costs an AAudio open. |
| `PaAAudio_GetDefaultOptions` / `PaAAudio_GetOptions` / `PaAAudio_SetOptions(opts or NULL)` | any | Sharing/performance modes (the input mode applies to duplex input too), input preset and a separate `stereoInputPreset` for ≥ 2-channel input (the bridge uses CAMCORDER: the stereo microphone pair), usage/content type, `acceptAnyRate`, duplex warm-up lengths, warm-up timeout, max frames per PortAudio callback, auto-grow of the output buffer, `ignoreMMapQuirks`. Applies to streams opened later. |
| `PaAAudio_SetInputGain(linear)` | any | Software input gain in [0, 16] applied in the callback (3.7.9 has no record gain without PortMixer). |
| `PaAAudio_GetActiveStreamStats(&s)` | any, never blocks on audio threads | Snapshot of the most recently started stream, kept after stop/close: running/active/disconnected/lastAAudioError/warmupTimedOut, rate, device ids, bursts, buffer size/capacity, granted performance & sharing modes, formats, input channels opened, input preset, MMAP disabled by a device quirk, measured output/input latency, **duplex offset** (median DAC−ADC of paired frames = round-trip correction), xruns, padded/dropped input frames, callback/frame counts, CPU load. |
| `PaAAudio_GetLastErrorText()` / `PaAAudio_ClearLastError()` | any | `"AAUDIO_ERROR_X: <operation>"`; `AudioIO::LastPaErrorString()` shows only `Pa_GetErrorText`, append this. Thread-local copy. |

## Device model

Host API name `"AAudio"` (`"Null"` on the host), type `paInDevelopment` (PortAudio has no AAudio
type id; Audacity's `paALSA`/WASAPI special cases therefore stay inactive).

| Index | Name | Channels | AAudio device |
|---|---|---|---|
| 0 | `Default Output` | out 2 | `AAUDIO_UNSPECIFIED` (follows Android routing) |
| 1 | `Default Input` | in 2 | `AAUDIO_UNSPECIFIED` |
| 2+ | from `PaAAudio_SetDeviceList` | as supplied | `aaudioDeviceId` |

`Pa_GetDefaultOutputDevice()`/`Pa_GetDefaultInputDevice()` are always 0/1, so `AudioIO::Init`
writes `Default Output`/`Default Input`/`AAudio` to the prefs on first run and
`AudioIOBase::getPlayDevIndex` never hits its `assert(false)` path. `Pa_Initialize` never touches
the microphone (no permission needed) and fails only on out-of-memory. `defaultSampleRate` = the
descriptor's native rate or the default rate; `defaultLow*Latency = 2·burst/rate`,
`defaultHighOutputLatency = 0.1`, `defaultHighInputLatency = 0.04`. Changing devices needs
`Pa_Terminate(); Pa_Initialize();` while AudioIO is idle, then `AudioIO::HandleDeviceChange()`.

## Sample-rate policy ("native preferred, everything accepted")

* `Pa_IsFormatSupported` (and therefore AudioIOBase's rate probes, which cache positive answers
  forever) reports only native rates: output = device native rate; input = its native rate **or**
  the default output's native rate (so duplex always has a common rate). `acceptAnyRate=1` accepts
  8000–192000.
* `Pa_OpenStream` accepts any integral rate in [8000, 192000] and asks AAudio for exactly that rate
  (AAudio/AudioFlinger resample; MMAP is then usually not used). This keeps cached rates working
  after a route change.
* Result: `AudioIO::GetBestRate(…, 44100)` returns the native rate (48000 typically) and Audacity
  resamples (verified by `portaudio.audioio_monitor`).

## Stream state machine

Flags (atomics): `isActive`, `isStopped`, `abortRequested`, `inCallback`, `finished`,
`finishedNotified`, `closed`; callback-thread-only `phase` ∈ {WARMUP_DRAIN, WARMUP_CUSHION,
WARMUP_DISCARD, RUNNING, DRAINING}.

| State | `Pa_IsStreamStopped` | `Pa_IsStreamActive` | Entered by |
|---|---|---|---|
| Open | 1 | 0 | `Pa_OpenStream` (both AAudio streams opened eagerly, not started) |
| Running (incl. duplex warm-up) | 0 | 1 | `Pa_StartStream`: `isActive=1` *before* `requestStart(in)`, `requestStart(out)` |
| Draining (user returned `paComplete`, output) | 0 | 1 | silence for `bufferSize + burst` frames, then → Finished |
| Finished ("callback finished") | 0 | 0 | drain done, `paAbort`, input-only `paComplete`, AAudio error/disconnect, duplex warm-up timeout, input read error |
| Stopped | 1 | 0 | `Pa_StopStream`/`Pa_AbortStream` (from Running or Finished) |
| Closed | `paBadStreamPtr` | `paBadStreamPtr` | `Pa_CloseStream` (magic cleared; memory freed ≥ 1 s later) |

`streamFinishedCallback` runs exactly once per start (from the callback/error thread on Finished,
or from Stop/Abort), never after close. A restart after Stop works (counters and clocks reset).

## Threads and synchronization

| Thread | Runs | Rules |
|---|---|---|
| Caller (Audacity engine thread) | Initialize/Terminate, Open/Start/Stop/Abort/Close, IsFormatSupported | serialised per stream by the caller (PortAudio contract) |
| AudioIO buffer thread | `Pa_IsStreamActive` (`AudioIO.cpp:2374`) concurrently with Close | reads an atomic; after close a cleared magic (memory lives ≥ 1 s) |
| AAudio output data callback (output-only and duplex) | `OutputCallback`: duplex input read, buffer processor, user callback | no locks, no allocation, no logging, only `AAudioStream_get*` and `AAudioStream_read(in, …, timeout 0)` on the *other* stream; always returns CONTINUE |
| AAudio input data callback (input-only) | `InputCallback`; the AAudio buffer is never written (gain copies first) | same |
| AAudio error callback thread | `ErrorCallback`: records error/disconnected, marks Finished, sets the error text (`g_lock`) | never stops/closes/waits |
| Poller (`PaAAudioPoll`, one per running stream, 20 ms) | `AAudioStream_getTimestamp` for both streams → seqlocked clock samples; latency stats; output-only buffer auto-grow on xrun | normal priority; joined in Stop/Abort before any AAudio stop |
| Reaper (`PaAAudioReaper`, detached, one per close) | waits for a blocked callback (no timeout), `requestStop` + wait STOPPED, 10 ms, `AAudioStream_close`, then frees the memory ≥ 1 s after close | never holds locks while waiting |

* **No user callback after Stop/Abort returns** – Dekker pair: the callback stores `inCallback=1`
  then loads `abortRequested`; Stop stores `abortRequested=1` then waits for `inCallback==0`
  (seq_cst). A callback that entered after the store sees the abort and outputs silence.
* **Deadlock safety** (Audacity's `CallbackDoSeek` blocks in the callback on `mSuspendAudioThread`,
  which `AudioIO::StopStream` holds while calling Abort/Close): Stop/Abort wait at most 300 ms for
  the callback, then mark the AAudio stop as deferred and return; Close never blocks and hands
  the AAudio objects to the reaper. `AAudioStream_close`/`requestStop` are never called while our
  callback may run (the simulator's MMAP-like `requestStop` *joins* the callback thread, so a
  violation would hang the tests). If the caller restarts a deferred-stopped stream, Start first
  waits ≤ 2 s for the callback.
* `g_lock` (process mutex) guards configuration, error text and the stats snapshot; never taken on
  data-callback threads, never held across blocking AAudio calls.
* Timestamps: poller writes `{framePosition, timeNs}` with a seqlock; the callback reads it with
  bounded retries (falls back to an estimate). `Pa_GetStreamCpuLoad`/stats read an atomic copy of
  the callback-owned CPU-load measurer.

## Full duplex (output-driven)

The output stream has the data callback; the input stream is opened in read mode with
`inputPerformanceMode` (`LOW_LATENCY` by default) and read non-blocking from the output callback.
Its capacity hint is 4096 frames: AAudio's legacy path passes it to `AudioRecord` as `frameCount`,
and AudioFlinger grants FAST (and RAW) capture only up to its 4096-frame pipe (Oboe clamps input
capacity the same way, b/80308183); with `NONE` it is max(0.2 s, 4096). The excess threshold below
is capped to fit that FIFO. Start order: input, then output. Warm-up (output silent, Audacity time does not move): **drain** (≈ 80 ms of callbacks
that got input data; discard it all), **cushion** (1 callback), **discard** (≈ 120 ms; read one
buffer when available). Steady state reads exactly `n` frames per `n` output frames:

* short read → zero padding, `paInputUnderflow`, the deficit is remembered as *debt*;
* surplus later (`avail > n + max(bursts)`) with debt → frames discarded to repay it
  (`paInputOverflow`, alignment restored);
* no debt and `avail > n + 3·max(bursts) + 10 ms` (input clock faster) → surplus discarded
  (`paInputOverflow`), so latency stays bounded;
* AAudio input xrun → `paInputOverflow` (Audacity records a dropout only for real data loss);
  xruns during the warm-up (whose input is discarded anyway) are not reported;
* read error / `DISCONNECTED` → Finished; no input data at all for `warmupTimeoutMs` (1.5 s) during
  warm-up → Finished with `warmupTimedOut` and `AAUDIO_ERROR_TIMEOUT`.

Mono microphones: if AAudio opens fewer (or more) input channels than requested, user channel `c`
reads device channel `c % inCh` through the buffer processor's channel descriptors. Devices whose
`AudioDeviceInfo` reports only one channel (mono USB microphone or speaker) are offered with 2
channels: AAudio converts in SHARED mode, and Audacity always opens 2 playback channels and the
`/AudioIO/RecordChannels` preference for recording.

## Timestamps and latency

* Frame indices come from AAudio (`getFramesWritten(out)` at output-callback entry, and in the
  input-only callback `getFramesRead(in) − numFrames`: AOSP counts an input buffer as read before
  calling back – legacy `callDataCallbackFrames`, MMAP `callbackLoop`; read-mode input:
  `getFramesRead` before `AAudioStream_read`) – the documented timestamp units, including frames
  lost in overruns.
* `outputBufferDacTime = tsTime + (outIndex − tsPos)/rate`, `inputBufferAdcTime` likewise;
  estimates (`now + bufferSize/rate`, `now − (n+burst)/rate`) until the first timestamp; never
  decreasing; `currentTime` and `Pa_GetStreamTime` = `CLOCK_MONOTONIC` seconds.
* `streamInfo.outputLatency = (bufferSize + burst)/rate` (AudioIO sizes its ring from it);
  `inputLatency = 2·burst/rate`. Output buffer size = `clamp(suggestedLatency·rate, 2·burst,
  capacity)`.
* Stats: `outputLatencySec = (framesWritten − tsPos)/rate − (now − tsTime)`,
  `inputLatencySec = (now − tsTime) − (framesRead − tsPos)/rate`, `duplexOffsetSec` = median of the
  last 32 steady-state `outputBufferDacTime − inputBufferAdcTime` values; the bridge writes
  `/AudioIO/LatencyCorrection = −duplexOffsetSec·1000` (+ user trim) of the route before an
  overdub, and re-aligns the take to its own measurement when it is committed.

## Device quirks (MMAP)

`Pa_Initialize` reads the system properties Oboe's `QuirksManager::isMMapSafe` uses
(`ro.product.manufacturer`, `ro.arch`, `ro.hardware.chipname`, `ro.build.changelist`,
`ro.soc.model`, `ro.build.version.sdk`). On Samsung Exynos 990 builds < 19350896 (corrupt
low-latency recording, b/159066712) and Exynos 9810 builds ≤ 18847185 (silence unless
VOICE_COMMUNICATION, oboe#1110) the input, and on SM8150 with Android ≤ 9 every stream, is opened
with `AAudio_setMMapPolicy(NEVER)` (dlsym'ed from libaaudio like Oboe's `AAudioExtensions`;
restored right after the open), or without `LOW_LATENCY` if libaaudio does not export it.
`stats.inputMMapDisabled/outputMMapDisabled` report it; `ignoreMMapQuirks` turns it off.

## Errors and disconnects

Open falls back float → int16, the other input channel count (1↔2) and input preset →
`VOICE_RECOGNITION` (the default; the bridge chooses UNPROCESSED where supported, else
CAMCORDER for stereo), except after `DISCONNECTED`, `NO_SERVICE`, `INVALID_RATE`. AAudio results map
to `paDeviceUnavailable` (DISCONNECTED), `paInvalidSampleRate`, `paInvalidChannelCount`
(OUT_OF_RANGE), `paSampleFormatNotSupported`, `paInsufficientMemory`, `paTimedOut`, otherwise
`paUnanticipatedHostError` + `Pa_GetLastHostErrorInfo` (`paInDevelopment`, AAudio code, text).
A disconnect makes the stream Finished (`Pa_IsStreamActive()==0`, not stopped) and sets
`stats.disconnected`; the bridge's tick then calls `AudioIO::StopStream()` (recording is committed
normally) and tells the user. No automatic reopen. Note (critic C22): every failed
`Pa_OpenStream` costs AudioIO ≥ 1 s of retries (~5 s in the first 10 s), so gate recording on the
RECORD_AUDIO permission.

## PortAudio API coverage

| Function | Implementation |
|---|---|
| `Pa_Initialize` / `Pa_Terminate` | Device table built from defaults + Java list; Terminate waits ≤ 2 s for pending reaper closes (pa_front closes open streams first). |
| `Pa_GetHostApiCount/Info`, `Pa_GetDefaultHostApi`, `Pa_HostApiTypeIdToHostApiIndex`, `Pa_HostApiDeviceIndexToDeviceIndex`, `Pa_GetDeviceCount/Info`, `Pa_GetDefault{In,Out}putDevice` | pa_front over our table (1 host API, ≥ 2 devices). |
| `Pa_IsFormatSupported` | Device/channel/format/host-specific-info validation + rate policy; returns `paFormatIsSupported` (0). |
| `Pa_OpenStream` | Callback streams only (blocking → `paInternalError`, text "blocking streams … not supported"); interleaved and non-interleaved user buffers in any PortAudio sample format (converted by `pa_process`; host side AAudio float, int16 fallback); `framesPerBuffer` 0 (variable, ≤ `maxFramesPerUserCallback`) or fixed (adapting processor); flags `paClipOff`, `paDitherOff`, `paNeverDropInput`, `paPrimeOutputBuffersUsingStreamCallback` accepted (the last is ignored), platform flags → `paInvalidFlag`; `paUseHostApiSpecificDeviceSpecification` and host-specific stream info rejected. |
| `Pa_StartStream` | see state machine; AAudio start failure → mapped error, stream stays stopped. |
| `Pa_StopStream` | = Abort (AAudio `requestStop` lets queued output play). |
| `Pa_AbortStream` | see deadlock safety; `paStreamIsStopped` on a stopped stream (pa_front). |
| `Pa_CloseStream` | never blocks on the callback; pa_front aborts a running stream first. |
| `Pa_IsStreamStopped` / `Pa_IsStreamActive` | atomics (table above). |
| `Pa_GetStreamInfo` | `outputLatency`, `inputLatency`, `sampleRate` valid right after open. |
| `Pa_GetStreamTime` | `CLOCK_MONOTONIC` seconds. |
| `Pa_GetStreamCpuLoad` | PortAudio CPU-load measurer (published atomically). |
| `Pa_SetStreamFinishedCallback` | once per start, never after close. |
| `Pa_ReadStream` / `Pa_WriteStream` / `Pa_Get{Read,Write}Available` | not supported (callback streams return PortAudio's "callback stream" errors). |
| `PaStreamCallbackFlags` | `paInputOverflow` (input xrun or discarded input), `paInputUnderflow` (zero padding), `paOutputUnderflow` (output xrun); never `paPrimingOutput`/`paOutputOverflow`. |

## The "Null" device (host build)

`pa_aaudio.c` runs unchanged on `src/pa_aaudio_null.c`, so the host tests exercise the real host
API. The simulator paces callback threads with absolute `CLOCK_MONOTONIC` deadlines (default
48 kHz, 480-frame bursts, capacity 16 bursts, initial output buffer 2 bursts), measures output
(RMS/peak per channel), generates input (440 Hz sine, amplitude 0.5, or a loopback of output
channel 0 with a configurable delay), models timestamps/xruns/overruns consistently (an input
buffer is counted as read before its callback, like AOSP), refuses FAST capture to an input asking
for more than 4096 frames of capacity, and can simulate disconnects, xruns, stalled input, input
clock drift, mono microphones, open failures, system properties and a device that records silence
through MMAP (`mmapInputSilent`); `PaNull_GetLastOpen` reports what each open requested. It counts AAudio API misuse (stop/close/wait/read from the stream's own data callback,
close while a callback runs, any call on a closed stream) instead of crashing; every test asserts
zero misuse. See `include/pa_null.h`. For AudioIO tests on the host link `portaudio::android`
and call `PaNull_*` before/while running AudioIO (e.g. loopback for overdub alignment).

## Verification done (host)

* `ctest --test-dir native/build-host -L portaudio` – 29 tests: enumeration/re-init, rate policy,
  device injection, output/input/int16/duplex/loopback, fixed `framesPerBuffer` (adapting buffer
  processor), paComplete drain, paAbort, idempotence of
  Stop/Abort/Close/Terminate, blocked-callback deadlock safety, disconnect (output and duplex),
  xruns + buffer growth, open errors and fallbacks, mono mic, warm-up timeout, ±5 % clock drift,
  input gain, Terminate with a running stream, input-only ADC time of the first frame, mono
  `AudioDeviceInfo` devices, duplex input capacity / FAST path / warm-up xruns, MMAP quirks,
  input presets, and `portaudio.audioio_monitor` (Audacity's
  `AudioIO::Init`, rate probes, `GetBestRate`, 3× `StartMonitoring`/`StopStream`).
* Loopback: the measured lag of every impulse equals `duplexOffsetSec·rate + delay` exactly.
* ThreadSanitizer and AddressSanitizer+UBSan builds of the same tests: clean (one intentional
  TSan report suppressed: the test's deliberate `Pa_CloseStream` on a just-closed pointer races
  with the delayed `free`, which is ordered by time, not by synchronization).

## Self-review: what can only be checked on a device

* AAudio behaviour the simulator assumes: `getFramesWritten(out)` at output-callback entry
  indexes the first frame of the buffer, `getFramesRead(in)` at input-callback entry the frame
  after its last (AOSP source); `requestStop` from another thread with a running callback returns
  (MMAP joins the callback thread – our callback returns quickly once `abortRequested` is set);
  timestamps use the same frame units as the counters; `AAudioStream_read(timeout 0)` never blocks.
* Legacy (non-MMAP) paths deliver input in large chunks; the warm-up/cushion heuristics and the
  excess threshold (3 bursts + 10 ms) may need tuning – watch `paddedInputFrames`,
  `droppedInputFrames` and `duplexOffsetSec` stability.
* `AAudioStream_getTimestamp` is called from the poller only (Oboe advice for API < 30).
* `ProbeDefaults` opens an output stream inside `Pa_Initialize` if Java did not call
  `PaAAudio_SetDefaults` (cost ~10–100 ms).
* An input preset of `UNPROCESSED` may be refused on some devices (falls back to
  `VOICE_RECOGNITION`); CAMCORDER really being the stereo pair, and FAST capture with the
  4096-frame duplex input, need a device (`stats.inputPerformanceMode`).
* The MMAP quirk list is Oboe's; `AAudio_setMMapPolicy` reached through dlsym is not NDK API.
* Not implemented: blocking streams, automatic reopen after disconnect, `AAudioStream_release`
  (API 30), MMAP detection.

### Device test checklist

1. `Pa_Initialize` without RECORD_AUDIO permission: 2 devices, no mic indicator; logcat `PaAAudio`
   "initialized".
2. Playback of a 44.1 kHz project on a 48 kHz device: stats rate 48000, performance mode
   LOW_LATENCY, no xruns; selection play stops by itself (`Pa_IsStreamActive` → 0 after
   paComplete + buffer).
3. Loop play with seek spam (CallbackDoSeek) then Stop: no hang (logcat "callback blocked … deferring"
   is acceptable), no crash 1–2 s later (delayed free).
4. Record mono and stereo on the built-in mic (mono mic → stereo request works); int16 and float
   projects; input gain.
5. Overdub with headphones and a loopback cable: `duplexOffsetSec` stable within ±1 ms across
   runs; recorded click aligned within ±1 ms after `/AudioIO/LatencyCorrection = −offset`.
6. Duplex with USB mic at 44.1 kHz + phone speaker at 48 kHz (different clocks): runs, occasional
   pads/drops only, latency bounded.
7. Unplug headphones / USB device during playback and during recording: stream Finished,
   `stats.disconnected=1`, recording committed, a new Play opens on the new route.
8. Microphone silenced/stalled (another app, background without FGS): duplex warm-up timeout after
   1.5 s with a clear message instead of hanging at t0.
9. Bluetooth A2DP output: `outputLatencySec` 0.15–0.3 s reported; play-head compensation.
10. 100× start/stop cycles and device rescans (`Pa_Terminate/Pa_Initialize` while idle): no leaks
    (`dumpsys media.aaudio`), no stuck streams.
