/*
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Simulated AAudio ("Null" device) for the Linux host build of the Audacity
 * Android port.  Implements the AAudio subset declared in
 * src/null/aaudio/AAudio.h so that src/pa_aaudio.c -- the real Android host
 * API -- runs unchanged in host tests, plus the test API in pa_null.h.
 *
 * Model (all times CLOCK_MONOTONIC, R = stream rate, B = burst):
 *   output: a callback thread wakes at start + k*B/R (absolute deadlines),
 *           asks for B frames and measures them.  Frame p is presented at
 *           start + (p - base + bufferSize)/R; timestamps follow that model.
 *           A wake-up later than 4 bursts counts an xrun and shifts the
 *           presentation timeline (an underrun gap).
 *   input (callback): frames [base + (k-1)B, base + kB) are delivered at
 *           start + k*B/R; frame p was captured at start + (p - base)/R.
 *   input (read mode): frames become available in bursts as the clock
 *           advances (rate R * (1 + drift)); a reader slower than the buffer
 *           capacity loses the oldest frames (xrun, framesRead jumps).
 *   loopback: channel 0 of every output frame is stored in an "air" ring at
 *           its presentation time; input frames read the air at their
 *           capture time minus loopbackDelayFrames.
 * The simulator is free to lock and allocate (it is not real-time code); it
 * never frees stream objects, so calls on closed streams are detected and
 * counted as misuse instead of crashing.
 */

#ifndef _GNU_SOURCE
#define _GNU_SOURCE 1
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

#include "pa_null.h"

#define NS_PER_SEC 1000000000LL
#define NS_PER_MS 1000000LL
#define STREAM_MAGIC 0x4e554c4cu
#define STREAM_MAGIC_CLOSED 0x44454144u
#define MAX_STREAMS 256
#define AIR_FRAMES (1 << 18) /* ~5.4 s at 48 kHz */
#define MEASURED_CHANNELS 8

#ifndef M_PI
#define M_PI 3.14159265358979323846
#endif

struct AAudioStreamBuilderStruct {
    aaudio_direction_t direction;
    int32_t deviceId, sampleRate, channelCount, capacity;
    aaudio_format_t format;
    aaudio_sharing_mode_t sharing;
    aaudio_performance_mode_t perf;
    aaudio_usage_t usage;
    aaudio_content_type_t contentType;
    aaudio_input_preset_t preset;
    AAudioStream_dataCallback dataCb;
    void *dataUd;
    AAudioStream_errorCallback errorCb;
    void *errorUd;
};

struct AAudioStreamStruct {
    _Atomic uint32_t magic;
    int id;
    aaudio_direction_t direction;
    int32_t deviceId, rate, channels, capacity, burst;
    _Atomic int32_t bufferSize;
    aaudio_format_t format;
    aaudio_sharing_mode_t sharing;
    aaudio_performance_mode_t perf;
    AAudioStream_dataCallback dataCb;
    void *dataUd;
    AAudioStream_errorCallback errorCb;
    void *errorUd;

    pthread_mutex_t lock; /* everything below except the atomics */
    pthread_cond_t cond;  /* CLOCK_MONOTONIC */
    aaudio_stream_state_t state;
    int64_t framesWritten, framesRead;
    int64_t startNs, basePos;
    double rateFactor;
    int stalled;
    int64_t stallStartNs;
    int stopRequested;
    int callbackReturnedStop;
    int threadStarted;
    pthread_t thread;
    int errorCallbacksPending;

    _Atomic int32_t xruns;
    _Atomic int inDataCallback;
    _Atomic int cbThreadSet;
    pthread_t cbThread;
};

/* ------------------------------------------------------------------------ */
/* Global simulator state (g_sim)                                            */
/* ------------------------------------------------------------------------ */

static pthread_mutex_t g_sim = PTHREAD_MUTEX_INITIALIZER;
static PaNullConfig g_cfg;
static int g_cfgInitialized;
static int g_failErr, g_failDir = -1;
static int g_inputStalled;
static int g_misuse;
static char g_lastMisuse[256];
static int g_openCount, g_totalOpen, g_nextId;
static AAudioStream *g_streams[MAX_STREAMS];
static int g_streamCount;
static double g_sumsq[MEASURED_CHANNELS], g_peak[MEASURED_CHANNELS];
static int64_t g_outFrames, g_outCallbacks;
static int g_outChannels;
static float g_air[AIR_FRAMES];
static int64_t g_airTag[AIR_FRAMES]; /* air index + 1; 0 = empty */

static void DefaultConfig(PaNullConfig *c)
{
    memset(c, 0, sizeof *c);
    c->sampleRate = 48000.0;
    c->framesPerBurst = 480;
    c->bufferCapacityBursts = 16;
    c->maxInputChannels = 2;
    c->inputFrequency = 440.0;
    c->inputAmplitude = 0.5;
    c->loopback = 0;
    c->loopbackDelayFrames = 0;
    c->inputDriftPpm = 0.0;
}

/* g_sim held */
static void EnsureConfigLocked(void)
{
    if (!g_cfgInitialized) {
        DefaultConfig(&g_cfg);
        g_cfgInitialized = 1;
    }
}

static PaNullConfig GetConfig(void)
{
    PaNullConfig c;
    pthread_mutex_lock(&g_sim);
    EnsureConfigLocked();
    c = g_cfg;
    pthread_mutex_unlock(&g_sim);
    return c;
}

static int64_t MonoNs(void)
{
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (int64_t)ts.tv_sec * NS_PER_SEC + ts.tv_nsec;
}

static struct timespec ToTimespec(int64_t ns)
{
    struct timespec ts;
    ts.tv_sec = (time_t)(ns / NS_PER_SEC);
    ts.tv_nsec = (long)(ns % NS_PER_SEC);
    return ts;
}

static void SleepNs(int64_t ns)
{
    struct timespec ts;
    if (ns <= 0)
        return;
    ts = ToTimespec(ns);
    while (nanosleep(&ts, &ts) == -1 && errno == EINTR) {
    }
}

