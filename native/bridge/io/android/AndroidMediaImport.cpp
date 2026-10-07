/*  SPDX-License-Identifier: GPL-2.0-or-later */
/**********************************************************************

  Audacity Android port

  AndroidMediaImport.cpp

  Import plug-in for everything Android's MediaExtractor + MediaCodec can
  decode (AAC/M4A/MP4/3GP/AMR/MKV/WebM/MPEG-TS ...), through the NDK
  (AMediaExtractor, AMediaCodec).  It plays the role of Audacity's FFmpeg
  importer (modules/import-export/mod-ffmpeg/ImportFFmpeg.cpp by Joshua
  Haberman, Leland Lucius et al.), which the Android port does not build:
  the stream description string and the metadata rules
  (WriteMetadata/GetMetadata) are adapted from that file.

  Design: see README.md in this directory.

  * One WaveTrack per selected audio stream (mono / stereo; more channels
    become one mono track per channel, as ImportPCM does).
  * Decodes to float when the decoder honours "pcm-encoding" = float,
    else int16 (8/24/32-bit integer PCM from "audio/raw" is converted).
  * Registered so that Importer tries it after every other importer
    (see the comment at the registrar at the end of this file).

**********************************************************************/
#include "AndroidCodecs.h"
#include "AndroidMediaCommon.h"

#include "Import.h"
#include "ImportPlugin.h"
#include "ImportProgressListener.h"
#include "ImportUtils.h"
#include "MemoryX.h"
#include "Tags.h"
#include "WaveTrack.h"

#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstring>
#include <optional>
#include <string>
#include <vector>

#include <fcntl.h>
#include <sys/stat.h>
#include <unistd.h>

