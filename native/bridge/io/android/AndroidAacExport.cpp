/*  SPDX-License-Identifier: GPL-2.0-or-later */
/**********************************************************************

  Audacity Android port

  AndroidAacExport.cpp

  Export plug-in "M4A (AAC) Files": the device's AAC encoder
  (AMediaCodec "audio/mp4a-latm") + AMediaMuxer (MPEG-4) -> .m4a.
  Replaces Audacity's FFmpeg M4A export, which the port does not build.
  The structure follows the 3.7.9 exporters in modules/import-export
  (mod-ogg/ExportOGG.cpp by Joshua Haberman: processor context,
  Initialize/Process split, messages; mod-mp3/ExportMP3.cpp: options editor
  with options that are shown depending on another option).

  Design: see README.md in this directory.

  * Options: Profile (AAC-LC / HE-AAC / HE-AAC v2, only those the device
    encodes), and one "Bit Rate" enum per profile (the unused ones Hidden).
  * Sample rate list from a probe of the encoder (AndroidCodecs.h).
  * Mono / stereo (FormatInfo::maxChannels = 2), 16-bit PCM input from the
    Audacity Mixer (dithered by the mixer's sample format conversion).
  * No metadata (AMediaMuxer has no tag API): canMetaData = false.
  * On failure or cancel the partial file is deleted (also when the task
    is destroyed without running).

**********************************************************************/
#include "AndroidCodecs.h"
#include "AndroidMediaCommon.h"

#include "BasicSettings.h"
#include "ExportOptionsEditor.h"
#include "ExportPlugin.h"
#include "ExportPluginHelpers.h"
#include "ExportPluginRegistry.h"
#include "MemoryX.h"
#include "Mix.h"
#include "wxFileNameWrapper.h"

#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstring>
#include <iterator>
#include <mutex>
#include <optional>
#include <string>
#include <unordered_map>
#include <vector>

#include <fcntl.h>
#include <sys/stat.h>
#include <unistd.h>