static void Misuse(const char *fmt, ...) __attribute__((format(printf, 1, 2)));
static void Misuse(const char *fmt, ...)
{
    va_list ap;
    pthread_mutex_lock(&g_sim);
    ++g_misuse;
    va_start(ap, fmt);
    vsnprintf(g_lastMisuse, sizeof g_lastMisuse, fmt, ap);
    va_end(ap);
    fprintf(stderr, "Null AAudio: MISUSE: %s\n", g_lastMisuse);
    pthread_mutex_unlock(&g_sim);
}

static int Check(AAudioStream *s, const char *fn)
{
    if (!s) {
        Misuse("%s(NULL)", fn);
        return 0;
    }
    if (atomic_load(&s->magic) != STREAM_MAGIC) {
        Misuse("%s on a closed stream", fn);
        return 0;
    }
    return 1;
}

static int IsCallbackThread(AAudioStream *s)
{
    return atomic_load(&s->cbThreadSet) && pthread_equal(pthread_self(), s->cbThread);
}

static int BytesPerSample(aaudio_format_t f)
{
    return f == AAUDIO_FORMAT_PCM_I16 ? 2 : 4;
}

static void WriteSample(void *buf, aaudio_format_t f, size_t index, double v)
{
    if (f == AAUDIO_FORMAT_PCM_I16) {
        double x = v * 32767.0;
        x = x > 32767.0 ? 32767.0 : (x < -32768.0 ? -32768.0 : x);
        ((int16_t *)buf)[index] = (int16_t)lrint(x);
    } else {
        ((float *)buf)[index] = (float)v;
    }
}

static double ReadSample(const void *buf, aaudio_format_t f, size_t index)
{
    if (f == AAUDIO_FORMAT_PCM_I16)
        return (double)((const int16_t *)buf)[index] / 32768.0;
    return (double)((const float *)buf)[index];
}

/* ------------------------------------------------------------------------ */
/* Text                                                                      */
/* ------------------------------------------------------------------------ */

const char *AAudio_convertResultToText(aaudio_result_t r)
{
    switch (r) {
    case AAUDIO_OK: return "AAUDIO_OK";
    case AAUDIO_ERROR_DISCONNECTED: return "AAUDIO_ERROR_DISCONNECTED";
    case AAUDIO_ERROR_ILLEGAL_ARGUMENT: return "AAUDIO_ERROR_ILLEGAL_ARGUMENT";
    case AAUDIO_ERROR_INTERNAL: return "AAUDIO_ERROR_INTERNAL";
    case AAUDIO_ERROR_INVALID_STATE: return "AAUDIO_ERROR_INVALID_STATE";
    case AAUDIO_ERROR_INVALID_HANDLE: return "AAUDIO_ERROR_INVALID_HANDLE";
    case AAUDIO_ERROR_UNIMPLEMENTED: return "AAUDIO_ERROR_UNIMPLEMENTED";
    case AAUDIO_ERROR_UNAVAILABLE: return "AAUDIO_ERROR_UNAVAILABLE";
    case AAUDIO_ERROR_NO_FREE_HANDLES: return "AAUDIO_ERROR_NO_FREE_HANDLES";
    case AAUDIO_ERROR_NO_MEMORY: return "AAUDIO_ERROR_NO_MEMORY";
    case AAUDIO_ERROR_NULL: return "AAUDIO_ERROR_NULL";
    case AAUDIO_ERROR_TIMEOUT: return "AAUDIO_ERROR_TIMEOUT";
    case AAUDIO_ERROR_WOULD_BLOCK: return "AAUDIO_ERROR_WOULD_BLOCK";
    case AAUDIO_ERROR_INVALID_FORMAT: return "AAUDIO_ERROR_INVALID_FORMAT";
    case AAUDIO_ERROR_OUT_OF_RANGE: return "AAUDIO_ERROR_OUT_OF_RANGE";
    case AAUDIO_ERROR_NO_SERVICE: return "AAUDIO_ERROR_NO_SERVICE";
    case AAUDIO_ERROR_INVALID_RATE: return "AAUDIO_ERROR_INVALID_RATE";
    default: return "Unrecognized AAudio error.";
    }
}

const char *AAudio_convertStreamStateToText(aaudio_stream_state_t state)
{
    static const char *names[] = { "AAUDIO_STREAM_STATE_UNINITIALIZED", "AAUDIO_STREAM_STATE_UNKNOWN",
                                   "AAUDIO_STREAM_STATE_OPEN",          "AAUDIO_STREAM_STATE_STARTING",
                                   "AAUDIO_STREAM_STATE_STARTED",       "AAUDIO_STREAM_STATE_PAUSING",
                                   "AAUDIO_STREAM_STATE_PAUSED",        "AAUDIO_STREAM_STATE_FLUSHING",
                                   "AAUDIO_STREAM_STATE_FLUSHED",       "AAUDIO_STREAM_STATE_STOPPING",
                                   "AAUDIO_STREAM_STATE_STOPPED",       "AAUDIO_STREAM_STATE_CLOSING",
                                   "AAUDIO_STREAM_STATE_CLOSED",        "AAUDIO_STREAM_STATE_DISCONNECTED" };
    if (state < 0 || state >= (aaudio_stream_state_t)(sizeof names / sizeof names[0]))
        return "Unrecognized AAudio state.";
    return names[state];
}

/* ------------------------------------------------------------------------ */
/* Builder                                                                   */
/* ------------------------------------------------------------------------ */

aaudio_result_t AAudio_createStreamBuilder(AAudioStreamBuilder **builder)
{
    AAudioStreamBuilder *b;
    if (!builder)
        return AAUDIO_ERROR_NULL;
    b = (AAudioStreamBuilder *)calloc(1, sizeof *b);
    if (!b)
        return AAUDIO_ERROR_NO_MEMORY;
    b->direction = AAUDIO_DIRECTION_OUTPUT;
    b->format = AAUDIO_FORMAT_UNSPECIFIED;
    b->sharing = AAUDIO_SHARING_MODE_SHARED;
    b->perf = AAUDIO_PERFORMANCE_MODE_NONE;
    b->usage = AAUDIO_USAGE_MEDIA;
    b->contentType = AAUDIO_CONTENT_TYPE_MUSIC;
    b->preset = AAUDIO_INPUT_PRESET_VOICE_RECOGNITION;
    *builder = b;
    return AAUDIO_OK;
}

