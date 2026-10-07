/*
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * PortAudio host API for Android AAudio (Audacity Android port).
 *
 * The structure (host-API representation, stream interface, buffer
 * processor use) follows PortAudio's skeleton host API,
 * src/hostapi/skeleton/pa_hostapi_skeleton.c,
 * Copyright (c) 1999-2002 Ross Bencina, Phil Burk (PortAudio licence).
 * Full duplex follows the pattern of Oboe's FullDuplexStream (output-driven
 * callback, non-blocking input read, drain/cushion/discard warm-up).
 *
 * Design: notes/audio-io.md section 4; self-review, state machine and
 * threading rules: native/portaudio-android/README.md.
 *
 * On Android this file is compiled against the NDK's <aaudio/AAudio.h> and
 * linked with libaaudio.  On the Linux host it is compiled against the
 * AAudio subset in src/null/aaudio/AAudio.h, implemented by the simulated
 * device in src/pa_aaudio_null.c (PA_AAUDIO_NULL_BACKEND=1), and registered
 * as host API "Null".
 */

#ifndef _GNU_SOURCE
#define _GNU_SOURCE 1 /* pthread_setname_np on glibc */
#endif

#include <errno.h>
#include <math.h>
#include <pthread.h>
#include <stdarg.h>
#include <stdatomic.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#include <aaudio/AAudio.h>

#ifdef __ANDROID__
#include <android/log.h>
#endif

#include "pa_allocation.h"
#include "pa_cpuload.h"
#include "pa_hostapi.h"
#include "pa_process.h"
#include "pa_stream.h"
#include "pa_util.h"

#include "pa_android_aaudio.h"

#ifndef PA_AAUDIO_NULL_BACKEND
#define PA_AAUDIO_NULL_BACKEND 0
#endif

#if PA_AAUDIO_NULL_BACKEND
#define PA_AAUDIO_HOST_NAME PA_NULL_HOST_API_NAME
#else
#define PA_AAUDIO_HOST_NAME PA_AAUDIO_HOST_API_NAME
#endif

PaError PaAAudio_Initialize(PaUtilHostApiRepresentation **hostApi, PaHostApiIndex index);

/* ------------------------------------------------------------------------ */
/* Constants                                                                 */
/* ------------------------------------------------------------------------ */

#define NS_PER_SEC 1000000000LL
#define NS_PER_MS 1000000LL

#define MIN_RATE 8000.0
#define MAX_RATE 192000.0
#define FALLBACK_RATE 48000.0
#define FALLBACK_BURST 192

#define ABORT_WAIT_NS (300 * NS_PER_MS)          /* Abort waits this long for a busy callback */
#define STATE_WAIT_NS (500 * NS_PER_MS)          /* wait for AAUDIO_STREAM_STATE_STOPPED */
#define CLOSE_DELAY_NS (10 * NS_PER_MS)          /* Oboe: sleep between stop and close */
#define FREE_DELAY_NS (1000 * NS_PER_MS)         /* stream memory lives >= 1 s after close */
#define TERMINATE_WAIT_NS (2000 * NS_PER_MS)     /* Terminate waits for pending teardowns */
#define POLL_PERIOD_NS (20 * NS_PER_MS)          /* timestamp poller */
#define DUPLEX_OFFSET_HISTORY 32

enum {
    PH_WARMUP_DRAIN,   /* full duplex: discard stale input */
    PH_WARMUP_CUSHION, /* full duplex: let input accumulate */
    PH_WARMUP_DISCARD, /* full duplex: reach equilibrium */
    PH_RUNNING,        /* user callback is called */
    PH_DRAINING        /* user callback returned paComplete: play out queued output */
};

/* ------------------------------------------------------------------------ */
/* Logging (never from the data callback)                                    */
/* ------------------------------------------------------------------------ */

#ifdef __ANDROID__
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "PaAAudio", __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, "PaAAudio", __VA_ARGS__)
#else
static void HostLog(const char *level, const char *fmt, ...) __attribute__((format(printf, 2, 3)));
static void HostLog(const char *level, const char *fmt, ...)
{
    static int enabled = -1;
    va_list ap;
    if (enabled < 0)
        enabled = getenv("PA_AAUDIO_DEBUG") != NULL;
    if (!enabled)
        return;
    fprintf(stderr, "PaAAudio %s: ", level);
    va_start(ap, fmt);
    vfprintf(stderr, fmt, ap);
    va_end(ap);
    fputc('\n', stderr);
}
#define LOGI(...) HostLog("I", __VA_ARGS__)
#define LOGW(...) HostLog("W", __VA_ARGS__)
#endif

/* ------------------------------------------------------------------------ */
/* Time helpers                                                              */
/* ------------------------------------------------------------------------ */

static int64_t MonoNs(void)
{
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (int64_t)ts.tv_sec * NS_PER_SEC + ts.tv_nsec;
}

static void SleepNs(int64_t ns)
{
    struct timespec ts;
    if (ns <= 0)
        return;
    ts.tv_sec = (time_t)(ns / NS_PER_SEC);
    ts.tv_nsec = (long)(ns % NS_PER_SEC);
    while (nanosleep(&ts, &ts) == -1 && errno == EINTR) {
    }
}

static void SetThreadName(const char *name)
{
#if defined(__ANDROID__) || defined(__linux__)
    pthread_setname_np(pthread_self(), name);
#else
    (void)name;
#endif
}

/* ------------------------------------------------------------------------ */
/* Global configuration and statistics (protected by g_lock)                 */
/* ------------------------------------------------------------------------ */

typedef struct PaAAudioStream PaAAudioStream;

static pthread_mutex_t g_lock = PTHREAD_MUTEX_INITIALIZER;

static PaAAudioDeviceDesc *g_userDevs;  /* owned copies, names strdup'ed */
static int g_userDevCount;
static double g_defaultRate;            /* 0 = probe at Pa_Initialize */
static int g_defaultBurst;
static PaAAudioOptions g_opts;
static int g_optsInitialized;
static char g_lastError[256];
static PaAAudioStreamStats g_stats;
static PaAAudioStream *g_statsStream;   /* stream whose live counters feed g_stats */
static int g_pendingTeardowns;          /* streams whose AAudio objects the reaper still has to close */

static _Atomic float g_inputGain = 1.0f;

static void DefaultOptions(PaAAudioOptions *o)
{
    memset(o, 0, sizeof *o);
    o->sharingMode = AAUDIO_SHARING_MODE_SHARED;
    o->outputPerformanceMode = AAUDIO_PERFORMANCE_MODE_LOW_LATENCY;
    o->inputPerformanceMode = AAUDIO_PERFORMANCE_MODE_LOW_LATENCY;
    o->inputPreset = AAUDIO_INPUT_PRESET_VOICE_RECOGNITION;
    o->usage = AAUDIO_USAGE_MEDIA;
    o->contentType = AAUDIO_CONTENT_TYPE_MUSIC;
    o->acceptAnyRate = 0;
    o->duplexDrainCallbacks = 0;
    o->duplexCushionBursts = 0;
    o->duplexDiscardCallbacks = 0;
    o->warmupTimeoutMs = 1500;
    o->maxFramesPerUserCallback = 2048;
    o->autoGrowOutputBuffer = 1;
}

/* g_lock held */
static void EnsureOptionsLocked(void)
{
    if (!g_optsInitialized) {
        DefaultOptions(&g_opts);
        g_optsInitialized = 1;
    }
}

static int ClampInt(int v, int lo, int hi)
{
    return v < lo ? lo : (v > hi ? hi : v);
}

void PaAAudio_GetDefaultOptions(PaAAudioOptions *opts)
{
    if (opts)
        DefaultOptions(opts);
}

void PaAAudio_SetOptions(const PaAAudioOptions *opts)
{
    PaAAudioOptions o;
    if (opts)
        o = *opts;
    else
        DefaultOptions(&o);
    if (o.sharingMode != AAUDIO_SHARING_MODE_EXCLUSIVE)
        o.sharingMode = AAUDIO_SHARING_MODE_SHARED;
    if (o.outputPerformanceMode < AAUDIO_PERFORMANCE_MODE_NONE
        || o.outputPerformanceMode > AAUDIO_PERFORMANCE_MODE_LOW_LATENCY)
        o.outputPerformanceMode = AAUDIO_PERFORMANCE_MODE_LOW_LATENCY;
    if (o.inputPerformanceMode < AAUDIO_PERFORMANCE_MODE_NONE
        || o.inputPerformanceMode > AAUDIO_PERFORMANCE_MODE_LOW_LATENCY)
        o.inputPerformanceMode = AAUDIO_PERFORMANCE_MODE_LOW_LATENCY;
    if (o.inputPreset <= 0)
        o.inputPreset = AAUDIO_INPUT_PRESET_VOICE_RECOGNITION;
    if (o.usage <= 0)
        o.usage = AAUDIO_USAGE_MEDIA;
    if (o.contentType <= 0)
        o.contentType = AAUDIO_CONTENT_TYPE_MUSIC;
    o.acceptAnyRate = o.acceptAnyRate ? 1 : 0;
    o.duplexDrainCallbacks = ClampInt(o.duplexDrainCallbacks, 0, 1000);
    o.duplexCushionBursts = ClampInt(o.duplexCushionBursts, 0, 100);
    o.duplexDiscardCallbacks = ClampInt(o.duplexDiscardCallbacks, 0, 1000);
    if (o.warmupTimeoutMs <= 0)
        o.warmupTimeoutMs = 1500;
    o.warmupTimeoutMs = ClampInt(o.warmupTimeoutMs, 50, 60000);
    if (o.maxFramesPerUserCallback <= 0)
        o.maxFramesPerUserCallback = 2048;
    o.maxFramesPerUserCallback = ClampInt(o.maxFramesPerUserCallback, 64, 16384);
    o.autoGrowOutputBuffer = o.autoGrowOutputBuffer ? 1 : 0;

    pthread_mutex_lock(&g_lock);
    g_opts = o;
    g_optsInitialized = 1;
    pthread_mutex_unlock(&g_lock);
}