namespace aubridge::android_media {

namespace {

using Clock = std::chrono::steady_clock;

//! Encoder input: interleaved int16 frames per Mixer::Process()
constexpr size_t kMixerFrames = 4096;
//! Requested encoder input buffer size (8192 stereo frames)
constexpr int32_t kMaxInputBytes = 32768;
constexpr int64_t kDequeueTimeoutUs = 10000;
//! No input accepted and no output produced for this long: give up
constexpr auto kStallTimeout = std::chrono::seconds(10);

// Probe parameters
constexpr int kCandidateRates[] = {
   8000, 11025, 12000, 16000, 22050, 24000, 32000, 44100, 48000,
   64000, 88200, 96000 };
constexpr int kFallbackRates[] = {
   8000, 11025, 12000, 16000, 22050, 24000, 32000, 44100, 48000 };
constexpr auto kProbeTimeout = std::chrono::milliseconds(1500);
//! Upper bound for the whole probe (it runs on the calling thread)
constexpr auto kProbeBudget = std::chrono::seconds(5);
//! Give up probing after this many probes that ran into kProbeTimeout
constexpr int kMaxProbeTimeouts = 2;
constexpr size_t kProbeFrames = 8192;

//! HE-AAC (SBR) output rates: the core codec runs at half the rate
constexpr int kMinSbrRate = 16000;
constexpr int kMaxSbrRate = 48000;

FormatPtr MakeEncoderFormat(int rate, int channels, int profile, int bitrate)
{
   FormatPtr format{ AMediaFormat_new() };
   if (!format)
      return format;
   AMediaFormat_setString(format.get(), AMEDIAFORMAT_KEY_MIME, kAacMime);
   AMediaFormat_setInt32(format.get(), AMEDIAFORMAT_KEY_SAMPLE_RATE, rate);
   AMediaFormat_setInt32(format.get(), AMEDIAFORMAT_KEY_CHANNEL_COUNT, channels);
   AMediaFormat_setInt32(format.get(), AMEDIAFORMAT_KEY_BIT_RATE, bitrate);
   AMediaFormat_setInt32(format.get(), AMEDIAFORMAT_KEY_AAC_PROFILE, profile);
   AMediaFormat_setInt32(format.get(), AMEDIAFORMAT_KEY_MAX_INPUT_SIZE, kMaxInputBytes);
   return format;
}

const char *ProfileName(int profile)
{
   switch (profile) {
   case kAacProfileLC: return "AAC-LC";
   case kAacProfileHE: return "HE-AAC";
   case kAacProfileHEv2: return "HE-AAC v2";
   default: return "AAC";
   }
}

bool Contains(const std::vector<int> &values, int value)
{
   return std::find(values.begin(), values.end(), value) != values.end();
}

// --------------------------------------------------------------------------
// AudioSpecificConfig (ISO/IEC 14496-3 1.6.2.1), to verify that a probe for
// HE-AAC really produced SBR/PS and was not silently downgraded to AAC-LC
// --------------------------------------------------------------------------
struct AscInfo
{
   int objectType{};    //!< signalled audioObjectType (5 = SBR, 29 = PS)
   int sampleRate{};    //!< sampling frequency of the core (AAC) layer
   bool sbr{};
   bool ps{};
};

class BitReader
{
   const uint8_t *mData;
   size_t mBits;
   size_t mPos{};
public:
   BitReader(const uint8_t *data, size_t size) : mData{ data }, mBits{ size * 8 } {}
   bool Read(unsigned count, uint32_t &value)
   {
      if (mPos + count > mBits)
         return false;
      value = 0;
      for (unsigned i = 0; i < count; ++i, ++mPos)
         value = (value << 1) | ((mData[mPos / 8] >> (7 - mPos % 8)) & 1u);
      return true;
   }
};

std::optional<AscInfo> ParseAsc(const uint8_t *data, size_t size)
{
   static const int rates[] = { 96000, 88200, 64000, 48000, 44100, 32000,
      24000, 22050, 16000, 12000, 11025, 8000, 7350 };
   BitReader bits{ data, size };
   const auto readObjectType = [&](uint32_t &type) {
      if (!bits.Read(5, type))
         return false;
      if (type == 31) {
         uint32_t ext;
         if (!bits.Read(6, ext))
            return false;
         type = 32 + ext;
      }
      return true;
   };
   const auto readRate = [&](int &rate) {
      uint32_t index;
      if (!bits.Read(4, index))
         return false;
      if (index == 15) {
         uint32_t explicitRate;
         if (!bits.Read(24, explicitRate))
            return false;
         rate = int(explicitRate);
      }
      else
         rate = index < std::size(rates) ? rates[index] : 0;
      return true;
   };

   AscInfo info;
   uint32_t type, channelConfig;
   if (!readObjectType(type) || !readRate(info.sampleRate) ||
       !bits.Read(4, channelConfig))
      return std::nullopt;
   info.objectType = int(type);
   if (type == 5 || type == 29) {
      // Explicit hierarchical signalling: extension rate, then core type
      info.sbr = true;
      info.ps = type == 29;
      int extensionRate;
      if (!readRate(extensionRate))
         return std::nullopt;
   }
   return info;
}

// --------------------------------------------------------------------------
// Capability probe
// --------------------------------------------------------------------------
struct ProbeOutcome
{
   bool encoded{};                 //!< at least one access unit came out
   bool timedOut{};                //!< neither output nor an error in time
   std::optional<AscInfo> asc;
};

//! Configures an encoder, encodes kProbeFrames of silence and checks that
//! encoded audio comes out.  Codec2's software AAC encoder validates some
//! settings only when encoding starts, so configure()/start() alone would
//! not be a reliable test.
ProbeOutcome ProbeEncode(int rate, int channels, int profile, int bitrate)
{
   ProbeOutcome outcome;
   CodecPtr codec{ AMediaCodec_createEncoderByType(kAacMime) };
   const auto format = MakeEncoderFormat(rate, channels, profile, bitrate);
   if (!codec || !format)
      return outcome;
   AMediaCodec *const c = codec.get();
   if (AMediaCodec_configure(c, format.get(), nullptr, nullptr,
          AMEDIACODEC_CONFIGURE_FLAG_ENCODE) != AMEDIA_OK ||
       AMediaCodec_start(c) != AMEDIA_OK)
      return outcome;

   const size_t frameBytes = sizeof(int16_t) * size_t(channels);
   size_t framesLeft = kProbeFrames;
   int64_t framesQueued = 0;
   bool inEos = false;
   const auto deadline = Clock::now() + kProbeTimeout;
   while (Clock::now() < deadline && !outcome.encoded) {
      if (!inEos) {
         const ssize_t index = AMediaCodec_dequeueInputBuffer(c, 0);
         if (index >= 0) {
            size_t capacity = 0;
            uint8_t *const buffer = AMediaCodec_getInputBuffer(c, size_t(index), &capacity);
            if (!buffer)
               break;
            const uint64_t pts = uint64_t(framesQueued * 1000000 / rate);
            media_status_t status;
            if (framesLeft == 0) {
               status = AMediaCodec_queueInputBuffer(c, size_t(index), 0, 0,
                  pts, AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM);
               inEos = true;
            }
            else {
               const size_t n = std::min(framesLeft, capacity / frameBytes);
               if (n == 0)
                  break;
               std::memset(buffer, 0, n * frameBytes);
               status = AMediaCodec_queueInputBuffer(c, size_t(index), 0,
                  n * frameBytes, pts, 0);
               framesLeft -= n;
               framesQueued += int64_t(n);
            }
            if (status != AMEDIA_OK)
               break;
         }
         else if (index != AMEDIACODEC_INFO_TRY_AGAIN_LATER)
            break;
      }

      AMediaCodecBufferInfo info{};
      const ssize_t index = AMediaCodec_dequeueOutputBuffer(c, &info, 5000);
      if (index == AMEDIACODEC_INFO_OUTPUT_FORMAT_CHANGED) {
         FormatPtr output{ AMediaCodec_getOutputFormat(c) };
         void *csd = nullptr;
         size_t csdSize = 0;
         if (output &&
             AMediaFormat_getBuffer(output.get(), keys::kCsd0, &csd, &csdSize) &&
             csd)
            outcome.asc = ParseAsc(static_cast<const uint8_t *>(csd), csdSize);
         continue;
      }
      if (index == AMEDIACODEC_INFO_TRY_AGAIN_LATER ||
          index == AMEDIACODEC_INFO_OUTPUT_BUFFERS_CHANGED)
         continue;
      if (index < 0)
         break;   // codec error: these settings do not work
      if (info.flags & AMEDIACODEC_BUFFER_FLAG_CODEC_CONFIG) {
         size_t capacity = 0;
         uint8_t *const base = AMediaCodec_getOutputBuffer(c, size_t(index), &capacity);
         if (base && info.size > 0 && info.offset >= 0 && !outcome.asc &&
             size_t(info.offset) + size_t(info.size) <= capacity)
            outcome.asc = ParseAsc(base + info.offset, size_t(info.size));
      }
      else if (info.size > 0)
         outcome.encoded = true;
      const bool eos = info.flags & AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM;
      AMediaCodec_releaseOutputBuffer(c, size_t(index), false);
      if (eos)
         break;
   }
   outcome.timedOut = !outcome.encoded && Clock::now() >= deadline;
   AMediaCodec_stop(c);
   return outcome;
}

AacEncoderCaps ProbeAacEncoder()
{
   AacEncoderCaps caps;
   const auto started = Clock::now();
   {
      CodecPtr encoder{ AMediaCodec_createEncoderByType(kAacMime) };
      if (!encoder) {
         AUMEDIA_LOGW("no AAC encoder on this device");
         return caps;
      }
      char *name = nullptr;
      if (AMediaCodec_getName(encoder.get(), &name) == AMEDIA_OK && name) {
         AUMEDIA_LOGI("AAC encoder: %s", name);
         AMediaCodec_releaseName(encoder.get(), name);
      }
   }
   caps.available = true;

   const auto deadline = started + kProbeBudget;
   int timeouts = 0;
   for (const int rate : kCandidateRates) {
      if (Clock::now() > deadline || timeouts >= kMaxProbeTimeouts)
         break;
      const auto outcome = ProbeEncode(rate, 2, kAacProfileLC, 64000);
      if (outcome.encoded)
         caps.sampleRates.push_back(rate);
      else if (outcome.timedOut)
         ++timeouts;
   }
   caps.verified = !caps.sampleRates.empty();
   if (!caps.verified)
      caps.sampleRates.assign(std::begin(kFallbackRates), std::end(kFallbackRates));

   caps.profiles = { kAacProfileLC };
   const int probeRate = Contains(caps.sampleRates, 44100) ? 44100
      : Contains(caps.sampleRates, 48000) ? 48000 : 0;
   if (caps.verified && probeRate != 0 && timeouts < kMaxProbeTimeouts) {
      // HE-AAC: SBR signalled explicitly, or implicitly (core at half rate)
      if (Clock::now() < deadline) {
         const auto he = ProbeEncode(probeRate, 2, kAacProfileHE, 48000);
         if (he.encoded && he.asc &&
             (he.asc->sbr || he.asc->sampleRate * 2 == probeRate))
            caps.profiles.push_back(kAacProfileHE);
      }
      if (Clock::now() < deadline) {
         const auto ps = ProbeEncode(probeRate, 2, kAacProfileHEv2, 32000);
         if (ps.encoded && ps.asc &&
             (ps.asc->ps || (!ps.asc->sbr && ps.asc->sampleRate * 2 == probeRate)))
            caps.profiles.push_back(kAacProfileHEv2);
      }
   }

   std::string rates;
   for (const int rate : caps.sampleRates)
      rates += (rates.empty() ? "" : ",") + std::to_string(rate);
   AUMEDIA_LOGI("AAC encoder probe (%lld ms): %s rates [%s], profiles:%s%s%s",
      (long long)std::chrono::duration_cast<std::chrono::milliseconds>(
         Clock::now() - started).count(),
      caps.verified ? "verified" : "UNVERIFIED", rates.c_str(),
      " LC", Contains(caps.profiles, kAacProfileHE) ? " HE" : "",
      Contains(caps.profiles, kAacProfileHEv2) ? " HEv2" : "");
   return caps;
}

//! Rates offered for a profile.  SBR needs an output rate of 16-48 kHz.
std::vector<int> SampleRatesFor(const AacEncoderCaps &caps, int profile)
{
   if (profile == kAacProfileLC)
      return caps.sampleRates;
   std::vector<int> result;
   for (const int rate : caps.sampleRates)
      if (rate >= kMinSbrRate && rate <= kMaxSbrRate)
         result.push_back(rate);
   return result.empty() ? caps.sampleRates : result;
}

// --------------------------------------------------------------------------
// Options
// --------------------------------------------------------------------------
enum : int {
   OptionIDBitRate = 0,      //!< AAC-LC bit rate (bps)
   OptionIDProfile = 1,      //!< 2 / 5 / 29
   OptionIDBitRateHE = 2,    //!< HE-AAC bit rate (bps)
   OptionIDBitRateHEv2 = 3,  //!< HE-AAC v2 bit rate (bps)
};

constexpr int kDefaultBitRate = 192000;
constexpr int kDefaultBitRateHE = 48000;
constexpr int kDefaultBitRateHEv2 = 32000;

/* i18n-hint: kbps is the bitrate of the file, kilobits per second */
inline TranslatableString n_kbps(int n) { return XO("%d kbps").Format(n); }

ExportOption BitRateOption(int id, std::initializer_list<int> kbps, int defaultBps)
{
   ExportOption option{ id, XO("Bit Rate"), defaultBps, ExportOption::TypeEnum };
   for (const int k : kbps) {
      option.values.emplace_back(k * 1000);
      option.names.push_back(n_kbps(k));
   }
   return option;
}

int BitRateOptionFor(int profile)
{
   switch (profile) {
   case kAacProfileHE: return OptionIDBitRateHE;
   case kAacProfileHEv2: return OptionIDBitRateHEv2;
   default: return OptionIDBitRate;
   }
}

int DefaultBitRateFor(int profile)
{
   switch (profile) {
   case kAacProfileHE: return kDefaultBitRateHE;
   case kAacProfileHEv2: return kDefaultBitRateHEv2;
   default: return kDefaultBitRate;
   }
}

const wxChar *ConfigKey(int id)
{
   switch (id) {
   case OptionIDProfile: return wxT("/FileFormats/AndroidAAC/Profile");
   case OptionIDBitRateHE: return wxT("/FileFormats/AndroidAAC/BitRateHE");
   case OptionIDBitRateHEv2: return wxT("/FileFormats/AndroidAAC/BitRateHEv2");
   default: return wxT("/FileFormats/AndroidAAC/BitRate");
   }
}

class AACOptionsEditor final : public ExportOptionsEditor
{
   const AacEncoderCaps &mCaps;
   Listener *const mListener;
   //! In display order: Profile, then the bit rates
   std::vector<ExportOption> mOptions;
   std::unordered_map<int, ExportValue> mValues;

public:
   AACOptionsEditor(const AacEncoderCaps &caps, Listener *listener)
      : mCaps{ caps }, mListener{ listener }
   {
      ExportOption profile{ OptionIDProfile, XO("Profile"), kAacProfileLC,
         ExportOption::TypeEnum };
      for (const int p : mCaps.profiles) {
         profile.values.emplace_back(p);
         profile.names.push_back(Verbatim(ProfileName(p)));
      }
      if (mCaps.profiles.size() < 2)
         profile.flags |= ExportOption::ReadOnly;
      mOptions.push_back(std::move(profile));

      mOptions.push_back(BitRateOption(OptionIDBitRate,
         { 32, 48, 64, 96, 128, 160, 192, 256, 320 }, kDefaultBitRate));
      if (Contains(mCaps.profiles, kAacProfileHE))
         mOptions.push_back(BitRateOption(OptionIDBitRateHE,
            { 24, 32, 48, 64 }, kDefaultBitRateHE));
      if (Contains(mCaps.profiles, kAacProfileHEv2))
         mOptions.push_back(BitRateOption(OptionIDBitRateHEv2,
            { 16, 24, 32, 48 }, kDefaultBitRateHEv2));

      for (const auto &option : mOptions)
         mValues[option.id] = option.defaultValue;
      UpdateVisibility();
   }

