/*
 * Hand-written config.h for LAME 3.100 (encoder library only) used by the
 * Audacity Android port.  Replaces the autoconf-generated one; valid for the
 * LP64 Linux/Android targets this build supports (arm64-v8a, x86_64) and
 * for 32-bit Android ABIs.  Portable C code paths only (no NASM/SSE).
 */
#ifndef LAME_CONFIG_H
#define LAME_CONFIG_H

#define STDC_HEADERS 1
#define HAVE_ERRNO_H 1
#define HAVE_FCNTL_H 1
#define HAVE_LIMITS_H 1
#define HAVE_INTTYPES_H 1
#define HAVE_STDINT_H 1
#define HAVE_STDLIB_H 1
#define HAVE_STRING_H 1
#define HAVE_STRINGS_H 1
#define HAVE_UNISTD_H 1
#define HAVE_SYS_STAT_H 1
#define HAVE_SYS_TYPES_H 1
#define HAVE_SYS_TIME_H 1
#define HAVE_MEMORY_H 1
#define HAVE_DLFCN_H 1
#define HAVE_GETTIMEOFDAY 1
#define HAVE_STRTOL 1

#define HAVE_INT8_T 1
#define HAVE_INT16_T 1
#define HAVE_INT32_T 1
#define HAVE_INT64_T 1
#define HAVE_UINT8_T 1
#define HAVE_UINT16_T 1
#define HAVE_UINT32_T 1
#define HAVE_UINT64_T 1

typedef float ieee754_float32_t;
typedef double ieee754_float64_t;
typedef long double ieee854_float80_t;
#define HAVE_IEEE754_FLOAT32_T 1
#define HAVE_IEEE754_FLOAT64_T 1
#define HAVE_IEEE854_FLOAT80_T 1

/* IEEE 754 tricks used by the reference configure on all IEEE platforms */
#define TAKEHIRO_IEEE754_HACK 1
#define USE_FAST_LOG 1

#define LAME_LIBRARY_BUILD 1

#define PACKAGE "lame"
#define PACKAGE_NAME "lame"
#define PACKAGE_VERSION "3.100"
#define VERSION "3.100"

#endif /* LAME_CONFIG_H */
