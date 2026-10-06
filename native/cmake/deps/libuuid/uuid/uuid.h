/*
 * Minimal <uuid/uuid.h> for the Audacity Android port.
 *
 * lib-uuid (USE_LIBUUID branch, used on Linux) only calls uuid_generate().
 * Android has no libuuid, so this provides a random (RFC 4122 version 4)
 * implementation.  It is used for the host build too, so both builds share
 * the same code path.
 */
#ifndef AUDACITY_PORT_UUID_UUID_H
#define AUDACITY_PORT_UUID_UUID_H

#ifdef __cplusplus
extern "C" {
#endif

typedef unsigned char uuid_t[16];

void uuid_generate(uuid_t out);
void uuid_generate_random(uuid_t out);

#ifdef __cplusplus
}
#endif

#endif