   int GetOptionsCount() const override { return int(mOptions.size()); }

   bool GetOption(int index, ExportOption &option) const override
   {
      if (index < 0 || index >= int(mOptions.size()))
         return false;
      option = mOptions[size_t(index)];
      return true;
   }

   bool GetValue(ExportOptionID id, ExportValue &value) const override
   {
      const auto it = mValues.find(id);
      if (it == mValues.end())
         return false;
      value = it->second;
      return true;
   }

   bool SetValue(ExportOptionID id, const ExportValue &value) override
   {
      const auto it = mValues.find(id);
      if (it == mValues.end() || it->second.index() != value.index() ||
          !IsAllowed(id, value))
         return false;
      if (it->second == value)
         return true;
      it->second = value;
      if (id == OptionIDProfile) {
         UpdateVisibility();
         if (mListener) {
            mListener->OnExportOptionChangeBegin();
            for (const auto &option : mOptions)
               if (option.id != OptionIDProfile)
                  mListener->OnExportOptionChange(option);
            mListener->OnExportOptionChangeEnd();
            mListener->OnSampleRateListChange();
         }
      }
      return true;
   }

   SampleRateList GetSampleRateList() const override
   {
      return SampleRatesFor(mCaps, Profile());
   }

   void Load(const audacity::BasicSettings &config) override
   {
      for (auto &[id, value] : mValues) {
         int stored;
         if (config.Read(ConfigKey(id), &stored) && IsAllowed(id, stored))
            value = stored;
      }
      UpdateVisibility();
   }