namespace {

using namespace aubridge::android_media;
using ImportResult = ImportProgressListener::ImportResult;
using Clock = std::chrono::steady_clock;

#define DESC XO("AAC, M4A, AMR and other formats (Android)")

//! Extensions this importer gets "first dibs" on.  Formats with a native
//! importer (wav, aiff, mp3, ogg, opus, flac, wv, ...) are deliberately not
//! listed: for those this plug-in is only the last resort.
const auto kExtensions = {
   wxT("m4a"), wxT("m4b"), wxT("m4r"), wxT("mp4"), wxT("m4v"), wxT("mov"),
   wxT("aac"), wxT("adts"),
   wxT("3gp"), wxT("3gpp"), wxT("3g2"), wxT("3ga"),
   wxT("amr"), wxT("awb"),
   wxT("mkv"), wxT("mka"), wxT("webm"), wxT("weba"),
   wxT("ts"),
};

constexpr int32_t kMaxChannels = 64;
//! Decoder input buffer size when the container does not say
constexpr int32_t kDefaultMaxInputSize = 128 * 1024;
//! Output buffers are polled with this timeout when nothing could be fed
constexpr int64_t kDequeueTimeoutUs = 10000;
//! Minimum interval between OnImportProgress calls (cancel latency)
constexpr auto kProgressInterval = std::chrono::milliseconds(40);
//! No input accepted and no output produced for this long: give up
constexpr auto kStallTimeout = std::chrono::seconds(10);
//! Input EOS was queued but the decoder never returns the EOS buffer
constexpr auto kEosTimeout = std::chrono::seconds(3);
//! At most this much audio with negative timestamps is dropped at the
//! start (encoder priming hidden by an MP4 edit list)
constexpr double kMaxLeadingDropSeconds = 0.25;
//! A first "segment" shorter than this, decoded before the output sample
//! rate settled (e.g. implicit HE-AAC signalling), is discarded
constexpr double kDiscardSegmentSeconds = 0.1;

std::string CodecName(const std::string &mime)
{
   static const struct { const char *mime; const char *name; } names[] = {
      { "audio/mp4a-latm", "AAC" },
      { "audio/3gpp", "AMR-NB" },
      { "audio/amr-wb", "AMR-WB" },
      { "audio/mpeg", "MP3" },
      { "audio/mpeg-L1", "MPEG Layer I" },
      { "audio/mpeg-L2", "MPEG Layer II" },
      { "audio/vorbis", "Vorbis" },
      { "audio/opus", "Opus" },
      { "audio/flac", "FLAC" },
      { "audio/raw", "PCM" },
      { "audio/g711-alaw", "G.711 A-law" },
      { "audio/g711-mlaw", "G.711 mu-law" },
      { "audio/ac3", "AC-3" },
      { "audio/eac3", "E-AC-3" },
      { "audio/eac3-joc", "E-AC-3 JOC" },
      { "audio/ac4", "AC-4" },
      { "audio/alac", "ALAC" },
      { "audio/gsm", "GSM" },
      { "audio/qcelp", "QCELP" },
      { "audio/x-ms-wma", "WMA" },
      { "audio/mhm1", "MPEG-H" },
   };
   for (const auto &entry : names)
      if (mime == entry.mime)
         return entry.name;
   return mime;
}

wxString ContainerName(const std::string &mime)
{
   static const struct { const char *mime; const char *name; } names[] = {
      { "audio/mp4", "MPEG-4" },
      { "video/mp4", "MPEG-4" },
      { "audio/3gpp", "3GPP" },
      { "video/3gpp", "3GPP" },
      { "audio/x-matroska", "Matroska" },
      { "video/x-matroska", "Matroska" },
      { "audio/webm", "WebM" },
      { "video/webm", "WebM" },
      { "audio/aac-adts", "AAC (ADTS)" },
      { "audio/amr", "AMR" },
      { "audio/3gpp-amr", "AMR" },
      { "audio/amr-wb", "AMR-WB" },
      { "video/mp2ts", "MPEG-TS" },
      { "video/mp2p", "MPEG-PS" },
      { "audio/x-wav", "WAV" },
      { "audio/ogg", "Ogg" },
      { "application/ogg", "Ogg" },
      { "audio/flac", "FLAC" },
      { "audio/mpeg", "MPEG audio" },
      { "audio/midi", "MIDI" },
   };
   for (const auto &entry : names)
      if (mime == entry.mime)
         return wxString::FromUTF8(entry.name);
   return wxString::FromUTF8(mime.c_str());
}

struct StreamInfo
{
   size_t trackIndex{};         //!< index in AMediaExtractor
   std::string mime;
   int32_t rate{};              //!< from the container (output may differ)
   int32_t channels{};
   int64_t durationUs{};        //!< <= 0: unknown
   bool use{ true };            //!< see SetStreamUsage
};

struct OutputFormat
{
   int32_t rate{};
   int32_t channels{};
   int32_t encoding{ kEncodingPcm16 };
   bool Valid() const noexcept
   { return rate > 0 && channels > 0 && channels <= kMaxChannels; }
};

OutputFormat ReadOutputFormat(AMediaCodec *codec)
{
   OutputFormat result;
   FormatPtr format{ AMediaCodec_getOutputFormat(codec) };
   if (!format)
      return result;
   result.rate = GetInt32(format.get(), AMEDIAFORMAT_KEY_SAMPLE_RATE, 0);
   result.channels = GetInt32(format.get(), AMEDIAFORMAT_KEY_CHANNEL_COUNT, 0);
   // Absent key: 16 bit, the MediaCodec default
   result.encoding = GetInt32(format.get(), keys::kPcmEncoding, kEncodingPcm16);
   return result;
}

//! How decoded samples of a given encoding are handed to WaveChannel
struct PcmLayout
{
   size_t bytes{};                         //!< bytes per sample in the buffer
   sampleFormat append{ int16Sample };     //!< format given to AppendBuffer
   sampleFormat effective{ int16Sample };  //!< precision of the data
   bool convert{};                         //!< convert to float first
};

std::optional<PcmLayout> LayoutOf(int32_t encoding)
{
   switch (encoding) {
   case kEncodingDefault:
   case kEncodingPcm16:
      return PcmLayout{ 2, int16Sample, int16Sample, false };
   case kEncodingPcmFloat:
      return PcmLayout{ 4, floatSample, floatSample, false };
   case kEncodingPcm8:
      return PcmLayout{ 1, floatSample, int16Sample, true };
   case kEncodingPcm24Packed:
      return PcmLayout{ 3, floatSample, int24Sample, true };
   case kEncodingPcm32:
      return PcmLayout{ 4, floatSample, floatSample, true };
   default:
      return std::nullopt;
   }
}

void ConvertToFloat(const uint8_t *src, size_t samples, int32_t encoding, float *dst)
{
   switch (encoding) {
   case kEncodingPcm8:
      for (size_t i = 0; i < samples; ++i)
         dst[i] = (int(src[i]) - 128) / 128.0f;
      break;
   case kEncodingPcm24Packed:
      for (size_t i = 0; i < samples; ++i, src += 3) {
         const int32_t v = int32_t(uint32_t(src[0]) | (uint32_t(src[1]) << 8) |
            (uint32_t(src[2]) << 16) | (src[2] & 0x80 ? 0xFF000000u : 0u));
         dst[i] = float(v / 8388608.0);
      }
      break;
   case kEncodingPcm32:
      for (size_t i = 0; i < samples; ++i, src += 4) {
         int32_t v;
         std::memcpy(&v, src, sizeof v);
         dst[i] = float(v / 2147483648.0);
      }
      break;
   default:
      std::fill(dst, dst + samples, 0.0f);
      break;
   }
}

//! Receives the decoded interleaved PCM of one stream and appends it to
//! WaveTracks.  Normally there is one "segment" (one track, or one mono
//! track per channel).  When the output sample rate changes after audio was
//! appended (e.g. concatenated broadcast captures), a new segment (new
//! tracks, positioned at the elapsed time) starts, because a WaveTrack has
//! one rate.  A channel-count change keeps the tracks and maps channels.
class PcmSink final
{
   struct Segment
   {
      TrackListHolder tracks;
      int32_t rate{};
      int32_t channels{};
      double start{};
      long long frames{};
   };

