/*  SPDX-License-Identifier: GPL-2.0-or-later */
/**********************************************************************

  Audacity Android port -- host test harness of native/bridge/io/android

  fake_control.h: scenario control of the fake NDK media layer

**********************************************************************/
#pragma once
#include <cstdint>
#include <map>
#include <string>
#include <vector>

namespace fake {

struct Track {
   std::string mime = "audio/mp4a-latm";
   int rate = 44100, channels = 2;
   int64_t durationUs = -2;        // -2: computed, -1/0: unknown
   std::string language;
   int bitrate = 128000;
   int framesPerPacket = 1024;
   int64_t firstPtsUs = 0;
   int totalFrames = 44100;
   bool encrypted = false;
   bool hasMaxInputSize = true;
   int rawEncoding = 0;            // audio/raw: input = output encoding (3, 21, 22, 2, 4)
};

struct Media {
   std::string containerMime = "audio/mp4";
   std::vector<Track> tracks;
   std::map<std::string, std::string> fileMeta;
   bool drm = false;
   int readErrorAtPacket = -1;
};

struct Decoder {
   bool exists = true;
   bool honourFloat = true;
   bool rejectFloatConfigure = false;
   bool formatChangeFirst = true;
   int settleFrames = 0, settleRate = 0, settleChannels = 0;
   int rateChangeAtPacket = -1, newRate = 0;
   int errorAtPacket = -1;
   bool neverEos = false;
   int outputOffset = 0;
   int maxPendingOutputs = 4;
};

struct Encoder {
   bool exists = true;
   std::vector<int> rates{ 8000, 11025, 12000, 16000, 22050, 24000, 32000, 44100, 48000 };
   std::vector<int> profiles{ 2, 5, 29 };
   bool explicitSignalling = true;
   bool ignoreProfile = false;
   bool emitCodecConfigBuffer = true;
   bool csdInFormat = true;
   long long errorAtFrame = -1;
   int outputOffset = 7;
   bool hang = false;
};

struct Muxer {
   int failWriteAt = -1;
};

struct Stats {
   int extractorsLive = 0, codecsLive = 0, muxersLive = 0, formatsLive = 0;
   int outstandingOutputs = 0;
   int encodersCreated = 0, decodersCreated = 0;
   // muxer
   int muxerSamples = 0;
   long long muxerFrames = 0;
   bool muxerStarted = false, muxerStopped = false;
   bool muxerBadPayload = false, muxerPtsNotMonotonic = false;
   std::vector<uint8_t> muxerCsd;
   int lastEncoderProfile = 0, lastEncoderRate = 0, lastEncoderChannels = 0, lastEncoderBitrate = 0;
};

extern Media gMedia;
extern Decoder gDecoder;
extern Encoder gEncoder;
extern Muxer gMuxer;
extern Stats gStats;
void Reset();
//! Source signal of track t, channel c, frame n
float Signal(size_t t, int c, long long n, int rate);

} // namespace fake
