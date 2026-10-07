/*
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Host tests of the PortAudio AAudio host API (Audacity Android port),
 * running on the simulated "Null" AAudio device.
 *
 * Usage: portaudio-null-test <case>|all     (ctest runs one case per process)
 * Environment: PA_AAUDIO_DEBUG=1 prints the host API's log lines.
 */

#ifndef _GNU_SOURCE
#define _GNU_SOURCE 1
#endif

#include <math.h>
#include <pthread.h>
#include <stdatomic.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#include "portaudio.h"
#include "pa_android_aaudio.h"
#include "pa_null.h"

/* AAudio result codes used by the tests (same values as <aaudio/AAudio.h>) */
#define T_AAUDIO_ERROR_DISCONNECTED (-899)
#define T_AAUDIO_ERROR_INTERNAL (-896)
#define T_AAUDIO_ERROR_TIMEOUT (-885)
#define T_AAUDIO_ERROR_OUT_OF_RANGE (-882)
#define T_AAUDIO_ERROR_INVALID_RATE (-880)
#define T_AAUDIO_FORMAT_PCM_I16 1
#define T_AAUDIO_PERFORMANCE_MODE_NONE 10

static int g_failures;
static int g_checks;

#define CHECK(cond)                                                                                  \
    do {                                                                                             \
        ++g_checks;                                                                                  \
        if (!(cond)) {                                                                               \
            fprintf(stderr, "%s:%d: CHECK failed: %s\n", __FILE__, __LINE__, #cond);                 \
            ++g_failures;                                                                            \
        }                                                                                            \
    } while (0)

#define CHECK_PA(expr)                                                                               \
    do {                                                                                             \
        PaError e_ = (expr);                                                                         \
        ++g_checks;                                                                                  \
        if (e_ != paNoError) {                                                                       \
            fprintf(stderr, "%s:%d: %s -> %d %s [%s]\n", __FILE__, __LINE__, #expr, (int)e_,         \
                    Pa_GetErrorText(e_), PaAAudio_GetLastErrorText());                               \
            ++g_failures;                                                                            \
        }                                                                                            \
    } while (0)

#define CHECK_NEAR(a, b, tol)                                                                        \
    do {                                                                                             \
        const double a_ = (a), b_ = (b);                                                             \
        ++g_checks;                                                                                  \
        if (!(fabs(a_ - b_) <= (tol))) {                                                             \
            fprintf(stderr, "%s:%d: CHECK_NEAR failed: %s = %g, expected %g +- %g\n", __FILE__,      \
                    __LINE__, #a, a_, b_, (double)(tol));                                            \
            ++g_failures;                                                                            \
        }                                                                                            \
    } while (0)

static double Now(void)
{
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (double)ts.tv_sec + (double)ts.tv_nsec * 1e-9;
}

static void SleepMs(int ms)
{
    struct timespec ts;
    ts.tv_sec = ms / 1000;
    ts.tv_nsec = (long)(ms % 1000) * 1000000L;
    while (nanosleep(&ts, &ts) != 0) {
    }
}

/* Polls until fn(arg) is true or timeout; returns the elapsed seconds or -1. */
static double WaitFor(int (*fn)(void *), void *arg, double timeoutSec)
{
    const double t0 = Now();
    while (!fn(arg)) {
        if (Now() - t0 > timeoutSec)
            return -1.0;
        SleepMs(2);
    }
    return Now() - t0;
}

static int StreamInactive(void *s)
{
    return Pa_IsStreamActive((PaStream *)s) == 0;
}

static int NoOpenAAudioStreams(void *unused)
{
    (void)unused;
    return PaNull_GetOpenStreamCount() == 0;
}

/* ------------------------------------------------------------------------ */
/* The PortAudio callback used by all tests                                  */
/* ------------------------------------------------------------------------ */

#define MAX_IMPULSES 64

typedef struct Recorder {
    /* configuration */
    int inChannels, outChannels;
    PaSampleFormat inFormat;
    double rate, outFreq, outAmp;
    long completeAfterFrames; /* > 0: return paComplete once reached */
    long abortAfterFrames;    /* > 0: return paAbort once reached */
    long impulseEvery;        /* > 0: output impulses instead of a sine */
    /* blocking (deadlock test) */
    _Atomic int blockNow, blocked;
    pthread_mutex_t m;
    pthread_cond_t cv;
    /* results (read after the stream stopped, or atomics) */
    _Atomic long callbacks, frames;
    long minFrames, maxFrames;
    _Atomic int bad; /* timeInfo/buffer contract violations */
    _Atomic unsigned long flagsSeen;
    _Atomic int underflowCallbacks, overflowCallbacks;
    _Atomic int finishedCalls;
    double firstCallbackTime;
    double lastCurrent, lastDac, lastAdc;
    double inSumSq[2], inPeak[2];
    long inFrames;
    long framesSoFar;
    long impulsesOut[MAX_IMPULSES], impulsesIn[MAX_IMPULSES];
    int nImpulsesOut, nImpulsesIn;
    double offsets[4096];
    int nOffsets;
} Recorder;

static void RecorderInit(Recorder *r, int inCh, int outCh)
{
    memset(r, 0, sizeof *r);
    r->inChannels = inCh;
    r->outChannels = outCh;
    r->inFormat = paFloat32;
    r->rate = 48000.0;
    r->outFreq = 1000.0;
    r->outAmp = 0.5;
    r->minFrames = 1L << 30;
    r->lastCurrent = r->lastDac = r->lastAdc = -1e300;
    pthread_mutex_init(&r->m, NULL);
    pthread_cond_init(&r->cv, NULL);
}

static void Unblock(Recorder *r)
{
    pthread_mutex_lock(&r->m);
    atomic_store(&r->blockNow, 0);
    pthread_cond_broadcast(&r->cv);
    pthread_mutex_unlock(&r->m);
}

static void Bad(Recorder *r, const char *what, double a, double b)
{
    if (atomic_fetch_add(&r->bad, 1) < 5)
        fprintf(stderr, "callback contract: %s (%.9f vs %.9f)\n", what, a, b);
}

static int Callback(const void *input, void *output, unsigned long frames, const PaStreamCallbackTimeInfo *ti,
                    PaStreamCallbackFlags flags, void *userData)
{
    Recorder *r = (Recorder *)userData;
    unsigned long i;
    int c;
    atomic_fetch_add(&r->callbacks, 1);
    if (r->firstCallbackTime == 0.0)
        r->firstCallbackTime = Now();
    if ((long)frames < r->minFrames)
        r->minFrames = (long)frames;
    if ((long)frames > r->maxFrames)
        r->maxFrames = (long)frames;
    atomic_fetch_or(&r->flagsSeen, (unsigned long)flags);
    if (flags & paInputUnderflow)
        atomic_fetch_add(&r->underflowCallbacks, 1);
    if (flags & paInputOverflow)
        atomic_fetch_add(&r->overflowCallbacks, 1);

    /* buffers and timeInfo contract */
    if ((r->inChannels > 0) != (input != NULL))
        Bad(r, "input buffer presence", r->inChannels, input != NULL);
    if ((r->outChannels > 0) != (output != NULL))
        Bad(r, "output buffer presence", r->outChannels, output != NULL);
    if (frames == 0 || frames > 2048)
        Bad(r, "framesPerBuffer range", (double)frames, 2048);
    if (ti->currentTime < r->lastCurrent)
        Bad(r, "currentTime went backwards", ti->currentTime, r->lastCurrent);
    if (fabs(ti->currentTime - Now()) > 0.2)
        Bad(r, "currentTime is not CLOCK_MONOTONIC now", ti->currentTime, Now());
    r->lastCurrent = ti->currentTime;
    if (output) {
        if (ti->outputBufferDacTime < r->lastDac)
            Bad(r, "outputBufferDacTime went backwards", ti->outputBufferDacTime, r->lastDac);
        if (ti->outputBufferDacTime < ti->currentTime - 0.005)
            Bad(r, "outputBufferDacTime in the past", ti->outputBufferDacTime, ti->currentTime);
        r->lastDac = ti->outputBufferDacTime;
    }
    if (input) {
        if (ti->inputBufferAdcTime < r->lastAdc)
            Bad(r, "inputBufferAdcTime went backwards", ti->inputBufferAdcTime, r->lastAdc);
        if (ti->inputBufferAdcTime > ti->currentTime + 0.005)
            Bad(r, "inputBufferAdcTime in the future", ti->inputBufferAdcTime, ti->currentTime);
        r->lastAdc = ti->inputBufferAdcTime;
    }
    if (input && output && r->nOffsets < (int)(sizeof r->offsets / sizeof r->offsets[0]))
        r->offsets[r->nOffsets++] = ti->outputBufferDacTime - ti->inputBufferAdcTime;

    if (input) {
        for (i = 0; i < frames; ++i) {
            for (c = 0; c < r->inChannels && c < 2; ++c) {
                double v;
                if (r->inFormat == paInt16)
                    v = (double)((const int16_t *)input)[i * (unsigned long)r->inChannels + (unsigned long)c] / 32768.0;
                else
                    v = (double)((const float *)input)[i * (unsigned long)r->inChannels + (unsigned long)c];
                r->inSumSq[c] += v * v;
                if (fabs(v) > r->inPeak[c])
                    r->inPeak[c] = fabs(v);
                if (c == 0 && r->impulseEvery > 0 && v > 0.5 && r->nImpulsesIn < MAX_IMPULSES)
                    r->impulsesIn[r->nImpulsesIn++] = r->framesSoFar + (long)i;
            }
        }
        r->inFrames += (long)frames;
    }
    if (output) {
        float *out = (float *)output;
        for (i = 0; i < frames; ++i) {
            const long n = r->framesSoFar + (long)i;
            float v;
            if (r->impulseEvery > 0) {
                v = (n % r->impulseEvery) == 100 ? 0.9f : 0.0f;
                if (v != 0.0f && r->nImpulsesOut < MAX_IMPULSES)
                    r->impulsesOut[r->nImpulsesOut++] = n;
            } else {
                v = (float)(r->outAmp * sin(2.0 * M_PI * r->outFreq * (double)n / r->rate));
            }
            for (c = 0; c < r->outChannels; ++c)
                out[i * (unsigned long)r->outChannels + (unsigned long)c] = v;
        }
    }
    r->framesSoFar += (long)frames;
    atomic_fetch_add(&r->frames, (long)frames);
    if (atomic_load(&r->blockNow)) { /* like AudioIO's CallbackDoSeek waiting for a mutex */
        pthread_mutex_lock(&r->m);
        atomic_store(&r->blocked, 1);
        while (atomic_load(&r->blockNow))
            pthread_cond_wait(&r->cv, &r->m);
        atomic_store(&r->blocked, 0);
        pthread_mutex_unlock(&r->m);
    }
    if (r->completeAfterFrames > 0 && r->framesSoFar >= r->completeAfterFrames)
        return paComplete;
    if (r->abortAfterFrames > 0 && r->framesSoFar >= r->abortAfterFrames)
        return paAbort;
    return paContinue;
}

static void Finished(void *userData)
{
    atomic_fetch_add(&((Recorder *)userData)->finishedCalls, 1);
}

static double InputRms(const Recorder *r, int c)
{
    return r->inFrames > 0 ? sqrt(r->inSumSq[c] / (double)r->inFrames) : 0.0;
}

static double MedianOffset(Recorder *r)
{
    int i, j, n = r->nOffsets;
    for (i = 1; i < n; ++i) {
        const double x = r->offsets[i];
        for (j = i - 1; j >= 0 && r->offsets[j] > x; --j)
            r->offsets[j + 1] = r->offsets[j];
        r->offsets[j + 1] = x;
    }
    return n ? r->offsets[n / 2] : -1.0;
}

static PaError Open(PaStream **s, Recorder *r, PaDeviceIndex inDev, PaDeviceIndex outDev, double latency)
{
    PaStreamParameters in, out;
    memset(&in, 0, sizeof in);
    memset(&out, 0, sizeof out);
    in.device = inDev == paNoDevice ? Pa_GetDefaultInputDevice() : inDev;
    in.channelCount = r->inChannels;
    in.sampleFormat = r->inFormat;
    in.suggestedLatency = latency;
    out.device = outDev == paNoDevice ? Pa_GetDefaultOutputDevice() : outDev;
    out.channelCount = r->outChannels;
    out.sampleFormat = paFloat32;
    out.suggestedLatency = latency;
    return Pa_OpenStream(s, r->inChannels ? &in : NULL, r->outChannels ? &out : NULL, r->rate,
                         paFramesPerBufferUnspecified, paNoFlag, Callback, r);
}

static void CheckNoMisuse(void)
{
    if (PaNull_GetMisuseCount() != 0)
        fprintf(stderr, "last AAudio misuse: %s\n", PaNull_GetLastMisuse());
    CHECK(PaNull_GetMisuseCount() == 0);
}

/* ------------------------------------------------------------------------ */
/* Test cases                                                                */
/* ------------------------------------------------------------------------ */

static void TestStatsBeforeStart(void)
{
    PaAAudioStreamStats st;
    memset(&st, 0xff, sizeof st);
    CHECK(PaAAudio_GetActiveStreamStats(&st) == 0);
    CHECK(st.valid == 0);
    CHECK(PaAAudio_GetActiveStreamStats(NULL) == 0);
    CHECK(strcmp(PaAAudio_GetLastErrorText(), "") == 0);
}

static void TestEnumerate(void)
{
    const PaHostApiInfo *hi;
    const PaDeviceInfo *d0, *d1;
    CHECK_PA(Pa_Initialize());
    CHECK(Pa_GetHostApiCount() == 1);
    CHECK(Pa_GetDefaultHostApi() == 0);
    CHECK(Pa_HostApiTypeIdToHostApiIndex(paInDevelopment) == 0);
    hi = Pa_GetHostApiInfo(0);
    CHECK(hi != NULL);
    if (!hi)
        return;
    CHECK(strcmp(hi->name, PA_NULL_HOST_API_NAME) == 0);
    CHECK(hi->type == paInDevelopment);
    CHECK(hi->deviceCount == 2);
    CHECK(hi->defaultOutputDevice == 0);
    CHECK(hi->defaultInputDevice == 1);
    CHECK(Pa_GetDeviceCount() == 2);
    CHECK(Pa_GetDefaultOutputDevice() == 0);
    CHECK(Pa_GetDefaultInputDevice() == 1);
    CHECK(Pa_HostApiDeviceIndexToDeviceIndex(0, 1) == 1);
    d0 = Pa_GetDeviceInfo(0);
    d1 = Pa_GetDeviceInfo(1);
    CHECK(d0 && d1);
    if (!d0 || !d1)
        return;
    CHECK(strcmp(d0->name, PA_AAUDIO_DEFAULT_OUTPUT_NAME) == 0);
    CHECK(d0->maxOutputChannels == 2 && d0->maxInputChannels == 0);
    CHECK(d0->hostApi == 0);
    CHECK_NEAR(d0->defaultSampleRate, 48000.0, 0.0); /* probed from the simulated device */
    CHECK_NEAR(d0->defaultLowOutputLatency, 2.0 * 480 / 48000.0, 1e-12);
    CHECK_NEAR(d0->defaultHighOutputLatency, 0.1, 1e-12);
    CHECK(strcmp(d1->name, PA_AAUDIO_DEFAULT_INPUT_NAME) == 0);
    CHECK(d1->maxInputChannels == 2 && d1->maxOutputChannels == 0);
    CHECK_NEAR(d1->defaultHighInputLatency, 0.04, 1e-12);
    CHECK(Pa_GetDeviceInfo(2) == NULL);
    CHECK(PaNull_GetOpenStreamCount() == 0); /* the probe stream was closed */
    CHECK_PA(Pa_Terminate());
    /* re-initialization (device rescan) works */
    CHECK_PA(Pa_Initialize());
    CHECK(Pa_GetDeviceCount() == 2);
    CHECK_PA(Pa_Terminate());
    CheckNoMisuse();
}

static void TestRates(void)
{
    PaStreamParameters out = { 0, 1, paFloat32, 0.1, NULL };
    PaStreamParameters in = { 1, 1, paFloat32, 0.1, NULL };
    PaAAudioOptions o;
    PaNullConfig cfg;
    PaStream *s = NULL;
    Recorder r;
    PaAAudioStreamStats st;

    CHECK_PA(Pa_Initialize());
    CHECK(Pa_IsFormatSupported(NULL, &out, 48000.0) == paFormatIsSupported);
    CHECK(Pa_IsFormatSupported(NULL, &out, 44100.0) == paInvalidSampleRate);
    CHECK(Pa_IsFormatSupported(&in, NULL, 48000.0) == paFormatIsSupported);
    CHECK(Pa_IsFormatSupported(&in, NULL, 44100.0) == paInvalidSampleRate);
    CHECK(Pa_IsFormatSupported(&in, &out, 48000.0) == paFormatIsSupported);
    CHECK(Pa_IsFormatSupported(NULL, &out, 4000.0) == paInvalidSampleRate);
    CHECK(Pa_IsFormatSupported(NULL, &out, 44100.5) == paInvalidSampleRate);
    out.channelCount = 3;
    CHECK(Pa_IsFormatSupported(NULL, &out, 48000.0) == paInvalidChannelCount);
    out.channelCount = 1;
    in.sampleFormat = paInt16;
    CHECK(Pa_IsFormatSupported(&in, NULL, 48000.0) == paFormatIsSupported);
    in.sampleFormat = paFloat32;

    /* acceptAnyRate */
    PaAAudio_GetDefaultOptions(&o);
    o.acceptAnyRate = 1;
    PaAAudio_SetOptions(&o);
    CHECK(Pa_IsFormatSupported(NULL, &out, 44100.0) == paFormatIsSupported);
    CHECK(Pa_IsFormatSupported(&in, NULL, 22050.0) == paFormatIsSupported);
    PaAAudio_SetOptions(NULL);
    CHECK(Pa_IsFormatSupported(NULL, &out, 44100.0) == paInvalidSampleRate);

    /* OpenStream is lenient: a non-native rate opens (AAudio resamples) */
    RecorderInit(&r, 0, 2);
    r.rate = 44100.0;
    CHECK_PA(Open(&s, &r, paNoDevice, paNoDevice, 0.05));
    if (s) {
        CHECK_NEAR(Pa_GetStreamInfo(s)->sampleRate, 44100.0, 0.0);
        CHECK_PA(Pa_StartStream(s));
        SleepMs(200);
        CHECK(PaAAudio_GetActiveStreamStats(&st) == 1);
        CHECK_NEAR(st.sampleRate, 44100.0, 0.0);
        CHECK_PA(Pa_StopStream(s));
        CHECK(atomic_load(&r.callbacks) > 5);
        CHECK_PA(Pa_CloseStream(s));
    }
    CHECK_PA(Pa_Terminate());

    /* the default devices follow the simulated device's native rate (probe) ... */
    PaNull_GetConfig(&cfg);
    cfg.sampleRate = 44100.0;
    cfg.framesPerBurst = 441;
    PaNull_SetConfig(&cfg);
    CHECK_PA(Pa_Initialize());
    CHECK_NEAR(Pa_GetDeviceInfo(0)->defaultSampleRate, 44100.0, 0.0);
    CHECK_NEAR(Pa_GetDeviceInfo(1)->defaultSampleRate, 44100.0, 0.0);
    CHECK(Pa_IsFormatSupported(NULL, &out, 44100.0) == paFormatIsSupported);
    CHECK(Pa_IsFormatSupported(NULL, &out, 48000.0) == paInvalidSampleRate);
    CHECK_PA(Pa_Terminate());
    /* ... unless Java supplied them */
    PaAAudio_SetDefaults(96000.0, 256);
    CHECK_PA(Pa_Initialize());
    CHECK_NEAR(Pa_GetDeviceInfo(0)->defaultSampleRate, 96000.0, 0.0);
    CHECK_NEAR(Pa_GetDeviceInfo(0)->defaultLowOutputLatency, 2.0 * 256 / 96000.0, 1e-12);
    CHECK_PA(Pa_Terminate());
    PaAAudio_SetDefaults(0, 0);
    CHECK_PA(Pa_Initialize());
    CHECK_NEAR(Pa_GetDeviceInfo(0)->defaultSampleRate, 44100.0, 0.0);
    CHECK_PA(Pa_Terminate());
    CheckNoMisuse();
}

static void TestDevices(void)
{
    PaAAudioDeviceDesc devs[5];
    const PaDeviceInfo *d;
    PaStream *s = NULL;
    Recorder r;
    PaAAudioStreamStats st;
    memset(devs, 0, sizeof devs);
    devs[0].name = "USB: Interface";
    devs[0].aaudioDeviceId = 7;
    devs[0].maxInputChannels = 2;
    devs[0].maxOutputChannels = 2;
    devs[0].nativeSampleRate = 44100.0;
    devs[0].framesPerBurst = 256;
    devs[1].name = "USB: Interface"; /* duplicate name: skipped */
    devs[1].aaudioDeviceId = 8;
    devs[1].maxOutputChannels = 2;
    devs[2].name = "Telephony: nothing"; /* no channels: skipped */
    devs[3].name = PA_AAUDIO_DEFAULT_INPUT_NAME; /* clashes with a default device: skipped */
    devs[3].maxInputChannels = 1;
    devs[4].name = "Bluetooth: Headphones";
    devs[4].aaudioDeviceId = 9;
    devs[4].maxOutputChannels = 2;

    CHECK(PaAAudio_SetDeviceList(NULL, 3) == paInvalidDevice);
    CHECK(PaAAudio_SetDeviceList(devs, -1) == paInvalidDevice);
    CHECK_PA(PaAAudio_SetDevices(devs, 5));
    devs[0].name = "changed after the call"; /* the list was copied */
    CHECK_PA(Pa_Initialize());
    CHECK(Pa_GetDeviceCount() == 4);
    CHECK(Pa_GetDefaultOutputDevice() == 0 && Pa_GetDefaultInputDevice() == 1);
    d = Pa_GetDeviceInfo(2);
    CHECK(d && strcmp(d->name, "USB: Interface") == 0);
    if (d) {
        CHECK(d->maxInputChannels == 2 && d->maxOutputChannels == 2);
        CHECK_NEAR(d->defaultSampleRate, 44100.0, 0.0);
        CHECK_NEAR(d->defaultLowInputLatency, 2.0 * 256 / 44100.0, 1e-12);
    }
    d = Pa_GetDeviceInfo(3);
    CHECK(d && strcmp(d->name, "Bluetooth: Headphones") == 0);
    if (d) {
        CHECK(d->maxInputChannels == 0 && d->maxOutputChannels == 2);
        CHECK_NEAR(d->defaultSampleRate, 48000.0, 0.0); /* default rate */
    }
    /* open on the specific devices: the AAudio device id is used */
    RecorderInit(&r, 1, 2);
    r.rate = 44100.0;
    CHECK_PA(Open(&s, &r, 2, 2, 0.05));
    if (s) {
        CHECK_PA(Pa_StartStream(s));
        SleepMs(100);
        CHECK(PaAAudio_GetActiveStreamStats(&st) == 1);
        CHECK(st.outputDeviceId == 7 && st.inputDeviceId == 7);
        CHECK_PA(Pa_AbortStream(s));
        CHECK_PA(Pa_CloseStream(s));
    }
    /* a device without inputs cannot record */
    {
        PaStreamParameters in = { 3, 1, paFloat32, 0.05, NULL };
        CHECK(Pa_IsFormatSupported(&in, NULL, 48000.0) == paInvalidChannelCount);
    }
    CHECK_PA(Pa_Terminate());
    CHECK_PA(PaAAudio_SetDeviceList(NULL, 0));
    CHECK_PA(Pa_Initialize());
    CHECK(Pa_GetDeviceCount() == 2);
    CHECK_PA(Pa_Terminate());
    CheckNoMisuse();
}

static void TestOutput(void)
{
    PaStream *s = NULL;
    Recorder r;
    PaNullOutputStats os;
    PaAAudioStreamStats st;
    const PaStreamInfo *info;
    double t0, t1;
    long after;

    RecorderInit(&r, 0, 2);
    CHECK_PA(Pa_Initialize());
    CHECK_PA(Open(&s, &r, paNoDevice, paNoDevice, 0.05));
    if (!s)
        return;
    info = Pa_GetStreamInfo(s);
    CHECK(info != NULL);
    if (info) {
        /* (bufferSize + burst) / rate, bufferSize = suggested latency (2400 frames) */
        CHECK_NEAR(info->outputLatency, (2400.0 + 480.0) / 48000.0, 1e-9);
        CHECK_NEAR(info->inputLatency, 0.0, 0.0);
        CHECK_NEAR(info->sampleRate, 48000.0, 0.0);
    }
    CHECK(Pa_IsStreamStopped(s) == 1);
    CHECK(Pa_IsStreamActive(s) == 0);
    CHECK_PA(Pa_SetStreamFinishedCallback(s, Finished));
    PaNull_GetOutputStats(&os, 1);
    t0 = Pa_GetStreamTime(s);
    CHECK_PA(Pa_StartStream(s));
    CHECK(Pa_IsStreamActive(s) == 1);
    CHECK(Pa_IsStreamStopped(s) == 0);
    CHECK(Pa_StartStream(s) == paStreamIsNotStopped);
    SleepMs(500);
    CHECK(PaAAudio_GetActiveStreamStats(&st) == 1);
    CHECK(st.running == 1 && st.active == 1 && st.hasOutput == 1 && st.hasInput == 0);
    CHECK(st.outputBurst == 480);
    CHECK(st.outputBufferSize == 2400);
    CHECK(st.outputBufferCapacity == 16 * 480);
    CHECK(st.outputFormat == 2 /* AAUDIO_FORMAT_PCM_FLOAT */);
    CHECK(st.outputDeviceId == 1001);
    CHECK(st.outputLatencySec > 0.03 && st.outputLatencySec < 0.09);
    CHECK(st.callbackCount > 30);
    CHECK(st.cpuLoad >= 0.0 && st.cpuLoad < 1.0);
    CHECK(Pa_GetStreamCpuLoad(s) >= 0.0);
    CHECK_PA(Pa_StopStream(s));
    t1 = Pa_GetStreamTime(s);
    CHECK(t1 - t0 >= 0.45);
    CHECK(Pa_IsStreamStopped(s) == 1);
    CHECK(Pa_IsStreamActive(s) == 0);
    CHECK(atomic_load(&r.finishedCalls) == 1);
    CHECK(atomic_load(&r.callbacks) >= 35);
    CHECK(atomic_load(&r.frames) >= (long)(0.7 * 24000) && atomic_load(&r.frames) <= (long)(1.3 * 24000));
    CHECK(r.maxFrames <= 2048 && r.minFrames >= 1);
    CHECK(atomic_load(&r.bad) == 0);
    PaNull_GetOutputStats(&os, 0);
    CHECK(os.channels == 2);
    CHECK(os.frames >= atomic_load(&r.frames) && os.frames <= atomic_load(&r.frames) + 4 * 480);
    CHECK_NEAR(os.rms[0], 0.5 / sqrt(2.0), 0.02);
    CHECK_NEAR(os.rms[1], 0.5 / sqrt(2.0), 0.02);
    CHECK_NEAR(os.peak[0], 0.5, 0.01);
    CHECK(PaAAudio_GetActiveStreamStats(&st) == 1);
    CHECK(st.running == 0 && st.active == 0);
    /* no callbacks after Stop; Stop/Abort of a stopped stream */
    after = atomic_load(&r.callbacks);
    SleepMs(100);
    CHECK(atomic_load(&r.callbacks) == after);
    CHECK(Pa_StopStream(s) == paStreamIsStopped);
    CHECK(Pa_AbortStream(s) == paStreamIsStopped);
    /* restart */
    CHECK_PA(Pa_StartStream(s));
    SleepMs(150);
    CHECK(atomic_load(&r.callbacks) > after);
    CHECK_PA(Pa_AbortStream(s));
    CHECK(atomic_load(&r.finishedCalls) == 2);
    CHECK(Pa_IsStreamStopped(s) == 1);
    CHECK(atomic_load(&r.bad) == 0);
    CHECK_PA(Pa_CloseStream(s));
    /* the memory stays valid >= 1 s with a cleared magic */
    CHECK(Pa_CloseStream(s) == paBadStreamPtr);
    CHECK(Pa_IsStreamActive(s) == paBadStreamPtr);
    CHECK(Pa_IsStreamStopped(s) == paBadStreamPtr);
    CHECK(PaAAudio_GetActiveStreamStats(&st) == 1 && st.running == 0);
    CHECK(PaNull_GetOpenStreamCount() == 0);
    CHECK_PA(Pa_Terminate());
    CheckNoMisuse();
}

static void RunInput(PaSampleFormat fmt)
{
    PaStream *s = NULL;
    Recorder r;
    PaAAudioStreamStats st;
    RecorderInit(&r, 1, 0);
    r.inFormat = fmt;
    CHECK_PA(Pa_Initialize());
    CHECK_PA(Open(&s, &r, paNoDevice, paNoDevice, 0.05));
    if (!s)
        return;
    CHECK(Pa_GetStreamInfo(s)->inputLatency > 0.0);
    CHECK_PA(Pa_StartStream(s));
    SleepMs(500);
    CHECK(PaAAudio_GetActiveStreamStats(&st) == 1);
    CHECK(st.hasInput == 1 && st.hasOutput == 0);
    CHECK(st.inputChannelsOpened == 1);
    CHECK(st.inputLatencySec >= 0.0 && st.inputLatencySec < 0.05);
    CHECK_PA(Pa_AbortStream(s));
    CHECK(atomic_load(&r.callbacks) >= 35);
    CHECK_NEAR(InputRms(&r, 0), 0.5 / sqrt(2.0), 0.01);
    CHECK_NEAR(r.inPeak[0], 0.5, 0.01);
    CHECK(atomic_load(&r.bad) == 0);
    CHECK((atomic_load(&r.flagsSeen) & (paInputOverflow | paInputUnderflow)) == 0);
    CHECK_PA(Pa_CloseStream(s));
    CHECK_PA(Pa_Terminate());
    CHECK(PaNull_GetOpenStreamCount() == 0);
    CheckNoMisuse();
}

static void TestInput(void)
{
    RunInput(paFloat32);
}

static void TestInputInt16(void)
{
    RunInput(paInt16);
}

static void TestDuplex(void)
{
    PaStream *s = NULL;
    Recorder r;
    PaAAudioStreamStats st;
    double start;
    RecorderInit(&r, 1, 2);
    CHECK_PA(Pa_Initialize());
    CHECK_PA(Open(&s, &r, paNoDevice, paNoDevice, 0.05));
    if (!s)
        return;
    start = Now();
    CHECK_PA(Pa_StartStream(s));
    SleepMs(800);
    CHECK(PaAAudio_GetActiveStreamStats(&st) == 1);
    CHECK_PA(Pa_StopStream(s));
    /* warm-up: no user callback for ~8 drain + 1 cushion + 12 discard callbacks */
    CHECK(r.firstCallbackTime - start > 0.1 && r.firstCallbackTime - start < 0.5);
    CHECK(atomic_load(&r.callbacks) >= 25);
    CHECK(atomic_load(&r.bad) == 0);
    CHECK_NEAR(InputRms(&r, 0), 0.5 / sqrt(2.0), 0.01);
    CHECK(atomic_load(&r.underflowCallbacks) <= 2);
    CHECK(st.hasInput == 1 && st.hasOutput == 1);
    CHECK(st.inputChannelsOpened == 1);
    CHECK(st.paddedInputFrames <= 2 * 480);
    CHECK(st.droppedInputFrames <= 2 * 480);
    CHECK(st.warmupTimedOut == 0);
    /* (output buffer 2400 + one input burst of cushion) / 48000 = 60 ms in the simulator */
    CHECK(st.duplexOffsetSec > 0.04 && st.duplexOffsetSec < 0.09);
    CHECK_NEAR(MedianOffset(&r), st.duplexOffsetSec, 0.002);
    CHECK_PA(Pa_CloseStream(s));
    CHECK_PA(Pa_Terminate());
    CHECK(PaNull_GetOpenStreamCount() == 0);
    CheckNoMisuse();
}

/* Round trip through the simulated air: input frame V hears output frame U with
   V - U = (outputBufferDacTime - inputBufferAdcTime) * rate + acoustic delay. */
static void TestLoopback(void)
{
    PaStream *s = NULL;
    Recorder r;
    PaNullConfig cfg;
    PaAAudioStreamStats st;
    int i, matched = 0;
    double expected;
    PaNull_GetConfig(&cfg);
    cfg.loopback = 1;
    cfg.loopbackDelayFrames = 100;
    PaNull_SetConfig(&cfg);
    RecorderInit(&r, 1, 2);
    r.impulseEvery = 4800;
    CHECK_PA(Pa_Initialize());
    CHECK_PA(Open(&s, &r, paNoDevice, paNoDevice, 0.05));
    if (!s)
        return;
    CHECK_PA(Pa_StartStream(s));
    SleepMs(1200);
    CHECK(PaAAudio_GetActiveStreamStats(&st) == 1);
    CHECK_PA(Pa_StopStream(s));
    expected = st.duplexOffsetSec * 48000.0 + 100.0;
    printf("loopback: %d impulses out, %d in, duplexOffset %.6f s, expected lag %.1f frames\n", r.nImpulsesOut,
           r.nImpulsesIn, st.duplexOffsetSec, expected);
    CHECK(r.nImpulsesIn >= 3);
    for (i = 0; i < r.nImpulsesIn; ++i) {
        const long v = r.impulsesIn[i];
        long u = -1;
        int j;
        for (j = 0; j < r.nImpulsesOut; ++j)
            if (r.impulsesOut[j] <= v)
                u = r.impulsesOut[j];
        if (u < 0)
            continue;
        printf("  impulse out %ld -> in %ld: lag %ld\n", u, v, v - u);
        CHECK_NEAR((double)(v - u), expected, 2.0);
        ++matched;
    }
    CHECK(matched >= 3);
    CHECK(atomic_load(&r.bad) == 0);
    CHECK_PA(Pa_CloseStream(s));
    CHECK_PA(Pa_Terminate());
    CheckNoMisuse();
}

static void TestComplete(void)
{
    PaStream *s = NULL;
    Recorder r;
    double t, start;
    long cb;
    RecorderInit(&r, 0, 2);
    r.completeAfterFrames = 9600; /* 0.2 s */
    CHECK_PA(Pa_Initialize());
    CHECK_PA(Open(&s, &r, paNoDevice, paNoDevice, 0.05));
    if (!s)
        return;
    CHECK_PA(Pa_SetStreamFinishedCallback(s, Finished));
    start = Now();
    CHECK_PA(Pa_StartStream(s));
    t = WaitFor(StreamInactive, s, 2.0);
    CHECK(t > 0.0);
    /* 0.2 s of audio + the queued buffer (2400 + 480 frames = 60 ms) */
    CHECK_NEAR(Now() - start, 0.26, 0.1);
    CHECK(Pa_IsStreamStopped(s) == 0); /* "callback finished": active 0, stopped 0 */
    CHECK(atomic_load(&r.finishedCalls) == 1);
    cb = atomic_load(&r.callbacks);
    SleepMs(50);
    CHECK(atomic_load(&r.callbacks) == cb); /* no user callbacks after paComplete */
    CHECK(r.framesSoFar >= 9600 && r.framesSoFar < 9600 + 2048);
    CHECK_PA(Pa_AbortStream(s)); /* what AudioIO::StopStream does */
    CHECK(Pa_IsStreamStopped(s) == 1);
    CHECK(atomic_load(&r.finishedCalls) == 1);
    CHECK_PA(Pa_CloseStream(s));
    CHECK_PA(Pa_Terminate());
    CheckNoMisuse();
}

static void TestAbortReturn(void)
{
    PaStream *s = NULL;
    Recorder r;
    long cb;
    RecorderInit(&r, 1, 0);
    r.abortAfterFrames = 4800;
    CHECK_PA(Pa_Initialize());
    CHECK_PA(Open(&s, &r, paNoDevice, paNoDevice, 0.05));
    if (!s)
        return;
    CHECK_PA(Pa_StartStream(s));
    CHECK(WaitFor(StreamInactive, s, 1.0) > 0.0);
    cb = atomic_load(&r.callbacks);
    SleepMs(60);
    CHECK(atomic_load(&r.callbacks) == cb);
    CHECK(Pa_IsStreamStopped(s) == 0);
    CHECK_PA(Pa_StopStream(s));
    CHECK_PA(Pa_CloseStream(s));
    /* duplex paComplete from the input side drains the output, too */
    RecorderInit(&r, 1, 2);
    r.completeAfterFrames = 4800;
    CHECK_PA(Open(&s, &r, paNoDevice, paNoDevice, 0.05));
    if (s) {
        CHECK_PA(Pa_StartStream(s));
        CHECK(WaitFor(StreamInactive, s, 2.0) > 0.0);
        CHECK_PA(Pa_AbortStream(s));
        CHECK_PA(Pa_CloseStream(s));
    }
    CHECK_PA(Pa_Terminate());
    CheckNoMisuse();
}

static void TestIdempotence(void)
{
    PaStream *s = NULL;
    Recorder r;
    long cb;
    CHECK_PA(Pa_Initialize());
    /* open + close without start */
    RecorderInit(&r, 1, 2);
    CHECK_PA(Open(&s, &r, paNoDevice, paNoDevice, 0.05));
    CHECK(PaNull_GetOpenStreamCount() == 2);
    CHECK(Pa_StopStream(s) == paStreamIsStopped);
    CHECK(Pa_AbortStream(s) == paStreamIsStopped);
    CHECK_PA(Pa_CloseStream(s));
    CHECK(PaNull_GetOpenStreamCount() == 0);
    /* close a running stream directly (pa_front aborts it first) */
    RecorderInit(&r, 0, 2);
    CHECK_PA(Open(&s, &r, paNoDevice, paNoDevice, 0.05));
    CHECK_PA(Pa_StartStream(s));
    SleepMs(100);
    CHECK_PA(Pa_CloseStream(s));
    cb = atomic_load(&r.callbacks);
    SleepMs(60);
    CHECK(atomic_load(&r.callbacks) == cb);
    CHECK(cb > 3);
    CHECK(PaNull_GetOpenStreamCount() == 0);
    /* abort twice, start twice */
    RecorderInit(&r, 1, 0);
    CHECK_PA(Open(&s, &r, paNoDevice, paNoDevice, 0.05));
    CHECK_PA(Pa_StartStream(s));
    CHECK(Pa_StartStream(s) == paStreamIsNotStopped);
    SleepMs(50);
    CHECK_PA(Pa_AbortStream(s));
    CHECK(Pa_AbortStream(s) == paStreamIsStopped);
    CHECK(Pa_StopStream(s) == paStreamIsStopped);
    CHECK_PA(Pa_CloseStream(s));
    /* many open/start/abort/close cycles */
    {
        int i;
        for (i = 0; i < 10; ++i) {
            RecorderInit(&r, i & 1, 2);
            CHECK_PA(Open(&s, &r, paNoDevice, paNoDevice, 0.02));
            if (!s)
                break;
            CHECK_PA(Pa_StartStream(s));
            SleepMs(20);
            CHECK_PA(Pa_AbortStream(s));
            CHECK_PA(Pa_CloseStream(s));
        }
    }
    CHECK(PaNull_GetOpenStreamCount() == 0);
    CHECK_PA(Pa_Terminate());
    CHECK(Pa_Terminate() == paNotInitialized);
    CHECK(Pa_GetDeviceCount() == paNotInitialized);
    CheckNoMisuse();
}

/* AudioIO::StopStream holds a mutex that the callback (CallbackDoSeek) may be
   waiting for: Abort/Close must return anyway; the reaper finishes later. */
static void TestBlockedCallback(void)
{
    PaStream *s = NULL;
    Recorder r;
    double t;
    long cb;
    RecorderInit(&r, 1, 2);
    CHECK_PA(Pa_Initialize());
    CHECK_PA(Open(&s, &r, paNoDevice, paNoDevice, 0.05));
    if (!s)
        return;
    CHECK_PA(Pa_StartStream(s));
    SleepMs(400); /* past the duplex warm-up */
    atomic_store(&r.blockNow, 1);
    {
        const double t0 = Now();
        while (!atomic_load(&r.blocked) && Now() - t0 < 1.0)
            SleepMs(1);
    }
    CHECK(atomic_load(&r.blocked) == 1);
    t = Now();
    CHECK_PA(Pa_AbortStream(s));
    t = Now() - t;
    printf("blocked callback: Pa_AbortStream returned after %.3f s\n", t);
    CHECK(t > 0.25 && t < 1.0);
    CHECK(Pa_IsStreamStopped(s) == 1 && Pa_IsStreamActive(s) == 0);
    t = Now();
    CHECK_PA(Pa_CloseStream(s));
    t = Now() - t;
    CHECK(t < 0.05);
    CHECK(PaNull_GetOpenStreamCount() == 2); /* AAudio close deferred to the reaper */
    cb = atomic_load(&r.callbacks);
    Unblock(&r);
    CHECK(WaitFor(NoOpenAAudioStreams, NULL, 2.0) >= 0.0);
    SleepMs(50);
    CHECK(atomic_load(&r.callbacks) == cb); /* no user callback after the blocked one */
    CHECK(atomic_load(&r.bad) == 0);
    CHECK_PA(Pa_Terminate());
    CheckNoMisuse();
}

static void RunDisconnect(int duplex)
{
    PaStream *s = NULL;
    Recorder r;
    PaAAudioStreamStats st;
    RecorderInit(&r, duplex ? 1 : 0, 2);
    PaAAudio_ClearLastError();
    CHECK_PA(Pa_Initialize());
    CHECK_PA(Open(&s, &r, paNoDevice, paNoDevice, 0.05));
    if (!s)
        return;
    CHECK_PA(Pa_SetStreamFinishedCallback(s, Finished));
    CHECK_PA(Pa_StartStream(s));
    SleepMs(400);
    CHECK(Pa_IsStreamActive(s) == 1);
    PaNull_SimulateDisconnect();
    CHECK(WaitFor(StreamInactive, s, 0.5) >= 0.0);
    CHECK(Pa_IsStreamStopped(s) == 0);
    SleepMs(50); /* both error callbacks (output and input) */
    CHECK(PaAAudio_GetActiveStreamStats(&st) == 1);
    CHECK(st.disconnected == 1);
    CHECK(st.lastAAudioError == T_AAUDIO_ERROR_DISCONNECTED);
    CHECK(st.active == 0 && st.running == 1);
    CHECK(strstr(PaAAudio_GetLastErrorText(), "DISCONNECTED") != NULL);
    CHECK(atomic_load(&r.finishedCalls) == 1);
    /* what the bridge does next: AudioIO::StopStream -> Abort + Close */
    CHECK_PA(Pa_AbortStream(s));
    CHECK_PA(Pa_CloseStream(s));
    CHECK(atomic_load(&r.finishedCalls) == 1);
    CHECK(WaitFor(NoOpenAAudioStreams, NULL, 1.0) >= 0.0);
    CHECK(PaAAudio_GetActiveStreamStats(&st) == 1 && st.disconnected == 1 && st.running == 0);
    /* a new stream opens on the "new route" */
    RecorderInit(&r, 0, 2);
    CHECK_PA(Open(&s, &r, paNoDevice, paNoDevice, 0.05));
    if (s) {
        CHECK_PA(Pa_StartStream(s));
        SleepMs(60);
        CHECK(PaAAudio_GetActiveStreamStats(&st) == 1 && st.disconnected == 0);
        CHECK_PA(Pa_AbortStream(s));
        CHECK_PA(Pa_CloseStream(s));
    }
    CHECK_PA(Pa_Terminate());
    CheckNoMisuse();
}

static void TestDisconnect(void)
{
    RunDisconnect(0);
}

static void TestDuplexDisconnect(void)
{
    RunDisconnect(1);
}

static void TestXRun(void)
{
    PaStream *s = NULL;
    Recorder r;
    PaAAudioStreamStats st;
    CHECK_PA(Pa_Initialize());
    /* output-only: underflow flag and buffer growth */
    RecorderInit(&r, 0, 2);
    CHECK_PA(Open(&s, &r, paNoDevice, paNoDevice, 0.05));
    if (s) {
        CHECK_PA(Pa_StartStream(s));
        SleepMs(200);
        PaNull_SimulateXRun(0, 0);
        SleepMs(150);
        CHECK(PaAAudio_GetActiveStreamStats(&st) == 1);
        CHECK(st.outputXRuns >= 1);
        CHECK(st.outputBufferSize >= 2400 + 480); /* grown by one burst */
        CHECK_PA(Pa_AbortStream(s));
        CHECK((atomic_load(&r.flagsSeen) & paOutputUnderflow) != 0);
        CHECK_PA(Pa_CloseStream(s));
    }
    /* duplex: input overflow with lost frames */
    RecorderInit(&r, 1, 2);
    CHECK_PA(Open(&s, &r, paNoDevice, paNoDevice, 0.05));
    if (s) {
        CHECK_PA(Pa_StartStream(s));
        SleepMs(500);
        PaNull_SimulateXRun(1, 480);
        SleepMs(150);
        CHECK(PaAAudio_GetActiveStreamStats(&st) == 1);
        CHECK(st.inputXRuns >= 1);
        CHECK(st.outputBufferSize == 2400); /* never resized in duplex (alignment) */
        CHECK_PA(Pa_AbortStream(s));
        CHECK((atomic_load(&r.flagsSeen) & paInputOverflow) != 0);
        CHECK(Pa_IsStreamStopped(s) == 1);
        CHECK_PA(Pa_CloseStream(s));
    }
    /* input-only */
    RecorderInit(&r, 1, 0);
    CHECK_PA(Open(&s, &r, paNoDevice, paNoDevice, 0.05));
    if (s) {
        CHECK_PA(Pa_StartStream(s));
        SleepMs(100);
        PaNull_SimulateXRun(1, 480);
        SleepMs(100);
        CHECK_PA(Pa_AbortStream(s));
        CHECK((atomic_load(&r.flagsSeen) & paInputOverflow) != 0);
        CHECK_PA(Pa_CloseStream(s));
    }
    CHECK_PA(Pa_Terminate());
    CheckNoMisuse();
}

static void TestOpenErrors(void)
{
    PaStream *s = NULL;
    Recorder r;
    PaAAudioStreamStats st;
    const PaHostErrorInfo *hei;
    PaStreamParameters out = { 0, 2, paFloat32, 0.05, NULL };
    CHECK_PA(Pa_Initialize());

    /* no fallback helps: mapped PortAudio errors */
    RecorderInit(&r, 0, 2);
    PaNull_FailNextOpen(T_AAUDIO_ERROR_DISCONNECTED, 0);
    CHECK(Open(&s, &r, paNoDevice, paNoDevice, 0.05) == paDeviceUnavailable);
    CHECK(strstr(PaAAudio_GetLastErrorText(), "AAUDIO_ERROR_DISCONNECTED") != NULL);
    PaNull_FailNextOpen(T_AAUDIO_ERROR_INVALID_RATE, -1);
    CHECK(Open(&s, &r, paNoDevice, paNoDevice, 0.05) == paInvalidSampleRate);
    CHECK(PaNull_GetOpenStreamCount() == 0);

    /* the input fails after the output was opened: the output stream is closed again */
    RecorderInit(&r, 1, 2);
    PaNull_FailNextOpen(T_AAUDIO_ERROR_DISCONNECTED, 1);
    CHECK(Open(&s, &r, paNoDevice, paNoDevice, 0.05) == paDeviceUnavailable);
    CHECK(PaNull_GetOpenStreamCount() == 0);

    /* a host error: the float attempt fails, the int16 fallback opens */
    RecorderInit(&r, 0, 2);
    PaNull_FailNextOpen(T_AAUDIO_ERROR_INTERNAL, 0);
    CHECK_PA(Open(&s, &r, paNoDevice, paNoDevice, 0.05));
    if (s) {
        CHECK_PA(Pa_StartStream(s));
        SleepMs(100);
        CHECK(PaAAudio_GetActiveStreamStats(&st) == 1);
        CHECK(st.outputFormat == T_AAUDIO_FORMAT_PCM_I16);
        CHECK_PA(Pa_AbortStream(s));
        CHECK(atomic_load(&r.callbacks) > 3 && atomic_load(&r.bad) == 0);
        CHECK_PA(Pa_CloseStream(s));
    }

    /* input channel-count fallback */
    RecorderInit(&r, 2, 0);
    PaNull_FailNextOpen(T_AAUDIO_ERROR_OUT_OF_RANGE, 1);
    CHECK_PA(Open(&s, &r, paNoDevice, paNoDevice, 0.05));
    if (s)
        CHECK_PA(Pa_CloseStream(s));

    /* Pa_StartStream failure is reported (stream disconnected before start) */
    RecorderInit(&r, 0, 2);
    CHECK_PA(Open(&s, &r, paNoDevice, paNoDevice, 0.05));
    if (s) {
        PaNull_SimulateDisconnect();
        SleepMs(20);
        CHECK(Pa_StartStream(s) == paDeviceUnavailable);
        CHECK(Pa_IsStreamStopped(s) == 1 && Pa_IsStreamActive(s) == 0);
        CHECK_PA(Pa_CloseStream(s));
    }

    /* parameter validation */
    CHECK(Pa_OpenStream(&s, NULL, &out, 4000.0, 0, paNoFlag, Callback, &r) == paInvalidSampleRate);
    out.channelCount = 3;
    CHECK(Pa_OpenStream(&s, NULL, &out, 48000.0, 0, paNoFlag, Callback, &r) == paInvalidChannelCount);
    out.channelCount = 2;
    CHECK(Pa_OpenStream(&s, NULL, &out, 48000.0, 0, paPlatformSpecificFlags, Callback, &r) == paInvalidFlag);
    CHECK(Pa_OpenStream(&s, NULL, &out, 48000.0, 0, paNoFlag, NULL, NULL) != paNoError); /* blocking I/O */
    CHECK(strstr(PaAAudio_GetLastErrorText(), "blocking") != NULL);
    hei = Pa_GetLastHostErrorInfo();
    CHECK(hei != NULL);
    CHECK(PaNull_GetOpenStreamCount() == 0);
    CHECK_PA(Pa_Terminate());
    CheckNoMisuse();
}

static void TestMonoMic(void)
{
    PaStream *s = NULL;
    Recorder r;
    PaNullConfig cfg;
    PaAAudioStreamStats st;
    PaNull_GetConfig(&cfg);
    cfg.maxInputChannels = 1;
    PaNull_SetConfig(&cfg);
    CHECK_PA(Pa_Initialize());
    /* input-only */
    RecorderInit(&r, 2, 0);
    CHECK_PA(Open(&s, &r, paNoDevice, paNoDevice, 0.05));
    if (s) {
        CHECK_PA(Pa_StartStream(s));
        SleepMs(300);
        CHECK(PaAAudio_GetActiveStreamStats(&st) == 1);
        CHECK(st.inputChannelsOpened == 1);
        CHECK_PA(Pa_AbortStream(s));
        CHECK_NEAR(InputRms(&r, 0), 0.5 / sqrt(2.0), 0.01);
        CHECK_NEAR(InputRms(&r, 1), 0.5 / sqrt(2.0), 0.01);
        CHECK_PA(Pa_CloseStream(s));
    }
    /* duplex */
    RecorderInit(&r, 2, 2);
    CHECK_PA(Open(&s, &r, paNoDevice, paNoDevice, 0.05));
    if (s) {
        CHECK_PA(Pa_StartStream(s));
        SleepMs(600);
        CHECK_PA(Pa_AbortStream(s));
        CHECK(atomic_load(&r.callbacks) > 10);
        CHECK_NEAR(InputRms(&r, 0), 0.5 / sqrt(2.0), 0.02);
        CHECK_NEAR(InputRms(&r, 1), 0.5 / sqrt(2.0), 0.02);
        CHECK_PA(Pa_CloseStream(s));
    }
    CHECK_PA(Pa_Terminate());
    CheckNoMisuse();
}

static void TestWarmupTimeout(void)
{
    PaStream *s = NULL;
    Recorder r;
    PaAAudioOptions o;
    PaAAudioStreamStats st;
    double t;
    PaAAudio_GetDefaultOptions(&o);
    o.warmupTimeoutMs = 300;
    PaAAudio_SetOptions(&o);
    PaNull_SetInputStalled(1);
    CHECK_PA(Pa_Initialize());
    RecorderInit(&r, 1, 2);
    CHECK_PA(Open(&s, &r, paNoDevice, paNoDevice, 0.05));
    if (!s)
        return;
    CHECK_PA(Pa_StartStream(s));
    t = WaitFor(StreamInactive, s, 2.0);
    CHECK(t > 0.25 && t < 1.0);
    CHECK(PaAAudio_GetActiveStreamStats(&st) == 1);
    CHECK(st.warmupTimedOut == 1);
    CHECK(st.lastAAudioError == T_AAUDIO_ERROR_TIMEOUT);
    CHECK(atomic_load(&r.callbacks) == 0);
    CHECK_PA(Pa_AbortStream(s));
    CHECK_PA(Pa_CloseStream(s));
    /* input comes back: a new stream works */
    PaNull_SetInputStalled(0);
    RecorderInit(&r, 1, 2);
    CHECK_PA(Open(&s, &r, paNoDevice, paNoDevice, 0.05));
    if (s) {
        CHECK_PA(Pa_StartStream(s));
        SleepMs(500);
        CHECK(Pa_IsStreamActive(s) == 1);
        CHECK(atomic_load(&r.callbacks) > 5);
        CHECK_PA(Pa_AbortStream(s));
        CHECK_PA(Pa_CloseStream(s));
    }
    CHECK_PA(Pa_Terminate());
    PaAAudio_SetOptions(NULL);
    CheckNoMisuse();
}

static void RunDrift(double ppm, int expectPads)
{
    PaStream *s = NULL;
    Recorder r;
    PaNullConfig cfg;
    PaAAudioStreamStats st;
    PaNull_GetConfig(&cfg);
    cfg.inputDriftPpm = ppm;
    PaNull_SetConfig(&cfg);
    RecorderInit(&r, 1, 2);
    CHECK_PA(Open(&s, &r, paNoDevice, paNoDevice, 0.05));
    if (!s)
        return;
    CHECK_PA(Pa_StartStream(s));
    SleepMs(1500);
    CHECK(PaAAudio_GetActiveStreamStats(&st) == 1);
    CHECK(Pa_IsStreamActive(s) == 1);
    CHECK_PA(Pa_AbortStream(s));
    printf("drift %+.0f ppm: padded %lld dropped %lld inputLatency %.4f duplexOffset %.4f\n", ppm,
           (long long)st.paddedInputFrames, (long long)st.droppedInputFrames, st.inputLatencySec,
           st.duplexOffsetSec);
    if (expectPads)
        CHECK(st.paddedInputFrames > 0);
    else
        CHECK(st.droppedInputFrames > 0);
    CHECK(st.inputLatencySec < 0.15); /* bounded, no growing backlog */
    CHECK(atomic_load(&r.bad) == 0);
    CHECK_PA(Pa_CloseStream(s));
}

static void TestDrift(void)
{
    CHECK_PA(Pa_Initialize());
    RunDrift(-50000.0, 1); /* input 5 % slow: zero padding */
    RunDrift(+50000.0, 0); /* input 5 % fast: frames dropped to bound latency */
    CHECK_PA(Pa_Terminate());
    CheckNoMisuse();
}

static void TestGain(void)
{
    PaStream *s = NULL;
    Recorder r;
    CHECK_PA(Pa_Initialize());
    PaAAudio_SetInputGain(2.0f);
    RecorderInit(&r, 1, 0);
    CHECK_PA(Open(&s, &r, paNoDevice, paNoDevice, 0.05));
    if (s) {
        CHECK_PA(Pa_StartStream(s));
        SleepMs(300);
        CHECK_PA(Pa_AbortStream(s));
        CHECK_NEAR(InputRms(&r, 0), 1.0 / sqrt(2.0), 0.02);
        CHECK_PA(Pa_CloseStream(s));
    }
    /* int16 input path */
    RecorderInit(&r, 1, 0);
    r.inFormat = paInt16;
    PaAAudio_SetInputGain(0.5f);
    CHECK_PA(Open(&s, &r, paNoDevice, paNoDevice, 0.05));
    if (s) {
        CHECK_PA(Pa_StartStream(s));
        SleepMs(300);
        CHECK_PA(Pa_AbortStream(s));
        CHECK_NEAR(InputRms(&r, 0), 0.25 / sqrt(2.0), 0.01);
        CHECK_PA(Pa_CloseStream(s));
    }
    PaAAudio_SetInputGain(1.0f);
    CHECK_PA(Pa_Terminate());
    CheckNoMisuse();
}

static void TestTerminateOpen(void)
{
    PaStream *s = NULL;
    Recorder r;
    RecorderInit(&r, 1, 2);
    CHECK_PA(Pa_Initialize());
    CHECK_PA(Open(&s, &r, paNoDevice, paNoDevice, 0.05));
    CHECK_PA(Pa_StartStream(s));
    SleepMs(100);
    CHECK_PA(Pa_Terminate()); /* closes the running stream */
    CHECK(PaNull_GetOpenStreamCount() == 0);
    CHECK_PA(Pa_Initialize());
    CHECK(Pa_GetDeviceCount() == 2);
    CHECK_PA(Pa_Terminate());
    CheckNoMisuse();
}

/* ------------------------------------------------------------------------ */

typedef struct {
    const char *name;
    void (*fn)(void);
} TestCase;

static const TestCase kTests[] = {
    { "stats_before_start", TestStatsBeforeStart },
    { "enumerate", TestEnumerate },
    { "rates", TestRates },
    { "devices", TestDevices },
    { "output", TestOutput },
    { "input", TestInput },
    { "input_int16", TestInputInt16 },
    { "duplex", TestDuplex },
    { "loopback", TestLoopback },
    { "complete", TestComplete },
    { "abort_return", TestAbortReturn },
    { "idempotence", TestIdempotence },
    { "blocked_callback", TestBlockedCallback },
    { "disconnect", TestDisconnect },
    { "duplex_disconnect", TestDuplexDisconnect },
    { "xrun", TestXRun },
    { "open_errors", TestOpenErrors },
    { "mono_mic", TestMonoMic },
    { "warmup_timeout", TestWarmupTimeout },
    { "drift", TestDrift },
    { "gain", TestGain },
    { "terminate_open", TestTerminateOpen },
};

int main(int argc, char **argv)
{
    size_t i;
    int ran = 0;
    const char *which = argc > 1 ? argv[1] : "all";
    setvbuf(stdout, NULL, _IONBF, 0);
    for (i = 0; i < sizeof kTests / sizeof kTests[0]; ++i) {
        if (strcmp(which, "all") && strcmp(which, kTests[i].name))
            continue;
        printf("== %s\n", kTests[i].name);
        if (!strcmp(which, "all")) {
            /* fresh simulator configuration per case */
            PaNull_SetConfig(NULL);
            PaNull_SetInputStalled(0);
            PaAAudio_SetOptions(NULL);
            PaAAudio_SetDefaults(0, 0);
            PaAAudio_SetDeviceList(NULL, 0);
        }
        kTests[i].fn();
        ++ran;
    }
    if (!ran) {
        fprintf(stderr, "unknown test case '%s'\n", which);
        return 2;
    }
    printf("%d checks, %d failures\n", g_checks, g_failures);
    return g_failures ? 1 : 0;
}
