/*
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * PortAudio host-API table for the Audacity Android port.
 *
 * Replaces src/os/unix/pa_unix_hostapis.c:
 *  - on Android, where none of the desktop Unix back ends (ALSA, OSS, JACK,
 *    PulseAudio, sndio) exist;
 *  - on the Linux host build when PA_USE_NULL is ON, where the same AAudio
 *    host API runs on a simulated device and is registered as "Null".
 *
 * PA_USE_AAUDIO=1 (set by native/cmake/deps/portaudio/CMakeLists.txt) adds
 * PaAAudio_Initialize from src/pa_aaudio.c.  It is the first entry, so it is
 * the default host API, and its devices "Default Output"/"Default Input" are
 * the default devices.  With no host API compiled in, Pa_Initialize() still
 * succeeds and reports zero devices.
 */

#include "pa_hostapi.h"

PaError PaAAudio_Initialize(PaUtilHostApiRepresentation **hostApi, PaHostApiIndex index);
PaError PaSkeleton_Initialize(PaUtilHostApiRepresentation **hostApi, PaHostApiIndex index);

PaUtilHostApiInitializer *paHostApiInitializers[] = {
#if defined(PA_USE_AAUDIO) && PA_USE_AAUDIO
    PaAAudio_Initialize,
#endif

#if PA_USE_SKELETON
    PaSkeleton_Initialize,
#endif

    0 /* NULL terminated array */
};
