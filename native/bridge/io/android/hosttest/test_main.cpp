/*  SPDX-License-Identifier: GPL-2.0-or-later */
/**********************************************************************

  Audacity Android port -- host test harness of native/bridge/io/android

  test_main.cpp: runs the real plug-in code (AndroidMediaImport.cpp,
  AndroidAacExport.cpp) against the fake NDK media layer (fake_ndk.cpp)
  and the host-built Audacity 3.7.9 libraries.  Modes: full (import +
  export), lconly, implicit, noencoder (one AAC encoder probe per process).

**********************************************************************/
#include "fake_control.h"
#include "AndroidCodecs.h"

#include <wx/filename.h>
#include <wx/init.h>
#include <wx/string.h>
#include <wx/utils.h>
#include <algorithm>
#include <cmath>
#include <cstdio>
#include <functional>
#include <map>
#include <memory>
#include <optional>
#include <string>
#include <thread>
#include <variant>
#include <vector>
#include <fcntl.h>
#include <unistd.h>
#include <sys/stat.h>

#include "AcidizerTags.h"
#include "BasicSettings.h"
#include "BasicUI.h"
#include "Export.h"
#include "ExportPlugin.h"
#include "ExportPluginRegistry.h"
#include "ExportUtils.h"
#include "FileNames.h"
#include "Import.h"
#include "ImportPlugin.h"
#include "ImportProgressListener.h"
#include "Prefs.h"
#include "Project.h"
#include "ProjectFileIO.h"
#include "Tags.h"
#include "TempDirectory.h"
#include "Track.h"
#include "UndoManager.h"
#include "WaveTrack.h"

namespace {
// MemorySettings and HeadlessServices: copied from native/tests/smoke/smoke.cpp
std::string Utf8(const wxString &s) { return std::string(s.utf8_str()); }

//! Minimal in-memory audacity::BasicSettings (the app uses a persistent one)
class MemorySettings final : public audacity::BasicSettings
{
   using Value = std::variant<wxString, bool, int, long, long long, double>;
   std::map<wxString, Value> mStorage;
   std::vector<wxString> mGroups{ "/" };

   wxString Path(const wxString &key) const
   {
      if (key.StartsWith("/"))
         return key;
      wxString group = mGroups.back();
      if (!group.EndsWith("/"))
         group += "/";
      return group + key;
   }

   template<typename T> bool DoRead(const wxString &key, T *value) const
   {
      auto it = mStorage.find(Path(key));
      if (it == mStorage.end())
         return false;
      if (auto p = std::get_if<T>(&it->second)) {
         *value = *p;
         return true;
      }
      return false;
   }

   bool DoWrite(const wxString &key, Value value)
   {
      mStorage[Path(key)] = std::move(value);
      return true;
   }

public:
   wxString GetGroup() const override { return mGroups.back(); }
   wxArrayString GetChildGroups() const override { return {}; }
   wxArrayString GetChildKeys() const override { return {}; }
   bool HasEntry(const wxString &key) const override
   { return mStorage.count(Path(key)) > 0; }
   bool HasGroup(const wxString &) const override { return false; }
   bool Remove(const wxString &key) override
   { return mStorage.erase(Path(key)) > 0; }
   void Clear() override { mStorage.clear(); }

   bool Read(const wxString &k, bool *v) const override { return DoRead(k, v); }
   bool Read(const wxString &k, int *v) const override { return DoRead(k, v); }
   bool Read(const wxString &k, long *v) const override { return DoRead(k, v); }
   bool Read(const wxString &k, long long *v) const override { return DoRead(k, v); }
   bool Read(const wxString &k, double *v) const override { return DoRead(k, v); }
   bool Read(const wxString &k, wxString *v) const override { return DoRead(k, v); }

   bool Write(const wxString &k, bool v) override { return DoWrite(k, v); }
   bool Write(const wxString &k, int v) override { return DoWrite(k, v); }
   bool Write(const wxString &k, long v) override { return DoWrite(k, v); }
   bool Write(const wxString &k, long long v) override { return DoWrite(k, v); }
   bool Write(const wxString &k, double v) override { return DoWrite(k, v); }
   bool Write(const wxString &k, const wxString &v) override { return DoWrite(k, v); }