   void Store(audacity::BasicSettings &config) const override
   {
      for (const auto &[id, value] : mValues)
         if (const auto number = std::get_if<int>(&value))
            config.Write(ConfigKey(id), *number);
   }

private:
   int Profile() const
   {
      const auto it = mValues.find(OptionIDProfile);
      const auto profile = it == mValues.end() ? nullptr : std::get_if<int>(&it->second);
      return profile ? *profile : kAacProfileLC;
   }

   //! Enum options accept only listed values (PlainExportOptionsEditor
   //! checks the type only)
   bool IsAllowed(ExportOptionID id, const ExportValue &value) const
   {
      for (const auto &option : mOptions)
         if (option.id == id)
            return (option.flags & ExportOption::TypeMask) != ExportOption::TypeEnum ||
               std::find(option.values.begin(), option.values.end(), value) !=
                  option.values.end();
      return false;
   }

   void UpdateVisibility()
   {
      const int visible = BitRateOptionFor(Profile());
      for (auto &option : mOptions) {
         if (option.id == OptionIDProfile)
            continue;
         if (option.id == visible)
            option.flags &= ~ExportOption::Hidden;
         else
            option.flags |= ExportOption::Hidden;
      }
   }
};

// --------------------------------------------------------------------------
// Processor
// --------------------------------------------------------------------------
[[noreturn]] void ThrowEncoderError(const char *what, long code)
{
   AUMEDIA_LOGE("AAC export: %s failed (%ld)", what, code);
   throw ExportErrorException(
      XO("The Android AAC encoder failed (%s, error %d).\n"
         "Another profile, bit rate or sample rate may work.")
         .Format(wxString::FromUTF8(what), int(code)),
      wxT("Error:_Unable_to_export"));
}

class AACExportProcessor final : public ExportProcessor
{
   struct
   {
      TranslatableString status;
      double t0{};
      double t1{};
      unsigned channels{};
      int rate{};
      int profile{};
      int bitrate{};
      std::unique_ptr<Mixer> mixer;
      wxFileNameWrapper fName;
      std::string path;          //!< UTF-8, for open/unlink
      CodecPtr codec;
      UniqueFd fd;
      MuxerPtr muxer;
      bool muxerStarted{ false };
      bool fileCreated{ false };
      bool finished{ false };    //!< the file is complete: keep it
   } context;

public:
   AACExportProcessor() = default;
   ~AACExportProcessor() override { Abort(); }