void AAudioStreamBuilder_setDeviceId(AAudioStreamBuilder *b, int32_t deviceId) { b->deviceId = deviceId; }
void AAudioStreamBuilder_setSampleRate(AAudioStreamBuilder *b, int32_t sampleRate) { b->sampleRate = sampleRate; }
void AAudioStreamBuilder_setChannelCount(AAudioStreamBuilder *b, int32_t channelCount) { b->channelCount = channelCount; }
void AAudioStreamBuilder_setFormat(AAudioStreamBuilder *b, aaudio_format_t format) { b->format = format; }
void AAudioStreamBuilder_setSharingMode(AAudioStreamBuilder *b, aaudio_sharing_mode_t m) { b->sharing = m; }
void AAudioStreamBuilder_setDirection(AAudioStreamBuilder *b, aaudio_direction_t d) { b->direction = d; }
void AAudioStreamBuilder_setBufferCapacityInFrames(AAudioStreamBuilder *b, int32_t n) { b->capacity = n; }
void AAudioStreamBuilder_setPerformanceMode(AAudioStreamBuilder *b, aaudio_performance_mode_t m) { b->perf = m; }
void AAudioStreamBuilder_setUsage(AAudioStreamBuilder *b, aaudio_usage_t u) { b->usage = u; }
void AAudioStreamBuilder_setContentType(AAudioStreamBuilder *b, aaudio_content_type_t c) { b->contentType = c; }
void AAudioStreamBuilder_setInputPreset(AAudioStreamBuilder *b, aaudio_input_preset_t p) { b->preset = p; }

void AAudioStreamBuilder_setDataCallback(AAudioStreamBuilder *b, AAudioStream_dataCallback cb, void *ud)
{
    b->dataCb = cb;
    b->dataUd = ud;
}

void AAudioStreamBuilder_setErrorCallback(AAudioStreamBuilder *b, AAudioStream_errorCallback cb, void *ud)
{
    b->errorCb = cb;
    b->errorUd = ud;
}

aaudio_result_t AAudioStreamBuilder_delete(AAudioStreamBuilder *b)
{
    free(b);
    return AAUDIO_OK;
}

aaudio_result_t AAudioStreamBuilder_openStream(AAudioStreamBuilder *b, AAudioStream **out)
{
    PaNullConfig c;
    AAudioStream *s;
    pthread_condattr_t ca;
    int32_t rate, channels;
    aaudio_format_t format;
    if (!b || !out)
        return AAUDIO_ERROR_NULL;
    *out = NULL;
    pthread_mutex_lock(&g_sim);
    EnsureConfigLocked();
    c = g_cfg;
    if (g_failErr && (g_failDir < 0 || g_failDir == b->direction)) {
        const aaudio_result_t err = g_failErr;
        g_failErr = 0;
        pthread_mutex_unlock(&g_sim);
        return err;
    }
    pthread_mutex_unlock(&g_sim);

    rate = b->sampleRate ? b->sampleRate : (int32_t)lrint(c.sampleRate);
    if (rate < 8000 || rate > 192000)
        return AAUDIO_ERROR_INVALID_RATE;
    channels = b->channelCount;
    if (channels == 0)
        channels = b->direction == AAUDIO_DIRECTION_OUTPUT ? 2 : (c.maxInputChannels < 2 ? c.maxInputChannels : 2);
    if (channels < 1 || channels > 8)
        return AAUDIO_ERROR_OUT_OF_RANGE;
    if (b->direction == AAUDIO_DIRECTION_INPUT && channels > c.maxInputChannels)
        channels = c.maxInputChannels; /* like a mono microphone: fewer channels than requested */
    format = b->format == AAUDIO_FORMAT_UNSPECIFIED ? AAUDIO_FORMAT_PCM_FLOAT : b->format;
    if (format != AAUDIO_FORMAT_PCM_FLOAT && format != AAUDIO_FORMAT_PCM_I16)
        return AAUDIO_ERROR_INVALID_FORMAT;
    s = (AAudioStream *)calloc(1, sizeof *s);
    if (!s)
        return AAUDIO_ERROR_NO_MEMORY;
    s->direction = b->direction;
    s->rate = rate;
    s->channels = channels;
    s->format = format;
    s->burst = c.framesPerBurst > 0 ? c.framesPerBurst : 480;
    s->capacity = s->burst * (c.bufferCapacityBursts > 1 ? c.bufferCapacityBursts : 2);
    if (b->capacity > s->capacity)
        s->capacity = ((b->capacity + s->burst - 1) / s->burst) * s->burst;
    atomic_store(&s->bufferSize, b->direction == AAUDIO_DIRECTION_OUTPUT ? 2 * s->burst : s->capacity);
    s->deviceId = b->deviceId ? b->deviceId : (b->direction == AAUDIO_DIRECTION_OUTPUT ? 1001 : 1002);
    s->sharing = b->sharing;
    s->perf = b->perf;
    s->dataCb = b->dataCb;
    s->dataUd = b->dataUd;
    s->errorCb = b->errorCb;
    s->errorUd = b->errorUd;
    s->rateFactor = 1.0;
    s->state = AAUDIO_STREAM_STATE_OPEN;
    pthread_mutex_init(&s->lock, NULL);
    pthread_condattr_init(&ca);
    pthread_condattr_setclock(&ca, CLOCK_MONOTONIC);
    pthread_cond_init(&s->cond, &ca);
    pthread_condattr_destroy(&ca);
    atomic_store(&s->magic, STREAM_MAGIC);

    pthread_mutex_lock(&g_sim);
    if (g_streamCount < MAX_STREAMS)
        g_streams[g_streamCount++] = s;
    ++g_openCount;
    ++g_totalOpen;
    s->id = ++g_nextId;
    pthread_mutex_unlock(&g_sim);
    *out = s;
    return AAUDIO_OK;
}

/* ------------------------------------------------------------------------ */
/* Simulated device clocks                                                   */
/* ------------------------------------------------------------------------ */