   bool Flush() noexcept override { return true; }

protected:
   void DoBeginGroup(const wxString &prefix) override
   { mGroups.push_back(Path(prefix)); }
   void DoEndGroup() noexcept override { mGroups.pop_back(); }
};

//! Headless audacity BasicUI services (the app installs real ones).
//! Without any installed services, code such as DBConnection::Close() gets a
//! null progress dialog from MakeGenericProgress() and crashes.
class HeadlessServices final : public BasicUI::Services
{
   struct Progress final : BasicUI::ProgressDialog {
      BasicUI::ProgressResult Poll(unsigned long long, unsigned long long,
         const TranslatableString &) override
      { return BasicUI::ProgressResult::Success; }
      void SetMessage(const TranslatableString &) override {}
      void SetDialogTitle(const TranslatableString &) override {}
      void Reinit() override {}
   };
   struct GenericProgress final : BasicUI::GenericProgressDialog {
      BasicUI::ProgressResult Pulse() override
      { return BasicUI::ProgressResult::Success; }
   };
   std::vector<BasicUI::Action> mPending;
   const std::thread::id mMainThread = std::this_thread::get_id();
public:
   void DoCallAfter(const BasicUI::Action &action) override
   { mPending.push_back(action); }
   void DoYield() override { DoProcessIdle(); }
   void DoProcessIdle() override
   {
      auto pending = std::move(mPending);
      mPending.clear();
      for (auto &action : pending)
         if (action)
            action();
   }
   void DoShowErrorDialog(const BasicUI::WindowPlacement &,
      const TranslatableString &title, const TranslatableString &message,
      const ManualPageID &, const BasicUI::ErrorDialogOptions &) override
   {
      std::fprintf(stderr, "[error dialog] %s: %s\n",
         Utf8(title.Translation()).c_str(), Utf8(message.Translation()).c_str());
   }
   BasicUI::MessageBoxResult DoMessageBox(const TranslatableString &message,
      BasicUI::MessageBoxOptions) override
   {
      std::fprintf(stderr, "[message box] %s\n",
         Utf8(message.Translation()).c_str());
      return BasicUI::MessageBoxResult::Ok;
   }
   std::unique_ptr<BasicUI::ProgressDialog> DoMakeProgress(
      const TranslatableString &, const TranslatableString &, unsigned,
      const TranslatableString &) override
   { return std::make_unique<Progress>(); }
   std::unique_ptr<BasicUI::GenericProgressDialog> DoMakeGenericProgress(
      const BasicUI::WindowPlacement &, const TranslatableString &,
      const TranslatableString &, int) override
   { return std::make_unique<GenericProgress>(); }
   int DoMultiDialog(const TranslatableString &, const TranslatableString &,
      const TranslatableStrings &, const ManualPageID &,
      const TranslatableString &, bool) override
   { return 0; }
   bool DoOpenInDefaultBrowser(const wxString &) override { return false; }
   std::unique_ptr<BasicUI::WindowPlacement> DoFindFocus() override
   { return std::make_unique<BasicUI::WindowPlacement>(); }
   void DoSetFocus(const BasicUI::WindowPlacement &) override {}
   bool IsUsingRtlLayout() const override { return false; }
   bool IsUiThread() const override
   { return std::this_thread::get_id() == mMainThread; }
};


int failures = 0;
void Check(bool ok, const char *what)
{
   std::printf("[%s] %s\n", ok ? " OK " : "FAIL", what);
   std::fflush(stdout);
   if (!ok)
      ++failures;
}

using namespace aubridge::android_media;
using IR = ImportProgressListener::ImportResult;

wxString gTempDir;
AudacityProject *gProject{};

struct Listener final : ImportProgressListener {
   std::function<void(ImportFileHandle &)> onOpen;
   int progressCalls = 0, cancelAt = -1, stopAt = -1;
   double lastProgress = -1; bool progressWentBack = false, progressOutOfRange = false;
   ImportFileHandle *handle{};
   std::optional<IR> result;
   int openedStreams = 0;
   std::vector<std::string> streamInfo;
   bool OnImportFileOpened(ImportFileHandle &h) override {
      handle = &h; openedStreams = h.GetStreamCount();
      for (auto &s : h.GetStreamInfo()) streamInfo.push_back(Utf8(s.Translation()));
      if (onOpen) onOpen(h);
      else for (int i = 0; i < h.GetStreamCount(); ++i) h.SetStreamUsage(i, true);
      return true;
   }
   void OnImportProgress(double p) override {
      ++progressCalls;
      if (p < 0 || p > 1) progressOutOfRange = true;
      if (p + 1e-9 < lastProgress) progressWentBack = true;
      lastProgress = p;
      if (progressCalls == cancelAt) handle->Cancel();
      if (progressCalls == stopAt) handle->Stop();
   }
   std::string errorText;
   void OnImportResult(IR r) override {
      result = r;
      if (r == IR::Error && handle) errorText = Utf8(handle->GetErrorMessage().Translation());
   }
};

struct ImportOutcome {
   bool ok = false; TrackHolders tracks; TranslatableString error; Tags tags;
};

ImportOutcome RunImport(const char *name, Listener &listener)
{
   const wxString path = gTempDir + "/" + name;
   if (auto f = std::fopen(path.utf8_str(), "wb")) { std::fputs("FAKEMEDIA and some bytes", f); std::fclose(f); }
   ImportOutcome out;
   std::optional<LibFileFormats::AcidizerTags> acid;
   out.ok = Importer::Get().Import(*gProject, path, &listener,
      &WaveTrackFactory::Get(*gProject), out.tracks, &out.tags, acid, out.error);
   return out;
}

bool NoLeaks()
{
   using fake::gStats;
   const bool ok = gStats.extractorsLive == 0 && gStats.codecsLive == 0 &&
      gStats.outstandingOutputs == 0 && gStats.muxersLive == 0 && gStats.formatsLive == 0;
   if (!ok)
      std::printf("   leaks: extractors %d codecs %d outputs %d muxers %d formats %d\n",
         gStats.extractorsLive, gStats.codecsLive, gStats.outstandingOutputs,
         gStats.muxersLive, gStats.formatsLive);
   return ok;
}

WaveTrack *Wave(const ImportOutcome &o, size_t i)
{ return i < o.tracks.size() ? dynamic_cast<WaveTrack *>(o.tracks[i].get()) : nullptr; }

long long Frames(const WaveTrack *t)
{ return t ? (long long)std::llround((t->GetEndTime() - t->GetStartTime()) * t->GetRate()) : -1; }

//! Max abs difference between track channel c and the fake source signal
double MaxError(const WaveTrack *t, size_t trackIndex, int c, int srcChannel, long long srcOffset, long long count)
{
   std::vector<float> buf(static_cast<size_t>(count));
   auto channels = t->Channels();
   auto it = channels.begin(); std::advance(it, c);
   (*it)->GetFloats(buf.data(), t->TimeToLongSamples(t->GetStartTime()), size_t(count));
   double err = 0;
   for (long long n = 0; n < count; ++n)
      err = std::max(err, std::fabs(double(buf[size_t(n)]) - fake::Signal(trackIndex, srcChannel, n + srcOffset, int(t->GetRate()))));
   return err;
}

fake::Track Aac(int rate = 44100, int ch = 2, int frames = 44100)
{ fake::Track t; t.rate = rate; t.channels = ch; t.totalFrames = frames; return t; }

// --------------------------------------------------------------- import
void ImportTests()
{
   std::printf("\n== import ==\n");
   Importer::Get().Initialize();
   {
      const auto types = Importer::Get().GetFileTypes();
      Check(types.size() >= 4 && types.back().description.MSGID().GET() ==
         wxString(kImporterDescription), "file types: android importer registered (last)");
      std::string order;
      for (size_t i = 3; i < types.size(); ++i)
         order += (i > 3 ? ", " : "") + Utf8(types[i].description.MSGID().GET());
      const auto opus = order.find("Opus files"), ours = order.find(kImporterDescription);
      Check(opus == std::string::npos || opus < ours,
         ("importer order (with the modules that are linked): " + order).c_str());
      wxString pref; gPrefs->Read(wxT("/Importers"), &pref);
      std::printf("   /Importers = %s\n", Utf8(pref).c_str());
   }
   {  // 1 basic stereo float
      fake::Reset(); fake::gMedia.tracks = { Aac(44100, 2, 44100 * 2 + 300) };
      Listener l; auto o = RunImport("basic.m4a", l);
      auto t = Wave(o, 0);
      Check(o.ok && o.tracks.size() == 1 && t && t->NChannels() == 2 && t->GetRate() == 44100 &&
         Frames(t) == 44100 * 2 + 300 && t->GetStartTime() == 0, "basic: 1 stereo track, exact length");
      Check(t && t->GetSampleFormat() == floatSample && MaxError(t, 0, 0, 0, 0, 1000) < 1e-6 &&
         MaxError(t, 0, 1, 1, 0, 1000) < 1e-6, "basic: float samples, channel order");
      std::printf("   progress calls %d last %.6f back %d range %d\n", l.progressCalls, l.lastProgress, l.progressWentBack, l.progressOutOfRange);
      Check(l.result == IR::Success && l.progressCalls > 0 && !l.progressWentBack && !l.progressOutOfRange &&
         std::fabs(l.lastProgress - 1.0) < 1e-9, "basic: result Success, progress 0..1 monotonic, ends at 1");
      Check(l.streamInfo.size() == 1 && l.streamInfo[0].find("Codec[AAC]") != std::string::npos,
         ("basic: stream info '" + (l.streamInfo.empty() ? std::string() : l.streamInfo[0]) + "'").c_str());
      o.tracks.clear(); Check(NoLeaks(), "basic: no leaked NDK objects / output buffers");
   }
   {  // 2 decoder ignores float: int16
      fake::Reset(); fake::gDecoder.honourFloat = false; fake::gMedia.tracks = { Aac(48000, 1, 48000) };
      Listener l; auto o = RunImport("int16.m4a", l); auto t = Wave(o, 0);
      Check(o.ok && t && t->NChannels() == 1 && t->GetRate() == 48000 && Frames(t) == 48000 &&
         MaxError(t, 0, 0, 0, 0, 4800) < 1.0 / 32767 + 1e-6, "int16 output: mono 48 kHz, values within 1 LSB");
      o.tracks.clear(); Check(NoLeaks(), "int16 output: no leaks");
   }
   {  // 3 configure rejects float -> retry without
      fake::Reset(); fake::gDecoder.rejectFloatConfigure = true; fake::gMedia.tracks = { Aac() };
      Listener l; auto o = RunImport("nofloat.m4a", l);
      Check(o.ok && o.tracks.size() == 1 && fake::gStats.decodersCreated >= 3, "float rejected at configure: retried without, imported");
      o.tracks.clear(); Check(NoLeaks(), "float rejected: no leaks");
   }
   {  // 4 no OUTPUT_FORMAT_CHANGED before data, odd output offset
      fake::Reset(); fake::gDecoder.formatChangeFirst = false; fake::gDecoder.outputOffset = 12;
      fake::gMedia.tracks = { Aac(22050, 2, 22050) };
      Listener l; auto o = RunImport("nofc.mp4", l); auto t = Wave(o, 0);
      Check(o.ok && t && t->GetRate() == 22050 && Frames(t) == 22050 && MaxError(t, 0, 1, 1, 0, 2000) < 1e-6,
         "no format-changed event, buffer offset 12: format from getOutputFormat, data correct");
      o.tracks.clear(); Check(NoLeaks(), "no format-changed: no leaks");
   }
   {  // 5 output settles after the first 1024 frames (implicit SBR / PS)
      fake::Reset(); fake::gDecoder.settleFrames = 1024; fake::gDecoder.settleRate = 22050; fake::gDecoder.settleChannels = 1;
      fake::gMedia.tracks = { Aac(44100, 2, 44100) };
      Listener l; auto o = RunImport("settle.m4a", l); auto t = Wave(o, 0);
      Check(o.ok && o.tracks.size() == 1 && t && t->NChannels() == 2 && t->GetRate() == 44100 &&
         Frames(t) == 44100 - 1024 && std::fabs(t->GetStartTime() - 1024.0 / 22050) < 1e-6 &&
         MaxError(t, 0, 0, 0, 1024, 1000) < 1e-6,
         "settling first output (22050/mono, 1024 frames) discarded; one stereo 44.1k track placed after it");
      o.tracks.clear(); Check(NoLeaks(), "settle: no leaks");
   }
   {  // 6 mid-stream rate change -> second track
      fake::Reset(); fake::gDecoder.rateChangeAtPacket = 20; fake::gDecoder.newRate = 48000;
      fake::gMedia.tracks = { Aac(44100, 2, 44100) };
      Listener l; auto o = RunImport("ratechange.m4a", l);
      auto a = Wave(o, 0), b = Wave(o, 1);
      if (a && b) std::printf("   a: %g Hz %lld frames start %g; b: %g Hz %lld frames start %g\n", a->GetRate(), Frames(a), a->GetStartTime(), b->GetRate(), Frames(b), b->GetStartTime());
      Check(o.ok && o.tracks.size() == 2 && a && b && a->GetRate() == 44100 && b->GetRate() == 48000 &&
         Frames(a) == 20 * 1024 && Frames(b) == 44100 - 20 * 1024 &&
         std::fabs(b->GetStartTime() - 20 * 1024 / 44100.0) <= 1.0 / 48000,
         "rate change at packet 20: second track at the elapsed time");
      o.tracks.clear(); Check(NoLeaks(), "rate change: no leaks");
   }
   {  // 7 negative time stamps (edit list priming) dropped
      fake::Reset(); auto tr = Aac(44100, 2, 44100); tr.firstPtsUs = -(2112LL * 1000000 / 44100) - 1;
      fake::gMedia.tracks = { tr };
      Listener l; auto o = RunImport("priming.m4a", l); auto t = Wave(o, 0);
      const long long expectDrop = (long long)std::ceil(double(-tr.firstPtsUs) * 44100 / 1e6);
      Check(o.ok && t && Frames(t) == 44100 - expectDrop && MaxError(t, 0, 0, 0, expectDrop, 2000) < 1e-6 &&
         t->GetStartTime() == 0,
         ("negative PTS: " + std::to_string(expectDrop) + " leading frames dropped exactly").c_str());
      o.tracks.clear(); Check(NoLeaks(), "priming: no leaks");
   }
   {  // 8 multi-stream, selection, video track skipped
      fake::Reset(); fake::Track v; v.mime = "video/avc";
      auto a2 = Aac(32000, 1, 32000); a2.language = "fin"; a2.mime = "audio/opus";
      fake::gMedia.containerMime = "video/x-matroska";
      fake::gMedia.tracks = { v, Aac(44100, 2, 22050), a2 };
      Listener l; auto o = RunImport("multi.mkv", l);
      Check(o.ok && l.openedStreams == 2 && o.tracks.size() == 2 && Wave(o, 0)->GetRate() == 44100 &&
         Wave(o, 1)->GetRate() == 32000 && MaxError(Wave(o, 1), 2, 0, 0, 0, 1000) < 1e-6,
         "two audio streams (+video skipped): two tracks, correct data per stream");
      Check(l.streamInfo.size() == 2 && l.streamInfo[1].find("Language[fin]") != std::string::npos &&
         l.streamInfo[1].find("Opus") != std::string::npos, ("stream info: " + l.streamInfo[1]).c_str());
      o.tracks.clear();
      Listener l2; l2.onOpen = [](ImportFileHandle &h) { h.SetStreamUsage(0, false); h.SetStreamUsage(1, true); };
      auto o2 = RunImport("multi2.mkv", l2);
      Check(o2.ok && o2.tracks.size() == 1 && Wave(o2, 0)->GetRate() == 32000, "SetStreamUsage: only the selected stream imported");
      o2.tracks.clear(); Check(NoLeaks(), "multi-stream: no leaks");
   }
   {  // 9 cancel
      fake::Reset(); fake::gMedia.tracks = { Aac(44100, 2, 44100 * 30) };
      Listener l; l.cancelAt = 2; auto o = RunImport("cancel.m4a", l);
      Check(!o.ok && o.tracks.empty() && l.result == IR::Cancelled && o.error.empty(), "cancel: no tracks, Cancelled, no error text");
      Check(NoLeaks(), "cancel: no leaks");
   }
   {  // 10 stop keeps partial data
      fake::Reset(); fake::gMedia.tracks = { Aac(44100, 2, 44100 * 30) };
      Listener l; l.stopAt = 2; auto o = RunImport("stop.m4a", l); auto t = Wave(o, 0);
      Check(o.ok && t && l.result == IR::Stopped && Frames(t) > 0 && Frames(t) < 44100 * 30, "stop: partial track kept, Stopped");
      o.tracks.clear(); Check(NoLeaks(), "stop: no leaks");
   }
   {  // 11 decoder error mid-stream: partial + warning
      fake::Reset(); fake::gDecoder.errorAtPacket = 10; fake::gMedia.tracks = { Aac(44100, 2, 44100) };
      Listener l; auto o = RunImport("broken.m4a", l); auto t = Wave(o, 0);
      Check(o.ok && t && Frames(t) > 0 && Frames(t) <= 10 * 1024 && l.result == IR::Success,
         "decoder error after 10 packets: partial audio kept (warning shown)");
      o.tracks.clear(); Check(NoLeaks(), "decoder error: no leaks");
   }
   {  // 12 decoder error at once: Error + message
      fake::Reset(); fake::gDecoder.errorAtPacket = 0; fake::gMedia.tracks = { Aac() };
      Listener l; auto o = RunImport("dead.m4a", l);
      Check(!o.ok && o.tracks.empty() && l.result == IR::Error && !l.errorText.empty(),
         ("decoder error at start: Error, message '" + l.errorText + "'").c_str());
      Check(NoLeaks(), "dead decoder: no leaks");
   }
   {  // 13 no EOS from decoder -> finish after the timeout
      fake::Reset(); fake::gDecoder.neverEos = true; fake::gMedia.tracks = { Aac(44100, 2, 4410) };
      Listener l; const auto t0 = std::chrono::steady_clock::now(); auto o = RunImport("noeos.m4a", l);
      const double secs = std::chrono::duration<double>(std::chrono::steady_clock::now() - t0).count();
      Check(o.ok && Frames(Wave(o, 0)) == 4410 && secs > 2.5 && secs < 6, "decoder without EOS: all audio, finished by watchdog");
      o.tracks.clear(); Check(NoLeaks(), "no EOS: no leaks");
   }
   {  // 14 tags
      fake::Reset(); fake::gMedia.tracks = { Aac() };
      fake::gMedia.fileMeta = { { "title", "Song" }, { "albumartist", "Band" }, { "album", "LP" },
         { "cdtracknum", "3/12" }, { "genre", "17" }, { "date", "2019-05-01T00:00:00Z" } };
      Listener l; auto o = RunImport("tags.m4a", l);
      Check(o.ok && o.tags.GetTag(TAG_TITLE) == "Song" && o.tags.GetTag(TAG_ARTIST) == "Band" &&
         o.tags.GetTag(TAG_ALBUM) == "LP" && o.tags.GetTag(TAG_TRACK) == "3" &&
         o.tags.GetTag(TAG_GENRE) == "Rock" && o.tags.GetTag(TAG_YEAR) == "2019",
         ("tags: " + Utf8(o.tags.GetTag(TAG_TITLE)) + "/" + Utf8(o.tags.GetTag(TAG_ARTIST)) + "/" +
          Utf8(o.tags.GetTag(TAG_TRACK)) + "/" + Utf8(o.tags.GetTag(TAG_GENRE)) + "/" + Utf8(o.tags.GetTag(TAG_YEAR))).c_str());
      o.tracks.clear();
   }
   {  // 15 raw 24-bit packed, 8-bit, 32-bit
      for (int enc : { 21, 3, 22 }) {
         fake::Reset(); fake::Track tr = Aac(44100, 2, 8000); tr.mime = "audio/raw"; tr.rawEncoding = enc;
         fake::gMedia.containerMime = "audio/x-wav"; fake::gMedia.tracks = { tr };
         Listener l; auto o = RunImport("raw.ts", l); auto t = Wave(o, 0);
         const double tol = enc == 3 ? 1.0 / 127 : enc == 21 ? 1e-6 : 1e-7;
         Check(o.ok && t && Frames(t) == 8000 && MaxError(t, 0, 1, 1, 0, 8000) < tol + 1e-7,
            ("audio/raw pcm-encoding " + std::to_string(enc) + " converted").c_str());
         o.tracks.clear();
      }
      Check(NoLeaks(), "raw: no leaks");
   }
   {  // 16 6 channels -> 6 mono tracks
      fake::Reset(); fake::gMedia.tracks = { Aac(48000, 6, 4800) };
      Listener l; auto o = RunImport("surround.mka", l);
      bool ok = o.ok && o.tracks.size() == 6;
      for (size_t i = 0; ok && i < 6; ++i) ok = Wave(o, i)->NChannels() == 1 && MaxError(Wave(o, i), 0, 0, int(i), 0, 4800) < 1e-6;
      Check(ok, "6 channels: 6 mono tracks in channel order");
      o.tracks.clear();
   }
   {  // 17 DRM and garbage are rejected in Open
      fake::Reset(); fake::gMedia.drm = true; fake::gMedia.tracks = { Aac() };
      Listener l; auto o = RunImport("drm.m4a", l);
      Check(!o.ok && l.openedStreams == 0, "DRM: not opened");
      fake::Reset(); fake::gMedia.tracks = { Aac() };
      const wxString path = gTempDir + "/garbage.m4a";
      if (auto f = std::fopen(path.utf8_str(), "wb")) { std::fputs("not media", f); std::fclose(f); }
      TrackHolders tracks; Tags tags; std::optional<LibFileFormats::AcidizerTags> acid; TranslatableString err;
      Listener l2;
      const bool ok2 = Importer::Get().Import(*gProject, path, &l2, &WaveTrackFactory::Get(*gProject), tracks, &tags, acid, err);
      Check(!ok2 && l2.openedStreams == 0 && NoLeaks(), "non-media file: not opened, no leaks");
   }
   {  // 18 truncated file (read error) keeps data + warning
      fake::Reset(); fake::gMedia.readErrorAtPacket = 5; fake::gMedia.tracks = { Aac() };
      Listener l; auto o = RunImport("trunc.m4a", l);
      Check(o.ok && Frames(Wave(o, 0)) == 5 * 1024, "read error at packet 5: 5 packets kept (warning shown)");
      o.tracks.clear(); Check(NoLeaks(), "truncated: no leaks");
   }
   {  // 19 no max-input-size in the container
      fake::Reset(); auto tr = Aac(44100, 2, 44100); tr.hasMaxInputSize = false; tr.framesPerPacket = 4096;
      fake::gMedia.tracks = { tr };
      Listener l; auto o = RunImport("nomaxinput.mkv", l);
      Check(o.ok && Frames(Wave(o, 0)) == 44100, "no max-input-size: 32 KiB packets fit (default raised)");
      o.tracks.clear();
   }
}

// --------------------------------------------------------------- export
struct Delegate final : ExportProcessorDelegate {
   int progressCalls = 0, cancelAt = -1, stopAt = -1; double last = 0; bool back = false;
   bool IsCancelled() const override { return cancelAt > 0 && progressCalls >= cancelAt; }
   bool IsStopped() const override { return stopAt > 0 && progressCalls >= stopAt; }
   void SetStatusString(const TranslatableString &) override {}
   void OnProgress(double p) override { ++progressCalls; if (p + 1e-9 < last) back = true; last = p; }
};

std::tuple<ExportPlugin *, int> FindAac()
{
   for (auto t : ExportPluginRegistry::Get()) {
      auto [plugin, index] = t;
      if (plugin->GetFormatInfo(index).description.MSGID().GET() == wxString(kExportFormatKey))
         return t;
   }
   return {};
}

struct ExportOutcome { std::optional<ExportResult> result; std::string exception; bool fileExists = false; long long fileSize = 0; };

ExportOutcome RunExport(const ExportProcessor::Parameters &params, double rate, unsigned channels,
   double t1, Delegate &delegate, bool run = true, const char *name = "out.m4a")
{
   auto [plugin, index] = FindAac();
   const wxString path = gTempDir + "/" + name;
   wxRemoveFile(path);
   ExportOutcome out;
   try {
      ExportTaskBuilder b;
      b.SetFileName(wxFileName{ path }).SetPlugin(plugin, index).SetParameters(params)
         .SetSampleRate(rate).SetRange(0, t1, false).SetNumChannels(channels);
      auto task = b.Build(*gProject);
      if (run) {
         auto future = task.get_future();
         task(delegate);
         try { out.result = future.get(); }
         catch (const ExportErrorException &e) { out.exception = "ExportErrorException: " + Utf8(e.GetMessage().Translation()); }
         catch (const ExportDiskFullError &) { out.exception = "ExportDiskFullError"; }
         catch (const ExportException &e) { out.exception = "ExportException: " + Utf8(e.What()); }
      }
   }
   catch (const ExportException &e) { out.exception = "ExportException(Build): " + Utf8(e.What()); }
   catch (const ExportErrorException &e) { out.exception = "ExportErrorException(Build): " + Utf8(e.GetMessage().Translation()); }
   struct stat st{};
   out.fileExists = ::stat(path.utf8_str(), &st) == 0;
   out.fileSize = out.fileExists ? (long long)st.st_size : 0;
   return out;
}

struct EditorListener final : ExportOptionsEditor::Listener {
   int begins = 0, changes = 0, ends = 0, rateChanges = 0, formatChanges = 0;
   void OnExportOptionChangeBegin() override { ++begins; }
   void OnExportOptionChangeEnd() override { ++ends; }
   void OnExportOptionChange(const ExportOption &) override { ++changes; }
   void OnFormatInfoChange() override { ++formatChanges; }
   void OnSampleRateListChange() override { ++rateChanges; }
};

bool Hidden(ExportOptionsEditor &e, int id)
{
   for (int i = 0; i < e.GetOptionsCount(); ++i) { ExportOption o; e.GetOption(i, o); if (o.id == id) return o.flags & ExportOption::Hidden; }
   return true;
}

void ExportTests(const std::string &mode)
{
   std::printf("\n== export (%s) ==\n", mode.c_str());
   ExportPluginRegistry::Get().Initialize();
   auto [plugin, index] = FindAac();
   Check(plugin != nullptr, "export format 'M4A (AAC) Files' registered");
   if (!plugin) return;
   {
      std::string order, previous, before;
      for (auto t : ExportPluginRegistry::Get()) {
         auto [p, i] = t;
         const auto key = Utf8(p->GetFormatInfo(i).description.MSGID().GET());
         if (key == kExportFormatKey) before = previous;
         order += (order.empty() ? "" : ", ") + key;
         previous = key;
      }
      Check(before == "MP3 Files" || before.empty() || order.find("MP3") == std::string::npos,
         ("export order (M4A after MP3): " + order).c_str());
   }
   const auto info = plugin->GetFormatInfo(index);
   Check(info.maxChannels == 2 && !info.canMetaData && info.extensions.size() == 1 && info.extensions[0] == "m4a" &&
      plugin->GetMimeTypes(index) == std::vector<std::string>{ "audio/mp4" }, "format info: m4a, 2 ch, no metadata, audio/mp4");

   const auto &caps = AacEncoderCapabilities();
   std::string rates; for (int r : caps.sampleRates) rates += std::to_string(r) + " ";
   std::string profiles; for (int p : caps.profiles) profiles += std::to_string(p) + " ";
   std::printf("   caps: available %d verified %d rates [%s] profiles [%s]\n", caps.available, caps.verified, rates.c_str(), profiles.c_str());
   if (mode == "noencoder") {
      Check(!caps.available, "probe: no encoder");
      Delegate d; auto o = RunExport({}, 44100, 2, 1.0, d);
      Check(!o.result && o.exception.find("no AAC encoder") != std::string::npos && !o.fileExists, ("no encoder: " + o.exception).c_str());
      return;
   }
   if (mode == "lconly") {
      Check(caps.verified && caps.profiles == std::vector<int>{ 2 }, "probe: encoder ignoring the profile -> LC only");
      EditorListener el; auto ed = plugin->CreateOptionsEditor(index, &el);
      ExportOption o; ed->GetOption(0, o);
      Check(ed->GetOptionsCount() == 2 && o.id == 1 && (o.flags & ExportOption::ReadOnly) && o.values.size() == 1,
         "editor: LC only -> Profile read-only, no HE bit rate options");
      return;
   }
   if (mode == "implicit") {
      Check(caps.profiles == std::vector<int>{ 2, 5, 29 }, "probe: implicit SBR signalling accepted for HE and HEv2");
      return;
   }
   Check(caps.available && caps.verified && caps.profiles == std::vector<int>{ 2, 5, 29 } &&
      caps.sampleRates == std::vector<int>{ 8000, 11025, 12000, 16000, 22050, 24000, 32000, 44100, 48000 },
      "probe: rates (64k-96k rejected by the fake) and LC/HE/HEv2 found");

   // ---- options editor
   EditorListener el; auto ed = plugin->CreateOptionsEditor(index, &el);
   ExportOption first; ed->GetOption(0, first);
   Check(ed->GetOptionsCount() == 4 && first.id == 1 && first.values.size() == 3 && !Hidden(*ed, 0) &&
      Hidden(*ed, 2) && Hidden(*ed, 3), "editor: Profile first; LC bit rate visible, HE ones hidden");
   Check(ed->GetSampleRateList().size() == 9, "editor: LC sample rates = probed list");
   Check(!ed->SetValue(0, 12345) && !ed->SetValue(1, 7) && !ed->SetValue(1, std::string("5")) && !ed->SetValue(9, 1),
      "editor: rejects values not in the enum, wrong types, unknown ids");
   Check(ed->SetValue(1, 5) && Hidden(*ed, 0) && !Hidden(*ed, 2) && Hidden(*ed, 3) && el.rateChanges == 1 &&
      el.begins == 1 && el.ends == 1 && el.changes == 3, "editor: profile HE -> HE bit rate visible, listener notified");
   const auto heRates = ed->GetSampleRateList();
   Check(heRates.front() == 16000 && heRates.back() == 48000 && heRates.size() == 6, "editor: HE sample rates 16k..48k");
   Check(ed->SetValue(2, 64000), "editor: HE bit rate 64 kbps accepted");
   ed->Store(*gPrefs);
   auto ed2 = plugin->CreateOptionsEditor(index, nullptr); ed2->Load(*gPrefs);
   ExportValue v; ed2->GetValue(1, v); ExportValue v2; ed2->GetValue(2, v2);
   Check(std::get<int>(v) == 5 && std::get<int>(v2) == 64000 && !Hidden(*ed2, 2), "editor: Store/Load round trip");
   ed->SetValue(1, 2); ed->Store(*gPrefs);

   // ---- a 2 s stereo track in the project
   auto &tracks = TrackList::Get(*gProject);
   tracks.Clear();
   {
      fake::Reset(); fake::gMedia.tracks = { Aac(44100, 2, 88200) };
      Listener l; auto o = RunImport("src.m4a", l);
      for (auto &t : o.tracks) tracks.Add(t);
   }
   Check(std::fabs(tracks.GetEndTime() - 2.0) < 1e-9, "export source: 2 s stereo track");

   const ExportProcessor::Parameters lc{ { 1, 2 }, { 0, 192000 } };
   {  fake::Reset(); Delegate d; auto o = RunExport(lc, 44100, 2, 2.0, d);
      Check(o.result == ExportResult::Success && o.fileExists && o.fileSize > 0 && fake::gStats.muxerStopped &&
         fake::gStats.muxerFrames == 88200 && !fake::gStats.muxerBadPayload && !fake::gStats.muxerPtsNotMonotonic &&
         !fake::gStats.muxerCsd.empty() && d.progressCalls > 0 && !d.back,
         ("export LC: Success, all 88200 frames muxed, payload at offset, PTS monotonic (" + o.exception + ")").c_str());
      Check(fake::gStats.lastEncoderBitrate == 192000 && fake::gStats.lastEncoderProfile == 2 && fake::gStats.lastEncoderChannels == 2,
         "export LC: encoder configured with 192 kbps, LC, stereo");
      Check(NoLeaks(), "export LC: no leaks"); }
   {  fake::Reset(); Delegate d; d.cancelAt = 3; auto o = RunExport(lc, 44100, 2, 2.0, d);
      Check(o.result == ExportResult::Cancelled && !o.fileExists && NoLeaks(), "export cancel: Cancelled, file deleted, no leaks"); }
   {  fake::Reset(); Delegate d; d.stopAt = 5; auto o = RunExport(lc, 44100, 2, 2.0, d);
      Check(o.result == ExportResult::Stopped && o.fileExists && fake::gStats.muxerStopped &&
         fake::gStats.muxerFrames > 0 && fake::gStats.muxerFrames < 88200 && NoLeaks(),
         ("export stop: Stopped, shorter valid file (" + std::to_string(fake::gStats.muxerFrames) + " frames)").c_str()); }
   {  fake::Reset(); fake::gEncoder.errorAtFrame = 20000; Delegate d; auto o = RunExport(lc, 44100, 2, 2.0, d);
      Check(!o.result && o.exception.find("ExportErrorException") == 0 && !o.fileExists && NoLeaks(),
         ("export encoder error: exception, file deleted (" + o.exception + ")").c_str()); }
   {  fake::Reset(); fake::gMuxer.failWriteAt = 10; Delegate d; auto o = RunExport(lc, 44100, 2, 2.0, d);
      Check(!o.result && o.exception == "ExportDiskFullError" && !o.fileExists && NoLeaks(), "export write error: disk-full error, file deleted"); }
   {  fake::Reset(); Delegate d; auto o = RunExport(lc, 7000, 2, 2.0, d);
      Check(!o.result && o.exception.find("Build") != std::string::npos && !o.fileExists && NoLeaks(),
         ("export unsupported rate: Initialize throws, no file (" + o.exception + ")").c_str()); }
   {  fake::Reset(); Delegate d; auto o = RunExport(lc, 44100, 2, 2.0, d, false);
      Check(!o.fileExists && NoLeaks(), "export task destroyed without running: file deleted"); }
   {  fake::Reset(); Delegate d; auto o = RunExport({ { 1, 29 }, { 3, 32000 } }, 44100, 1, 2.0, d);
      Check(o.result == ExportResult::Success && fake::gStats.lastEncoderProfile == 5 && fake::gStats.lastEncoderBitrate == 32000,
         "export HEv2 + mono: falls back to HE-AAC with the HEv2 bit rate"); }
   {  fake::Reset(); Delegate d; auto o = RunExport({ { 1, 5 }, { 2, 48000 } }, 44100, 2, 2.0, d);
      Check(o.result == ExportResult::Success && fake::gStats.lastEncoderProfile == 5 && fake::gStats.lastEncoderBitrate == 48000 &&
         fake::gStats.muxerFrames == 88200, "export HE-AAC 48 kbps"); }
   {  fake::Reset(); Delegate d; auto o = RunExport({ { 1, 5 } }, 8000, 2, 2.0, d);
      Check(!o.result && o.exception.find("sample rate") != std::string::npos, "export HE at 8 kHz: refused (SBR needs >= 16 kHz)"); }
   {  fake::Reset(); Delegate d; auto o = RunExport({ { 1, 2 }, { 0, 320000 } }, 8000, 1, 2.0, d);
      Check(o.result == ExportResult::Success && fake::gStats.lastEncoderBitrate == 48000 && fake::gStats.muxerFrames == 16000,
         "export LC 8 kHz mono 320 kbps: bit rate clamped to 48 kbps, resampled length right"); }
   {  fake::Reset(); fake::gEncoder.csdInFormat = false; Delegate d; auto o = RunExport(lc, 44100, 2, 2.0, d);
      Check(o.result == ExportResult::Success && !fake::gStats.muxerCsd.empty(), "export: csd taken from the CODEC_CONFIG buffer when the format lacks it"); }
   {  fake::Reset(); fake::gEncoder.csdInFormat = false; fake::gEncoder.emitCodecConfigBuffer = false; Delegate d;
      auto o = RunExport(lc, 44100, 2, 2.0, d);
      Check(!o.result && !o.fileExists && NoLeaks(), ("export without any csd: error, file deleted (" + o.exception + ")").c_str()); }
   {  fake::Reset(); fake::gEncoder.outputOffset = 0; Delegate d; auto o = RunExport(lc, 48000, 2, 1.0, d);
      Check(o.result == ExportResult::Success && fake::gStats.muxerFrames == 48000 && !fake::gStats.muxerBadPayload,
         "export 48 kHz, 1 s range, offset 0"); }
   tracks.Clear();
}
} // namespace

