/*
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Host-only stand-in for the NDK's <aaudio/AAudio.h> (Audacity Android port).
 *
 * Declares the subset of the AAudio C API that src/pa_aaudio.c uses, with the
 * same names, types and constant values as the NDK header (NDK r28,
 * API level 26-28 functions only).  It is on the include path only when the
 * host build compiles pa_aaudio.c against the simulated device in
 * src/pa_aaudio_null.c; Android builds use the real NDK header.
 */
#ifndef PA_NULL_AAUDIO_AAUDIO_H
#define PA_NULL_AAUDIO_AAUDIO_H

#include <stdbool.h>
#include <stdint.h>
#include <time.h>

#ifdef __cplusplus
extern "C" {
#endif

#define AAUDIO_UNSPECIFIED 0

enum { AAUDIO_DIRECTION_OUTPUT, AAUDIO_DIRECTION_INPUT };
typedef int32_t aaudio_direction_t;

enum {
    AAUDIO_FORMAT_INVALID = -1,
    AAUDIO_FORMAT_UNSPECIFIED = 0,
    AAUDIO_FORMAT_PCM_I16,
    AAUDIO_FORMAT_PCM_FLOAT,
    AAUDIO_FORMAT_PCM_I24_PACKED,
    AAUDIO_FORMAT_PCM_I32
};
typedef int32_t aaudio_format_t;

enum {
    AAUDIO_OK,
    AAUDIO_ERROR_BASE = -900,
    AAUDIO_ERROR_DISCONNECTED,
    AAUDIO_ERROR_ILLEGAL_ARGUMENT,
    AAUDIO_ERROR_INTERNAL = AAUDIO_ERROR_ILLEGAL_ARGUMENT + 2,
    AAUDIO_ERROR_INVALID_STATE,
    AAUDIO_ERROR_INVALID_HANDLE = AAUDIO_ERROR_INVALID_STATE + 3,
    AAUDIO_ERROR_UNIMPLEMENTED = AAUDIO_ERROR_INVALID_HANDLE + 2,
    AAUDIO_ERROR_UNAVAILABLE,
    AAUDIO_ERROR_NO_FREE_HANDLES,
    AAUDIO_ERROR_NO_MEMORY,
    AAUDIO_ERROR_NULL,
    AAUDIO_ERROR_TIMEOUT,
    AAUDIO_ERROR_WOULD_BLOCK,
    AAUDIO_ERROR_INVALID_FORMAT,
    AAUDIO_ERROR_OUT_OF_RANGE,
    AAUDIO_ERROR_NO_SERVICE,
    AAUDIO_ERROR_INVALID_RATE
};
typedef int32_t aaudio_result_t;

enum {
    AAUDIO_STREAM_STATE_UNINITIALIZED = 0,
    AAUDIO_STREAM_STATE_UNKNOWN,
    AAUDIO_STREAM_STATE_OPEN,
    AAUDIO_STREAM_STATE_STARTING,
    AAUDIO_STREAM_STATE_STARTED,
    AAUDIO_STREAM_STATE_PAUSING,
    AAUDIO_STREAM_STATE_PAUSED,
    AAUDIO_STREAM_STATE_FLUSHING,
    AAUDIO_STREAM_STATE_FLUSHED,
    AAUDIO_STREAM_STATE_STOPPING,
    AAUDIO_STREAM_STATE_STOPPED,
    AAUDIO_STREAM_STATE_CLOSING,
    AAUDIO_STREAM_STATE_CLOSED,
    AAUDIO_STREAM_STATE_DISCONNECTED
};
typedef int32_t aaudio_stream_state_t;

enum { AAUDIO_SHARING_MODE_EXCLUSIVE, AAUDIO_SHARING_MODE_SHARED };
typedef int32_t aaudio_sharing_mode_t;

enum {
    AAUDIO_PERFORMANCE_MODE_NONE = 10,
    AAUDIO_PERFORMANCE_MODE_POWER_SAVING,
    AAUDIO_PERFORMANCE_MODE_LOW_LATENCY
};
typedef int32_t aaudio_performance_mode_t;

enum { AAUDIO_USAGE_MEDIA = 1 };
typedef int32_t aaudio_usage_t;

enum { AAUDIO_CONTENT_TYPE_SPEECH = 1, AAUDIO_CONTENT_TYPE_MUSIC = 2 };
typedef int32_t aaudio_content_type_t;

enum {
    AAUDIO_INPUT_PRESET_GENERIC = 1,
    AAUDIO_INPUT_PRESET_CAMCORDER = 5,
    AAUDIO_INPUT_PRESET_VOICE_RECOGNITION = 6,
    AAUDIO_INPUT_PRESET_VOICE_COMMUNICATION = 7,
    AAUDIO_INPUT_PRESET_UNPROCESSED = 9,
    AAUDIO_INPUT_PRESET_VOICE_PERFORMANCE = 10
};
typedef int32_t aaudio_input_preset_t;

typedef struct AAudioStreamStruct AAudioStream;
typedef struct AAudioStreamBuilderStruct AAudioStreamBuilder;

enum { AAUDIO_CALLBACK_RESULT_CONTINUE = 0, AAUDIO_CALLBACK_RESULT_STOP };
typedef int32_t aaudio_data_callback_result_t;

typedef aaudio_data_callback_result_t (*AAudioStream_dataCallback)(
    AAudioStream *stream, void *userData, void *audioData, int32_t numFrames);
typedef void (*AAudioStream_errorCallback)(
    AAudioStream *stream, void *userData, aaudio_result_t error);

const char *AAudio_convertResultToText(aaudio_result_t returnCode);
const char *AAudio_convertStreamStateToText(aaudio_stream_state_t state);

aaudio_result_t AAudio_createStreamBuilder(AAudioStreamBuilder **builder);
void AAudioStreamBuilder_setDeviceId(AAudioStreamBuilder *builder, int32_t deviceId);
void AAudioStreamBuilder_setSampleRate(AAudioStreamBuilder *builder, int32_t sampleRate);
void AAudioStreamBuilder_setChannelCount(AAudioStreamBuilder *builder, int32_t channelCount);
void AAudioStreamBuilder_setFormat(AAudioStreamBuilder *builder, aaudio_format_t format);
void AAudioStreamBuilder_setSharingMode(AAudioStreamBuilder *builder, aaudio_sharing_mode_t sharingMode);
void AAudioStreamBuilder_setDirection(AAudioStreamBuilder *builder, aaudio_direction_t direction);
void AAudioStreamBuilder_setBufferCapacityInFrames(AAudioStreamBuilder *builder, int32_t numFrames);
void AAudioStreamBuilder_setPerformanceMode(AAudioStreamBuilder *builder, aaudio_performance_mode_t mode);
void AAudioStreamBuilder_setUsage(AAudioStreamBuilder *builder, aaudio_usage_t usage);
void AAudioStreamBuilder_setContentType(AAudioStreamBuilder *builder, aaudio_content_type_t contentType);
void AAudioStreamBuilder_setInputPreset(AAudioStreamBuilder *builder, aaudio_input_preset_t inputPreset);
void AAudioStreamBuilder_setDataCallback(AAudioStreamBuilder *builder,
    AAudioStream_dataCallback callback, void *userData);
void AAudioStreamBuilder_setErrorCallback(AAudioStreamBuilder *builder,
    AAudioStream_errorCallback callback, void *userData);
aaudio_result_t AAudioStreamBuilder_openStream(AAudioStreamBuilder *builder, AAudioStream **stream);
aaudio_result_t AAudioStreamBuilder_delete(AAudioStreamBuilder *builder);

aaudio_result_t AAudioStream_close(AAudioStream *stream);
aaudio_result_t AAudioStream_requestStart(AAudioStream *stream);
aaudio_result_t AAudioStream_requestStop(AAudioStream *stream);
aaudio_stream_state_t AAudioStream_getState(AAudioStream *stream);
aaudio_result_t AAudioStream_waitForStateChange(AAudioStream *stream,
    aaudio_stream_state_t inputState, aaudio_stream_state_t *nextState, int64_t timeoutNanoseconds);
aaudio_result_t AAudioStream_read(AAudioStream *stream, void *buffer, int32_t numFrames,
    int64_t timeoutNanoseconds);
aaudio_result_t AAudioStream_setBufferSizeInFrames(AAudioStream *stream, int32_t numFrames);
int32_t AAudioStream_getBufferSizeInFrames(AAudioStream *stream);
int32_t AAudioStream_getFramesPerBurst(AAudioStream *stream);
int32_t AAudioStream_getBufferCapacityInFrames(AAudioStream *stream);
int32_t AAudioStream_getXRunCount(AAudioStream *stream);
int32_t AAudioStream_getSampleRate(AAudioStream *stream);
int32_t AAudioStream_getChannelCount(AAudioStream *stream);
int32_t AAudioStream_getDeviceId(AAudioStream *stream);
aaudio_format_t AAudioStream_getFormat(AAudioStream *stream);
aaudio_sharing_mode_t AAudioStream_getSharingMode(AAudioStream *stream);
aaudio_performance_mode_t AAudioStream_getPerformanceMode(AAudioStream *stream);
aaudio_direction_t AAudioStream_getDirection(AAudioStream *stream);
int64_t AAudioStream_getFramesWritten(AAudioStream *stream);
int64_t AAudioStream_getFramesRead(AAudioStream *stream);
aaudio_result_t AAudioStream_getTimestamp(AAudioStream *stream, clockid_t clockid,
    int64_t *framePosition, int64_t *timeNanoseconds);
aaudio_input_preset_t AAudioStream_getInputPreset(AAudioStream *stream);

/* Not in the NDK header: libaaudio exports these (API 28+) and the Android build of
   pa_aaudio.c reaches them with dlsym(), like Oboe's AAudioExtensions.  The policy is
   process-wide and applies to streams opened afterwards. */
enum {
    AAUDIO_POLICY_NEVER = 1,
    AAUDIO_POLICY_AUTO,
    AAUDIO_POLICY_ALWAYS
};
typedef int32_t aaudio_policy_t;
aaudio_result_t AAudio_setMMapPolicy(aaudio_policy_t policy);
aaudio_policy_t AAudio_getMMapPolicy(void);

#ifdef __cplusplus
}
#endif

#endif /* PA_NULL_AAUDIO_AAUDIO_H */