   WaveTrackFactory &mFactory;
   std::vector<Segment> mSegments;
   std::vector<float> mScratch;
   bool mWarnedChannels{ false };

public:
   explicit PcmSink(WaveTrackFactory &factory) : mFactory{ factory } {}

   bool Empty() const noexcept
   {
      return std::all_of(mSegments.begin(), mSegments.end(),
         [](const Segment &s){ return s.frames == 0; });
   }

   void Append(const uint8_t *data, size_t frames,
      const OutputFormat &format, const PcmLayout &layout)
   {
      if (frames == 0)
         return;
      if (mSegments.empty() || mSegments.back().rate != format.rate ||
          (mSegments.back().channels != format.channels && IsSettling()))
         StartSegment(format, layout);
      auto &segment = mSegments.back();

      const uint8_t *base = data;
      size_t sampleBytes = layout.bytes;
      if (layout.convert) {
         const size_t samples = frames * size_t(format.channels);
         if (mScratch.size() < samples)
            mScratch.resize(samples);
         ConvertToFloat(data, samples, format.encoding, mScratch.data());
         base = reinterpret_cast<const uint8_t *>(mScratch.data());
         sampleBytes = sizeof(float);
      }

      if (segment.channels != format.channels && !mWarnedChannels) {
         mWarnedChannels = true;
         AUMEDIA_LOGW("channel count changed from %d to %d during import; "
            "mapping channels", int(segment.channels), int(format.channels));
      }

      unsigned channel = 0;
      ImportUtils::ForEachChannel(*segment.tracks, [&](WaveChannel &wc) {
         // Fewer source channels than tracks: repeat the last one
         const unsigned source =
            std::min<unsigned>(channel, unsigned(format.channels) - 1);
         wc.AppendBuffer(
            reinterpret_cast<constSamplePtr>(base + source * sampleBytes),
            layout.append, frames, unsigned(format.channels), layout.effective);
         ++channel;
      });
      segment.frames += (long long)frames;
   }

   //! Flushes the tracks and moves them to \p out
   void Finish(TrackHolders &out)
   {
      for (auto &segment : mSegments) {
         if (segment.frames == 0)
            continue;
         const auto first = out.size();
         ImportUtils::FinalizeImport(out, std::move(*segment.tracks));
         if (segment.start > 0)
            for (auto i = first; i < out.size(); ++i)
               if (const auto track = dynamic_cast<WaveTrack *>(out[i].get()))
                  track->MoveTo(segment.start);
      }
      mSegments.clear();
   }

private:
   //! Only a very short first segment exists: the decoder may still be
   //! settling its output format (implicit SBR / PS signalling)
   bool IsSettling() const noexcept
   {
      return mSegments.size() == 1 &&
         mSegments.front().frames < kDiscardSegmentSeconds * mSegments.front().rate;
   }

   void StartSegment(const OutputFormat &format, const PcmLayout &layout)
   {
      double start = 0;
      if (!mSegments.empty()) {
         const auto &previous = mSegments.back();
         start = previous.start + double(previous.frames) / previous.rate;
         if (IsSettling()) {
            AUMEDIA_LOGW("discarding %lld frames decoded as %d Hz/%d ch before "
               "the output format settled at %d Hz/%d ch", previous.frames,
               int(previous.rate), int(previous.channels), int(format.rate),
               int(format.channels));
            mSegments.pop_back();
         }
         else
            AUMEDIA_LOGW("sample rate changed from %d to %d Hz at %.3f s: "
               "the rest goes to a new track", int(previous.rate),
               int(format.rate), start);
      }
      Segment segment;
      segment.tracks = mFactory.CreateMany(size_t(format.channels),
         ImportUtils::ChooseFormat(layout.effective), double(format.rate));
      segment.rate = format.rate;
      segment.channels = format.channels;
      segment.start = start;
      mSegments.push_back(std::move(segment));
   }
};

class AndroidMediaImportFileHandle final : public ImportFileHandleEx
{
   enum class DecodeStatus { Finished, Stopped, Cancelled, Failed };

   // Declaration order matters for destruction: nothing below outlives mFd
   UniqueFd mFd;
   off64_t mFileSize{};
   std::string mContainerMime;
   std::vector<StreamInfo> mStreams;
   TranslatableStrings mStreamInfo;
   std::vector<std::pair<const char *, std::string>> mMetadata;
   TranslatableString mErrorMessage;
   TranslatableStrings mWarnings;

public:
   explicit AndroidMediaImportFileHandle(const FilePath &filename)
      : ImportFileHandleEx{ filename }
   {}