static void FreeUserDevicesLocked(void)
{
    int i;
    for (i = 0; i < g_userDevCount; ++i)
        free((void *)g_userDevs[i].name);
    free(g_userDevs);
    g_userDevs = NULL;
    g_userDevCount = 0;
}

PaError PaAAudio_SetDeviceList(const PaAAudioDeviceDesc *devs, int count)
{
    PaAAudioDeviceDesc *copy = NULL;
    int i, n = 0;
    if (count < 0 || (count > 0 && !devs))
        return paInvalidDevice;
    if (count > 0) {
        copy = (PaAAudioDeviceDesc *)calloc((size_t)count, sizeof *copy);
        if (!copy)
            return paInsufficientMemory;
        for (i = 0; i < count; ++i) {
            const PaAAudioDeviceDesc *d = &devs[i];
            int j, dup = 0;
            if (!d->name || !d->name[0])
                continue;
            if (d->maxInputChannels <= 0 && d->maxOutputChannels <= 0)
                continue;
            if (!strcmp(d->name, PA_AAUDIO_DEFAULT_OUTPUT_NAME) || !strcmp(d->name, PA_AAUDIO_DEFAULT_INPUT_NAME))
                continue;
            for (j = 0; j < n; ++j)
                if (!strcmp(copy[j].name, d->name))
                    dup = 1;
            if (dup)
                continue;
            copy[n] = *d;
            copy[n].name = strdup(d->name);
            if (!copy[n].name) {
                for (j = 0; j < n; ++j)
                    free((void *)copy[j].name);
                free(copy);
                return paInsufficientMemory;
            }
            copy[n].maxInputChannels = d->maxInputChannels > 0 ? d->maxInputChannels : 0;
            copy[n].maxOutputChannels = d->maxOutputChannels > 0 ? d->maxOutputChannels : 0;
            if (!(copy[n].nativeSampleRate >= MIN_RATE && copy[n].nativeSampleRate <= MAX_RATE))
                copy[n].nativeSampleRate = 0;
            if (copy[n].framesPerBurst < 0)
                copy[n].framesPerBurst = 0;
            ++n;
        }
    }
    pthread_mutex_lock(&g_lock);
    FreeUserDevicesLocked();
    g_userDevs = copy;
    g_userDevCount = n;
    pthread_mutex_unlock(&g_lock);
    return paNoError;
}

void PaAAudio_SetDefaults(double nativeRate, int framesPerBurst)
{
    pthread_mutex_lock(&g_lock);
    if (nativeRate >= MIN_RATE && nativeRate <= MAX_RATE) {
        g_defaultRate = nativeRate;
        g_defaultBurst = framesPerBurst > 0 ? framesPerBurst : 0;
    } else {
        g_defaultRate = 0;
        g_defaultBurst = 0;
    }
    pthread_mutex_unlock(&g_lock);
}

void PaAAudio_SetInputGain(float linear)
{
    if (!(linear >= 0.0f))
        linear = 0.0f;
    if (linear > 16.0f)
        linear = 16.0f;
    atomic_store(&g_inputGain, linear);
}

static void SetLastErrorText(aaudio_result_t r, const char *what)
{
    pthread_mutex_lock(&g_lock);
    if (r != AAUDIO_OK)
        snprintf(g_lastError, sizeof g_lastError, "%s: %s", AAudio_convertResultToText(r), what);
    else
        snprintf(g_lastError, sizeof g_lastError, "%s", what);
    pthread_mutex_unlock(&g_lock);
}

const char *PaAAudio_GetLastErrorText(void)
{
    static _Thread_local char copy[sizeof g_lastError];
    pthread_mutex_lock(&g_lock);
    memcpy(copy, g_lastError, sizeof copy);
    pthread_mutex_unlock(&g_lock);
    return copy;
}

void PaAAudio_ClearLastError(void)
{
    pthread_mutex_lock(&g_lock);
    g_lastError[0] = '\0';
    pthread_mutex_unlock(&g_lock);
}

static PaError MapAAudioError(aaudio_result_t r, const char *what)
{
    if (r == AAUDIO_OK)
        return paNoError;
    SetLastErrorText(r, what);
    LOGW("%s failed: %s (%d)", what, AAudio_convertResultToText(r), (int)r);
    switch (r) {
    case AAUDIO_ERROR_DISCONNECTED:
        return paDeviceUnavailable;
    case AAUDIO_ERROR_INVALID_RATE:
        return paInvalidSampleRate;
    case AAUDIO_ERROR_OUT_OF_RANGE:
        return paInvalidChannelCount;
    case AAUDIO_ERROR_INVALID_FORMAT:
        return paSampleFormatNotSupported;
    case AAUDIO_ERROR_NO_MEMORY:
        return paInsufficientMemory;
    case AAUDIO_ERROR_TIMEOUT:
        return paTimedOut;
    default:
        PaUtil_SetLastHostErrorInfo(paInDevelopment, (long)r, AAudio_convertResultToText(r));
        return paUnanticipatedHostError;
    }
}

/* ------------------------------------------------------------------------ */
/* Host API and stream representations                                       */
/* ------------------------------------------------------------------------ */

typedef struct {
    PaUtilHostApiRepresentation inheritedHostApiRep; /* must be first */
    PaUtilStreamInterface callbackStreamInterface;
    PaUtilAllocationGroup *allocations;
    PaAAudioDeviceDesc *devs; /* parallel to deviceInfos; names live in the allocation group */
    int devCount;
} PaAAudioHostApi;

/* {framePosition, timeNanoseconds} published by the poller (seqlock) */
typedef struct {
    _Atomic uint32_t seq;
    _Atomic int64_t framePos;
    _Atomic int64_t timeNs;
} ClockSample;

struct PaAAudioStream {
    PaUtilStreamRepresentation streamRepresentation; /* must be first */
    PaUtilCpuLoadMeasurer cpuLoadMeasurer;
    PaUtilBufferProcessor bufferProcessor;
    int bufferProcessorInitialized;

    PaAAudioOptions opts;
    AAudioStream *out, *in; /* either may be NULL */
    double rate;
    int outCh;              /* output channels (== user output channels) */
    int inCh;               /* channels the AAudio input stream delivers */
    int inUserCh;           /* channels the user asked for */
    aaudio_format_t outFormat, inFormat;
    int outFrameBytes, inFrameBytes, inSampleBytes;
    int outBurst, inBurst, outCapacity, inCapacity, outBufferSize;
    int segFrames;          /* max frames per buffer-processor pass */
    void *inBuf;            /* input scratch: segFrames * inFrameBytes */
    int drainCallbacks, cushionCallbacks, discardCallbacks;
    int64_t excessThreshold;

    /* control: engine thread <-> AAudio threads */
    _Atomic int isActive;
    _Atomic int isStopped;
    _Atomic int abortRequested;
    _Atomic int inCallback;
    _Atomic int finished;
    _Atomic int finishedNotified;
    _Atomic int closed;

    /* engine thread only */
    int started;
    int deferredStop;       /* Abort could not stop AAudio: a callback was blocked */
    int aaudioStopped;
    int needsTeardown;
    int64_t closeNs;

    /* data-callback thread only (initialized by StartStream before requestStart) */
    int phase;
    int warmupCount;
    int gotInputData;
    int64_t warmupStartNs;
    int64_t drainFramesLeft;
    int32_t lastXrunOut, lastXrunIn;
    int64_t inputDebt;
    double lastDacTime, lastAdcTime;

    /* written by AAudio threads, read by stats */
    _Atomic int64_t callbackCount;
    _Atomic int64_t framesProcessed;
    _Atomic int64_t paddedInputFrames;
    _Atomic int64_t droppedInputFrames;
    _Atomic int outXRuns, inXRuns;
    _Atomic int lastAAudioError;
    _Atomic int disconnected;
    _Atomic int warmupTimedOut;
    _Atomic double duplexOffsets[DUPLEX_OFFSET_HISTORY];
    _Atomic uint32_t duplexOffsetCount;
    _Atomic double cpuLoad;  /* published copy of the callback-owned cpuLoadMeasurer */

    ClockSample outClock, inClock;

    /* timestamp poller */
    pthread_t poller;
    int pollerRunning;
    int pollStop;
    pthread_mutex_t pollLock;
    pthread_cond_t pollCond;
    int pollSyncInitialized;
    double pollOutLatency, pollInLatency; /* written by the poller under g_lock */
};

/* ------------------------------------------------------------------------ */
/* Clock samples (seqlock: one writer = poller, readers = callbacks)          */
/* ------------------------------------------------------------------------ */

static void ResetClock(ClockSample *c)
{
    atomic_store(&c->seq, 0u);
    atomic_store(&c->framePos, 0);
    atomic_store(&c->timeNs, 0);
}

static void WriteClock(ClockSample *c, int64_t pos, int64_t ns)
{
    uint32_t s = atomic_load_explicit(&c->seq, memory_order_relaxed);
    atomic_store_explicit(&c->seq, s + 1, memory_order_relaxed);
    atomic_thread_fence(memory_order_release);
    atomic_store_explicit(&c->framePos, pos, memory_order_relaxed);
    atomic_store_explicit(&c->timeNs, ns, memory_order_relaxed);
    atomic_store_explicit(&c->seq, s + 2, memory_order_release);
}

static int ReadClock(ClockSample *c, int64_t *pos, int64_t *ns)
{
    int tries;
    for (tries = 0; tries < 8; ++tries) {
        uint32_t s1 = atomic_load_explicit(&c->seq, memory_order_acquire), s2;
        int64_t p, t;
        if (s1 == 0)
            return 0; /* never written since start */
        if (s1 & 1u)
            continue;
        p = atomic_load_explicit(&c->framePos, memory_order_relaxed);
        t = atomic_load_explicit(&c->timeNs, memory_order_relaxed);
        atomic_thread_fence(memory_order_acquire);
        s2 = atomic_load_explicit(&c->seq, memory_order_relaxed);
        if (s1 == s2) {
            *pos = p;
            *ns = t;
            return 1;
        }
    }
    return 0;
}

/* ------------------------------------------------------------------------ */
/* Small AAudio helpers                                                      */
/* ------------------------------------------------------------------------ */

