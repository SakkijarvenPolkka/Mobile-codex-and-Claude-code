/*  SPDX-License-Identifier: GPL-2.0-or-later */
/**********************************************************************

  Audacity Android port -- host test harness of native/bridge/io/android

  fake_ndk.cpp: fake AMediaExtractor / AMediaCodec / AMediaMuxer /
  AMediaFormat with scripted behaviour (format changes, errors, offsets,
  missing EOS, codec specific data, ...).

**********************************************************************/
#include "fake_ndk_media.h"
#include "android/log.h"
#include "fake_control.h"

#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstdarg>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <deque>
#include <map>
#include <string>
#include <thread>
#include <variant>
#include <vector>
#include <unistd.h>

namespace fake {
Media gMedia; Decoder gDecoder; Encoder gEncoder; Muxer gMuxer; Stats gStats;
void Reset() { gMedia = {}; gDecoder = {}; gEncoder = {}; gMuxer = {}; gStats = {}; }
float Signal(size_t t, int c, long long n, int rate)
{ return float(0.5 * std::sin(2 * M_PI * (440.0 + 110.0 * c + 50.0 * t) * double(n) / rate)); }
}
using namespace fake;

extern "C" int __android_log_print(int prio, const char *tag, const char *fmt, ...)
{
   static const char *names = "??VDIWEF";
   std::fprintf(stderr, "  [%c/%s] ", names[prio & 7], tag);
   va_list ap; va_start(ap, fmt); std::vfprintf(stderr, fmt, ap); va_end(ap);
   std::fputc('\n', stderr);
   return 0;
}

const char *AMEDIAFORMAT_KEY_MIME = "mime";
const char *AMEDIAFORMAT_KEY_SAMPLE_RATE = "sample-rate";
const char *AMEDIAFORMAT_KEY_CHANNEL_COUNT = "channel-count";
const char *AMEDIAFORMAT_KEY_DURATION = "durationUs";
const char *AMEDIAFORMAT_KEY_BIT_RATE = "bitrate";
const char *AMEDIAFORMAT_KEY_LANGUAGE = "language";
const char *AMEDIAFORMAT_KEY_MAX_INPUT_SIZE = "max-input-size";
const char *AMEDIAFORMAT_KEY_AAC_PROFILE = "aac-profile";

// ---------------------------------------------------------------- format
struct AMediaFormat {
   std::map<std::string, std::variant<int32_t, int64_t, std::string, std::vector<uint8_t>>> v;
};
AMediaFormat *AMediaFormat_new() { ++gStats.formatsLive; return new AMediaFormat; }
media_status_t AMediaFormat_delete(AMediaFormat *f) { if (f) { --gStats.formatsLive; delete f; } return AMEDIA_OK; }
template<typename T> static bool Get(AMediaFormat *f, const char *n, T *out)
{
   auto it = f->v.find(n); if (it == f->v.end()) return false;
   if (auto p = std::get_if<T>(&it->second)) { *out = *p; return true; }
   return false;
}
bool AMediaFormat_getInt32(AMediaFormat *f, const char *n, int32_t *o) { return Get(f, n, o); }
bool AMediaFormat_getInt64(AMediaFormat *f, const char *n, int64_t *o) { return Get(f, n, o); }
bool AMediaFormat_getString(AMediaFormat *f, const char *n, const char **o)
{ auto it = f->v.find(n); if (it == f->v.end()) return false; auto p = std::get_if<std::string>(&it->second); if (!p) return false; *o = p->c_str(); return true; }
bool AMediaFormat_getBuffer(AMediaFormat *f, const char *n, void **d, size_t *s)
{ auto it = f->v.find(n); if (it == f->v.end()) return false; auto p = std::get_if<std::vector<uint8_t>>(&it->second); if (!p) return false; *d = p->data(); *s = p->size(); return true; }
void AMediaFormat_setInt32(AMediaFormat *f, const char *n, int32_t x) { f->v[n] = x; }
void AMediaFormat_setInt64(AMediaFormat *f, const char *n, int64_t x) { f->v[n] = x; }
void AMediaFormat_setString(AMediaFormat *f, const char *n, const char *x) { f->v[n] = std::string(x); }
void AMediaFormat_setBuffer(AMediaFormat *f, const char *n, const void *d, size_t s)
{ auto p = static_cast<const uint8_t*>(d); f->v[n] = std::vector<uint8_t>(p, p + s); }

