/*
 * Audacity Android port: <sys/timeb.h> for libnyquist on Android (bionic has
 * none).  Nyquist's cmt/midifns.c includes it on UNIX but, with HAS_FTIME
 * unset (sys/unix/switches.h sets HAS_GETTIMEOFDAY), never calls ftime().
 */
#ifndef AUDACITY_PORT_SYS_TIMEB_H
#define AUDACITY_PORT_SYS_TIMEB_H
#include <time.h>
struct timeb {
   time_t time;
   unsigned short millitm;
   short timezone;
   short dstflag;
};
#endif