/* s->lock held.  Read-mode input: frames captured so far become readable. */
static void UpdateProduction(AAudioStream *s, int64_t now)
{
    double rel;
    int64_t produced, w;
    if (s->direction != AAUDIO_DIRECTION_INPUT || s->dataCb || s->state != AAUDIO_STREAM_STATE_STARTED || s->stalled)
        return;
    rel = (double)(now - s->startNs) * (double)s->rate * s->rateFactor / 1e9;
    produced = rel > 0 ? ((int64_t)rel / s->burst) * s->burst : 0;
    w = s->basePos + produced;
    if (w > s->framesWritten)
        s->framesWritten = w;
    if (s->framesWritten - s->framesRead > s->capacity) {
        s->framesRead = s->framesWritten - s->capacity; /* overrun: oldest frames lost */
        atomic_fetch_add(&s->xruns, 1);
    }
}

static double AirRead(int64_t idx)
{
    const size_t slot = (size_t)(idx & (AIR_FRAMES - 1));
    return g_airTag[slot] == idx + 1 ? (double)g_air[slot] : 0.0;
}

/* Input frames [pos, pos + n) of a stream whose frame p was captured at
   startNs + (p - basePos) / (rate * factor). */
static void GenerateInput(const AAudioStream *s, void *buf, int64_t pos, int32_t n, int64_t startNs, int64_t basePos,
                          double factor, const PaNullConfig *c)
{
    int32_t j;
    int ch;
    if (c->loopback)
        pthread_mutex_lock(&g_sim);
    for (j = 0; j < n; ++j) {
        const int64_t p = pos + j;
        double v;
        if (c->loopback) {
            const double tNs = (double)startNs + (double)(p - basePos) * 1e9 / ((double)s->rate * factor);
            v = AirRead(llround(tNs * (double)s->rate / 1e9) - c->loopbackDelayFrames);
        } else {
            v = c->inputAmplitude * sin(2.0 * M_PI * c->inputFrequency * (double)p / (double)s->rate);
        }
        for (ch = 0; ch < s->channels; ++ch)
            WriteSample(buf, s->format, (size_t)j * (size_t)s->channels + (size_t)ch, v);
    }
    if (c->loopback)
        pthread_mutex_unlock(&g_sim);
}

static void MeasureOutput(const AAudioStream *s, const void *buf, int64_t pos, int32_t n, int64_t startNs,
                          int64_t basePos, int32_t bufferSize)
{
    const int chMeasured = s->channels < MEASURED_CHANNELS ? s->channels : MEASURED_CHANNELS;
    const double airBase = (double)startNs * (double)s->rate / 1e9 + (double)(pos - basePos + bufferSize);
    int32_t j;
    int ch;
    pthread_mutex_lock(&g_sim);
    g_outChannels = s->channels;
    for (j = 0; j < n; ++j) {
        const int64_t idx = llround(airBase + j);
        const size_t slot = (size_t)(idx & (AIR_FRAMES - 1));
        for (ch = 0; ch < chMeasured; ++ch) {
            const double v = ReadSample(buf, s->format, (size_t)j * (size_t)s->channels + (size_t)ch);
            g_sumsq[ch] += v * v;
            if (fabs(v) > g_peak[ch])
                g_peak[ch] = fabs(v);
        }
        g_air[slot] = (float)ReadSample(buf, s->format, (size_t)j * (size_t)s->channels);
        g_airTag[slot] = idx + 1;
    }
    g_outFrames += n;
    ++g_outCallbacks;
    pthread_mutex_unlock(&g_sim);
}

/* Data-callback thread of a started callback stream. */
static void *CallbackThread(void *arg)
{
    AAudioStream *s = (AAudioStream *)arg;
    const double periodNs = (double)s->burst * 1e9 / (double)s->rate;
    const size_t bytes = (size_t)s->burst * (size_t)s->channels * (size_t)BytesPerSample(s->format);
    void *buf = calloc(1, bytes);
    const int output = s->direction == AAUDIO_DIRECTION_OUTPUT;
    int64_t k = output ? 0 : 1;
    s->cbThread = pthread_self();
    atomic_store(&s->cbThreadSet, 1);
    pthread_setname_np(pthread_self(), output ? "NullAAudioOut" : "NullAAudioIn");
    if (!buf)
        return NULL;
    for (;;) {
        PaNullConfig c;
        int64_t deadline, now, pos, startNs, basePos;
        int32_t bufferSize;
        aaudio_data_callback_result_t r;
        struct timespec ts;

        pthread_mutex_lock(&s->lock);
        if (!output && s->stalled) {
            /* no data: wait one period; unstalling shifts startNs by the stall time */
            deadline = MonoNs() + (int64_t)periodNs;
            ts = ToTimespec(deadline);
            while (!s->stopRequested && s->state != AAUDIO_STREAM_STATE_DISCONNECTED && s->stalled)
                if (pthread_cond_timedwait(&s->cond, &s->lock, &ts) == ETIMEDOUT)
                    break;
            if (s->stopRequested || s->state == AAUDIO_STREAM_STATE_DISCONNECTED) {
                pthread_mutex_unlock(&s->lock);
                break;
            }
            pthread_mutex_unlock(&s->lock);
            continue;
        }
        deadline = s->startNs + llround((double)k * periodNs);
        ts = ToTimespec(deadline);
        while (!s->stopRequested && s->state != AAUDIO_STREAM_STATE_DISCONNECTED && MonoNs() < deadline)
            if (pthread_cond_timedwait(&s->cond, &s->lock, &ts) == ETIMEDOUT)
                break;
        if (s->stopRequested || s->state == AAUDIO_STREAM_STATE_DISCONNECTED) {
            pthread_mutex_unlock(&s->lock);
            break;
        }
        if (!output && s->stalled) {
            pthread_mutex_unlock(&s->lock);
            continue;
        }
        now = MonoNs();
        if ((double)(now - deadline) > 4.0 * periodNs) {
            const int64_t missed = (int64_t)((double)(now - deadline) / periodNs);
            atomic_fetch_add(&s->xruns, 1);
            if (output) {
                s->startNs += llround((double)missed * periodNs); /* underrun gap */
            } else {
                k += missed; /* overrun: frames captured meanwhile are lost */
                s->framesWritten += missed * s->burst;
                s->framesRead += missed * s->burst;
            }
        }
        if (s->callbackReturnedStop) {
            pthread_mutex_unlock(&s->lock);
            ++k;
            continue;
        }
        pos = output ? s->framesWritten : s->framesRead;
        startNs = s->startNs;
        basePos = s->basePos;
        bufferSize = atomic_load(&s->bufferSize);
        pthread_mutex_unlock(&s->lock);

        if (!output) {
            c = GetConfig();
            GenerateInput(s, buf, pos, s->burst, startNs, basePos, 1.0, &c);
        }
        atomic_store(&s->inDataCallback, 1);
        r = s->dataCb(s, s->dataUd, buf, s->burst);
        atomic_store(&s->inDataCallback, 0);

        pthread_mutex_lock(&s->lock);
        s->framesWritten += s->burst;
        if (!output)
            s->framesRead += s->burst;
        if (r == AAUDIO_CALLBACK_RESULT_STOP)
            s->callbackReturnedStop = 1;
        pthread_mutex_unlock(&s->lock);
        if (output)
            MeasureOutput(s, buf, pos, s->burst, startNs, basePos, bufferSize);
        ++k;
    }
    free(buf);
    return NULL;
}