static int BytesPerSample(int enc) { switch (enc) { case 3: return 1; case 21: return 3; case 22: return 4; case 4: return 4; default: return 2; } }

// ------------------------------------------------------------- extractor
struct AMediaExtractor { bool opened = false; int selected = -1; long long packet = 0; };
static long long PacketCount(const Track &t) { return (t.totalFrames + t.framesPerPacket - 1) / t.framesPerPacket; }
static int PacketFrames(const Track &t, long long p) { return int(std::min<long long>(t.framesPerPacket, t.totalFrames - p * t.framesPerPacket)); }
static int InBytesPerFrame(const Track &t) { return t.rawEncoding ? BytesPerSample(t.rawEncoding) * t.channels : 4 * t.channels; }

AMediaExtractor *AMediaExtractor_new() { ++gStats.extractorsLive; return new AMediaExtractor; }
media_status_t AMediaExtractor_delete(AMediaExtractor *e) { if (e) { --gStats.extractorsLive; delete e; } return AMEDIA_OK; }
media_status_t AMediaExtractor_setDataSourceFd(AMediaExtractor *e, int fd, off64_t off, off64_t len)
{
   char magic[9] = {};
   if (len < 9 || pread(fd, magic, 9, off) != 9 || std::memcmp(magic, "FAKEMEDIA", 9) != 0)
      return AMEDIA_ERROR_UNSUPPORTED;
   e->opened = true; return AMEDIA_OK;
}
size_t AMediaExtractor_getTrackCount(AMediaExtractor *e) { return e->opened ? gMedia.tracks.size() : 0; }
AMediaFormat *AMediaExtractor_getTrackFormat(AMediaExtractor *, size_t i)
{
   if (i >= gMedia.tracks.size()) return nullptr;
   const auto &t = gMedia.tracks[i];
   auto f = AMediaFormat_new();
   AMediaFormat_setString(f, "mime", t.mime.c_str());
   if (t.mime.rfind("audio/", 0) == 0) {
      AMediaFormat_setInt32(f, "sample-rate", t.rate);
      AMediaFormat_setInt32(f, "channel-count", t.channels);
      if (t.rawEncoding) AMediaFormat_setInt32(f, "pcm-encoding", t.rawEncoding);
   }
   const int64_t dur = t.durationUs == -2 ? int64_t(t.totalFrames) * 1000000 / t.rate : t.durationUs;
   if (dur > 0) AMediaFormat_setInt64(f, "durationUs", dur);
   if (!t.language.empty()) AMediaFormat_setString(f, "language", t.language.c_str());
   if (t.bitrate) AMediaFormat_setInt32(f, "bitrate", t.bitrate);
   if (t.hasMaxInputSize) AMediaFormat_setInt32(f, "max-input-size", t.framesPerPacket * InBytesPerFrame(t));
   return f;
}
media_status_t AMediaExtractor_selectTrack(AMediaExtractor *e, size_t i)
{ if (i >= gMedia.tracks.size()) return AMEDIA_ERROR_INVALID_PARAMETER; e->selected = int(i); e->packet = 0; return AMEDIA_OK; }
static const Track *Sel(AMediaExtractor *e)
{ return (e->selected >= 0 && e->packet < PacketCount(gMedia.tracks[e->selected])) ? &gMedia.tracks[e->selected] : nullptr; }
ssize_t AMediaExtractor_getSampleSize(AMediaExtractor *e)
{ auto t = Sel(e); return t ? ssize_t(PacketFrames(*t, e->packet) * InBytesPerFrame(*t)) : -1; }
uint32_t AMediaExtractor_getSampleFlags(AMediaExtractor *e)
{ auto t = Sel(e); return t ? (t->encrypted ? 3u : 1u) : 0u; }
int64_t AMediaExtractor_getSampleTime(AMediaExtractor *e)
{ auto t = Sel(e); return t ? t->firstPtsUs + e->packet * t->framesPerPacket * 1000000LL / t->rate : -1; }
bool AMediaExtractor_advance(AMediaExtractor *e) { if (Sel(e)) ++e->packet; return Sel(e) != nullptr; }
ssize_t AMediaExtractor_readSampleData(AMediaExtractor *e, uint8_t *buf, size_t cap)
{
   auto t = Sel(e); if (!t) return -1;
   if (e->packet == gMedia.readErrorAtPacket) return -1;
   const int frames = PacketFrames(*t, e->packet);
   const size_t bytes = size_t(frames) * InBytesPerFrame(*t);
   if (bytes > cap) return -1;
   const size_t ti = size_t(e->selected);
   uint8_t *p = buf;
   for (int f = 0; f < frames; ++f)
      for (int c = 0; c < t->channels; ++c) {
         const long long n = e->packet * t->framesPerPacket + f;
         const float x = Signal(ti, c, n, t->rate);
         switch (t->rawEncoding) {
         case 3: *p++ = uint8_t(std::lround(x * 127.0) + 128); break;
         case 21: { int32_t v = int32_t(std::lround(x * 8388607.0)); p[0] = v & 0xff; p[1] = (v >> 8) & 0xff; p[2] = (v >> 16) & 0xff; p += 3; break; }
         case 22: { int32_t v = int32_t(std::lround(x * 2147483647.0)); std::memcpy(p, &v, 4); p += 4; break; }
         case 2: { int16_t v = int16_t(std::lround(x * 32767.0)); std::memcpy(p, &v, 2); p += 2; break; }
         default: std::memcpy(p, &x, 4); p += 4; break;
         }
      }
   return ssize_t(bytes);
}
static PsshInfo sPssh{ 1 };
PsshInfo *AMediaExtractor_getPsshInfo(AMediaExtractor *) { return gMedia.drm ? &sPssh : nullptr; }
AMediaFormat *AMediaExtractor_getFileFormat(AMediaExtractor *)
{
   auto f = AMediaFormat_new();
   AMediaFormat_setString(f, "mime", gMedia.containerMime.c_str());
   for (auto &[k, v] : gMedia.fileMeta) AMediaFormat_setString(f, k.c_str(), v.c_str());
   return f;
}

