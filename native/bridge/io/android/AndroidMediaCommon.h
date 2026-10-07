/*  SPDX-License-Identifier: GPL-2.0-or-later */
/**********************************************************************

  Audacity Android port

  AndroidMediaCommon.h

  Small helpers shared by the NDK media plug-ins of the bridge "io"
  module (AndroidMediaImport.cpp, AndroidAacExport.cpp): RAII owners for
  AMediaFormat / AMediaCodec / AMediaExtractor / AMediaMuxer and file
  descriptors, logging, and the AMediaFormat keys that are spelled as
  literals.

  Android only (links libmediandk, liblog).

**********************************************************************/
#pragma once

#include <media/NdkMediaCodec.h>
#include <media/NdkMediaError.h>
#include <media/NdkMediaExtractor.h>
#include <media/NdkMediaFormat.h>
#include <media/NdkMediaMuxer.h>

#include <android/log.h>

#include <cstdint>
#include <memory>
#include <string>

#include <sys/types.h>
#include <unistd.h>

namespace aubridge::android_media {

inline constexpr const char *kLogTag = "AudacityMedia";

#define AUMEDIA_LOGI(...) \
   __android_log_print(ANDROID_LOG_INFO, ::aubridge::android_media::kLogTag, __VA_ARGS__)
#define AUMEDIA_LOGW(...) \
   __android_log_print(ANDROID_LOG_WARN, ::aubridge::android_media::kLogTag, __VA_ARGS__)
#define AUMEDIA_LOGE(...) \
   __android_log_print(ANDROID_LOG_ERROR, ::aubridge::android_media::kLogTag, __VA_ARGS__)

//! android.media.AudioFormat.ENCODING_* (values of the "pcm-encoding" key)
enum PcmEncoding : int32_t {
   kEncodingDefault = 1,      //!< means 16 bit
   kEncodingPcm16 = 2,
   kEncodingPcm8 = 3,         //!< unsigned 8 bit
   kEncodingPcmFloat = 4,
   kEncodingPcm24Packed = 21, //!< 3 bytes little endian, signed
   kEncodingPcm32 = 22,       //!< signed 32 bit integer
};

//! AMediaFormat keys.  Keys whose AMEDIAFORMAT_KEY_* symbol is newer than
//! API 21 are written as literals: referencing a newer `extern const char*`
//! would make the library fail to load on older devices if minSdk is ever
//! lowered (it is 28 today).
namespace keys {
inline constexpr const char *kPcmEncoding = "pcm-encoding";  // API 28 symbol
inline constexpr const char *kCsd0 = "csd-0";
// Container metadata (AMediaExtractor_getFileFormat, filled since API 29)
inline constexpr const char *kTitle = "title";
inline constexpr const char *kArtist = "artist";
inline constexpr const char *kAlbum = "album";
inline constexpr const char *kAlbumArtist = "albumartist";
inline constexpr const char *kAuthor = "author";
inline constexpr const char *kComposer = "composer";
inline constexpr const char *kGenre = "genre";
inline constexpr const char *kYear = "year";
inline constexpr const char *kDate = "date";
inline constexpr const char *kCdTrackNumber = "cdtracknum";
inline constexpr const char *kDiscNumber = "discnum";
} // namespace keys

inline constexpr const char *kAacMime = "audio/mp4a-latm";

struct FormatDeleter {
   void operator()(AMediaFormat *p) const noexcept { AMediaFormat_delete(p); }
};
using FormatPtr = std::unique_ptr<AMediaFormat, FormatDeleter>;

//! AMediaCodec_delete releases the codec in any state (no stop() needed)
struct CodecDeleter {
   void operator()(AMediaCodec *p) const noexcept { AMediaCodec_delete(p); }
};
using CodecPtr = std::unique_ptr<AMediaCodec, CodecDeleter>;

struct ExtractorDeleter {
   void operator()(AMediaExtractor *p) const noexcept { AMediaExtractor_delete(p); }
};
using ExtractorPtr = std::unique_ptr<AMediaExtractor, ExtractorDeleter>;

struct MuxerDeleter {
   void operator()(AMediaMuxer *p) const noexcept { AMediaMuxer_delete(p); }
};
using MuxerPtr = std::unique_ptr<AMediaMuxer, MuxerDeleter>;

//! Owning POSIX file descriptor
class UniqueFd final
{
   int mFd{ -1 };
public:
   UniqueFd() = default;
   explicit UniqueFd(int fd) noexcept : mFd{ fd } {}
   UniqueFd(const UniqueFd &) = delete;
   UniqueFd &operator=(const UniqueFd &) = delete;
   UniqueFd(UniqueFd &&other) noexcept : mFd{ other.Release() } {}
   UniqueFd &operator=(UniqueFd &&other) noexcept
   {
      if (this != &other)
         Reset(other.Release());
      return *this;
   }
   ~UniqueFd() { Reset(); }

   int Get() const noexcept { return mFd; }
   explicit operator bool() const noexcept { return mFd >= 0; }
   int Release() noexcept { const int fd = mFd; mFd = -1; return fd; }
   //! Closes the current descriptor; returns the result of close() (0 if none)
   int Reset(int fd = -1) noexcept
   {
      int result = 0;
      if (mFd >= 0)
         result = ::close(mFd);
      mFd = fd;
      return result;
   }
};

//! Copy of a string value (the pointer returned by AMediaFormat_getString
//! is owned by the format)
inline std::string GetString(AMediaFormat *format, const char *key)
{
   const char *value = nullptr;
   if (format && AMediaFormat_getString(format, key, &value) && value)
      return value;
   return {};
}

inline int32_t GetInt32(AMediaFormat *format, const char *key, int32_t fallback)
{
   int32_t value = 0;
   if (format && AMediaFormat_getInt32(format, key, &value))
      return value;
   return fallback;
}

inline int64_t GetInt64(AMediaFormat *format, const char *key, int64_t fallback)
{
   int64_t value = 0;
   if (format && AMediaFormat_getInt64(format, key, &value))
      return value;
   return fallback;
}

//! True for the negative dequeue results that are not "informational"
//! (TRY_AGAIN_LATER, OUTPUT_FORMAT_CHANGED, OUTPUT_BUFFERS_CHANGED):
//! they are media_status_t errors (e.g. AMEDIA_ERROR_UNKNOWN).
inline bool IsCodecError(ssize_t index) noexcept
{
   return index < 0 &&
      index != AMEDIACODEC_INFO_TRY_AGAIN_LATER &&
      index != AMEDIACODEC_INFO_OUTPUT_FORMAT_CHANGED &&
      index != AMEDIACODEC_INFO_OUTPUT_BUFFERS_CHANGED;
}

} // namespace aubridge::android_media
