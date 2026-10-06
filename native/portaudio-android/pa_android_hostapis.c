/*
 * PortAudio host-API table for Android (Audacity Android port).
 *
 * Replaces src/os/unix/pa_unix_hostapis.c on Android: none of the desktop
 * Unix back ends (ALSA, OSS, JACK, PulseAudio, sndio) exist there.
 *
 * The AAudio host API is compiled in when the CMake option PA_USE_AAUDIO is
 * ON (it defines PA_USE_AAUDIO=1 and adds the sources listed in
 * native/portaudio-android/sources.cmake).  Its entry point must be
 *
 *    PaError PaAAudio_Initialize(PaUtilHostApiRepresentation **hostApi,
 *                                PaHostApiIndex index);
 *
 * With no host API compiled in, Pa_Initialize() still succeeds and reports
 * zero devices, which is what the host unit-test build and an Android build
 * without audio I/O need.
 *
 * Licence: same as PortAudio (MIT-style), see PortAudio's LICENSE.txt.
 */

#include "pa_hostapi.h"

PaError PaAAudio_Initialize( PaUtilHostApiRepresentation **hostApi, PaHostApiIndex index );
PaError PaSkeleton_Initialize( PaUtilHostApiRepresentation **hostApi, PaHostApiIndex index );

PaUtilHostApiInitializer *paHostApiInitializers[] =
    {
#if PA_USE_AAUDIO
        PaAAudio_Initialize,
#endif

#if PA_USE_SKELETON
        PaSkeleton_Initialize,
#endif

        0   /* NULL terminated array */
    };
