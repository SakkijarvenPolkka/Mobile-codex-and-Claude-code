/*
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * PortAudio AAudio host API (Audacity Android port) -- extension API.
 *
 * The host API itself is reached through the normal PortAudio API
 * (Pa_Initialize, Pa_OpenStream, ...).  It is registered as host API "AAudio"
 * (type paInDevelopment; PortAudio has no type id for AAudio).  On the Linux
 * host build the same implementation runs on a simulated AAudio device and is
 * registered as host API "Null" (see pa_null.h); all functions below exist on
 * both platforms, so the bridge can call them unconditionally.
 *
 * Device model (fixed indices inside the host API):
 *   0  "Default Output"  output, 2 ch, AAUDIO_UNSPECIFIED device (follows Android routing)
 *   1  "Default Input"   input,  2 ch, AAUDIO_UNSPECIFIED device
 *   2+ devices supplied with PaAAudio_SetDeviceList() (AudioManager.getDevices())
 * Every device offers at least 2 channels in each direction it supports, even when
 * AudioDeviceInfo reports only [1] (mono USB microphone, mono speaker): AAudio converts
 * the channel count in SHARED mode, and input streams fall back to the other count and
 * map user channel c to device channel c % n.
 * Pa_GetDefaultOutputDevice()/Pa_GetDefaultInputDevice() are always valid.
 *
 * Thread rules: the Set* functions take an internal mutex and may be called
 * from any thread, but device-list and default changes only take effect at
 * the next Pa_Initialize() (Pa_Terminate(); Pa_Initialize(); -- only while no
 * stream is open).  The Get* functions never block on audio threads and are
 * safe from any thread, including the UI thread at frame rate.
 */
#ifndef PA_ANDROID_AAUDIO_H
#define PA_ANDROID_AAUDIO_H

#include <stdint.h>

#include "portaudio.h"