static int BytesPerSample(aaudio_format_t f)
{
    return f == AAUDIO_FORMAT_PCM_I16 ? 2 : 4;
}

static PaSampleFormat HostSampleFormat(aaudio_format_t f)
{
    return f == AAUDIO_FORMAT_PCM_I16 ? paInt16 : paFloat32;
}

/* Waits until 'as' is STOPPED (or DISCONNECTED/CLOSED, or timeout). Not from callbacks. */
static void WaitForStopped(AAudioStream *as, int64_t timeoutNs)
{
    const int64_t deadline = MonoNs() + timeoutNs;
    aaudio_stream_state_t state;
    if (!as)
        return;
    state = AAudioStream_getState(as);
    while (state != AAUDIO_STREAM_STATE_STOPPED && state != AAUDIO_STREAM_STATE_DISCONNECTED
           && state != AAUDIO_STREAM_STATE_CLOSED && state != AAUDIO_STREAM_STATE_OPEN
           && state != AAUDIO_STREAM_STATE_UNINITIALIZED) {
        aaudio_stream_state_t next = state;
        const int64_t left = deadline - MonoNs();
        if (left <= 0)
            break;
        if (AAudioStream_waitForStateChange(as, state, &next, left) != AAUDIO_OK)
            break;
        state = next;
    }
}

/* Stops both AAudio streams (output first: its callback reads the input). Engine/reaper thread. */
static void StopAAudio(PaAAudioStream *st)
{
    if (st->out)
        AAudioStream_requestStop(st->out);
    if (st->in)
        AAudioStream_requestStop(st->in);
    WaitForStopped(st->out, STATE_WAIT_NS);
    WaitForStopped(st->in, STATE_WAIT_NS);
    st->aaudioStopped = 1;
}

static void CloseAAudio(PaAAudioStream *st)
{
    if (st->started)
        SleepNs(CLOSE_DELAY_NS); /* Oboe workaround: let a late legacy callback finish */
    if (st->out) {
        AAudioStream_close(st->out);
        st->out = NULL;
    }
    if (st->in) {
        AAudioStream_close(st->in);
        st->in = NULL;
    }
}

static int WaitCallbackIdle(PaAAudioStream *st, int64_t timeoutNs)
{
    const int64_t deadline = MonoNs() + timeoutNs;
    while (atomic_load(&st->inCallback)) {
        if (timeoutNs >= 0 && MonoNs() > deadline)
            return 0;
        SleepNs(250000);
    }
    return 1;
}

/* Called when the stream stops producing callbacks for the user (any thread). */
static void NotifyFinished(PaAAudioStream *st)
{
    if (atomic_exchange(&st->finishedNotified, 1))
        return;
    if (!atomic_load(&st->closed) && st->streamRepresentation.streamFinishedCallback)
        st->streamRepresentation.streamFinishedCallback(st->streamRepresentation.userData);
}

/* "Callback finished": Pa_IsStreamActive() becomes 0, Pa_IsStreamStopped() stays 0. */
static void MarkFinished(PaAAudioStream *st)
{
    if (atomic_exchange(&st->finished, 1))
        return;
    atomic_store(&st->isActive, 0);
    if (!atomic_load(&st->isStopped))
        NotifyFinished(st);
}

static void RecordStreamError(PaAAudioStream *st, aaudio_result_t r)
{
    atomic_store(&st->lastAAudioError, (int)r);
    if (r == AAUDIO_ERROR_DISCONNECTED)
        atomic_store(&st->disconnected, 1);
}

/* ------------------------------------------------------------------------ */
/* Statistics                                                                */
/* ------------------------------------------------------------------------ */

static double MedianDuplexOffset(PaAAudioStream *st)
{
    double v[DUPLEX_OFFSET_HISTORY];
    uint32_t count = atomic_load(&st->duplexOffsetCount);
    int n = count > DUPLEX_OFFSET_HISTORY ? DUPLEX_OFFSET_HISTORY : (int)count;
    int i, j;
    if (n == 0)
        return -1.0;
    for (i = 0; i < n; ++i)
        v[i] = atomic_load_explicit(&st->duplexOffsets[i], memory_order_relaxed);
    for (i = 1; i < n; ++i) {
        double x = v[i];
        for (j = i - 1; j >= 0 && v[j] > x; --j)
            v[j + 1] = v[j];
        v[j + 1] = x;
    }
    return (n & 1) ? v[n / 2] : 0.5 * (v[n / 2 - 1] + v[n / 2]);
}

/* g_lock held; st == g_statsStream, so its AAudio streams are open */
static void RefreshStatsLocked(PaAAudioStream *st)
{
    PaAAudioStreamStats *s = &g_stats;
    s->running = !atomic_load(&st->isStopped) && !atomic_load(&st->closed);
    s->active = atomic_load(&st->isActive);
    s->disconnected = atomic_load(&st->disconnected);
    s->lastAAudioError = atomic_load(&st->lastAAudioError);
    s->warmupTimedOut = atomic_load(&st->warmupTimedOut);
    s->outputXRuns = atomic_load(&st->outXRuns);
    s->inputXRuns = atomic_load(&st->inXRuns);
    s->paddedInputFrames = atomic_load(&st->paddedInputFrames);
    s->droppedInputFrames = atomic_load(&st->droppedInputFrames);
    s->callbackCount = atomic_load(&st->callbackCount);
    s->framesProcessed = atomic_load(&st->framesProcessed);
    s->outputLatencySec = st->pollOutLatency;
    s->inputLatencySec = st->pollInLatency;
    s->duplexOffsetSec = (st->in && st->out) ? MedianDuplexOffset(st) : -1.0;
    if (st->out)
        s->outputBufferSize = AAudioStream_getBufferSizeInFrames(st->out);
    s->cpuLoad = atomic_load_explicit(&st->cpuLoad, memory_order_relaxed);
}

/* Static part, at StartStream (engine thread) */
static void PublishStreamStarted(PaAAudioStream *st)
{
    PaAAudioStreamStats s;
    memset(&s, 0, sizeof s);
    s.valid = 1;
    s.running = 1;
    s.active = 1;
    s.hasOutput = st->out != NULL;
    s.hasInput = st->in != NULL;
    s.sampleRate = st->rate;
    if (st->out) {
        s.outputDeviceId = AAudioStream_getDeviceId(st->out);
        s.outputBurst = st->outBurst;
        s.outputBufferSize = AAudioStream_getBufferSizeInFrames(st->out);
        s.outputBufferCapacity = st->outCapacity;
        s.outputPerformanceMode = AAudioStream_getPerformanceMode(st->out);
        s.outputSharingMode = AAudioStream_getSharingMode(st->out);
        s.outputFormat = st->outFormat;
    }
    if (st->in) {
        s.inputDeviceId = AAudioStream_getDeviceId(st->in);
        s.inputBurst = st->inBurst;
        s.inputBufferCapacity = st->inCapacity;
        s.inputPerformanceMode = AAudioStream_getPerformanceMode(st->in);
        s.inputSharingMode = AAudioStream_getSharingMode(st->in);
        s.inputFormat = st->inFormat;
        s.inputChannelsOpened = st->inCh;
    }
    s.outputLatencySec = -1.0;
    s.inputLatencySec = -1.0;
    s.duplexOffsetSec = -1.0;
    pthread_mutex_lock(&g_lock);
    g_stats = s;
    g_statsStream = st;
    pthread_mutex_unlock(&g_lock);
}

static void PublishStreamStopped(PaAAudioStream *st, int detach)
{
    pthread_mutex_lock(&g_lock);
    if (g_statsStream == st) {
        RefreshStatsLocked(st);
        g_stats.running = 0;
        if (detach) {
            g_stats.active = 0;
            g_statsStream = NULL;
        }
    }
    pthread_mutex_unlock(&g_lock);
}

int PaAAudio_GetActiveStreamStats(PaAAudioStreamStats *out)
{
    if (!out)
        return 0;
    pthread_mutex_lock(&g_lock);
    if (g_statsStream)
        RefreshStatsLocked(g_statsStream);
    *out = g_stats;
    pthread_mutex_unlock(&g_lock);
    return out->valid;
}

/* ------------------------------------------------------------------------ */
/* Timestamp poller (normal-priority thread, one per running stream)          */
/* ------------------------------------------------------------------------ */

static void PollOnce(PaAAudioStream *st, int32_t *lastOutXrun)
{
    int64_t pos, ns, now;
    double outLat = -1.0, inLat = -1.0;
    if (st->out && AAudioStream_getTimestamp(st->out, CLOCK_MONOTONIC, &pos, &ns) == AAUDIO_OK) {
        const int64_t written = AAudioStream_getFramesWritten(st->out);
        WriteClock(&st->outClock, pos, ns);
        now = MonoNs();
        outLat = (double)(written - pos) / st->rate - (double)(now - ns) * 1e-9;
    }
    if (st->in && AAudioStream_getTimestamp(st->in, CLOCK_MONOTONIC, &pos, &ns) == AAUDIO_OK) {
        const int64_t read = AAudioStream_getFramesRead(st->in);
        WriteClock(&st->inClock, pos, ns);
        now = MonoNs();
        inLat = (double)(now - ns) * 1e-9 - (double)(read - pos) / st->rate;
    }
    /* Oboe LatencyTuner-like growth, output-only streams (duplex alignment must not change) */
    if (st->out && !st->in && st->opts.autoGrowOutputBuffer) {
        const int32_t x = AAudioStream_getXRunCount(st->out);
        if (*lastOutXrun >= 0 && x > *lastOutXrun) {
            const int32_t size = AAudioStream_getBufferSizeInFrames(st->out);
            if (size > 0 && size + st->outBurst <= st->outCapacity) {
                const aaudio_result_t r = AAudioStream_setBufferSizeInFrames(st->out, size + st->outBurst);
                LOGI("output xrun: buffer %d -> %d frames", (int)size, (int)r);
            }
        }
        *lastOutXrun = x;
    }
    pthread_mutex_lock(&g_lock);
    st->pollOutLatency = outLat;
    st->pollInLatency = inLat;
    pthread_mutex_unlock(&g_lock);
}