/* ------------------------------------------------------------------------ */
/* Stream control                                                            */
/* ------------------------------------------------------------------------ */

aaudio_result_t AAudioStream_requestStart(AAudioStream *s)
{
    PaNullConfig c;
    if (!Check(s, "AAudioStream_requestStart"))
        return AAUDIO_ERROR_INVALID_HANDLE;
    if (IsCallbackThread(s)) {
        Misuse("AAudioStream_requestStart from its own data callback");
        return AAUDIO_ERROR_INVALID_STATE;
    }
    if (s->direction == AAUDIO_DIRECTION_OUTPUT && !s->dataCb)
        return AAUDIO_ERROR_UNIMPLEMENTED; /* the simulator has no AAudioStream_write */
    c = GetConfig();
    pthread_mutex_lock(&s->lock);
    if (s->state == AAUDIO_STREAM_STATE_DISCONNECTED) {
        pthread_mutex_unlock(&s->lock);
        return AAUDIO_ERROR_DISCONNECTED;
    }
    if (s->state == AAUDIO_STREAM_STATE_STARTED || s->state == AAUDIO_STREAM_STATE_STARTING) {
        pthread_mutex_unlock(&s->lock);
        return AAUDIO_OK;
    }
    if (s->threadStarted) {
        /* previous run not stopped through requestStop: refuse like AAudio would */
        pthread_mutex_unlock(&s->lock);
        return AAUDIO_ERROR_INVALID_STATE;
    }
    s->startNs = MonoNs();
    s->basePos = s->framesWritten;
    s->framesRead = s->framesWritten; /* input: no stale data; output: nothing queued */
    s->rateFactor = (s->direction == AAUDIO_DIRECTION_INPUT && !s->dataCb) ? 1.0 + c.inputDriftPpm * 1e-6 : 1.0;
    pthread_mutex_lock(&g_sim);
    s->stalled = s->direction == AAUDIO_DIRECTION_INPUT && g_inputStalled;
    pthread_mutex_unlock(&g_sim);
    s->stallStartNs = s->startNs;
    s->stopRequested = 0;
    s->callbackReturnedStop = 0;
    s->state = AAUDIO_STREAM_STATE_STARTED;
    pthread_cond_broadcast(&s->cond);
    if (s->dataCb) {
        if (pthread_create(&s->thread, NULL, CallbackThread, s) != 0) {
            s->state = AAUDIO_STREAM_STATE_STOPPED;
            pthread_mutex_unlock(&s->lock);
            return AAUDIO_ERROR_NO_FREE_HANDLES;
        }
        s->threadStarted = 1;
    }
    pthread_mutex_unlock(&s->lock);
    return AAUDIO_OK;
}

/* Stops the stream and joins its callback thread (like MMAP AAudio: blocks while the
   data callback is busy). */
static aaudio_result_t StopAndJoin(AAudioStream *s)
{
    pthread_t t;
    int join;
    aaudio_result_t r;
    pthread_mutex_lock(&s->lock);
    if (s->state == AAUDIO_STREAM_STATE_STARTED || s->state == AAUDIO_STREAM_STATE_STARTING)
        s->state = AAUDIO_STREAM_STATE_STOPPING;
    s->stopRequested = 1;
    join = s->threadStarted;
    s->threadStarted = 0;
    t = s->thread;
    pthread_cond_broadcast(&s->cond);
    pthread_mutex_unlock(&s->lock);
    if (join)
        pthread_join(t, NULL);
    pthread_mutex_lock(&s->lock);
    if (s->state != AAUDIO_STREAM_STATE_DISCONNECTED) {
        if (s->direction == AAUDIO_DIRECTION_OUTPUT)
            s->framesRead = s->framesWritten; /* queued frames played out */
        s->state = AAUDIO_STREAM_STATE_STOPPED;
    }
    r = s->state == AAUDIO_STREAM_STATE_DISCONNECTED ? AAUDIO_ERROR_DISCONNECTED : AAUDIO_OK;
    pthread_cond_broadcast(&s->cond);
    pthread_mutex_unlock(&s->lock);
    return r;
}

aaudio_result_t AAudioStream_requestStop(AAudioStream *s)
{
    if (!Check(s, "AAudioStream_requestStop"))
        return AAUDIO_ERROR_INVALID_HANDLE;
    if (IsCallbackThread(s)) {
        Misuse("AAudioStream_requestStop from its own data callback");
        return AAUDIO_ERROR_INVALID_STATE;
    }
    return StopAndJoin(s);
}