   bool Initialize(AudacityProject &project, const Parameters &parameters,
      const wxFileNameWrapper &fName, double t0, double t1, bool selectionOnly,
      double sampleRate, unsigned numChannels,
      MixerOptions::Downmix *mixerSpec, const Tags *metadata) override;

   ExportResult Process(ExportProcessorDelegate &delegate) override;

private:
   ExportResult Encode(ExportProcessorDelegate &delegate);
   //! Adds the track (output format + csd-0) and starts the muxer.  Without
   //! codec specific data: returns false, or throws if \p mustStart
   bool StartMuxer(const std::vector<uint8_t> &codecConfig, ssize_t &track,
      bool mustStart);
   //! Releases codec, muxer, file; deletes the file unless finished
   void Abort() noexcept;
};

void AACExportProcessor::Abort() noexcept
{
   context.mixer.reset();
   context.codec.reset();
   if (context.muxer && context.muxerStarted)
      AMediaMuxer_stop(context.muxer.get());   // result irrelevant
   context.muxerStarted = false;
   context.muxer.reset();
   context.fd.Reset();
   if (context.fileCreated && !context.finished) {
      ::unlink(context.path.c_str());
      context.fileCreated = false;
   }
}

bool AACExportProcessor::Initialize(AudacityProject &project,
   const Parameters &parameters, const wxFileNameWrapper &fName,
   double t0, double t1, bool selectionOnly, double sampleRate,
   unsigned numChannels, MixerOptions::Downmix *mixerSpec, const Tags *)
{
   const auto &caps = AacEncoderCapabilities();
   if (!caps.available)
      throw ExportException(_("This device has no AAC encoder."));
   if (numChannels < 1 || numChannels > 2)
      throw ExportException(_("AAC export supports mono and stereo only."));

   int profile = ExportPluginHelpers::GetParameterValue<int>(
      parameters, OptionIDProfile, kAacProfileLC);
   if (!Contains(caps.profiles, profile)) {
      AUMEDIA_LOGW("AAC profile %d not supported here; using AAC-LC", profile);
      profile = kAacProfileLC;
   }
   // The bit rate the user chose, from the option of the chosen profile
   int bitrate = ExportPluginHelpers::GetParameterValue<int>(
      parameters, BitRateOptionFor(profile), DefaultBitRateFor(profile));
   if (bitrate <= 0)
      bitrate = DefaultBitRateFor(profile);
   if (profile == kAacProfileHEv2 && numChannels == 1)
      // Parametric stereo needs a stereo input; mono HE-AAC v2 = HE-AAC
      profile = Contains(caps.profiles, kAacProfileHE) ? kAacProfileHE : kAacProfileLC;

   const int rate = int(std::lround(sampleRate));
   if (!Contains(SampleRatesFor(caps, profile), rate))
      throw ExportException(wxString::Format(
         _("The AAC encoder of this device does not support a sample rate of %d Hz for %s."),
         rate, ProfileName(profile)));

   if (profile == kAacProfileLC)
      // AAC-LC carries at most 6144 bits per channel per 1024 samples
      bitrate = std::min(bitrate, 6 * rate * int(numChannels));

   context.t0 = t0;
   context.t1 = t1;
   context.channels = numChannels;
   context.rate = rate;
   context.profile = profile;
   context.bitrate = bitrate;
   context.fName = fName;
   context.path = fName.GetFullPath().utf8_str().data();

   // 1. Encoder first: nothing is written if it rejects the settings
   context.codec.reset(AMediaCodec_createEncoderByType(kAacMime));
   if (!context.codec)
      throw ExportException(_("This device has no AAC encoder."));
   {
      const auto format = MakeEncoderFormat(rate, int(numChannels), profile, bitrate);
      media_status_t status = format ? AMediaCodec_configure(context.codec.get(),
         format.get(), nullptr, nullptr, AMEDIACODEC_CONFIGURE_FLAG_ENCODE)
         : AMEDIA_ERROR_UNKNOWN;
      if (status == AMEDIA_OK)
         status = AMediaCodec_start(context.codec.get());
      if (status != AMEDIA_OK) {
         AUMEDIA_LOGE("AAC encoder configure/start failed (%d): %s %d bps %d Hz %u ch",
            int(status), ProfileName(profile), bitrate, rate, numChannels);
         Abort();
         throw ExportException(wxString::Format(
            _("The AAC encoder of this device rejected the settings (%s, %d kbps, %d Hz, %u channels)."),
            ProfileName(profile), bitrate / 1000, rate, numChannels));
      }
   }

   try {
      // 2. File (the MPEG-4 writer seeks back to write the 'moov' box)
      context.fd.Reset(::open(context.path.c_str(),
         O_RDWR | O_CREAT | O_TRUNC | O_CLOEXEC, 0644));
      if (!context.fd)
         throw ExportException(_("Unable to open target file for writing"));
      context.fileCreated = true;

      context.muxer.reset(AMediaMuxer_new(context.fd.Get(), AMEDIAMUXER_OUTPUT_FORMAT_MPEG_4));
      if (!context.muxer)
         throw ExportException(_("Unable to create the MPEG-4 (M4A) container."));

      // 3. Mixer: interleaved 16 bit, the encoder's native input
      context.mixer = ExportPluginHelpers::CreateMixer(project, selectionOnly,
         t0, t1, numChannels, kMixerFrames, true, sampleRate, int16Sample,
         mixerSpec);
   }
   catch (...) {
      Abort();
      throw;
   }

   context.status = selectionOnly
      ? XO("Exporting the selected audio as AAC")
      : XO("Exporting the audio as AAC");
   AUMEDIA_LOGI("AAC export: %s, %d bps, %d Hz, %u ch -> %s", ProfileName(profile),
      bitrate, rate, numChannels, context.path.c_str());
   return true;
}

ExportResult AACExportProcessor::Process(ExportProcessorDelegate &delegate)
{
   try {
      return Encode(delegate);
   }
   catch (...) {
      Abort();
      throw;
   }
}

bool AACExportProcessor::StartMuxer(
   const std::vector<uint8_t> &codecConfig, ssize_t &track, bool mustStart)
{
   FormatPtr output{ AMediaCodec_getOutputFormat(context.codec.get()) };
   if (!output)
      ThrowEncoderError("getOutputFormat", 0);
   void *csd = nullptr;
   size_t csdSize = 0;
   if (!AMediaFormat_getBuffer(output.get(), keys::kCsd0, &csd, &csdSize)) {
      // Some encoders deliver the AudioSpecificConfig only as a
      // CODEC_CONFIG buffer (after the format change); the MPEG-4 writer
      // needs it in the track format
      if (codecConfig.empty()) {
         if (!mustStart)
            return false;      // wait for the CODEC_CONFIG buffer
         ThrowEncoderError("no codec specific data", 0);
      }
      AMediaFormat_setBuffer(output.get(), keys::kCsd0, codecConfig.data(),
         codecConfig.size());
   }
   track = AMediaMuxer_addTrack(context.muxer.get(), output.get());
   if (track < 0)
      ThrowEncoderError("AMediaMuxer_addTrack", long(track));
   if (const auto status = AMediaMuxer_start(context.muxer.get()); status != AMEDIA_OK)
      ThrowEncoderError("AMediaMuxer_start", status);
   context.muxerStarted = true;
   return true;
}

ExportResult AACExportProcessor::Encode(ExportProcessorDelegate &delegate)
{
   delegate.SetStatusString(context.status);

   AMediaCodec *const codec = context.codec.get();
   const size_t frameBytes = sizeof(int16_t) * context.channels;
   auto result = ExportResult::Success;

   const char *pending = nullptr;     // mixer output not yet queued
   size_t pendingFrames = 0;
   bool mixerDone = false, inEos = false, outEos = false;
   int64_t framesQueued = 0;
   int64_t lastPts = INT64_MIN;
   long long samplesWritten = 0;
   ssize_t track = -1;
   std::vector<uint8_t> codecConfig;
   auto lastActivity = Clock::now();

   while (!outEos) {
      bool fed = false;

      // 1. Feed PCM from the mixer
      while (!inEos) {
         if (pendingFrames == 0 && !mixerDone) {
            pendingFrames = context.mixer->Process();
            pending = context.mixer->GetBuffer();
            if (pendingFrames == 0)
               mixerDone = true;
            result = ExportPluginHelpers::UpdateProgress(
               delegate, *context.mixer, context.t0, context.t1);
            if (result == ExportResult::Cancelled) {
               Abort();          // the task deletes the file as well
               return result;
            }
            if (result == ExportResult::Stopped)
               mixerDone = true; // queue what was mixed, then finish the file
         }

         const ssize_t index = AMediaCodec_dequeueInputBuffer(codec, 0);
         if (index == AMEDIACODEC_INFO_TRY_AGAIN_LATER)
            break;
         if (index < 0)
            ThrowEncoderError("dequeueInputBuffer", long(index));
         size_t capacity = 0;
         uint8_t *const buffer = AMediaCodec_getInputBuffer(codec, size_t(index), &capacity);
         if (!buffer)
            ThrowEncoderError("getInputBuffer", 0);

         const uint64_t pts = uint64_t(framesQueued * 1000000 / context.rate);
         media_status_t status;
         if (pendingFrames == 0) {
            status = AMediaCodec_queueInputBuffer(codec, size_t(index), 0, 0,
               pts, AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM);
            inEos = true;
         }
         else {
            const size_t n = std::min(pendingFrames, capacity / frameBytes);
            if (n == 0)
               ThrowEncoderError("input buffer too small", long(capacity));
            std::memcpy(buffer, pending, n * frameBytes);
            status = AMediaCodec_queueInputBuffer(codec, size_t(index), 0,
               n * frameBytes, pts, 0);
            pending += n * frameBytes;
            pendingFrames -= n;
            framesQueued += int64_t(n);
         }
         if (status != AMEDIA_OK)
            ThrowEncoderError("queueInputBuffer", status);
         fed = true;
         lastActivity = Clock::now();
      }

      // 2. Drain encoded access units into the muxer
      int64_t timeoutUs = fed ? 0 : kDequeueTimeoutUs;
      while (!outEos) {
         AMediaCodecBufferInfo info{};
         const ssize_t index = AMediaCodec_dequeueOutputBuffer(codec, &info, timeoutUs);
         timeoutUs = 0;
         if (index == AMEDIACODEC_INFO_TRY_AGAIN_LATER)
            break;
         if (index == AMEDIACODEC_INFO_OUTPUT_BUFFERS_CHANGED)
            continue;
         if (index == AMEDIACODEC_INFO_OUTPUT_FORMAT_CHANGED) {
            // Carries csd-0 (AudioSpecificConfig) for the MPEG-4 'esds'
            if (!context.muxerStarted)
               StartMuxer(codecConfig, track, false);
            else
               AUMEDIA_LOGW("AAC export: ignoring a second output format change");
            lastActivity = Clock::now();
            continue;
         }
         if (index < 0)
            ThrowEncoderError("dequeueOutputBuffer", long(index));

         lastActivity = Clock::now();
         const auto release = finally([codec, index]{
            AMediaCodec_releaseOutputBuffer(codec, size_t(index), false); });
         if (info.flags & AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM)
            outEos = true;
         if (info.size <= 0)
            continue;
         size_t capacity = 0;
         uint8_t *const base = AMediaCodec_getOutputBuffer(codec, size_t(index), &capacity);
         if (!base || info.offset < 0 ||
             size_t(info.offset) + size_t(info.size) > capacity)
            ThrowEncoderError("getOutputBuffer", long(info.size));
         if (info.flags & AMEDIACODEC_BUFFER_FLAG_CODEC_CONFIG) {
            codecConfig.assign(base + info.offset, base + info.offset + info.size);
            continue;
         }
         if (!context.muxerStarted)
            StartMuxer(codecConfig, track, true);

         AMediaCodecBufferInfo sample = info;
         sample.flags &= ~uint32_t(AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM);
         // The MPEG-4 writer wants non-decreasing time stamps
         if (sample.presentationTimeUs < lastPts)
            sample.presentationTimeUs = lastPts;
         lastPts = sample.presentationTimeUs;
         // Pass the buffer *base*: AMediaMuxer_writeSampleData adds
         // info.offset itself
         if (AMediaMuxer_writeSampleData(context.muxer.get(), size_t(track),
                base, &sample) != AMEDIA_OK)
            throw ExportDiskFullError(context.fName);
         ++samplesWritten;
      }

      if (!outEos && Clock::now() - lastActivity > kStallTimeout)
         ThrowEncoderError("encoder stalled", 0);
   }

   if (samplesWritten == 0 || !context.muxerStarted)
      throw ExportException(_("The AAC encoder produced no audio."));

   // 3. Finalise: 'moov' is written by stop()
   context.codec.reset();
   const auto status = AMediaMuxer_stop(context.muxer.get());
   context.muxerStarted = false;
   context.muxer.reset();
   if (status != AMEDIA_OK) {
      AUMEDIA_LOGE("AMediaMuxer_stop failed (%d)", int(status));
      throw ExportDiskFullError(context.fName);
   }
   if (context.fd.Reset() != 0)
      throw ExportDiskFullError(context.fName);
   context.mixer.reset();
   context.finished = true;
   AUMEDIA_LOGI("AAC export finished: %lld access units, %lld frames%s",
      samplesWritten, (long long)framesQueued,
      result == ExportResult::Stopped ? " (stopped)" : "");
   return result;    // Success, or Stopped (a valid, shorter file)
}

// --------------------------------------------------------------------------
// Plug-in
// --------------------------------------------------------------------------
class ExportAndroidAAC final : public ExportPlugin
{
public:
   int GetFormatCount() const override { return 1; }