   //! Cheap check whether MediaExtractor understands the file and a decoder
   //! exists for at least one audio track.  Called by Importer for files
   //! with one of our extensions first, and for any file all other importers
   //! rejected.
   bool Probe();

   TranslatableString GetErrorMessage() const override { return mErrorMessage; }

   TranslatableString GetFileDescription() override
   {
      if (mContainerMime.empty())
         return DESC;
      /* i18n-hint: %s is a container format name such as "MPEG-4" */
      return XO("%s file (Android media decoder)")
         .Format(ContainerName(mContainerMime));
   }

   ByteCount GetFileUncompressedBytes() override
   {
      const bool anyUsed = std::any_of(mStreams.begin(), mStreams.end(),
         [](const StreamInfo &s){ return s.use; });
      ByteCount total = 0;
      for (const auto &s : mStreams) {
         if (anyUsed && !s.use)
            continue;
         if (s.durationUs > 0 && s.rate > 0 && s.channels > 0)
            total += ByteCount(double(s.durationUs) / 1e6 * s.rate *
               s.channels * sizeof(float));
      }
      return total;
   }

   wxInt32 GetStreamCount() override { return wxInt32(mStreams.size()); }

   const TranslatableStrings &GetStreamInfo() override { return mStreamInfo; }

   void SetStreamUsage(wxInt32 streamID, bool use) override
   {
      if (streamID >= 0 && size_t(streamID) < mStreams.size())
         mStreams[size_t(streamID)].use = use;
   }