int main(int argc, char **argv)
{
   const std::string mode = argc > 1 ? argv[1] : "full";
   if (mode == "lconly") fake::gEncoder.ignoreProfile = true;
   if (mode == "implicit") fake::gEncoder.explicitSignalling = false;
   if (mode == "noencoder") fake::gEncoder.exists = false;

   wxInitializer initializer;
   HeadlessServices ui; BasicUI::Install(&ui);
   InitPreferences(std::make_unique<MemorySettings>());
   gTempDir = wxFileName::CreateTempFileName("ioandroid-test");
   wxRemoveFile(gTempDir);
   wxFileName::Mkdir(gTempDir, 0700, wxPATH_MKDIR_FULL);
   TempDirectory::SetDefaultTempDir(gTempDir);
   FileNames::UpdateDefaultPath(FileNames::Operation::Temp, gTempDir);
   ProjectFileIO::InitializeSQL();
   auto project = AudacityProject::Create();
   gProject = project.get();
   Check(ProjectFileIO::Get(*project).OpenProject(), "temporary project database");

   if (mode == "full")
      ImportTests();
   else {
      fake::Reset();
      if (mode == "lconly") fake::gEncoder.ignoreProfile = true;
      if (mode == "implicit") fake::gEncoder.explicitSignalling = false;
      if (mode == "noencoder") fake::gEncoder.exists = false;
   }
   ExportTests(mode);

   ProjectFileIO::Get(*project).SetBypass();
   UndoManager::Get(*project).ClearStates();
   TrackList::Get(*project).Clear();
   ProjectFileIO::Get(*project).CloseProject();
   WaveTrackFactory::Destroy(*project);
   project.reset();
   wxFileName::Rmdir(gTempDir, wxPATH_RMDIR_RECURSIVE);
   std::printf("\n%s: %d failure(s)\n", mode.c_str(), failures);
   return failures ? 1 : 0;
}