// ----------------------------------------------------------------- codec
struct Out { bool fc = false; int rate = 0, ch = 0, enc = 2; std::vector<uint8_t> data; int64_t pts = 0; uint32_t flags = 0; };
struct AMediaCodec {
   bool encoder = false; std::string mime;
   bool configured = false, started = false, error = false, inEos = false;
   size_t inCapacity = 8192; std::vector<std::vector<uint8_t>> inSlots;
   std::deque<Out> pending; std::map<size_t, std::vector<uint8_t>> outSlots; size_t nextSlot = 100;
   int curRate = 0, curCh = 0, curEnc = 2;
   // decoder
   int rate = 0, ch = 0, outEnc = 2, rawEnc = 0; long long packets = 0, framesOut = 0; bool sentFirst = false; bool settled = true;
   // encoder
   int profile = 2, bitrate = 0; bool failOnProcess = false; long long framesIn = 0, acc = 0, aus = 0; int64_t firstPts = -1;
};
AMediaCodec *AMediaCodec_createDecoderByType(const char *mime)
{
   if (!gDecoder.exists || std::strncmp(mime, "audio/", 6) != 0) return nullptr;
   ++gStats.codecsLive; ++gStats.decodersCreated;
   auto c = new AMediaCodec; c->mime = mime; return c;
}
AMediaCodec *AMediaCodec_createEncoderByType(const char *mime)
{
   if (!gEncoder.exists || std::strcmp(mime, "audio/mp4a-latm") != 0) return nullptr;
   ++gStats.codecsLive; ++gStats.encodersCreated;
   auto c = new AMediaCodec; c->encoder = true; c->mime = mime; return c;
}
media_status_t AMediaCodec_delete(AMediaCodec *c)
{
   if (!c) return AMEDIA_OK;
   gStats.outstandingOutputs -= int(c->outSlots.size());   // released implicitly
   if (!c->outSlots.empty()) std::fprintf(stderr, "  [fake] codec deleted with %zu unreleased output buffers\n", c->outSlots.size());
   --gStats.codecsLive; delete c; return AMEDIA_OK;
}
media_status_t AMediaCodec_configure(AMediaCodec *c, const AMediaFormat *cf, ANativeWindow *, AMediaCrypto *, uint32_t flags)
{
   auto f = const_cast<AMediaFormat*>(cf);
   if (c->configured) return AMEDIA_ERROR_INVALID_OPERATION;
   int32_t v = 0;
   if (c->encoder) {
      if (!(flags & AMEDIACODEC_CONFIGURE_FLAG_ENCODE)) return AMEDIA_ERROR_INVALID_PARAMETER;
      Get(f, "sample-rate", &c->rate); Get(f, "channel-count", &c->ch);
      Get(f, "aac-profile", &c->profile); Get(f, "bitrate", &c->bitrate);
      if (std::find(gEncoder.rates.begin(), gEncoder.rates.end(), c->rate) == gEncoder.rates.end()) return AMEDIA_ERROR_UNSUPPORTED;
      if (c->ch < 1 || c->ch > 2) return AMEDIA_ERROR_UNSUPPORTED;
      if (gEncoder.ignoreProfile) c->profile = 2;
      else if (std::find(gEncoder.profiles.begin(), gEncoder.profiles.end(), c->profile) == gEncoder.profiles.end())
         c->failOnProcess = true;   // Codec2 style: detected when encoding starts
      gStats.lastEncoderProfile = c->profile; gStats.lastEncoderRate = c->rate;
      gStats.lastEncoderChannels = c->ch; gStats.lastEncoderBitrate = c->bitrate;
      if (Get(f, "max-input-size", &v) && v > 0) c->inCapacity = size_t(v);
   }
   else {
      Get(f, "sample-rate", &c->rate); Get(f, "channel-count", &c->ch);
      if (c->mime == "audio/raw") { Get(f, "pcm-encoding", &c->rawEnc); if (!c->rawEnc) c->rawEnc = 2; c->outEnc = c->rawEnc; }
      else if (Get(f, "pcm-encoding", &v) && v == 4) {
         if (gDecoder.rejectFloatConfigure) return AMEDIA_ERROR_UNSUPPORTED;
         c->outEnc = gDecoder.honourFloat ? 4 : 2;
      }
      if (Get(f, "max-input-size", &v) && v > 0) c->inCapacity = size_t(v);
      c->settled = gDecoder.settleFrames == 0;
   }
   c->inSlots.assign(4, std::vector<uint8_t>(c->inCapacity));
   c->curRate = c->rate; c->curCh = c->ch; c->curEnc = c->outEnc;
   c->configured = true; return AMEDIA_OK;
}
media_status_t AMediaCodec_start(AMediaCodec *c) { if (!c->configured) return AMEDIA_ERROR_INVALID_OPERATION; c->started = true; return AMEDIA_OK; }
media_status_t AMediaCodec_stop(AMediaCodec *c) { c->started = false; return AMEDIA_OK; }
ssize_t AMediaCodec_dequeueInputBuffer(AMediaCodec *c, int64_t)
{
   if (!c->started) return AMEDIA_ERROR_INVALID_OPERATION;
   if (c->error) return AMEDIA_ERROR_UNKNOWN;
   if (c->inEos || int(c->pending.size()) >= gDecoder.maxPendingOutputs + (c->encoder ? 4 : 0)) return AMEDIACODEC_INFO_TRY_AGAIN_LATER;
   return 1;  // any slot: processing is synchronous
}
uint8_t *AMediaCodec_getInputBuffer(AMediaCodec *c, size_t idx, size_t *size)
{ if (idx >= c->inSlots.size()) return nullptr; *size = c->inCapacity; return c->inSlots[idx].data(); }

