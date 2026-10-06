/* See uuid/uuid.h.  RFC 4122 version 4 (random) UUIDs. */
#include "uuid/uuid.h"

#include <stdlib.h>
#include <string.h>

#if defined(__ANDROID__) || defined(__APPLE__) || defined(__FreeBSD__)
#  define AUDACITY_HAVE_ARC4RANDOM 1
#else
#  include <sys/random.h>
#  include <errno.h>
#endif

static void fill_random(unsigned char *buf, size_t len)
{
#if defined(AUDACITY_HAVE_ARC4RANDOM)
   arc4random_buf(buf, len);
#else
   size_t done = 0;
   while (done < len) {
      ssize_t n = getrandom(buf + done, len - done, 0);
      if (n < 0) {
         if (errno == EINTR)
            continue;
         abort();
      }
      done += (size_t)n;
   }
#endif
}

void uuid_generate_random(uuid_t out)
{
   fill_random(out, 16);
   out[6] = (unsigned char)((out[6] & 0x0F) | 0x40); /* version 4 */
   out[8] = (unsigned char)((out[8] & 0x3F) | 0x80); /* RFC 4122 variant */
}

void uuid_generate(uuid_t out)
{
   uuid_generate_random(out);
}