   void Import(ImportProgressListener &progressListener,
      WaveTrackFactory *trackFactory, TrackHolders &outTracks, Tags *tags,
      std::optional<LibFileFormats::AcidizerTags> &acidTags) override;

private:
   ExtractorPtr OpenExtractor() const;
   static CodecPtr CreateDecoder(AMediaExtractor *extractor, const StreamInfo &s);
   DecodeStatus RunDecoder(const StreamInfo &s, double progressBase,
      double progressSpan, ImportProgressListener &progress, PcmSink &sink,
      TranslatableString &error);
   void ReadMetadata(AMediaFormat *fileFormat);
   void WriteMetadata(Tags *tags);
};

ExtractorPtr AndroidMediaImportFileHandle::OpenExtractor() const
{
   ExtractorPtr extractor{ AMediaExtractor_new() };
   if (!extractor)
      return {};
   // The NDK dup()s the descriptor; mFd stays ours
   const auto status = AMediaExtractor_setDataSourceFd(
      extractor.get(), mFd.Get(), 0, mFileSize);
   if (status != AMEDIA_OK)
      return {};
   return extractor;
}

bool AndroidMediaImportFileHandle::Probe()
{
   // Copy: utf8_str() of a temporary wxString may point into that string's
   // conversion cache, which dies with the temporary
   const std::string path{ GetFilename().utf8_str().data() };
   mFd.Reset(::open(path.c_str(), O_RDONLY | O_CLOEXEC));
   if (!mFd)
      return false;
   struct stat st{};
   if (::fstat(mFd.Get(), &st) != 0 || !S_ISREG(st.st_mode) || st.st_size <= 0)
      return false;
   mFileSize = st.st_size;

   auto extractor = OpenExtractor();
   if (!extractor)
      return false;

   if (AMediaExtractor_getPsshInfo(extractor.get()) != nullptr) {
      AUMEDIA_LOGW("%s: DRM protected, not importing", path.c_str());
      return false;
   }

   if (FormatPtr fileFormat{ AMediaExtractor_getFileFormat(extractor.get()) }) {
      mContainerMime = GetString(fileFormat.get(), AMEDIAFORMAT_KEY_MIME);
      ReadMetadata(fileFormat.get());
   }

   const size_t count = AMediaExtractor_getTrackCount(extractor.get());
   for (size_t i = 0; i < count; ++i) {
      FormatPtr format{ AMediaExtractor_getTrackFormat(extractor.get(), i) };
      if (!format)
         continue;
      StreamInfo s;
      s.trackIndex = i;
      s.mime = GetString(format.get(), AMEDIAFORMAT_KEY_MIME);
      if (s.mime.compare(0, 6, "audio/") != 0)
         continue;
      // Is there a decoder at all?  (Device dependent: AC-3, DTS, ...)
      if (CodecPtr probe{ AMediaCodec_createDecoderByType(s.mime.c_str()) };
          !probe)
      {
         AUMEDIA_LOGW("%s: no decoder for track %zu (%s)", path.c_str(), i,
            s.mime.c_str());
         continue;
      }
      s.rate = GetInt32(format.get(), AMEDIAFORMAT_KEY_SAMPLE_RATE, 0);
      s.channels = GetInt32(format.get(), AMEDIAFORMAT_KEY_CHANNEL_COUNT, 0);
      s.durationUs = GetInt64(format.get(), AMEDIAFORMAT_KEY_DURATION, 0);
      const auto bitrate = GetInt32(format.get(), AMEDIAFORMAT_KEY_BIT_RATE, 0);
      auto language = GetString(format.get(), AMEDIAFORMAT_KEY_LANGUAGE);
      if (language.empty())
         language = "und";

      // Same text as the FFmpeg importer (ImportFFmpeg.cpp), so that existing
      // translations apply
      mStreamInfo.push_back(XO(
/* i18n-hint: "codec" is short for a "coder-decoder" algorithm */
"Index[%02x] Codec[%s], Language[%s], Bitrate[%s], Channels[%d], Duration[%d]")
         .Format(
            int(i),
            wxString::FromUTF8(CodecName(s.mime).c_str()),
            wxString::FromUTF8(language.c_str()),
            bitrate > 0 ? wxString::Format(wxT("%d"), int(bitrate)) : wxString{ wxT("?") },
            int(s.channels),
            int(s.durationUs > 0 ? s.durationUs / 1000000 : 0)));
      mStreams.push_back(std::move(s));
   }
   return !mStreams.empty();
}

CodecPtr AndroidMediaImportFileHandle::CreateDecoder(
   AMediaExtractor *extractor, const StreamInfo &s)
{
   // "audio/raw": the track's pcm-encoding describes the *input*; leave it.
   // Otherwise ask for float output first (honoured by recent decoders;
   // others ignore it and deliver 16 bit), then retry without.
   const bool raw = s.mime == "audio/raw";
   for (int attempt = raw ? 1 : 0; attempt < 2; ++attempt) {
      FormatPtr format{ AMediaExtractor_getTrackFormat(extractor, s.trackIndex) };
      if (!format)
         return {};
      if (attempt == 0)
         AMediaFormat_setInt32(format.get(), keys::kPcmEncoding, kEncodingPcmFloat);
      // Without "max-input-size" (some Matroska tracks) the codec picks a
      // small default and big frames (FLAC, PCM) would not fit
      if (GetInt32(format.get(), AMEDIAFORMAT_KEY_MAX_INPUT_SIZE, 0) <= 0)
         AMediaFormat_setInt32(format.get(), AMEDIAFORMAT_KEY_MAX_INPUT_SIZE,
            kDefaultMaxInputSize);
      CodecPtr codec{ AMediaCodec_createDecoderByType(s.mime.c_str()) };
      if (!codec)
         return {};
      auto status = AMediaCodec_configure(codec.get(), format.get(), nullptr, nullptr, 0);
      if (status == AMEDIA_OK)
         status = AMediaCodec_start(codec.get());
      if (status == AMEDIA_OK)
         return codec;
      AUMEDIA_LOGW("decoder for %s: configure/start failed (%d)%s",
         s.mime.c_str(), int(status),
         attempt == 0 ? ", retrying without float output" : "");
   }
   return {};
}

auto AndroidMediaImportFileHandle::RunDecoder(const StreamInfo &s,
   double progressBase, double progressSpan, ImportProgressListener &progress,
   PcmSink &sink, TranslatableString &error) -> DecodeStatus
{
   const auto codecName = wxString::FromUTF8(CodecName(s.mime).c_str());
   const auto failure = [&](const char *what, long code) {
      AUMEDIA_LOGE("track %zu (%s): %s failed (%ld)", s.trackIndex,
         s.mime.c_str(), what, code);
      /* i18n-hint: %s is a codec name such as "AAC", %d an error code */
      error = XO("The Android decoder for %s failed (error %d).")
         .Format(codecName, int(code));
      return DecodeStatus::Failed;
   };

   // A fresh extractor per stream: no track selected, positioned at 0
   const auto extractor = OpenExtractor();
   if (!extractor)
      return failure("setDataSourceFd", 0);
   if (const auto st = AMediaExtractor_selectTrack(extractor.get(), s.trackIndex);
       st != AMEDIA_OK)
      return failure("selectTrack", st);

   const auto codec = CreateDecoder(extractor.get(), s);
   if (!codec) {
      error = XO("The Android decoder for %s could not be started.")
         .Format(codecName);
      return DecodeStatus::Failed;
   }
   AMediaCodec *const c = codec.get();

   OutputFormat format;              // valid once known
   bool inEos = false, outEos = false;
   int64_t lastInputPts = 0, lastOutputPts = 0;
   long long droppedFrames = 0;
   auto lastActivity = Clock::now();
   auto lastProgress = Clock::time_point{};

   while (!outEos) {
      bool fed = false;

      // 1. Feed compressed samples
      while (!inEos) {
         const ssize_t index = AMediaCodec_dequeueInputBuffer(c, 0);
         if (index == AMEDIACODEC_INFO_TRY_AGAIN_LATER)
            break;
         if (index < 0)
            return failure("dequeueInputBuffer", long(index));
         size_t capacity = 0;
         uint8_t *const buffer = AMediaCodec_getInputBuffer(c, size_t(index), &capacity);
         if (!buffer)
            return failure("getInputBuffer", 0);

         ssize_t size = -1;
         const ssize_t sampleSize = AMediaExtractor_getSampleSize(extractor.get());
         if (sampleSize >= 0) {
            if (size_t(sampleSize) > capacity)
               return failure("sample larger than the input buffer", long(sampleSize));
            if (AMediaExtractor_getSampleFlags(extractor.get()) &
                AMEDIAEXTRACTOR_SAMPLE_FLAG_ENCRYPTED)
            {
               error = XO("The audio is encrypted (DRM) and cannot be imported.");
               return DecodeStatus::Failed;
            }
            size = AMediaExtractor_readSampleData(extractor.get(), buffer, capacity);
            if (size < 0) {
               AUMEDIA_LOGW("track %zu: read error at %lld us; treating it as "
                  "the end of the stream", s.trackIndex, (long long)lastInputPts);
               /* i18n-hint: %d is a stream number */
               mWarnings.push_back(XO("Stream %d: the file could not be read "
                  "to the end (truncated or damaged?); the rest is missing.")
                  .Format(int(s.trackIndex)));
            }
         }

         media_status_t status;
         if (size < 0) {
            // End of the track, or the unreadable rest of a truncated file:
            // keep what was decoded
            status = AMediaCodec_queueInputBuffer(c, size_t(index), 0, 0,
               uint64_t(lastInputPts), AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM);
            inEos = true;
         }
         else {
            // Negative times (edit lists) survive the uint64_t round trip
            const int64_t pts = AMediaExtractor_getSampleTime(extractor.get());
            status = AMediaCodec_queueInputBuffer(c, size_t(index), 0,
               size_t(size), uint64_t(pts), 0);
            lastInputPts = pts;
            AMediaExtractor_advance(extractor.get());
         }
         if (status != AMEDIA_OK)
            return failure("queueInputBuffer", status);
         fed = true;
         lastActivity = Clock::now();
      }

      // 2. Drain decoded PCM; wait a little only if nothing could be fed
      int64_t timeoutUs = fed ? 0 : kDequeueTimeoutUs;
      while (!outEos) {
         AMediaCodecBufferInfo info{};
         const ssize_t index = AMediaCodec_dequeueOutputBuffer(c, &info, timeoutUs);
         timeoutUs = 0;
         if (index == AMEDIACODEC_INFO_TRY_AGAIN_LATER)
            break;
         if (index == AMEDIACODEC_INFO_OUTPUT_BUFFERS_CHANGED)
            continue;
         if (index == AMEDIACODEC_INFO_OUTPUT_FORMAT_CHANGED) {
            const auto newFormat = ReadOutputFormat(c);
            if (newFormat.Valid()) {
               AUMEDIA_LOGI("track %zu (%s): output %d Hz, %d ch, encoding %d",
                  s.trackIndex, s.mime.c_str(), int(newFormat.rate),
                  int(newFormat.channels), int(newFormat.encoding));
               format = newFormat;
            }
            else
               AUMEDIA_LOGW("track %zu: ignoring invalid output format "
                  "(%d Hz, %d ch)", s.trackIndex, int(newFormat.rate),
                  int(newFormat.channels));
            lastActivity = Clock::now();
            continue;
         }
         if (index < 0)
            return failure("dequeueOutputBuffer", long(index));

         lastActivity = Clock::now();
         // Give the buffer back on every path out of this scope
         const auto release = finally([c, index]{
            AMediaCodec_releaseOutputBuffer(c, size_t(index), false); });

         if (info.flags & AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM)
            outEos = true;
         if (info.size <= 0 || (info.flags & AMEDIACODEC_BUFFER_FLAG_CODEC_CONFIG))
            continue;

         if (!format.Valid()) {
            // No OUTPUT_FORMAT_CHANGED before the first buffer (old codecs)
            format = ReadOutputFormat(c);
            if (!format.Valid())
               format = OutputFormat{ s.rate, s.channels, kEncodingPcm16 };
            if (!format.Valid())
               return failure("unknown output format", 0);
         }
         const auto layout = LayoutOf(format.encoding);
         if (!layout) {
            error = XO("The Android decoder for %s produced an unsupported "
               "sample format (%d).").Format(codecName, int(format.encoding));
            return DecodeStatus::Failed;
         }

         size_t capacity = 0;
         uint8_t *const base = AMediaCodec_getOutputBuffer(c, size_t(index), &capacity);
         if (!base || info.offset < 0 ||
             size_t(info.offset) + size_t(info.size) > capacity)
            return failure("getOutputBuffer", long(info.size));

         const size_t frameBytes = layout->bytes * size_t(format.channels);
         const uint8_t *data = base + info.offset;
         size_t frames = size_t(info.size) / frameBytes;

         // Leading frames with negative time stamps are encoder priming
         // hidden by an edit list ('elst', applied by MPEG4Extractor since
         // Android 11): drop them, like a player does (gapless)
         if (sink.Empty() && info.presentationTimeUs < 0) {
            const long long maxDrop =
               (long long)(kMaxLeadingDropSeconds * format.rate) - droppedFrames;
            const long long wanted = (long long)std::ceil(
               double(-info.presentationTimeUs) * format.rate / 1e6);
            const long long drop = std::clamp<long long>(
               std::min<long long>(wanted, (long long)frames), 0, std::max(0LL, maxDrop));
            data += size_t(drop) * frameBytes;
            frames -= size_t(drop);
            droppedFrames += drop;
         }
         if (frames > 0)
            sink.Append(data, frames, format, *layout);
         lastOutputPts = std::max(lastOutputPts, int64_t(info.presentationTimeUs));
      }

      // 3. Progress; the listener may call Cancel() or Stop() from it
      const auto now = Clock::now();
      if (outEos || now - lastProgress >= kProgressInterval) {
         lastProgress = now;
         const double fraction = outEos ? 1.0 : s.durationUs > 0
            ? std::clamp(double(lastOutputPts) / double(s.durationUs), 0.0, 1.0)
            : 0.0;
         progress.OnImportProgress(progressBase + progressSpan * fraction);
         if (IsCancelled())
            return DecodeStatus::Cancelled;
         if (IsStopped())
            return DecodeStatus::Stopped;
      }

      // 4. Watchdog against decoders that stop talking
      if (!outEos) {
         const auto idle = now - lastActivity;
         if (inEos && idle > kEosTimeout) {
            AUMEDIA_LOGW("track %zu (%s): no end-of-stream buffer from the "
               "decoder; finishing", s.trackIndex, s.mime.c_str());
            break;
         }
         if (idle > kStallTimeout)
            return failure("decoder stalled", 0);
      }
   }
   if (droppedFrames > 0)
      AUMEDIA_LOGI("track %zu: dropped %lld leading frames with negative "
         "time stamps", s.trackIndex, droppedFrames);
   return DecodeStatus::Finished;
}

void AndroidMediaImportFileHandle::Import(
   ImportProgressListener &progressListener, WaveTrackFactory *trackFactory,
   TrackHolders &outTracks, Tags *tags,
   std::optional<LibFileFormats::AcidizerTags> &)
{
   BeginImport();
   outTracks.clear();
   mErrorMessage = {};
   mWarnings.clear();

   std::vector<size_t> used;
   for (size_t i = 0; i < mStreams.size(); ++i)
      if (mStreams[i].use)
         used.push_back(i);
   if (used.empty() || !trackFactory) {
      mErrorMessage = trackFactory
         ? XO("No audio stream was selected for import.")
         : XO("Internal error: no track factory.");
      progressListener.OnImportResult(ImportResult::Error);
      return;
   }

   bool stopped = false;
   const double span = 1.0 / double(used.size());
   for (size_t k = 0; k < used.size(); ++k) {
      const auto &s = mStreams[used[k]];
      PcmSink sink{ *trackFactory };
      TranslatableString error;
      const auto status = RunDecoder(s, k * span, span, progressListener, sink, error);

      if (status == DecodeStatus::Cancelled) {
         outTracks.clear();
         progressListener.OnImportResult(ImportResult::Cancelled);
         return;
      }
      if (status == DecodeStatus::Failed) {
         if (!sink.Empty()) {
            // Keep the audio decoded before the failure, and say so
            /* i18n-hint: %d is a stream number, %s an error message */
            mWarnings.push_back(XO("Stream %d was imported only partially: %s")
               .Format(int(s.trackIndex), error));
            sink.Finish(outTracks);
         }
         else {
            if (mErrorMessage.empty())
               mErrorMessage = error;
            mWarnings.push_back(XO("Stream %d could not be imported: %s")
               .Format(int(s.trackIndex), error));
         }
      }
      else {
         if (sink.Empty() && status == DecodeStatus::Finished)
            mWarnings.push_back(XO("Stream %d contains no audio.")
               .Format(int(s.trackIndex)));
         sink.Finish(outTracks);
      }
      if (status == DecodeStatus::Stopped || IsStopped()) {
         stopped = true;
         break;
      }
   }

   if (outTracks.empty()) {
      if (stopped) {
         // Stopped before any audio arrived: like a cancel (a "Stopped"
         // result without tracks would make Importer try other plug-ins)
         progressListener.OnImportResult(ImportResult::Cancelled);
         return;
      }
      if (mErrorMessage.empty())
         mErrorMessage = XO("No audio could be decoded from this file.");
      progressListener.OnImportResult(ImportResult::Error);
      return;
   }

   WriteMetadata(tags);

   if (!mWarnings.empty()) {
      auto message = mWarnings.front();
      for (size_t i = 1; i < mWarnings.size(); ++i)
         message.Join(mWarnings[i], wxT("\n"));
      ImportUtils::ShowMessageBox(message, XO("Import"));
   }

   progressListener.OnImportResult(
      stopped ? ImportResult::Stopped : ImportResult::Success);
}

void AndroidMediaImportFileHandle::ReadMetadata(AMediaFormat *fileFormat)
{
   // AMediaExtractor_getFileFormat reports container metadata since API 29
   // (MPEG4Extractor iTunes atoms, Matroska tags, ...); older systems only
   // report the mime type.
   for (const char *key : { keys::kTitle, keys::kArtist, keys::kAlbumArtist,
           keys::kAuthor, keys::kAlbum, keys::kGenre, keys::kYear, keys::kDate,
           keys::kCdTrackNumber })
   {
      auto value = GetString(fileFormat, key);
      if (!value.empty())
         mMetadata.emplace_back(key, std::move(value));
   }
}

// Adapted from FFmpegImportFileHandle::WriteMetadata (ImportFFmpeg.cpp):
// the file's tags replace the project's tags when the file has a title,
// artist or album.
void AndroidMediaImportFileHandle::WriteMetadata(Tags *tags)
{
   if (!tags || mMetadata.empty())
      return;
   const auto find = [this](const char *key) -> wxString {
      for (const auto &[k, v] : mMetadata)
         if (std::strcmp(k, key) == 0)
            return wxString::FromUTF8(v.c_str()).Strip(wxString::both);
      return {};
   };
   const auto first = [&](std::initializer_list<const char *> keys) -> wxString {
      for (const char *key : keys)
         if (auto value = find(key); !value.empty())
            return value;
      return {};
   };

   Tags temp;   // starts with the user's default tags, as in ImportFFmpeg
   const auto set = [&](const wxString &tag, const wxString &value) {
      if (!value.empty())
         temp.SetTag(tag, value);
   };
   set(TAG_TITLE, find(keys::kTitle));
   set(TAG_ARTIST, first({ keys::kArtist, keys::kAlbumArtist, keys::kAuthor }));
   set(TAG_ALBUM, find(keys::kAlbum));

   // "3/12" -> "3"
   set(TAG_TRACK, find(keys::kCdTrackNumber).BeforeFirst(wxT('/')).Strip(wxString::both));

   // MPEG4Extractor reports an iTunes 'gnre' atom as the ID3v1 genre index
   auto genre = find(keys::kGenre);
   {
      wxString digits = genre;
      if (digits.StartsWith(wxT("(")) && digits.EndsWith(wxT(")")))
         digits = digits.Mid(1, digits.length() - 2);
      long index = -1;
      if (!digits.empty() && digits.IsNumber() && digits.ToLong(&index)) {
         const auto name = temp.GetGenre(int(index));
         if (!name.empty())
            genre = name;
      }
   }
   set(TAG_GENRE, genre);

   auto year = find(keys::kYear);
   if (year.empty()) {
      // "2019-05-01T00:00:00Z", "20190501T000000.000Z", ...
      const auto date = find(keys::kDate);
      if (date.length() >= 4 && date.Left(4).IsNumber())
         year = date.Left(4);
   }
   set(TAG_YEAR, year);

   if (!temp.IsEmpty())
      *tags = temp;
}

class AndroidMediaImportPlugin final : public ImportPlugin
{
public:
   AndroidMediaImportPlugin()
      : ImportPlugin{ FileExtensions(kExtensions.begin(), kExtensions.end()) }
   {}

