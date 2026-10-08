/*
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * "Null" audio device for host tests (Audacity Android port) -- test API.
 *
 * Only available in the Linux host build (PA_USE_NULL=ON, the default there;
 * the macro PA_HAS_NULL_DEVICE is defined for consumers of the portaudio
 * target).  The host build compiles the real AAudio host API
 * (src/pa_aaudio.c) against a simulated AAudio implementation
 * (src/pa_aaudio_null.c), registered as PortAudio host API "Null" with the
 * same devices as on Android ("Default Output", "Default Input", plus
 * PaAAudio_SetDeviceList() devices).  The simulated device:
 *   - runs callback threads paced by CLOCK_MONOTONIC in real time
 *     (one burst per period, absolute deadlines);
 *   - discards output frames but measures them (RMS/peak per channel,
 *     PaNull_GetOutputStats);
 *   - produces input as a configurable sine (default 440 Hz, amplitude 0.5),
 *     or as a loopback of the output delayed by a configurable number of
 *     frames (to test round-trip latency compensation);
 *   - reports AAudio-style timestamps, xrun counts and frame counters
 *     consistent with that model, and can simulate disconnects, xruns,
 *     stalled input, input clock drift and open failures;
 *   - counts AAudio API misuse (stop/close/read from a data callback, close
 *     while a callback runs, calls on closed streams) instead of crashing.
 *
 * All functions are thread-safe.  Configuration changes apply to AAudio
 * streams opened (PaNull_SetConfig) or started afterwards, except the input
 * signal, which applies immediately.
 */
#ifndef PA_NULL_H
#define PA_NULL_H

#include <stdint.h>

#include "pa_android_aaudio.h"

#ifdef __cplusplus
extern "C" {
#endif

typedef struct PaNullConfig {
    double sampleRate;         /**< native rate of the simulated device (default 48000) */
    int framesPerBurst;        /**< period of the simulated device (default 480 = 10 ms) */
    int bufferCapacityBursts;  /**< AAudio buffer capacity in bursts (default 16) */
    int maxInputChannels;      /**< channels the simulated microphone can deliver (default 2);
                                    a request for more opens with this many (like a mono mic) */
    double inputFrequency;     /**< input sine frequency in Hz (default 440) */
    double inputAmplitude;     /**< input sine amplitude (default 0.5; 0 = silence) */
    int loopback;              /**< 1: input = channel 0 of the output, delayed by loopbackDelayFrames
                                    after it was "played" (default 0) */
    int loopbackDelayFrames;   /**< extra acoustic delay of the loopback (default 0) */
    double inputDriftPpm;      /**< read-mode (full-duplex) input clock error in ppm (default 0) */
    int mmapInputSilent;       /**< 1: like the Samsung devices in Oboe's QuirksManager, an input stream opened
                                    with LOW_LATENCY while the MMAP policy (AAudio_setMMapPolicy) is not NEVER
                                    records digital silence (default 0) */
} PaNullConfig;

/** What pa_aaudio.c asked AAudioStreamBuilder_openStream for, and what it got. */
typedef struct PaNullOpenInfo {
    int valid;                 /**< 1 once a stream of that direction was opened */
    int deviceId;              /**< requested (0 = AAUDIO_UNSPECIFIED) */
    int channelCount;          /**< requested (0 = unspecified) */
    int sampleRate;            /**< requested */
    int performanceMode;       /**< requested */
    int grantedPerformanceMode;/**< granted: an input asking LOW_LATENCY with a buffer capacity above 4096 frames
                                    gets NONE (AudioFlinger refuses FAST capture for frameCount > its 4096-frame
                                    pipe; Oboe's AudioStreamAAudio clamps input capacity for that reason) */
    int bufferCapacity;        /**< requested (0 = unspecified) */
    int inputPreset;           /**< requested (input only) */
    int usage, contentType;    /**< requested (output only) */
    int mmapPolicy;            /**< AAudio_getMMapPolicy() at open time (0 = unspecified, 1 = NEVER) */
} PaNullOpenInfo;

typedef struct PaNullOutputStats {
    int64_t frames;            /**< output frames consumed since the last reset */
    int64_t callbacks;         /**< output data callbacks since the last reset */
    int channels;              /**< channel count of the last output stream (<= 8 measured) */
    double rms[8];             /**< RMS per channel since the last reset */
    double peak[8];            /**< absolute peak per channel since the last reset */
} PaNullOutputStats;

PA_AAUDIO_EXPORT void PaNull_GetConfig(PaNullConfig *config);
/** NULL restores the defaults (and clears the simulated system properties and MMAP policy). */
PA_AAUDIO_EXPORT void PaNull_SetConfig(const PaNullConfig *config);
/** Changes only the input sine (applies immediately). */
PA_AAUDIO_EXPORT void PaNull_SetInputSignal(double frequencyHz, double amplitude);

/** Copies the output measurements; reset != 0 clears them afterwards. */
PA_AAUDIO_EXPORT void PaNull_GetOutputStats(PaNullOutputStats *stats, int reset);

/** Every started simulated stream becomes DISCONNECTED and its error callback runs. */
PA_AAUDIO_EXPORT void PaNull_SimulateDisconnect(void);
/** Increments the xrun counter of started streams: direction 0 output, 1 input, -1 both.
    Input xruns also lose 'lostFrames' frames of a read-mode input stream. */
PA_AAUDIO_EXPORT void PaNull_SimulateXRun(int direction, int lostFrames);
/** 1: the simulated microphone stops delivering frames (read-mode and callback input). */
PA_AAUDIO_EXPORT void PaNull_SetInputStalled(int stalled);
/** The next AAudioStreamBuilder_openStream for 'direction' (0 output, 1 input, -1 any)
    fails with aaudioError (an AAUDIO_ERROR_* value; 0 cancels). */
PA_AAUDIO_EXPORT void PaNull_FailNextOpen(int aaudioError, int direction);

/** The last successful open of 'direction' (0 output, 1 input); returns info->valid. */
PA_AAUDIO_EXPORT int PaNull_GetLastOpen(int direction, PaNullOpenInfo *info);

/** Simulated Android system properties (__system_property_get) read by pa_aaudio.c at
    Pa_Initialize, e.g. "ro.product.manufacturer".  value NULL removes the property;
    name NULL removes all of them. */
PA_AAUDIO_EXPORT void PaNull_SetSystemProperty(const char *name, const char *value);
/** Copies the property into value (at least PA_NULL_PROP_VALUE_MAX bytes; "" if unset) and
    returns its length, like __system_property_get. */
#define PA_NULL_PROP_VALUE_MAX 92
PA_AAUDIO_EXPORT int PaNull_GetSystemProperty(const char *name, char *value);

/** Number of AAudio API misuses detected so far, and a description of the last one. */
PA_AAUDIO_EXPORT int PaNull_GetMisuseCount(void);
PA_AAUDIO_EXPORT const char *PaNull_GetLastMisuse(void);
/** Simulated AAudio streams currently open (opened and not yet closed). */
PA_AAUDIO_EXPORT int PaNull_GetOpenStreamCount(void);
/** Total simulated AAudio streams opened since process start. */
PA_AAUDIO_EXPORT int PaNull_GetTotalOpenCount(void);

#ifdef __cplusplus
}
#endif

#endif /* PA_NULL_H */
