/*
 * Audacity Android port: force-included into libnyquist on Android.
 * Nyquist's sys/unix/switches.h relies on glibc's <sys/types.h> for the BSD
 * type names ulong and ushort (it only typedefs them itself on macOS and the
 * BSDs).  Bionic does not provide them.
 */
#ifndef AUDACITY_PORT_NYQUIST_ANDROID_COMPAT_H
#define AUDACITY_PORT_NYQUIST_ANDROID_COMPAT_H
#include <sys/types.h>
typedef unsigned long ulong;
typedef unsigned short ushort;
#endif