   //! Persistent ID (never change it)
   wxString GetPluginStringID() override { return wxT("android-mediacodec"); }

   TranslatableString GetPluginFormatDescription() override { return DESC; }

   std::unique_ptr<ImportFileHandle> Open(
      const FilePath &filename, AudacityProject *) override
   {
      auto handle = std::make_unique<AndroidMediaImportFileHandle>(filename);
      if (!handle->Probe())
         return nullptr;
      return handle;
   }
};

// Registration order.  Importer::Initialize collects plug-ins once, merging
// the registry with the preference "/Importers" (default
// "AUP,PCM,OGG,FLAC,MP3,LOF,WavPack,portsmf,FFmpeg").  Items named there come
// first, in that order; the others are appended in a final pass in
// *ascending identifier order* (lib-registries Registry.cpp, MergeItems:
// an OrderingHint::End item would be placed in an earlier pass and end up
// *before* "Opus", whose hint is Unspecified).  An identifier starting with
// a lower-case letter sorts after every capitalised one, so this plug-in is
// tried after PCM, OGG, FLAC, MP3, LOF, WavPack and Opus, and the merged
// order is then remembered in "/Importers".  Files with one of kExtensions
// still try this plug-in first (Import.cpp: extension matches go first).
Importer::RegisteredImportPlugin sRegistered{ kImporterRegistryId,
   std::make_unique<AndroidMediaImportPlugin>() };

} // namespace