static Out FC(int rate, int ch, int enc) { Out o; o.fc = true; o.rate = rate; o.ch = ch; o.enc = enc; return o; }

static std::vector<uint8_t> MakeAsc(int rate, int ch, int profile, bool explicitSig)
{
   static const int rates[] = { 96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350 };
   auto sfi = [&](int r) { for (int i = 0; i < 13; ++i) if (rates[i] == r) return i; return 15; };
   std::vector<int> bits;
   auto put = [&](unsigned v, int n) { for (int i = n - 1; i >= 0; --i) bits.push_back((v >> i) & 1); };
   if (profile == 2) { put(2, 5); put(sfi(rate), 4); put(ch, 4); }
   else if (explicitSig) { put(profile, 5); put(sfi(rate / 2), 4); put(ch, 4); put(sfi(rate), 4); put(2, 5); }
   else { put(2, 5); put(sfi(rate / 2), 4); put(ch, 4); }
   put(0, 3);
   std::vector<uint8_t> out((bits.size() + 7) / 8);
   for (size_t i = 0; i < bits.size(); ++i) out[i / 8] |= uint8_t(bits[i] << (7 - i % 8));
   return out;
}

static void Encode(AMediaCodec *c, const uint8_t *data, size_t size, uint64_t time, uint32_t flags)
{
   if (c->failOnProcess || gEncoder.hang) { if (c->failOnProcess) c->error = true; return; }
   const int frameSize = c->profile == 2 ? 1024 : 2048;
   if (!c->sentFirst) {
      c->sentFirst = true;
      Out fc = FC(c->rate, c->ch, 2);
      c->pending.push_back(fc);
      if (gEncoder.emitCodecConfigBuffer) {
         Out cfg; cfg.data = MakeAsc(c->rate, c->ch, c->profile, gEncoder.explicitSignalling); cfg.flags = AMEDIACODEC_BUFFER_FLAG_CODEC_CONFIG;
         c->pending.push_back(cfg);
      }
   }
   if (c->firstPts < 0 && size > 0) c->firstPts = int64_t(time);
   const long long frames = (long long)(size / (2 * size_t(c->ch)));
   (void)data;
   c->framesIn += frames; c->acc += frames;
   if (gEncoder.errorAtFrame >= 0 && c->framesIn >= gEncoder.errorAtFrame) { c->error = true; return; }
   auto emit = [&](long long n) {
      Out au; au.data.resize(8); std::memcpy(au.data.data(), "AU01", 4); uint32_t nn = uint32_t(n); std::memcpy(au.data.data() + 4, &nn, 4);
      au.pts = (c->firstPts < 0 ? 0 : c->firstPts) + c->aus * frameSize * 1000000LL / c->rate; ++c->aus; c->pending.push_back(au); };
   while (c->acc >= frameSize) { emit(frameSize); c->acc -= frameSize; }
   if (flags & AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM) {
      c->inEos = true;
      if (c->acc > 0) { emit(c->acc); c->acc = 0; }
      Out eos; eos.flags = AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM; c->pending.push_back(eos);
   }
}