static void *PollerMain(void *arg)
{
    PaAAudioStream *st = (PaAAudioStream *)arg;
    int32_t lastOutXrun = -1;
    SetThreadName("PaAAudioPoll");
    pthread_mutex_lock(&st->pollLock);
    while (!st->pollStop) {
        struct timespec until;
        int64_t deadline;
        pthread_mutex_unlock(&st->pollLock);
        PollOnce(st, &lastOutXrun);
        deadline = MonoNs() + POLL_PERIOD_NS;
        until.tv_sec = (time_t)(deadline / NS_PER_SEC);
        until.tv_nsec = (long)(deadline % NS_PER_SEC);
        pthread_mutex_lock(&st->pollLock);
        while (!st->pollStop) {
            if (pthread_cond_timedwait(&st->pollCond, &st->pollLock, &until) == ETIMEDOUT)
                break;
        }
    }
    pthread_mutex_unlock(&st->pollLock);
    return NULL;
}

static void StartPoller(PaAAudioStream *st)
{
    if (st->pollerRunning || !st->pollSyncInitialized)
        return;
    st->pollStop = 0;
    if (pthread_create(&st->poller, NULL, PollerMain, st) == 0)
        st->pollerRunning = 1;
    else
        LOGW("cannot start the timestamp poller; timeInfo uses estimates");
}

static void StopPoller(PaAAudioStream *st)
{
    if (!st->pollerRunning)
        return;
    pthread_mutex_lock(&st->pollLock);
    st->pollStop = 1;
    pthread_cond_signal(&st->pollCond);
    pthread_mutex_unlock(&st->pollLock);
    pthread_join(st->poller, NULL);
    st->pollerRunning = 0;
}

/* ------------------------------------------------------------------------ */
/* Callback helpers (real-time: no locks, no allocation, no logging)          */
/* ------------------------------------------------------------------------ */

static void FillTimeInfo(PaAAudioStream *st, PaStreamCallbackTimeInfo *ti, int64_t outIndex,
                         int64_t inIndex, int32_t frames, int *bothFromTimestamps)
{
    const double now = (double)MonoNs() * 1e-9;
    int64_t pos, ns;
    int outValid = 0, inValid = 0;
    ti->currentTime = now;
    ti->outputBufferDacTime = 0.0;
    ti->inputBufferAdcTime = 0.0;
    if (st->out) {
        double dac;
        if (ReadClock(&st->outClock, &pos, &ns)) {
            dac = (double)ns * 1e-9 + (double)(outIndex - pos) / st->rate;
            outValid = 1;
        } else {
            dac = now + (double)st->outBufferSize / st->rate;
        }
        if (dac < st->lastDacTime)
            dac = st->lastDacTime; /* never go backwards (estimate -> timestamp switch) */
        st->lastDacTime = dac;
        ti->outputBufferDacTime = dac;
    }
    if (st->in) {
        double adc;
        if (ReadClock(&st->inClock, &pos, &ns)) {
            adc = (double)ns * 1e-9 + (double)(inIndex - pos) / st->rate;
            inValid = 1;
        } else {
            adc = now - (double)(frames + st->inBurst) / st->rate;
        }
        if (adc < st->lastAdcTime)
            adc = st->lastAdcTime;
        st->lastAdcTime = adc;
        ti->inputBufferAdcTime = adc;
    }
    if (bothFromTimestamps)
        *bothFromTimestamps = outValid && inValid;
}

static void ApplyGain(PaAAudioStream *st, void *buf, int32_t frames, float gain)
{
    const size_t n = (size_t)frames * (size_t)st->inCh;
    size_t i;
    if (st->inFormat == AAUDIO_FORMAT_PCM_I16) {
        int16_t *p = (int16_t *)buf;
        for (i = 0; i < n; ++i) {
            const float v = (float)p[i] * gain;
            p[i] = (int16_t)(v > 32767.0f ? 32767 : (v < -32768.0f ? -32768 : (int)lrintf(v)));
        }
    } else {
        float *p = (float *)buf;
        for (i = 0; i < n; ++i)
            p[i] *= gain;
    }
}

/* Maps the AAudio input layout (inCh interleaved) onto the user's channels. */
static void SetHostInput(PaAAudioStream *st, const void *data, int32_t frames)
{
    int c;
    PaUtil_SetInputFrameCount(&st->bufferProcessor, (unsigned long)frames);
    for (c = 0; c < st->inUserCh; ++c)
        PaUtil_SetInputChannel(&st->bufferProcessor, (unsigned int)c,
                               (unsigned char *)(uintptr_t)data + (size_t)(c % st->inCh) * (size_t)st->inSampleBytes,
                               (unsigned int)st->inCh);
}

static int64_t InputAvailable(PaAAudioStream *st)
{
    const int64_t avail = AAudioStream_getFramesWritten(st->in) - AAudioStream_getFramesRead(st->in);
    return avail > 0 ? avail : 0;
}

/* Reads and throws away up to 'frames' input frames; returns frames discarded or an error. */
static int64_t DiscardInput(PaAAudioStream *st, int64_t frames)
{
    int64_t done = 0;
    while (done < frames) {
        const int32_t want = (int32_t)(frames - done < st->segFrames ? frames - done : st->segFrames);
        const aaudio_result_t got = AAudioStream_read(st->in, st->inBuf, want, 0);
        if (got < 0)
            return got == AAUDIO_ERROR_INVALID_STATE ? done : got;
        if (got == 0)
            break;
        done += got;
    }
    return done;
}

/* Full-duplex warm-up.  Returns 0 while warming up (output silent), < 0 on error. */
static aaudio_result_t DuplexWarmup(PaAAudioStream *st, int32_t n)
{
    const int64_t elapsed = MonoNs() - st->warmupStartNs;
    const int64_t timeoutNs = (int64_t)st->opts.warmupTimeoutMs * NS_PER_MS;
    switch (st->phase) {
    case PH_WARMUP_DRAIN: {
        const int64_t got = DiscardInput(st, (int64_t)st->inCapacity + n);
        if (got < 0)
            return (aaudio_result_t)got;
        if (got > 0) {
            st->gotInputData = 1;
            if (--st->warmupCount <= 0) {
                st->phase = PH_WARMUP_CUSHION;
                st->warmupCount = st->cushionCallbacks;
            }
        }
        break;
    }
    case PH_WARMUP_CUSHION:
        if (--st->warmupCount <= 0) {
            st->phase = PH_WARMUP_DISCARD;
            st->warmupCount = st->discardCallbacks;
        }
        break;
    case PH_WARMUP_DISCARD:
        if (InputAvailable(st) >= n) {
            const int64_t got = DiscardInput(st, n);
            if (got < 0)
                return (aaudio_result_t)got;
        }
        if (--st->warmupCount <= 0)
            st->phase = PH_RUNNING; /* the next callback is the first real one */
        break;
    default:
        return 0;
    }
    if (st->phase != PH_RUNNING
        && ((!st->gotInputData && elapsed > timeoutNs) || elapsed > timeoutNs + 3 * NS_PER_SEC)) {
        atomic_store(&st->warmupTimedOut, 1);
        return AAUDIO_ERROR_TIMEOUT;
    }
    return 0;
}

/* Steady-state duplex input: exactly n frames into inBuf (zero-padded).
   Returns the frames really read, or an AAudio error. */
static aaudio_result_t ReadDuplexInput(PaAAudioStream *st, int32_t n, PaStreamCallbackFlags *flags,
                                       int64_t *firstIndex)
{
    const int32_t x = AAudioStream_getXRunCount(st->in);
    const int64_t slack = st->inBurst > st->outBurst ? st->inBurst : st->outBurst;
    int64_t avail, drop = 0;
    int32_t got = 0;
    if (x > st->lastXrunIn) {
        *flags |= paInputOverflow; /* the AAudio FIFO overflowed: input data was lost */
        atomic_fetch_add(&st->inXRuns, x - st->lastXrunIn);
        st->lastXrunIn = x;
    }
    avail = InputAvailable(st);
    if (st->inputDebt > 0 && avail > n + slack) {
        drop = avail - n - slack; /* repay earlier zero padding: keep input and output aligned */
        if (drop > st->inputDebt)
            drop = st->inputDebt;
    } else if (st->inputDebt == 0 && avail > n + st->excessThreshold) {
        drop = avail - n - slack; /* input clock faster than output: bound the latency */
    }
    if (drop > 0) {
        const int64_t dropped = DiscardInput(st, drop);
        if (dropped < 0)
            return (aaudio_result_t)dropped;
        if (dropped > 0) {
            st->inputDebt -= dropped < st->inputDebt ? dropped : st->inputDebt;
            atomic_fetch_add(&st->droppedInputFrames, dropped);
            *flags |= paInputOverflow; /* real input data was discarded */
        }
    }
    *firstIndex = AAudioStream_getFramesRead(st->in); /* timestamp units; includes frames lost in overruns */
    while (got < n) {
        const aaudio_result_t r = AAudioStream_read(st->in, (unsigned char *)st->inBuf + (size_t)got * (size_t)st->inFrameBytes,
                                                    n - got, 0);
        if (r < 0) {
            if (r == AAUDIO_ERROR_INVALID_STATE)
                break;
            return r;
        }
        if (r == 0)
            break;
        got += r;
    }
    if (got < n) {
        memset((unsigned char *)st->inBuf + (size_t)got * (size_t)st->inFrameBytes, 0,
               (size_t)(n - got) * (size_t)st->inFrameBytes);
        st->inputDebt += n - got;
        atomic_fetch_add(&st->paddedInputFrames, (int64_t)(n - got));
        *flags |= paInputUnderflow;
    }
    {
        const float gain = atomic_load(&g_inputGain);
        if (gain != 1.0f)
            ApplyGain(st, st->inBuf, n, gain);
    }
    return got;
}

static void PushDuplexOffset(PaAAudioStream *st, double offset)
{
    const uint32_t i = atomic_fetch_add_explicit(&st->duplexOffsetCount, 1u, memory_order_relaxed);
    atomic_store_explicit(&st->duplexOffsets[i % DUPLEX_OFFSET_HISTORY], offset, memory_order_relaxed);
}