aaudio_result_t AAudioStream_close(AAudioStream *s)
{
    int i;
    if (!Check(s, "AAudioStream_close"))
        return AAUDIO_ERROR_INVALID_HANDLE;
    if (IsCallbackThread(s)) {
        Misuse("AAudioStream_close from its own data callback");
        return AAUDIO_ERROR_INVALID_STATE;
    }
    if (atomic_load(&s->inDataCallback))
        Misuse("AAudioStream_close while the data callback is running");
    StopAndJoin(s);
    pthread_mutex_lock(&s->lock);
    while (s->errorCallbacksPending > 0) /* AAudio does not close under a running error callback */
        pthread_cond_wait(&s->cond, &s->lock);
    atomic_store(&s->magic, STREAM_MAGIC_CLOSED);
    s->state = AAUDIO_STREAM_STATE_CLOSED;
    pthread_cond_broadcast(&s->cond);
    pthread_mutex_unlock(&s->lock);
    pthread_mutex_lock(&g_sim);
    for (i = 0; i < g_streamCount; ++i)
        if (g_streams[i] == s) {
            g_streams[i] = g_streams[--g_streamCount];
            break;
        }
    --g_openCount;
    pthread_mutex_unlock(&g_sim);
    /* the object is intentionally never freed: later calls are reported as misuse */
    return AAUDIO_OK;
}

aaudio_stream_state_t AAudioStream_getState(AAudioStream *s)
{
    aaudio_stream_state_t st;
    if (!s)
        return AAUDIO_STREAM_STATE_UNINITIALIZED;
    if (atomic_load(&s->magic) != STREAM_MAGIC)
        return AAUDIO_STREAM_STATE_CLOSED;
    pthread_mutex_lock(&s->lock);
    st = s->state;
    pthread_mutex_unlock(&s->lock);
    return st;
}

aaudio_result_t AAudioStream_waitForStateChange(AAudioStream *s, aaudio_stream_state_t inputState,
                                                aaudio_stream_state_t *nextState, int64_t timeoutNanoseconds)
{
    struct timespec ts;
    aaudio_result_t r = AAUDIO_OK;
    if (!Check(s, "AAudioStream_waitForStateChange"))
        return AAUDIO_ERROR_INVALID_HANDLE;
    if (IsCallbackThread(s)) {
        Misuse("AAudioStream_waitForStateChange from its own data callback");
        return AAUDIO_ERROR_INVALID_STATE;
    }
    ts = ToTimespec(MonoNs() + (timeoutNanoseconds > 0 ? timeoutNanoseconds : 0));
    pthread_mutex_lock(&s->lock);
    while (s->state == inputState) {
        if (pthread_cond_timedwait(&s->cond, &s->lock, &ts) == ETIMEDOUT) {
            if (s->state == inputState)
                r = AAUDIO_ERROR_TIMEOUT;
            break;
        }
    }
    if (nextState)
        *nextState = s->state;
    pthread_mutex_unlock(&s->lock);
    return r;
}

aaudio_result_t AAudioStream_read(AAudioStream *s, void *buffer, int32_t numFrames, int64_t timeoutNanoseconds)
{
    PaNullConfig c;
    int64_t deadline;
    int32_t got = 0;
    size_t frameBytes;
    if (!Check(s, "AAudioStream_read"))
        return AAUDIO_ERROR_INVALID_HANDLE;
    frameBytes = (size_t)s->channels * (size_t)BytesPerSample(s->format);
    if (s->direction != AAUDIO_DIRECTION_INPUT)
        return AAUDIO_ERROR_UNIMPLEMENTED;
    if (s->dataCb) {
        Misuse("AAudioStream_read on a stream with a data callback");
        return AAUDIO_ERROR_INVALID_STATE;
    }
    if (!buffer || numFrames < 0)
        return AAUDIO_ERROR_ILLEGAL_ARGUMENT;
    c = GetConfig();
    deadline = MonoNs() + (timeoutNanoseconds > 0 ? timeoutNanoseconds : 0);
    for (;;) {
        int64_t pos, startNs, basePos, avail;
        double factor;
        int32_t take;
        pthread_mutex_lock(&s->lock);
        if (s->state == AAUDIO_STREAM_STATE_DISCONNECTED) {
            pthread_mutex_unlock(&s->lock);
            return AAUDIO_ERROR_DISCONNECTED;
        }
        if (s->state != AAUDIO_STREAM_STATE_STARTED) {
            pthread_mutex_unlock(&s->lock);
            return got > 0 ? got : AAUDIO_ERROR_INVALID_STATE;
        }
        UpdateProduction(s, MonoNs());
        avail = s->framesWritten - s->framesRead;
        take = (int32_t)(avail < numFrames - got ? avail : numFrames - got);
        pos = s->framesRead;
        startNs = s->startNs;
        basePos = s->basePos;
        factor = s->rateFactor;
        s->framesRead += take;
        pthread_mutex_unlock(&s->lock);
        if (take > 0)
            GenerateInput(s, (unsigned char *)buffer + (size_t)got * frameBytes, pos, take, startNs, basePos, factor, &c);
        got += take;
        if (got >= numFrames || timeoutNanoseconds <= 0 || MonoNs() >= deadline)
            break;
        SleepNs(NS_PER_MS);
    }
    return got;
}

aaudio_result_t AAudioStream_setBufferSizeInFrames(AAudioStream *s, int32_t numFrames)
{
    if (!Check(s, "AAudioStream_setBufferSizeInFrames"))
        return AAUDIO_ERROR_INVALID_HANDLE;
    if (numFrames < s->burst)
        numFrames = s->burst;
    if (numFrames > s->capacity)
        numFrames = s->capacity;
    atomic_store(&s->bufferSize, numFrames);
    return numFrames;
}

int32_t AAudioStream_getBufferSizeInFrames(AAudioStream *s)
{
    return Check(s, "AAudioStream_getBufferSizeInFrames") ? atomic_load(&s->bufferSize) : 0;
}

int32_t AAudioStream_getFramesPerBurst(AAudioStream *s)
{
    return Check(s, "AAudioStream_getFramesPerBurst") ? s->burst : 0;
}

int32_t AAudioStream_getBufferCapacityInFrames(AAudioStream *s)
{
    return Check(s, "AAudioStream_getBufferCapacityInFrames") ? s->capacity : 0;
}

int32_t AAudioStream_getXRunCount(AAudioStream *s)
{
    return Check(s, "AAudioStream_getXRunCount") ? atomic_load(&s->xruns) : 0;
}

int32_t AAudioStream_getSampleRate(AAudioStream *s)
{
    return Check(s, "AAudioStream_getSampleRate") ? s->rate : 0;
}