static void Decode(AMediaCodec *c, const uint8_t *data, size_t size, uint64_t time, uint32_t flags)
{
   const long long p = c->packets++;
   if (gDecoder.errorAtPacket >= 0 && p >= gDecoder.errorAtPacket) { c->error = true; return; }
   if (size > 0) {
      const bool raw = c->mime == "audio/raw";
      const int inBps = raw ? BytesPerSample(c->rawEnc) : 4;
      const size_t frames = size / (size_t(inBps) * c->ch);
      if (!c->sentFirst) {
         c->sentFirst = true;
         if (!c->settled) { c->pending.push_back(FC(gDecoder.settleRate, gDecoder.settleChannels, c->outEnc)); c->curCh = gDecoder.settleChannels; c->curRate = gDecoder.settleRate; }
         else if (gDecoder.formatChangeFirst) c->pending.push_back(FC(c->rate, c->ch, c->outEnc));
      }
      if (!c->settled && c->framesOut >= gDecoder.settleFrames) {
         c->settled = true; c->pending.push_back(FC(c->rate, c->ch, c->outEnc)); c->curCh = c->ch; c->curRate = c->rate;
      }
      if (p == gDecoder.rateChangeAtPacket) { c->pending.push_back(FC(gDecoder.newRate, c->curCh, c->outEnc)); c->curRate = gDecoder.newRate; }
      const int outCh = c->curCh;
      Out o; o.pts = int64_t(time);
      const int outBps = BytesPerSample(c->outEnc);
      o.data.resize(frames * size_t(outCh) * outBps);
      uint8_t *q = o.data.data();
      for (size_t f = 0; f < frames; ++f)
         for (int ch = 0; ch < outCh; ++ch) {
            const uint8_t *src = data + (f * c->ch + size_t(std::min(ch, c->ch - 1))) * inBps;
            if (raw) { std::memcpy(q, src, inBps); q += inBps; continue; }
            float x; std::memcpy(&x, src, 4);
            if (c->outEnc == 4) { std::memcpy(q, &x, 4); q += 4; }
            else { int16_t v = int16_t(std::lround(x * 32767.0)); std::memcpy(q, &v, 2); q += 2; }
         }
      c->framesOut += (long long)frames;
      c->pending.push_back(std::move(o));
   }
   if (flags & AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM) {
      c->inEos = true;
      if (!gDecoder.neverEos) { Out eos; eos.pts = int64_t(time); eos.flags = AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM; c->pending.push_back(eos); }
   }
}