/* ------------------------------------------------------------------------ */
/* AAudio callbacks                                                          */
/* ------------------------------------------------------------------------ */

/* Output-only and full-duplex streams. */
static aaudio_data_callback_result_t OutputCallback(AAudioStream *as, void *userData, void *audioData,
                                                    int32_t numFrames)
{
    PaAAudioStream *st = (PaAAudioStream *)userData;
    unsigned char *out = (unsigned char *)audioData;
    const size_t fb = (size_t)st->outFrameBytes;
    PaStreamCallbackFlags flags = 0;
    int result = paContinue;
    int32_t done = 0;
    int64_t outBase;
    (void)as;

    /* Dekker pair with StopInternal(): store inCallback, then load abortRequested */
    atomic_store(&st->inCallback, 1);
    if (numFrames <= 0)
        goto leave;
    if (atomic_load(&st->abortRequested) || atomic_load(&st->finished))
        goto silence;
    atomic_fetch_add_explicit(&st->callbackCount, 1, memory_order_relaxed);

    if (st->phase == PH_DRAINING) {
        st->drainFramesLeft -= numFrames;
        if (st->drainFramesLeft <= 0)
            MarkFinished(st);
        goto silence;
    }
    {
        const int32_t x = AAudioStream_getXRunCount(st->out);
        if (x > st->lastXrunOut) {
            flags |= paOutputUnderflow;
            atomic_fetch_add(&st->outXRuns, x - st->lastXrunOut);
            st->lastXrunOut = x;
        }
    }
    if (st->in && st->phase != PH_RUNNING) {
        const aaudio_result_t w = DuplexWarmup(st, numFrames);
        if (w < 0) {
            RecordStreamError(st, w);
            MarkFinished(st);
        }
        goto silence;
    }

    /* index of the first frame of this buffer, in AAudioStream_getTimestamp() units */
    outBase = AAudioStream_getFramesWritten(st->out);
    while (done < numFrames) {
        const int32_t seg = numFrames - done < st->segFrames ? numFrames - done : st->segFrames;
        PaStreamCallbackFlags f = flags;
        PaStreamCallbackTimeInfo ti;
        int64_t inIndex = 0;
        int fromTimestamps = 0;
        unsigned long processed;
        flags = 0;
        if (st->in) {
            const aaudio_result_t r = ReadDuplexInput(st, seg, &f, &inIndex);
            if (r < 0) {
                RecordStreamError(st, r);
                MarkFinished(st);
                break;
            }
        }
        FillTimeInfo(st, &ti, outBase + done, inIndex, seg, &fromTimestamps);
        if (st->in && fromTimestamps && done == 0)
            PushDuplexOffset(st, ti.outputBufferDacTime - ti.inputBufferAdcTime);
        PaUtil_BeginCpuLoadMeasurement(&st->cpuLoadMeasurer);
        PaUtil_BeginBufferProcessing(&st->bufferProcessor, &ti, f);
        if (st->in)
            SetHostInput(st, st->inBuf, seg);
        PaUtil_SetOutputFrameCount(&st->bufferProcessor, (unsigned long)seg);
        PaUtil_SetInterleavedOutputChannels(&st->bufferProcessor, 0, out + (size_t)done * fb, 0);
        processed = PaUtil_EndBufferProcessing(&st->bufferProcessor, &result);
        PaUtil_EndCpuLoadMeasurement(&st->cpuLoadMeasurer, processed);
        atomic_store_explicit(&st->cpuLoad, PaUtil_GetCpuLoad(&st->cpuLoadMeasurer), memory_order_relaxed);
        atomic_fetch_add_explicit(&st->framesProcessed, seg, memory_order_relaxed);
        done += seg;
        if (result != paContinue)
            break;
    }
    if (!atomic_load(&st->finished)) {
        if (result == paComplete) {
            /* play out what is queued in the AAudio buffer, then report "finished" */
            st->phase = PH_DRAINING;
            st->drainFramesLeft = (int64_t)AAudioStream_getBufferSizeInFrames(st->out) + st->outBurst;
        } else if (result != paContinue) {
            MarkFinished(st); /* paAbort (or an invalid value) */
        }
    }

silence:
    if (done < numFrames)
        memset(out + (size_t)done * fb, 0, (size_t)(numFrames - done) * fb);
leave:
    atomic_store(&st->inCallback, 0);
    return AAUDIO_CALLBACK_RESULT_CONTINUE; /* never STOP: Abort/Close stop the stream */
}

/* Input-only streams.  audioData belongs to AAudio and is never modified. */
static aaudio_data_callback_result_t InputCallback(AAudioStream *as, void *userData, void *audioData,
                                                   int32_t numFrames)
{
    PaAAudioStream *st = (PaAAudioStream *)userData;
    const unsigned char *in = (const unsigned char *)audioData;
    PaStreamCallbackFlags flags = 0;
    int result = paContinue;
    int32_t done = 0;
    int64_t inBase;
    float gain;
    (void)as;

    atomic_store(&st->inCallback, 1);
    if (numFrames <= 0)
        goto leave;
    if (atomic_load(&st->abortRequested) || atomic_load(&st->finished))
        goto leave;
    atomic_fetch_add_explicit(&st->callbackCount, 1, memory_order_relaxed);
    {
        const int32_t x = AAudioStream_getXRunCount(st->in);
        if (x > st->lastXrunIn) {
            flags |= paInputOverflow;
            atomic_fetch_add(&st->inXRuns, x - st->lastXrunIn);
            st->lastXrunIn = x;
        }
    }
    gain = atomic_load(&g_inputGain);
    inBase = AAudioStream_getFramesRead(st->in); /* index of audioData[0] in timestamp units */
    while (done < numFrames) {
        const int32_t seg = numFrames - done < st->segFrames ? numFrames - done : st->segFrames;
        const void *src = in + (size_t)done * (size_t)st->inFrameBytes;
        PaStreamCallbackTimeInfo ti;
        unsigned long processed;
        if (gain != 1.0f) {
            memcpy(st->inBuf, src, (size_t)seg * (size_t)st->inFrameBytes);
            ApplyGain(st, st->inBuf, seg, gain);
            src = st->inBuf;
        }
        FillTimeInfo(st, &ti, 0, inBase + done, seg, NULL);
        PaUtil_BeginCpuLoadMeasurement(&st->cpuLoadMeasurer);
        PaUtil_BeginBufferProcessing(&st->bufferProcessor, &ti, flags);
        flags = 0;
        SetHostInput(st, src, seg);
        processed = PaUtil_EndBufferProcessing(&st->bufferProcessor, &result);
        PaUtil_EndCpuLoadMeasurement(&st->cpuLoadMeasurer, processed);
        atomic_store_explicit(&st->cpuLoad, PaUtil_GetCpuLoad(&st->cpuLoadMeasurer), memory_order_relaxed);
        atomic_fetch_add_explicit(&st->framesProcessed, seg, memory_order_relaxed);
        done += seg;
        if (result != paContinue)
            break;
    }
    if (result != paContinue)
        MarkFinished(st); /* no output to drain: finished now */
leave:
    atomic_store(&st->inCallback, 0);
    return AAUDIO_CALLBACK_RESULT_CONTINUE;
}

/* AAudio error thread: only AAudioStream_get* and bookkeeping allowed here. */
static void ErrorCallback(AAudioStream *as, void *userData, aaudio_result_t error)
{
    PaAAudioStream *st = (PaAAudioStream *)userData;
    RecordStreamError(st, error);
    SetLastErrorText(error, as == st->out ? "output stream error callback" : "input stream error callback");
    LOGW("%s stream error: %s", as == st->out ? "output" : "input", AAudio_convertResultToText(error));
    if (!atomic_load(&st->closed))
        MarkFinished(st);
}

/* ------------------------------------------------------------------------ */
/* Stream lifetime: delayed free and the reaper                              */
/* ------------------------------------------------------------------------ */

static void FreeStreamMemory(PaAAudioStream *st)
{
    if (st->bufferProcessorInitialized) {
        PaUtil_TerminateBufferProcessor(&st->bufferProcessor);
        st->bufferProcessorInitialized = 0;
    }
    if (st->pollSyncInitialized) {
        pthread_cond_destroy(&st->pollCond);
        pthread_mutex_destroy(&st->pollLock);
    }
    PaUtil_FreeMemory(st->inBuf);
    PaUtil_FreeMemory(st);
}

static void TeardownDone(void)
{
    pthread_mutex_lock(&g_lock);
    --g_pendingTeardowns;
    pthread_mutex_unlock(&g_lock);
}

/* Detached thread per closed stream: finishes a deferred stop/close without ever
   blocking Pa_CloseStream, and frees the memory >= 1 s after Pa_CloseStream so a
   stray Pa_IsStreamActive() on the dead pointer reads a cleared magic. */
static void *ReaperMain(void *arg)
{
    PaAAudioStream *st = (PaAAudioStream *)arg;
    SetThreadName("PaAAudioReaper");
    if (st->needsTeardown) {
        int64_t lastLog = MonoNs();
        while (atomic_load(&st->inCallback)) { /* a user callback is still blocked: wait, no timeout */
            SleepNs(2 * NS_PER_MS);
            if (MonoNs() - lastLog > 5 * NS_PER_SEC) {
                LOGW("reaper: callback still blocked, AAudio close pending");
                lastLog = MonoNs();
            }
        }
        StopAAudio(st);
        CloseAAudio(st);
        TeardownDone();
    }
    SleepNs(st->closeNs + FREE_DELAY_NS - MonoNs());
    FreeStreamMemory(st);
    return NULL;
}

static void HandToReaper(PaAAudioStream *st)
{
    pthread_t t;
    pthread_attr_t attr;
    int ok;
    if (st->needsTeardown) {
        pthread_mutex_lock(&g_lock);
        ++g_pendingTeardowns;
        pthread_mutex_unlock(&g_lock);
    }
    pthread_attr_init(&attr);
    pthread_attr_setdetachstate(&attr, PTHREAD_CREATE_DETACHED);
    ok = pthread_create(&t, &attr, ReaperMain, st) == 0;
    pthread_attr_destroy(&attr);
    if (!ok) {
        /* Last resort: tear down synchronously (may wait for a blocked callback) and leak the
           memory instead of risking a use-after-free. */
        LOGW("cannot create the reaper thread; closing synchronously");
        if (st->needsTeardown) {
            WaitCallbackIdle(st, -1);
            StopAAudio(st);
            CloseAAudio(st);
            TeardownDone();
        }
    }
}