int32_t AAudioStream_getChannelCount(AAudioStream *s)
{
    return Check(s, "AAudioStream_getChannelCount") ? s->channels : 0;
}

int32_t AAudioStream_getDeviceId(AAudioStream *s)
{
    return Check(s, "AAudioStream_getDeviceId") ? s->deviceId : 0;
}

aaudio_format_t AAudioStream_getFormat(AAudioStream *s)
{
    return Check(s, "AAudioStream_getFormat") ? s->format : AAUDIO_FORMAT_INVALID;
}

aaudio_sharing_mode_t AAudioStream_getSharingMode(AAudioStream *s)
{
    return Check(s, "AAudioStream_getSharingMode") ? s->sharing : AAUDIO_SHARING_MODE_SHARED;
}

aaudio_performance_mode_t AAudioStream_getPerformanceMode(AAudioStream *s)
{
    return Check(s, "AAudioStream_getPerformanceMode") ? s->perf : AAUDIO_PERFORMANCE_MODE_NONE;
}

aaudio_direction_t AAudioStream_getDirection(AAudioStream *s)
{
    return Check(s, "AAudioStream_getDirection") ? s->direction : AAUDIO_DIRECTION_OUTPUT;
}

int64_t AAudioStream_getFramesWritten(AAudioStream *s)
{
    int64_t v;
    if (!Check(s, "AAudioStream_getFramesWritten"))
        return 0;
    pthread_mutex_lock(&s->lock);
    UpdateProduction(s, MonoNs());
    v = s->framesWritten;
    pthread_mutex_unlock(&s->lock);
    return v;
}

int64_t AAudioStream_getFramesRead(AAudioStream *s)
{
    int64_t v;
    if (!Check(s, "AAudioStream_getFramesRead"))
        return 0;
    pthread_mutex_lock(&s->lock);
    if (s->direction == AAUDIO_DIRECTION_OUTPUT && s->state == AAUDIO_STREAM_STATE_STARTED) {
        const int64_t played = (int64_t)floor((double)(MonoNs() - s->startNs) * (double)s->rate / 1e9)
                               - atomic_load(&s->bufferSize);
        v = s->basePos + (played > 0 ? played : 0);
        if (v > s->framesWritten)
            v = s->framesWritten;
    } else {
        UpdateProduction(s, MonoNs());
        v = s->framesRead;
    }
    pthread_mutex_unlock(&s->lock);
    return v;
}

aaudio_result_t AAudioStream_getTimestamp(AAudioStream *s, clockid_t clockid, int64_t *framePosition,
                                          int64_t *timeNanoseconds)
{
    aaudio_result_t r = AAUDIO_OK;
    int64_t now;
    if (!Check(s, "AAudioStream_getTimestamp"))
        return AAUDIO_ERROR_INVALID_HANDLE;
    if (clockid != CLOCK_MONOTONIC && clockid != CLOCK_BOOTTIME)
        return AAUDIO_ERROR_ILLEGAL_ARGUMENT;
    if (!framePosition || !timeNanoseconds)
        return AAUDIO_ERROR_NULL;
    pthread_mutex_lock(&s->lock);
    now = MonoNs();
    if (s->state != AAUDIO_STREAM_STATE_STARTED) {
        r = AAUDIO_ERROR_INVALID_STATE;
    } else if (s->direction == AAUDIO_DIRECTION_OUTPUT) {
        const int32_t bufferSize = atomic_load(&s->bufferSize);
        int64_t played = (int64_t)floor((double)(now - s->startNs) * (double)s->rate / 1e9) - bufferSize;
        if (played > s->framesWritten - s->basePos)
            played = s->framesWritten - s->basePos;
        if (played <= 0) {
            r = AAUDIO_ERROR_INVALID_STATE;
        } else {
            *framePosition = s->basePos + played;
            *timeNanoseconds = s->startNs + llround((double)(played + bufferSize) * 1e9 / (double)s->rate);
        }
    } else {
        int64_t pos;
        UpdateProduction(s, now);
        pos = s->framesWritten;
        if (pos <= s->basePos) {
            r = AAUDIO_ERROR_INVALID_STATE;
        } else {
            *framePosition = pos;
            *timeNanoseconds = s->startNs + llround((double)(pos - s->basePos) * 1e9 / ((double)s->rate * s->rateFactor));
        }
    }
    pthread_mutex_unlock(&s->lock);
    return r;
}

/* ------------------------------------------------------------------------ */
/* Test API (pa_null.h)                                                      */
/* ------------------------------------------------------------------------ */

void PaNull_GetConfig(PaNullConfig *config)
{
    if (config)
        *config = GetConfig();
}

void PaNull_SetConfig(const PaNullConfig *config)
{
    pthread_mutex_lock(&g_sim);
    if (config)
        g_cfg = *config;
    else
        DefaultConfig(&g_cfg);
    if (!(g_cfg.sampleRate >= 8000.0 && g_cfg.sampleRate <= 192000.0))
        g_cfg.sampleRate = 48000.0;
    if (g_cfg.framesPerBurst <= 0)
        g_cfg.framesPerBurst = 480;
    if (g_cfg.bufferCapacityBursts < 2)
        g_cfg.bufferCapacityBursts = 2;
    if (g_cfg.maxInputChannels < 1)
        g_cfg.maxInputChannels = 1;
    if (g_cfg.maxInputChannels > 8)
        g_cfg.maxInputChannels = 8;
    g_cfgInitialized = 1;
    pthread_mutex_unlock(&g_sim);
}

void PaNull_SetInputSignal(double frequencyHz, double amplitude)
{
    pthread_mutex_lock(&g_sim);
    EnsureConfigLocked();
    g_cfg.inputFrequency = frequencyHz;
    g_cfg.inputAmplitude = amplitude;
    pthread_mutex_unlock(&g_sim);
}

