/*  SPDX-License-Identifier: GPL-2.0-or-later */
/**********************************************************************

  Audacity Android port -- host test harness of native/bridge/io/android

  fake_ndk_media.h: host stand-in for the subset of the NDK media API used by
  the plug-ins (signatures as in the NDK r28 headers).

**********************************************************************/
#pragma once
#include <stddef.h>
#include <stdint.h>
#include <stdbool.h>
#include <sys/types.h>

extern "C" {
typedef enum {
   AMEDIA_OK = 0,
   AMEDIA_ERROR_BASE = -10000,
   AMEDIA_ERROR_UNKNOWN = AMEDIA_ERROR_BASE,
   AMEDIA_ERROR_MALFORMED = AMEDIA_ERROR_BASE - 1,
   AMEDIA_ERROR_UNSUPPORTED = AMEDIA_ERROR_BASE - 2,
   AMEDIA_ERROR_INVALID_OBJECT = AMEDIA_ERROR_BASE - 3,
   AMEDIA_ERROR_INVALID_PARAMETER = AMEDIA_ERROR_BASE - 4,
   AMEDIA_ERROR_INVALID_OPERATION = AMEDIA_ERROR_BASE - 5,
   AMEDIA_ERROR_END_OF_STREAM = AMEDIA_ERROR_BASE - 6,
   AMEDIA_ERROR_IO = AMEDIA_ERROR_BASE - 7,
   AMEDIA_ERROR_WOULD_BLOCK = AMEDIA_ERROR_BASE - 8,
} media_status_t;

typedef struct AMediaFormat AMediaFormat;
typedef struct AMediaCodec AMediaCodec;
typedef struct AMediaExtractor AMediaExtractor;
typedef struct AMediaMuxer AMediaMuxer;
typedef struct ANativeWindow ANativeWindow;
typedef struct AMediaCrypto AMediaCrypto;
typedef struct PsshInfo { size_t numentries; } PsshInfo;

struct AMediaCodecBufferInfo {
   int32_t offset;
   int32_t size;
   int64_t presentationTimeUs;
   uint32_t flags;
};
typedef struct AMediaCodecBufferInfo AMediaCodecBufferInfo;

enum {
   AMEDIACODEC_BUFFER_FLAG_KEY_FRAME = 1,
   AMEDIACODEC_BUFFER_FLAG_CODEC_CONFIG = 2,
   AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM = 4,
   AMEDIACODEC_BUFFER_FLAG_PARTIAL_FRAME = 8,
   AMEDIACODEC_CONFIGURE_FLAG_ENCODE = 1,
   AMEDIACODEC_INFO_OUTPUT_BUFFERS_CHANGED = -3,
   AMEDIACODEC_INFO_OUTPUT_FORMAT_CHANGED = -2,
   AMEDIACODEC_INFO_TRY_AGAIN_LATER = -1,
};
enum {
   AMEDIAEXTRACTOR_SAMPLE_FLAG_SYNC = 1,
   AMEDIAEXTRACTOR_SAMPLE_FLAG_ENCRYPTED = 2,
};
typedef enum {
   AMEDIAEXTRACTOR_SEEK_PREVIOUS_SYNC,
   AMEDIAEXTRACTOR_SEEK_NEXT_SYNC,
   AMEDIAEXTRACTOR_SEEK_CLOSEST_SYNC,
} SeekMode;
typedef enum {
   AMEDIAMUXER_OUTPUT_FORMAT_MPEG_4 = 0,
   AMEDIAMUXER_OUTPUT_FORMAT_WEBM = 1,
   AMEDIAMUXER_OUTPUT_FORMAT_THREE_GPP = 2,
} OutputFormat;

extern const char *AMEDIAFORMAT_KEY_MIME;
extern const char *AMEDIAFORMAT_KEY_SAMPLE_RATE;
extern const char *AMEDIAFORMAT_KEY_CHANNEL_COUNT;
extern const char *AMEDIAFORMAT_KEY_DURATION;
extern const char *AMEDIAFORMAT_KEY_BIT_RATE;
extern const char *AMEDIAFORMAT_KEY_LANGUAGE;
extern const char *AMEDIAFORMAT_KEY_MAX_INPUT_SIZE;
extern const char *AMEDIAFORMAT_KEY_AAC_PROFILE;

AMediaFormat *AMediaFormat_new();
media_status_t AMediaFormat_delete(AMediaFormat *);
bool AMediaFormat_getInt32(AMediaFormat *, const char *name, int32_t *out);
bool AMediaFormat_getInt64(AMediaFormat *, const char *name, int64_t *out);
bool AMediaFormat_getString(AMediaFormat *, const char *name, const char **out);
bool AMediaFormat_getBuffer(AMediaFormat *, const char *name, void **data, size_t *size);
void AMediaFormat_setInt32(AMediaFormat *, const char *name, int32_t value);
void AMediaFormat_setInt64(AMediaFormat *, const char *name, int64_t value);
void AMediaFormat_setString(AMediaFormat *, const char *name, const char *value);
void AMediaFormat_setBuffer(AMediaFormat *, const char *name, const void *data, size_t size);

AMediaExtractor *AMediaExtractor_new();
media_status_t AMediaExtractor_delete(AMediaExtractor *);
media_status_t AMediaExtractor_setDataSourceFd(AMediaExtractor *, int fd, off64_t offset, off64_t length);
size_t AMediaExtractor_getTrackCount(AMediaExtractor *);
AMediaFormat *AMediaExtractor_getTrackFormat(AMediaExtractor *, size_t idx);
media_status_t AMediaExtractor_selectTrack(AMediaExtractor *, size_t idx);
ssize_t AMediaExtractor_readSampleData(AMediaExtractor *, uint8_t *buffer, size_t capacity);
uint32_t AMediaExtractor_getSampleFlags(AMediaExtractor *);
int64_t AMediaExtractor_getSampleTime(AMediaExtractor *);
bool AMediaExtractor_advance(AMediaExtractor *);
PsshInfo *AMediaExtractor_getPsshInfo(AMediaExtractor *);
AMediaFormat *AMediaExtractor_getFileFormat(AMediaExtractor *);
ssize_t AMediaExtractor_getSampleSize(AMediaExtractor *);

AMediaCodec *AMediaCodec_createDecoderByType(const char *mime_type);
AMediaCodec *AMediaCodec_createEncoderByType(const char *mime_type);
media_status_t AMediaCodec_delete(AMediaCodec *);
media_status_t AMediaCodec_configure(AMediaCodec *, const AMediaFormat *format,
   ANativeWindow *surface, AMediaCrypto *crypto, uint32_t flags);
media_status_t AMediaCodec_start(AMediaCodec *);
media_status_t AMediaCodec_stop(AMediaCodec *);
ssize_t AMediaCodec_dequeueInputBuffer(AMediaCodec *, int64_t timeoutUs);
uint8_t *AMediaCodec_getInputBuffer(AMediaCodec *, size_t idx, size_t *out_size);
uint8_t *AMediaCodec_getOutputBuffer(AMediaCodec *, size_t idx, size_t *out_size);
media_status_t AMediaCodec_queueInputBuffer(AMediaCodec *, size_t idx, off_t offset,
   size_t size, uint64_t time, uint32_t flags);
ssize_t AMediaCodec_dequeueOutputBuffer(AMediaCodec *, AMediaCodecBufferInfo *info, int64_t timeoutUs);
AMediaFormat *AMediaCodec_getOutputFormat(AMediaCodec *);
media_status_t AMediaCodec_releaseOutputBuffer(AMediaCodec *, size_t idx, bool render);
media_status_t AMediaCodec_getName(AMediaCodec *, char **out_name);
void AMediaCodec_releaseName(AMediaCodec *, char *name);

AMediaMuxer *AMediaMuxer_new(int fd, OutputFormat format);
media_status_t AMediaMuxer_delete(AMediaMuxer *);
ssize_t AMediaMuxer_addTrack(AMediaMuxer *, const AMediaFormat *format);
media_status_t AMediaMuxer_start(AMediaMuxer *);
media_status_t AMediaMuxer_stop(AMediaMuxer *);
media_status_t AMediaMuxer_writeSampleData(AMediaMuxer *muxer, size_t trackIdx,
   const uint8_t *data, const AMediaCodecBufferInfo *info);
}