media_status_t AMediaCodec_queueInputBuffer(AMediaCodec *c, size_t idx, off_t off, size_t size, uint64_t time, uint32_t flags)
{
   if (!c->started || c->inEos || idx >= c->inSlots.size() || size_t(off) + size > c->inCapacity) return AMEDIA_ERROR_INVALID_OPERATION;
   const uint8_t *data = c->inSlots[idx].data() + off;
   if (c->encoder) Encode(c, data, size, time, flags); else Decode(c, data, size, time, flags);
   return AMEDIA_OK;
}
ssize_t AMediaCodec_dequeueOutputBuffer(AMediaCodec *c, AMediaCodecBufferInfo *info, int64_t timeoutUs)
{
   if (!c->started) return AMEDIA_ERROR_INVALID_OPERATION;
   if (c->error) return AMEDIA_ERROR_UNKNOWN;
   if (c->pending.empty()) {
      if (timeoutUs > 0) std::this_thread::sleep_for(std::chrono::microseconds(timeoutUs));
      return AMEDIACODEC_INFO_TRY_AGAIN_LATER;
   }
   Out o = std::move(c->pending.front()); c->pending.pop_front();
   if (o.fc) { c->curRate = o.rate; c->curCh = o.ch; c->curEnc = o.enc; return AMEDIACODEC_INFO_OUTPUT_FORMAT_CHANGED; }
   const int off = c->encoder ? gEncoder.outputOffset : gDecoder.outputOffset;
   std::vector<uint8_t> buf(size_t(off), 0xEE);
   buf.insert(buf.end(), o.data.begin(), o.data.end());
   const size_t slot = c->nextSlot++;
   c->outSlots[slot] = std::move(buf);
   ++gStats.outstandingOutputs;
   info->offset = off; info->size = int32_t(o.data.size()); info->presentationTimeUs = o.pts; info->flags = o.flags;
   return ssize_t(slot);
}
uint8_t *AMediaCodec_getOutputBuffer(AMediaCodec *c, size_t idx, size_t *size)
{ auto it = c->outSlots.find(idx); if (it == c->outSlots.end()) return nullptr; *size = it->second.size(); return it->second.data(); }
media_status_t AMediaCodec_releaseOutputBuffer(AMediaCodec *c, size_t idx, bool)
{
   if (!c->outSlots.erase(idx)) { std::fprintf(stderr, "  [fake] DOUBLE/INVALID release of %zu\n", idx); std::abort(); }
   --gStats.outstandingOutputs; return AMEDIA_OK;
}
AMediaFormat *AMediaCodec_getOutputFormat(AMediaCodec *c)
{
   auto f = AMediaFormat_new();
   if (c->encoder) {
      AMediaFormat_setString(f, "mime", "audio/mp4a-latm");
      AMediaFormat_setInt32(f, "sample-rate", c->rate); AMediaFormat_setInt32(f, "channel-count", c->ch);
      if (c->sentFirst && gEncoder.csdInFormat) { auto asc = MakeAsc(c->rate, c->ch, c->profile, gEncoder.explicitSignalling); AMediaFormat_setBuffer(f, "csd-0", asc.data(), asc.size()); }
   }
   else {
      AMediaFormat_setString(f, "mime", "audio/raw");
      AMediaFormat_setInt32(f, "sample-rate", c->curRate); AMediaFormat_setInt32(f, "channel-count", c->curCh);
      if (c->curEnc != 2) AMediaFormat_setInt32(f, "pcm-encoding", c->curEnc);
   }
   return f;
}
media_status_t AMediaCodec_getName(AMediaCodec *, char **out) { *out = strdup("c2.fake.aac.encoder"); return AMEDIA_OK; }
void AMediaCodec_releaseName(AMediaCodec *, char *name) { std::free(name); }