#ifdef __cplusplus
extern "C" {
#endif

#if defined(__GNUC__) || defined(__clang__)
#define PA_AAUDIO_EXPORT __attribute__((visibility("default")))
#else
#define PA_AAUDIO_EXPORT
#endif

/** Name of the host API as reported by Pa_GetHostApiInfo()->name. */
#define PA_AAUDIO_HOST_API_NAME "AAudio"
/** Name of the same host API on the Linux host build (simulated device). */
#define PA_NULL_HOST_API_NAME "Null"
/** Names of the two virtual devices at host-API device indices 0 and 1. */
#define PA_AAUDIO_DEFAULT_OUTPUT_NAME "Default Output"
#define PA_AAUDIO_DEFAULT_INPUT_NAME "Default Input"

/** One Android audio device (AudioDeviceInfo), as supplied by Java. */
typedef struct PaAAudioDeviceDesc {
    const char *name;          /**< UTF-8, unique and stable (stored in the /AudioIO/PlaybackDevice and
                                    /AudioIO/RecordingDevice prefs); copied */
    int32_t aaudioDeviceId;    /**< AudioDeviceInfo.getId(); 0 = AAUDIO_UNSPECIFIED (default routing) */
    int maxInputChannels;      /**< 0 = not an input device; PortAudio reports max(this, 2) */
    int maxOutputChannels;     /**< 0 = not an output device; PortAudio reports max(this, 2) */
    double nativeSampleRate;   /**< 0 = use the PaAAudio_SetDefaults() rate */
    int framesPerBurst;        /**< 0 = use the PaAAudio_SetDefaults() burst */
} PaAAudioDeviceDesc;

/** Stream options; apply to streams opened after PaAAudio_SetOptions(). */
typedef struct PaAAudioOptions {
    int sharingMode;           /**< AAUDIO_SHARING_MODE_SHARED (default, 1) or _EXCLUSIVE (0) */
    int outputPerformanceMode; /**< AAUDIO_PERFORMANCE_MODE_LOW_LATENCY (default, 12) */
    int inputPerformanceMode;  /**< input streams (input-only and full duplex); LOW_LATENCY (default) or NONE (10) */
    int inputPreset;           /**< AAUDIO_INPUT_PRESET_* ; default VOICE_RECOGNITION (6). Any other preset falls back to 6 if it cannot be opened */
    int usage;                 /**< AAUDIO_USAGE_MEDIA (default, 1) */
    int contentType;           /**< AAUDIO_CONTENT_TYPE_MUSIC (default, 2) */
    int acceptAnyRate;         /**< 1: Pa_IsFormatSupported accepts any rate in [8000,192000] (AAudio resamples);
                                    0 (default): only device native rates are reported as supported.
                                    Pa_OpenStream always accepts [8000,192000]. */
    int duplexDrainCallbacks;  /**< full-duplex warm-up: callbacks with input data to discard; 0 = automatic (~80 ms) */
    int duplexCushionBursts;   /**< callbacks to let input accumulate after the drain; 0 = 1 */
    int duplexDiscardCallbacks;/**< equilibrium callbacks (read at most one buffer); 0 = automatic (~120 ms) */
    int warmupTimeoutMs;       /**< full duplex: fail if the input delivers nothing for this long; 0 = 1500 */
    int maxFramesPerUserCallback; /**< upper bound of framesPerBuffer passed to the PortAudio callback; 0 = 2048 */
    int autoGrowOutputBuffer;  /**< 1 (default): output-only streams grow the AAudio buffer by one burst per xrun */
    int stereoInputPreset;     /**< preset of input streams opened with >= 2 channels (e.g. CAMCORDER (5), which uses
                                    the stereo microphone pair on most phones); 0 (default) = inputPreset */
    int ignoreMMapQuirks;      /**< 0 (default): on devices known to record silence or corrupt audio through MMAP
                                    (Oboe's QuirksManager list: Samsung Exynos 990 / 9810 builds, Qualcomm SM8150 on
                                    Android 9) the stream is opened with MMAP disabled; 1: never */
} PaAAudioOptions;

/** Snapshot of the most recently started stream (kept after it stops/closes). */
typedef struct PaAAudioStreamStats {
    int valid;                 /**< 1 once any stream was started since process start */
    int running;               /**< started and neither stopped, aborted nor closed */
    int active;                /**< Pa_IsStreamActive() of that stream (0 after paComplete drained, error, disconnect) */
    int disconnected;          /**< AAUDIO_ERROR_DISCONNECTED was reported (device unplugged / route lost) */
    int lastAAudioError;       /**< last AAudio error seen by the stream (0 = none) */
    int warmupTimedOut;        /**< full duplex: the input delivered no data during warm-up */
    int hasOutput, hasInput;
    double sampleRate;         /**< stream (device-side) sample rate */
    int outputDeviceId, inputDeviceId;   /**< AAudioStream_getDeviceId() (actual device) */
    int outputBurst, inputBurst;         /**< AAudioStream_getFramesPerBurst() */
    int outputBufferSize;                /**< AAudioStream_getBufferSizeInFrames(out) */
    int outputBufferCapacity, inputBufferCapacity;
    int outputPerformanceMode, inputPerformanceMode;   /**< as granted by AAudio */
    int outputSharingMode, inputSharingMode;           /**< as granted by AAudio */
    int outputFormat, inputFormat;       /**< AAUDIO_FORMAT_PCM_FLOAT (2) or _I16 (1) */
    int inputChannelsOpened;             /**< may differ from the requested count (mono mic -> stereo request) */
    double outputLatencySec;   /**< DAC time of the next written frame - now; -1 unknown */
    double inputLatencySec;    /**< now - ADC time of the next frame to read; -1 unknown */
    double duplexOffsetSec;    /**< median(outputBufferDacTime - inputBufferAdcTime) of paired frames (full duplex,
                                    steady state); this is the round-trip correction: /AudioIO/LatencyCorrection =
                                    -duplexOffsetSec*1000. -1 unknown */
    int outputXRuns, inputXRuns;         /**< AAudioStream_getXRunCount() since start */
    int64_t paddedInputFrames;           /**< full duplex: input frames zero-filled (input late) */
    int64_t droppedInputFrames;          /**< full duplex: input frames discarded to re-align (input early) */
    int64_t callbackCount;               /**< AAudio data callbacks since start */
    int64_t framesProcessed;             /**< frames passed to the PortAudio callback since start */
    double cpuLoad;                      /**< Pa_GetStreamCpuLoad() */
    int inputPreset;                     /**< AAudioStream_getInputPreset() of the input stream (0 = no input) */
    int inputMMapDisabled;               /**< 1: the input was opened with MMAP disabled (device quirk) */
    int outputMMapDisabled;              /**< 1: the output was opened with MMAP disabled (device quirk) */
} PaAAudioStreamStats;

/** Fills *opts with the built-in defaults (call, modify fields, then PaAAudio_SetOptions). */
PA_AAUDIO_EXPORT void PaAAudio_GetDefaultOptions(PaAAudioOptions *opts);
/** Sets options for streams opened later; NULL restores the defaults. Any thread. */
PA_AAUDIO_EXPORT void PaAAudio_SetOptions(const PaAAudioOptions *opts);
/** Fills *opts with the options currently in effect (read-modify-write with PaAAudio_SetOptions). */
PA_AAUDIO_EXPORT void PaAAudio_GetOptions(PaAAudioOptions *opts);

/**
 * Replaces the list of specific devices exposed after the two default devices.
 * The array and the names are copied.  Entries with an empty name, no channels
 * or a name equal to an earlier entry (or to a default device) are skipped.
 * Takes effect at the next Pa_Initialize().  count = 0 clears the list.
 * Returns paNoError, or paInvalidDevice if count < 0 or devs is NULL with
 * count > 0, or paInsufficientMemory.
 */
PA_AAUDIO_EXPORT PaError PaAAudio_SetDeviceList(const PaAAudioDeviceDesc *devs, int count);
/** Alias of PaAAudio_SetDeviceList (name used in the task description). */
#define PaAAudio_SetDevices PaAAudio_SetDeviceList

/**
 * Native rate and burst of the default route (AudioManager
 * PROPERTY_OUTPUT_SAMPLE_RATE / PROPERTY_OUTPUT_FRAMES_PER_BUFFER).  Used as
 * defaultSampleRate/latencies of the default devices and of devices whose
 * descriptor leaves them 0.  Takes effect at the next Pa_Initialize().  If it
 * is never called, Pa_Initialize probes once by opening (not starting) an
 * output stream.  nativeRate <= 0 re-enables probing.
 */
PA_AAUDIO_EXPORT void PaAAudio_SetDefaults(double nativeRate, int framesPerBurst);

/** Software input gain (linear, clamped to [0, 16]) applied in the callback. Any thread. */
PA_AAUDIO_EXPORT void PaAAudio_SetInputGain(float linear);

/**
 * Copies the statistics of the most recently started stream into *out.
 * Returns 1 if out->valid (a stream was started since process start), else 0
 * (and *out is zeroed).  Lock-free with respect to the audio threads; any thread.
 */
PA_AAUDIO_EXPORT int PaAAudio_GetActiveStreamStats(PaAAudioStreamStats *out);

/**
 * Text of the last AAudio error reported by this host API, e.g.
 * "AAUDIO_ERROR_DISCONNECTED: output data callback", or "" if none.
 * Audacity's LastPaErrorString() only shows Pa_GetErrorText(); append this.
 * The returned pointer is thread-local and valid until the next call on the
 * same thread.
 */
PA_AAUDIO_EXPORT const char *PaAAudio_GetLastErrorText(void);

/** Clears the last error text (e.g. before a new transport start). */
PA_AAUDIO_EXPORT void PaAAudio_ClearLastError(void);

#ifdef __cplusplus
}
#endif

#endif /* PA_ANDROID_AAUDIO_H */