   FormatInfo GetFormatInfo(int) const override
   {
      return { wxT("M4A"), XO("M4A (AAC) Files"), { wxT("m4a") }, 2u, false };
   }

   std::vector<std::string> GetMimeTypes(int) const override
   {
      return { "audio/mp4" };
   }

   std::unique_ptr<ExportOptionsEditor> CreateOptionsEditor(
      int, ExportOptionsEditor::Listener *listener) const override
   {
      return std::make_unique<AACOptionsEditor>(AacEncoderCapabilities(), listener);
   }

   std::unique_ptr<ExportProcessor> CreateProcessor(int) const override
   {
      return std::make_unique<AACExportProcessor>();
   }
};

// Listed right after MP3 in the export format list (pref "/Exporters")
ExportPluginRegistry::RegisteredPlugin sRegisteredPlugin{ kExporterRegistryId,
   []{ return std::make_unique<ExportAndroidAAC>(); },
   Registry::Placement{ wxEmptyString,
      { Registry::OrderingHint::After, wxT("MP3") } } };

} // namespace

const AacEncoderCaps &AacEncoderCapabilities()
{
   static AacEncoderCaps caps;
   static std::once_flag once;
   std::call_once(once, []{ caps = ProbeAacEncoder(); });
   return caps;
}

} // namespace aubridge::android_media