/* ------------------------------------------------------------------------ */
/* PortAudio stream interface                                                */
/* ------------------------------------------------------------------------ */

static void ResetRunState(PaAAudioStream *st)
{
    int i;
    st->phase = (st->in && st->out) ? PH_WARMUP_DRAIN : PH_RUNNING;
    st->warmupCount = st->drainCallbacks;
    st->gotInputData = 0;
    st->warmupStartNs = MonoNs();
    st->drainFramesLeft = 0;
    st->inputDebt = 0;
    st->lastXrunOut = st->out ? AAudioStream_getXRunCount(st->out) : 0;
    st->lastXrunIn = st->in ? AAudioStream_getXRunCount(st->in) : 0;
    st->lastDacTime = -1e300;
    st->lastAdcTime = -1e300;
    atomic_store(&st->callbackCount, 0);
    atomic_store(&st->framesProcessed, 0);
    atomic_store(&st->paddedInputFrames, 0);
    atomic_store(&st->droppedInputFrames, 0);
    atomic_store(&st->outXRuns, 0);
    atomic_store(&st->inXRuns, 0);
    atomic_store(&st->lastAAudioError, 0);
    atomic_store(&st->disconnected, 0);
    atomic_store(&st->warmupTimedOut, 0);
    atomic_store(&st->duplexOffsetCount, 0u);
    for (i = 0; i < DUPLEX_OFFSET_HISTORY; ++i)
        atomic_store(&st->duplexOffsets[i], 0.0);
    ResetClock(&st->outClock);
    ResetClock(&st->inClock);
    st->pollOutLatency = -1.0;
    st->pollInLatency = -1.0;
    PaUtil_ResetCpuLoadMeasurer(&st->cpuLoadMeasurer);
    atomic_store(&st->cpuLoad, 0.0);
}

static PaError StartStream(PaStream *s)
{
    PaAAudioStream *st = (PaAAudioStream *)s;
    aaudio_result_t r = AAUDIO_OK;
    const char *what = "";

    if (st->deferredStop) {
        /* a previous Abort could not stop AAudio; finish that first */
        if (!WaitCallbackIdle(st, 2 * NS_PER_SEC)) {
            SetLastErrorText(AAUDIO_OK, "the previous stream callback is still blocked");
            return paInternalError;
        }
        StopAAudio(st);
        st->deferredStop = 0;
    }
    PaUtil_ResetBufferProcessor(&st->bufferProcessor);
    ResetRunState(st);
    PublishStreamStarted(st);

    atomic_store(&st->finished, 0);
    atomic_store(&st->finishedNotified, 0);
    atomic_store(&st->abortRequested, 0);
    atomic_store(&st->isStopped, 0);
    atomic_store(&st->isActive, 1); /* before requestStart: callbacks may come immediately */
    st->started = 1;
    st->aaudioStopped = 0;

    if (st->in) {
        r = AAudioStream_requestStart(st->in);
        what = "requestStart(input)";
    }
    if (r == AAUDIO_OK && st->out) {
        r = AAudioStream_requestStart(st->out);
        what = "requestStart(output)";
    }
    if (r != AAUDIO_OK) {
        atomic_store(&st->abortRequested, 1);
        atomic_store(&st->isActive, 0);
        WaitCallbackIdle(st, ABORT_WAIT_NS);
        StopAAudio(st);
        atomic_store(&st->isStopped, 1);
        atomic_store(&st->finishedNotified, 1);
        RecordStreamError(st, r);
        PublishStreamStopped(st, 0);
        return MapAAudioError(r, what);
    }
    StartPoller(st);
    LOGI("started: %s%s rate %.0f, out burst %d buffer %d, in burst %d ch %d", st->out ? "out " : "",
         st->in ? "in" : "", st->rate, st->outBurst, st->outBufferSize, st->inBurst, st->inCh);
    return paNoError;
}

/* Pa_StopStream and Pa_AbortStream.  No user callback starts after this returns; if
   one is blocked inside the application (Audacity's CallbackDoSeek waits for a mutex
   the caller holds) the AAudio stop is deferred instead of deadlocking. */
static PaError StopInternal(PaAAudioStream *st)
{
    int idle;
    atomic_store(&st->abortRequested, 1);
    idle = WaitCallbackIdle(st, ABORT_WAIT_NS);
    StopPoller(st);
    if (idle) {
        StopAAudio(st);
        st->deferredStop = 0;
    } else {
        LOGW("stream callback blocked for %d ms; deferring the AAudio stop", (int)(ABORT_WAIT_NS / NS_PER_MS));
        st->deferredStop = 1;
    }
    atomic_store(&st->isActive, 0);
    atomic_store(&st->isStopped, 1);
    NotifyFinished(st);
    PublishStreamStopped(st, 0);
    return paNoError;
}

static PaError StopStream(PaStream *s)
{
    /* AAudio's requestStop lets the frames already queued in the output buffer play out */
    return StopInternal((PaAAudioStream *)s);
}

static PaError AbortStream(PaStream *s)
{
    return StopInternal((PaAAudioStream *)s);
}

static PaError CloseStream(PaStream *s)
{
    PaAAudioStream *st = (PaAAudioStream *)s;
    /* pa_front aborted the stream if it was not stopped */
    atomic_store(&st->closed, 1);
    PublishStreamStopped(st, 1);
    PaUtil_TerminateStreamRepresentation(&st->streamRepresentation); /* magic = 0 */
    StopPoller(st);
    st->closeNs = MonoNs();
    if (!st->deferredStop && (st->aaudioStopped || !st->started) && !atomic_load(&st->inCallback)) {
        CloseAAudio(st); /* callbacks are over: closing cannot block */
        st->needsTeardown = 0;
    } else {
        st->needsTeardown = 1;
    }
    HandToReaper(st);
    return paNoError;
}

static PaError IsStreamStopped(PaStream *s)
{
    return atomic_load(&((PaAAudioStream *)s)->isStopped) ? 1 : 0;
}

static PaError IsStreamActive(PaStream *s)
{
    return atomic_load(&((PaAAudioStream *)s)->isActive) ? 1 : 0;
}

static PaTime GetStreamTime(PaStream *s)
{
    (void)s;
    return (PaTime)MonoNs() * 1e-9; /* same clock as timeInfo->currentTime */
}

static double GetStreamCpuLoad(PaStream *s)
{
    return atomic_load_explicit(&((PaAAudioStream *)s)->cpuLoad, memory_order_relaxed);
}

/* ------------------------------------------------------------------------ */
/* Opening                                                                   */
/* ------------------------------------------------------------------------ */

static int ValidRate(double rate)
{
    return rate >= MIN_RATE && rate <= MAX_RATE && fabs(rate - floor(rate + 0.5)) < 1e-6;
}

static int SameRate(double a, double b)
{
    return fabs(a - b) < 0.5;
}

static PaError ValidateParameters(PaUtilHostApiRepresentation *hostApi, const PaStreamParameters *p, int isInput)
{
    const PaDeviceInfo *di;
    int maxCh;
    if (!p)
        return paNoError;
    if (p->device == paUseHostApiSpecificDeviceSpecification)
        return paInvalidDevice;
    if (p->device < 0 || p->device >= hostApi->info.deviceCount)
        return paInvalidDevice;
    di = hostApi->deviceInfos[p->device];
    maxCh = isInput ? di->maxInputChannels : di->maxOutputChannels;
    if (p->channelCount <= 0 || p->channelCount > maxCh)
        return paInvalidChannelCount;
    if (p->sampleFormat & paCustomFormat)
        return paSampleFormatNotSupported;
    if (Pa_GetSampleSize(p->sampleFormat) <= 0)
        return paSampleFormatNotSupported;
    if (p->hostApiSpecificStreamInfo)
        return paIncompatibleHostApiSpecificStreamInfo;
    return paNoError;
}

static PaError IsFormatSupported(PaUtilHostApiRepresentation *hostApi, const PaStreamParameters *inputParameters,
                                 const PaStreamParameters *outputParameters, double sampleRate)
{
    PaError err;
    int acceptAny;
    if ((err = ValidateParameters(hostApi, inputParameters, 1)) != paNoError)
        return err;
    if ((err = ValidateParameters(hostApi, outputParameters, 0)) != paNoError)
        return err;
    if (!ValidRate(sampleRate))
        return paInvalidSampleRate;
    pthread_mutex_lock(&g_lock);
    EnsureOptionsLocked();
    acceptAny = g_opts.acceptAnyRate;
    pthread_mutex_unlock(&g_lock);
    if (acceptAny)
        return paFormatIsSupported;
    /* "native preferred": report only native rates so AudioIO::GetBestRate picks them and
       Audacity resamples (AAudio stays on the MMAP/low-latency path).  An input may also
       run at the default output's native rate, so full duplex always finds a common rate. */
    if (outputParameters && !SameRate(sampleRate, hostApi->deviceInfos[outputParameters->device]->defaultSampleRate))
        return paInvalidSampleRate;
    if (inputParameters && !SameRate(sampleRate, hostApi->deviceInfos[inputParameters->device]->defaultSampleRate)
        && !SameRate(sampleRate, hostApi->deviceInfos[0]->defaultSampleRate))
        return paInvalidSampleRate;
    return paFormatIsSupported;
}