// ----------------------------------------------------------------- muxer
struct AMediaMuxer { int fd; bool hasTrack = false; int64_t lastPts = INT64_MIN; };
AMediaMuxer *AMediaMuxer_new(int fd, OutputFormat) { ++gStats.muxersLive; return new AMediaMuxer{ fd }; }
media_status_t AMediaMuxer_delete(AMediaMuxer *m) { if (m) { --gStats.muxersLive; delete m; } return AMEDIA_OK; }
ssize_t AMediaMuxer_addTrack(AMediaMuxer *m, const AMediaFormat *cf)
{
   auto f = const_cast<AMediaFormat*>(cf); void *d; size_t s;
   if (m->hasTrack || gStats.muxerStarted) return AMEDIA_ERROR_INVALID_OPERATION;
   if (!AMediaFormat_getBuffer(f, "csd-0", &d, &s)) return AMEDIA_ERROR_MALFORMED;
   gStats.muxerCsd.assign(static_cast<uint8_t*>(d), static_cast<uint8_t*>(d) + s);
   m->hasTrack = true; return 0;
}
media_status_t AMediaMuxer_start(AMediaMuxer *m) { if (!m->hasTrack) return AMEDIA_ERROR_INVALID_OPERATION; gStats.muxerStarted = true; return AMEDIA_OK; }
media_status_t AMediaMuxer_writeSampleData(AMediaMuxer *m, size_t track, const uint8_t *data, const AMediaCodecBufferInfo *info)
{
   if (!gStats.muxerStarted || track != 0) return AMEDIA_ERROR_INVALID_OPERATION;
   if (gMuxer.failWriteAt >= 0 && gStats.muxerSamples >= gMuxer.failWriteAt) return AMEDIA_ERROR_IO;
   const uint8_t *p = data + info->offset;   // as the real NDK
   if (info->size < 8 || std::memcmp(p, "AU01", 4) != 0) gStats.muxerBadPayload = true;
   else { uint32_t n; std::memcpy(&n, p + 4, 4); gStats.muxerFrames += n; }
   if (info->presentationTimeUs < m->lastPts) gStats.muxerPtsNotMonotonic = true;
   m->lastPts = info->presentationTimeUs;
   ++gStats.muxerSamples;
   if (write(m->fd, p, size_t(info->size)) != info->size) return AMEDIA_ERROR_IO;
   return AMEDIA_OK;
}
media_status_t AMediaMuxer_stop(AMediaMuxer *m)
{
   if (!gStats.muxerStarted) return AMEDIA_ERROR_INVALID_OPERATION;
   gStats.muxerStarted = false;
   if (gStats.muxerSamples == 0) return AMEDIA_ERROR_MALFORMED;
   gStats.muxerStopped = true;
   (void)!write(m->fd, "MOOV", 4);
   return AMEDIA_OK;
}