void PaNull_GetOutputStats(PaNullOutputStats *stats, int reset)
{
    int ch;
    if (!stats)
        return;
    memset(stats, 0, sizeof *stats);
    pthread_mutex_lock(&g_sim);
    stats->frames = g_outFrames;
    stats->callbacks = g_outCallbacks;
    stats->channels = g_outChannels;
    for (ch = 0; ch < MEASURED_CHANNELS; ++ch) {
        stats->rms[ch] = g_outFrames > 0 ? sqrt(g_sumsq[ch] / (double)g_outFrames) : 0.0;
        stats->peak[ch] = g_peak[ch];
    }
    if (reset) {
        g_outFrames = 0;
        g_outCallbacks = 0;
        memset(g_sumsq, 0, sizeof g_sumsq);
        memset(g_peak, 0, sizeof g_peak);
    }
    pthread_mutex_unlock(&g_sim);
}

/* Snapshot of the open streams (objects are never freed, so the pointers stay valid). */
static int SnapshotStreams(AAudioStream **out)
{
    int n;
    pthread_mutex_lock(&g_sim);
    n = g_streamCount;
    memcpy(out, g_streams, (size_t)n * sizeof *out);
    pthread_mutex_unlock(&g_sim);
    return n;
}

static void *ErrorThread(void *arg)
{
    AAudioStream *s = (AAudioStream *)arg;
    int alive;
    pthread_setname_np(pthread_self(), "NullAAudioErr");
    pthread_mutex_lock(&s->lock);
    alive = atomic_load(&s->magic) == STREAM_MAGIC;
    pthread_mutex_unlock(&s->lock);
    if (alive && s->errorCb)
        s->errorCb(s, s->errorUd, AAUDIO_ERROR_DISCONNECTED);
    pthread_mutex_lock(&s->lock);
    --s->errorCallbacksPending;
    pthread_cond_broadcast(&s->cond);
    pthread_mutex_unlock(&s->lock);
    return NULL;
}

void PaNull_SimulateDisconnect(void)
{
    AAudioStream *list[MAX_STREAMS];
    const int n = SnapshotStreams(list);
    int i;
    for (i = 0; i < n; ++i) {
        AAudioStream *s = list[i];
        pthread_t t;
        pthread_attr_t attr;
        int spawn = 0;
        pthread_mutex_lock(&s->lock);
        if (atomic_load(&s->magic) == STREAM_MAGIC && s->state != AAUDIO_STREAM_STATE_DISCONNECTED) {
            s->state = AAUDIO_STREAM_STATE_DISCONNECTED;
            pthread_cond_broadcast(&s->cond);
            if (s->errorCb) {
                ++s->errorCallbacksPending;
                spawn = 1;
            }
        }
        pthread_mutex_unlock(&s->lock);
        if (!spawn)
            continue;
        pthread_attr_init(&attr);
        pthread_attr_setdetachstate(&attr, PTHREAD_CREATE_DETACHED);
        if (pthread_create(&t, &attr, ErrorThread, s) != 0) {
            pthread_mutex_lock(&s->lock);
            --s->errorCallbacksPending;
            pthread_cond_broadcast(&s->cond);
            pthread_mutex_unlock(&s->lock);
        }
        pthread_attr_destroy(&attr);
    }
}

void PaNull_SimulateXRun(int direction, int lostFrames)
{
    AAudioStream *list[MAX_STREAMS];
    const int n = SnapshotStreams(list);
    int i;
    for (i = 0; i < n; ++i) {
        AAudioStream *s = list[i];
        pthread_mutex_lock(&s->lock);
        if (atomic_load(&s->magic) == STREAM_MAGIC && s->state == AAUDIO_STREAM_STATE_STARTED
            && (direction < 0 || direction == s->direction)) {
            atomic_fetch_add(&s->xruns, 1);
            if (s->direction == AAUDIO_DIRECTION_INPUT && lostFrames > 0) {
                if (s->dataCb) {
                    s->framesWritten += lostFrames;
                    s->framesRead += lostFrames;
                } else {
                    int64_t skip;
                    UpdateProduction(s, MonoNs());
                    skip = s->framesWritten - s->framesRead;
                    if (skip > lostFrames)
                        skip = lostFrames;
                    s->framesRead += skip;
                }
            }
        }
        pthread_mutex_unlock(&s->lock);
    }
}

void PaNull_SetInputStalled(int stalled)
{
    AAudioStream *list[MAX_STREAMS];
    int n, i;
    pthread_mutex_lock(&g_sim);
    g_inputStalled = stalled ? 1 : 0;
    pthread_mutex_unlock(&g_sim);
    n = SnapshotStreams(list);
    for (i = 0; i < n; ++i) {
        AAudioStream *s = list[i];
        const int64_t now = MonoNs();
        pthread_mutex_lock(&s->lock);
        if (atomic_load(&s->magic) == STREAM_MAGIC && s->direction == AAUDIO_DIRECTION_INPUT
            && s->state == AAUDIO_STREAM_STATE_STARTED && s->stalled != (stalled ? 1 : 0)) {
            if (stalled) {
                UpdateProduction(s, now);
                s->stalled = 1;
                s->stallStartNs = now;
            } else {
                s->startNs += now - s->stallStartNs; /* capture timeline resumes where it stopped */
                s->stalled = 0;
            }
            pthread_cond_broadcast(&s->cond);
        }
        pthread_mutex_unlock(&s->lock);
    }
}

void PaNull_FailNextOpen(int aaudioError, int direction)
{
    pthread_mutex_lock(&g_sim);
    g_failErr = aaudioError;
    g_failDir = direction;
    pthread_mutex_unlock(&g_sim);
}

int PaNull_GetMisuseCount(void)
{
    int n;
    pthread_mutex_lock(&g_sim);
    n = g_misuse;
    pthread_mutex_unlock(&g_sim);
    return n;
}

const char *PaNull_GetLastMisuse(void)
{
    static _Thread_local char copy[sizeof g_lastMisuse];
    pthread_mutex_lock(&g_sim);
    memcpy(copy, g_lastMisuse, sizeof copy);
    pthread_mutex_unlock(&g_sim);
    return copy;
}

int PaNull_GetOpenStreamCount(void)
{
    int n;
    pthread_mutex_lock(&g_sim);
    n = g_openCount;
    pthread_mutex_unlock(&g_sim);
    return n;
}

int PaNull_GetTotalOpenCount(void)
{
    int n;
    pthread_mutex_lock(&g_sim);
    n = g_totalOpen;
    pthread_mutex_unlock(&g_sim);
    return n;
}