static aaudio_result_t OpenOne(PaAAudioStream *st, aaudio_direction_t dir, const PaAAudioDeviceDesc *d, int channels,
                               aaudio_format_t format, int withCallback, aaudio_input_preset_t preset,
                               AAudioStream **result)
{
    AAudioStreamBuilder *b = NULL;
    aaudio_result_t r = AAudio_createStreamBuilder(&b);
    *result = NULL;
    if (r != AAUDIO_OK)
        return r;
    AAudioStreamBuilder_setDirection(b, dir);
    AAudioStreamBuilder_setDeviceId(b, d->aaudioDeviceId);
    AAudioStreamBuilder_setSampleRate(b, (int32_t)lrint(st->rate));
    AAudioStreamBuilder_setChannelCount(b, channels);
    AAudioStreamBuilder_setFormat(b, format);
    AAudioStreamBuilder_setSharingMode(b, st->opts.sharingMode);
    if (dir == AAUDIO_DIRECTION_OUTPUT) {
        AAudioStreamBuilder_setPerformanceMode(b, st->opts.outputPerformanceMode);
        AAudioStreamBuilder_setUsage(b, st->opts.usage);
        AAudioStreamBuilder_setContentType(b, st->opts.contentType);
        AAudioStreamBuilder_setDataCallback(b, OutputCallback, st);
    } else {
        AAudioStreamBuilder_setPerformanceMode(b, withCallback ? st->opts.inputPerformanceMode
                                                               : AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
        AAudioStreamBuilder_setInputPreset(b, preset);
        if (withCallback) {
            AAudioStreamBuilder_setDataCallback(b, InputCallback, st);
        } else {
            /* read-mode FIFO polled from the output callback: room for jitter */
            const int32_t cap = (int32_t)(0.2 * st->rate);
            AAudioStreamBuilder_setBufferCapacityInFrames(b, cap > 4096 ? cap : 4096);
        }
    }
    AAudioStreamBuilder_setErrorCallback(b, ErrorCallback, st);
    r = AAudioStreamBuilder_openStream(b, result);
    AAudioStreamBuilder_delete(b);
    if (r != AAUDIO_OK) {
        *result = NULL;
        return r;
    }
    if (AAudioStream_getSampleRate(*result) != (int32_t)lrint(st->rate))
        r = AAUDIO_ERROR_INVALID_RATE;
    else if (AAudioStream_getFormat(*result) != format)
        r = AAUDIO_ERROR_INVALID_FORMAT;
    else if (dir == AAUDIO_DIRECTION_OUTPUT && AAudioStream_getChannelCount(*result) != channels)
        r = AAUDIO_ERROR_OUT_OF_RANGE;
    else if (AAudioStream_getChannelCount(*result) <= 0)
        r = AAUDIO_ERROR_OUT_OF_RANGE;
    if (r != AAUDIO_OK) {
        AAudioStream_close(*result);
        *result = NULL;
    }
    return r;
}

/* Tries float, then int16; for input also the other channel count (mono mic) and the
   default input preset.  Returns the first error if nothing works. */
static aaudio_result_t OpenDirection(PaAAudioStream *st, aaudio_direction_t dir, const PaAAudioDeviceDesc *d,
                                     int channels, int withCallback, AAudioStream **result, aaudio_format_t *format)
{
    static const aaudio_format_t formats[2] = { AAUDIO_FORMAT_PCM_FLOAT, AAUDIO_FORMAT_PCM_I16 };
    aaudio_input_preset_t presets[2];
    int channelChoices[2];
    int nPresets = 1, nChannels = 1, p, c, f;
    aaudio_result_t first = AAUDIO_OK;
    presets[0] = st->opts.inputPreset;
    channelChoices[0] = channels;
    if (dir == AAUDIO_DIRECTION_INPUT) {
        if (presets[0] != AAUDIO_INPUT_PRESET_VOICE_RECOGNITION)
            presets[nPresets++] = AAUDIO_INPUT_PRESET_VOICE_RECOGNITION;
        if (channels <= 2)
            channelChoices[nChannels++] = channels == 1 ? 2 : 1;
    }
    for (p = 0; p < nPresets; ++p)
        for (c = 0; c < nChannels; ++c)
            for (f = 0; f < 2; ++f) {
                const aaudio_result_t r = OpenOne(st, dir, d, channelChoices[c], formats[f], withCallback, presets[p], result);
                if (r == AAUDIO_OK) {
                    *format = formats[f];
                    if (p > 0 || c > 0 || f > 0)
                        LOGI("%s opened with fallback: preset %d, %d ch, format %d",
                             dir == AAUDIO_DIRECTION_OUTPUT ? "output" : "input", (int)presets[p], channelChoices[c],
                             (int)formats[f]);
                    return AAUDIO_OK;
                }
                if (first == AAUDIO_OK)
                    first = r;
                if (r == AAUDIO_ERROR_DISCONNECTED || r == AAUDIO_ERROR_NO_SERVICE || r == AAUDIO_ERROR_INVALID_RATE)
                    return first; /* no fallback helps */
            }
    return first;
}

static void DestroyUnstartedStream(PaAAudioStream *st)
{
    if (st->out)
        AAudioStream_close(st->out);
    if (st->in)
        AAudioStream_close(st->in);
    st->out = st->in = NULL;
    FreeStreamMemory(st);
}

static PaError OpenStream(PaUtilHostApiRepresentation *hostApi, PaStream **s, const PaStreamParameters *inputParameters,
                          const PaStreamParameters *outputParameters, double sampleRate, unsigned long framesPerBuffer,
                          PaStreamFlags streamFlags, PaStreamCallback *streamCallback, void *userData)
{
    PaAAudioHostApi *h = (PaAAudioHostApi *)hostApi;
    PaAAudioStream *st;
    PaError result;
    aaudio_result_t r;

    if ((result = ValidateParameters(hostApi, inputParameters, 1)) != paNoError)
        return result;
    if ((result = ValidateParameters(hostApi, outputParameters, 0)) != paNoError)
        return result;
    if (!ValidRate(sampleRate))
        return paInvalidSampleRate;
    if (streamFlags & paPlatformSpecificFlags)
        return paInvalidFlag;
    if (!streamCallback) {
        SetLastErrorText(AAUDIO_OK, "blocking streams (Pa_ReadStream/Pa_WriteStream) are not supported");
        return paInternalError;
    }

    st = (PaAAudioStream *)PaUtil_AllocateZeroInitializedMemory((long)sizeof *st);
    if (!st)
        return paInsufficientMemory;
    pthread_mutex_lock(&g_lock);
    EnsureOptionsLocked();
    st->opts = g_opts;
    pthread_mutex_unlock(&g_lock);
    atomic_store(&st->isStopped, 1);
    atomic_store(&st->isActive, 0);
    st->rate = sampleRate;
    st->segFrames = st->opts.maxFramesPerUserCallback;
    st->pollOutLatency = st->pollInLatency = -1.0;
    {
        pthread_condattr_t ca;
        pthread_condattr_init(&ca);
        pthread_condattr_setclock(&ca, CLOCK_MONOTONIC);
        if (pthread_mutex_init(&st->pollLock, NULL) == 0) {
            if (pthread_cond_init(&st->pollCond, &ca) == 0)
                st->pollSyncInitialized = 1;
            else
                pthread_mutex_destroy(&st->pollLock);
        }
        pthread_condattr_destroy(&ca);
    }
    PaUtil_InitializeStreamRepresentation(&st->streamRepresentation, &h->callbackStreamInterface, streamCallback,
                                          userData);
    PaUtil_InitializeCpuLoadMeasurer(&st->cpuLoadMeasurer, sampleRate);

    if (outputParameters) {
        const PaAAudioDeviceDesc *d = &h->devs[outputParameters->device];
        int32_t want, capacity;
        r = OpenDirection(st, AAUDIO_DIRECTION_OUTPUT, d, outputParameters->channelCount, 1, &st->out, &st->outFormat);
        if (r != AAUDIO_OK) {
            result = MapAAudioError(r, "open output stream");
            goto error;
        }
        st->outCh = outputParameters->channelCount;
        st->outFrameBytes = st->outCh * BytesPerSample(st->outFormat);
        st->outBurst = AAudioStream_getFramesPerBurst(st->out);
        if (st->outBurst <= 0)
            st->outBurst = FALLBACK_BURST;
        capacity = AAudioStream_getBufferCapacityInFrames(st->out);
        st->outCapacity = capacity > 0 ? capacity : 0;
        want = (int32_t)lrint(outputParameters->suggestedLatency * sampleRate);
        if (want < 2 * st->outBurst)
            want = 2 * st->outBurst;
        if (capacity > 0 && want > capacity)
            want = capacity;
        AAudioStream_setBufferSizeInFrames(st->out, want);
        st->outBufferSize = AAudioStream_getBufferSizeInFrames(st->out);
        if (st->outBufferSize <= 0)
            st->outBufferSize = want;
        if (st->outCapacity < st->outBufferSize)
            st->outCapacity = st->outBufferSize;
    }
    if (inputParameters) {
        const PaAAudioDeviceDesc *d = &h->devs[inputParameters->device];
        int32_t capacity;
        r = OpenDirection(st, AAUDIO_DIRECTION_INPUT, d, inputParameters->channelCount, st->out == NULL, &st->in,
                          &st->inFormat);
        if (r != AAUDIO_OK) {
            result = MapAAudioError(r, "open input stream");
            goto error;
        }
        st->inUserCh = inputParameters->channelCount;
        st->inCh = AAudioStream_getChannelCount(st->in);
        st->inSampleBytes = BytesPerSample(st->inFormat);
        st->inFrameBytes = st->inCh * st->inSampleBytes;
        st->inBurst = AAudioStream_getFramesPerBurst(st->in);
        if (st->inBurst <= 0)
            st->inBurst = FALLBACK_BURST;
        capacity = AAudioStream_getBufferCapacityInFrames(st->in);
        st->inCapacity = capacity > 0 ? capacity : 4 * st->inBurst;
        st->inBuf = PaUtil_AllocateZeroInitializedMemory((long)st->segFrames * st->inFrameBytes);
        if (!st->inBuf) {
            result = paInsufficientMemory;
            goto error;
        }
    }

    result = PaUtil_InitializeBufferProcessor(
        &st->bufferProcessor, inputParameters ? inputParameters->channelCount : 0,
        inputParameters ? inputParameters->sampleFormat : paFloat32, HostSampleFormat(st->inFormat),
        outputParameters ? outputParameters->channelCount : 0, outputParameters ? outputParameters->sampleFormat : paFloat32,
        HostSampleFormat(st->outFormat), sampleRate, streamFlags, framesPerBuffer, (unsigned long)st->segFrames,
        paUtilBoundedHostBufferSize, streamCallback, userData);
    if (result != paNoError)
        goto error;
    st->bufferProcessorInitialized = 1;

    /* warm-up lengths (full duplex), in output callbacks */
    if (st->in && st->out) {
        const double perCallback = (double)st->outBurst / sampleRate;
        st->drainCallbacks = st->opts.duplexDrainCallbacks > 0 ? st->opts.duplexDrainCallbacks
                                                                : ClampInt((int)ceil(0.080 / perCallback), 3, 40);
        st->cushionCallbacks = st->opts.duplexCushionBursts > 0 ? st->opts.duplexCushionBursts : 1;
        st->discardCallbacks = st->opts.duplexDiscardCallbacks > 0 ? st->opts.duplexDiscardCallbacks
                                                                    : ClampInt((int)ceil(0.120 / perCallback), 3, 60);
        st->excessThreshold = 3 * (int64_t)(st->inBurst > st->outBurst ? st->inBurst : st->outBurst)
                              + (int64_t)(0.010 * sampleRate);
    }

    st->streamRepresentation.streamInfo.structVersion = 1;
    st->streamRepresentation.streamInfo.sampleRate = sampleRate;
    /* AudioIO sizes its playback ring from outputLatency: report the FIFO we keep full */
    st->streamRepresentation.streamInfo.outputLatency =
        st->out ? (double)(st->outBufferSize + st->outBurst) / sampleRate : 0.0;
    st->streamRepresentation.streamInfo.inputLatency = st->in ? (double)(2 * st->inBurst) / sampleRate : 0.0;

    LOGI("opened: rate %.0f out %d ch (fmt %d, burst %d, buffer %d/%d) in %d/%d ch (fmt %d, burst %d)", sampleRate,
         st->outCh, (int)st->outFormat, st->outBurst, st->outBufferSize, st->outCapacity, st->inCh, st->inUserCh,
         (int)st->inFormat, st->inBurst);
    *s = (PaStream *)st;
    return paNoError;

error:
    DestroyUnstartedStream(st);
    return result;
}

/* ------------------------------------------------------------------------ */
/* Host API                                                                  */
/* ------------------------------------------------------------------------ */

/* Native rate/burst of the default route, if Java did not supply them: open (never
   start) an output stream with unspecified parameters.  Never probes the input. */
static void ProbeDefaults(double *rate, int *burst)
{
    AAudioStreamBuilder *b = NULL;
    AAudioStream *s = NULL;
    *rate = FALLBACK_RATE;
    *burst = FALLBACK_BURST;
    if (AAudio_createStreamBuilder(&b) != AAUDIO_OK)
        return;
    AAudioStreamBuilder_setDirection(b, AAUDIO_DIRECTION_OUTPUT);
    AAudioStreamBuilder_setPerformanceMode(b, AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
    AAudioStreamBuilder_setSharingMode(b, AAUDIO_SHARING_MODE_SHARED);
    AAudioStreamBuilder_setFormat(b, AAUDIO_FORMAT_PCM_FLOAT);
    if (AAudioStreamBuilder_openStream(b, &s) == AAUDIO_OK && s) {
        const int32_t r = AAudioStream_getSampleRate(s), fb = AAudioStream_getFramesPerBurst(s);
        if (r >= MIN_RATE && r <= MAX_RATE)
            *rate = r;
        if (fb > 0)
            *burst = fb;
        AAudioStream_close(s);
    }
    AAudioStreamBuilder_delete(b);
}

static void Terminate(PaUtilHostApiRepresentation *hostApi)
{
    PaAAudioHostApi *h = (PaAAudioHostApi *)hostApi;
    const int64_t deadline = MonoNs() + TERMINATE_WAIT_NS;
    int left;
    /* streams were closed by pa_front; wait (bounded) for AAudio closes the reaper still owes */
    for (;;) {
        pthread_mutex_lock(&g_lock);
        left = g_pendingTeardowns;
        pthread_mutex_unlock(&g_lock);
        if (left <= 0 || MonoNs() > deadline)
            break;
        SleepNs(2 * NS_PER_MS);
    }
    if (left > 0)
        LOGW("Pa_Terminate: %d stream(s) still have a blocked callback; their AAudio close is pending", left);
    if (h->allocations) {
        PaUtil_FreeAllAllocations(h->allocations);
        PaUtil_DestroyAllocationGroup(h->allocations);
    }
    PaUtil_FreeMemory(h);
}

static char *GroupStrdup(PaUtilAllocationGroup *g, const char *s)
{
    const size_t n = strlen(s) + 1;
    char *p = (char *)PaUtil_GroupAllocateZeroInitializedMemory(g, (long)n);
    if (p)
        memcpy(p, s, n);
    return p;
}

PaError PaAAudio_Initialize(PaUtilHostApiRepresentation **hostApi, PaHostApiIndex hostApiIndex)
{
    PaError result = paNoError;
    PaAAudioHostApi *h;
    PaDeviceInfo *infos;
    double defRate;
    int defBurst, i, n, userCount;

    pthread_mutex_lock(&g_lock);
    EnsureOptionsLocked();
    defRate = g_defaultRate;
    defBurst = g_defaultBurst;
    pthread_mutex_unlock(&g_lock);
    if (defRate <= 0) {
        int probedBurst;
        ProbeDefaults(&defRate, &probedBurst);
        defBurst = probedBurst;
    }
    if (defBurst <= 0)
        defBurst = (int)lrint(defRate * 0.004); /* ~4 ms */

    h = (PaAAudioHostApi *)PaUtil_AllocateZeroInitializedMemory((long)sizeof *h);
    if (!h)
        return paInsufficientMemory;
    h->allocations = PaUtil_CreateAllocationGroup();
    if (!h->allocations) {
        result = paInsufficientMemory;
        goto error;
    }
    *hostApi = &h->inheritedHostApiRep;
    (*hostApi)->info.structVersion = 1;
    (*hostApi)->info.type = paInDevelopment; /* PortAudio has no type id for AAudio */
    (*hostApi)->info.name = PA_AAUDIO_HOST_NAME;
    (*hostApi)->info.defaultInputDevice = paNoDevice;
    (*hostApi)->info.defaultOutputDevice = paNoDevice;
    (*hostApi)->info.deviceCount = 0;

    pthread_mutex_lock(&g_lock);
    userCount = g_userDevCount;
    n = 2 + userCount;
    h->devs = (PaAAudioDeviceDesc *)PaUtil_GroupAllocateZeroInitializedMemory(h->allocations,
                                                                              (long)(sizeof(PaAAudioDeviceDesc) * (size_t)n));
    (*hostApi)->deviceInfos = (PaDeviceInfo **)PaUtil_GroupAllocateZeroInitializedMemory(
        h->allocations, (long)(sizeof(PaDeviceInfo *) * (size_t)n));
    infos = (PaDeviceInfo *)PaUtil_GroupAllocateZeroInitializedMemory(h->allocations,
                                                                     (long)(sizeof(PaDeviceInfo) * (size_t)n));
    if (!h->devs || !(*hostApi)->deviceInfos || !infos) {
        pthread_mutex_unlock(&g_lock);
        result = paInsufficientMemory;
        goto error;
    }
    h->devs[0].name = PA_AAUDIO_DEFAULT_OUTPUT_NAME;
    h->devs[0].aaudioDeviceId = AAUDIO_UNSPECIFIED;
    h->devs[0].maxOutputChannels = 2;
    h->devs[1].name = PA_AAUDIO_DEFAULT_INPUT_NAME;
    h->devs[1].aaudioDeviceId = AAUDIO_UNSPECIFIED;
    h->devs[1].maxInputChannels = 2;
    for (i = 0; i < userCount; ++i) {
        h->devs[2 + i] = g_userDevs[i];
        h->devs[2 + i].name = GroupStrdup(h->allocations, g_userDevs[i].name);
        if (!h->devs[2 + i].name) {
            pthread_mutex_unlock(&g_lock);
            result = paInsufficientMemory;
            goto error;
        }
    }
    pthread_mutex_unlock(&g_lock);

    for (i = 0; i < n; ++i) {
        PaAAudioDeviceDesc *d = &h->devs[i];
        PaDeviceInfo *di = &infos[i];
        const double rate = d->nativeSampleRate > 0 ? d->nativeSampleRate : defRate;
        const int burst = d->framesPerBurst > 0 ? d->framesPerBurst : defBurst;
        d->nativeSampleRate = rate;
        d->framesPerBurst = burst;
        di->structVersion = 2;
        di->name = d->name;
        di->hostApi = hostApiIndex;
        di->maxInputChannels = d->maxInputChannels;
        di->maxOutputChannels = d->maxOutputChannels;
        di->defaultLowInputLatency = 2.0 * burst / rate;
        di->defaultLowOutputLatency = 2.0 * burst / rate;
        di->defaultHighInputLatency = 0.04;
        di->defaultHighOutputLatency = 0.1;
        di->defaultSampleRate = rate;
        (*hostApi)->deviceInfos[i] = di;
    }
    h->devCount = n;
    (*hostApi)->info.deviceCount = n;
    (*hostApi)->info.defaultOutputDevice = 0;
    (*hostApi)->info.defaultInputDevice = 1;

    (*hostApi)->Terminate = Terminate;
    (*hostApi)->OpenStream = OpenStream;
    (*hostApi)->IsFormatSupported = IsFormatSupported;
    PaUtil_InitializeStreamInterface(&h->callbackStreamInterface, CloseStream, StartStream, StopStream, AbortStream,
                                     IsStreamStopped, IsStreamActive, GetStreamTime, GetStreamCpuLoad, PaUtil_DummyRead,
                                     PaUtil_DummyWrite, PaUtil_DummyGetReadAvailable, PaUtil_DummyGetWriteAvailable);
    LOGI("initialized: %d devices, default rate %.0f burst %d", n, defRate, defBurst);
    return paNoError;

error:
    if (h->allocations) {
        PaUtil_FreeAllAllocations(h->allocations);
        PaUtil_DestroyAllocationGroup(h->allocations);
    }
    PaUtil_FreeMemory(h);
    return result;
}
