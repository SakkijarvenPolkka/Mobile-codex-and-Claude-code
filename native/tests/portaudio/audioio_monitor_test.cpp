/*  SPDX-License-Identifier: GPL-2.0-or-later */
/*
 * Audacity Android port -- integration test: Audacity's own AudioIO
 * (lib-audio-io 3.7.9, unmodified) driving the PortAudio AAudio host API on
 * the simulated "Null" device (host build).
 *
 * Checks the parts of AudioIO that talk to PortAudio:
 *  - AudioIO::Init(): Pa_Initialize, default host/devices written to prefs;
 *  - the sample-rate policy: AudioIOBase rate probes report only the native
 *    rate, GetBestRate() picks it for a 44.1 kHz project;
 *  - StartMonitoring(): Pa_OpenStream (input only, project sample format),
 *    Pa_GetStreamInfo, Pa_StartStream, the capture meter is fed from the
 *    callback; StopStream(): Pa_AbortStream + Pa_CloseStream (reaper), with
 *    AudioIO's buffer thread polling Pa_IsStreamActive concurrently;
 *  - repeated start/stop cycles and AudioIO::Deinit() (Pa_Terminate).
 *
 * The in-memory settings and headless BasicUI services are minimal copies of
 * those in native/tests/smoke/smoke.cpp.
 */

#include <wx/init.h>
#include <wx/string.h>

#include <atomic>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <map>
#include <memory>
#include <thread>
#include <variant>
#include <vector>

#include "AudioIO.h"
#include "AudioIOBase.h"
#include "BasicSettings.h"
#include "BasicUI.h"
#include "Meter.h"
#include "Prefs.h"
#include "Project.h"

#include "pa_android_aaudio.h"
#include "pa_null.h"

namespace {

int gFailures = 0;

void Check(bool ok, const char *what)
{
   std::printf("%s %s\n", ok ? "ok  " : "FAIL", what);
   if (!ok)
      ++gFailures;
}

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
   bool HasEntry(const wxString &key) const override { return mStorage.count(Path(key)) > 0; }
   bool HasGroup(const wxString &) const override { return false; }
   bool Remove(const wxString &key) override { return mStorage.erase(Path(key)) > 0; }
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
   void DoBeginGroup(const wxString &prefix) override { mGroups.push_back(Path(prefix)); }
   void DoEndGroup() noexcept override { mGroups.pop_back(); }
};

class HeadlessServices final : public BasicUI::Services
{
   struct Progress final : BasicUI::ProgressDialog {
      BasicUI::ProgressResult Poll(unsigned long long, unsigned long long, const TranslatableString &) override
      { return BasicUI::ProgressResult::Success; }
      void SetMessage(const TranslatableString &) override {}
      void SetDialogTitle(const TranslatableString &) override {}
      void Reinit() override {}
   };
   struct GenericProgress final : BasicUI::GenericProgressDialog {
      BasicUI::ProgressResult Pulse() override { return BasicUI::ProgressResult::Success; }
   };

public:
   std::atomic<int> errorDialogs{ 0 };
   void DoCallAfter(const BasicUI::Action &) override {}
   void DoYield() override {}
   void DoProcessIdle() override {}
   void DoShowErrorDialog(const BasicUI::WindowPlacement &, const TranslatableString &title,
      const TranslatableString &message, const ManualPageID &, const BasicUI::ErrorDialogOptions &) override
   {
      ++errorDialogs;
      std::fprintf(stderr, "[error dialog] %s: %s\n", title.Translation().utf8_str().data(),
         message.Translation().utf8_str().data());
   }
   BasicUI::MessageBoxResult DoMessageBox(const TranslatableString &message, BasicUI::MessageBoxOptions) override
   {
      std::fprintf(stderr, "[message box] %s\n", message.Translation().utf8_str().data());
      return BasicUI::MessageBoxResult::Ok;
   }
   std::unique_ptr<BasicUI::ProgressDialog> DoMakeProgress(const TranslatableString &,
      const TranslatableString &, unsigned, const TranslatableString &) override
   { return std::make_unique<Progress>(); }
   std::unique_ptr<BasicUI::GenericProgressDialog> DoMakeGenericProgress(const BasicUI::WindowPlacement &,
      const TranslatableString &, const TranslatableString &, int) override
   { return std::make_unique<GenericProgress>(); }
   int DoMultiDialog(const TranslatableString &, const TranslatableString &, const TranslatableStrings &,
      const ManualPageID &, const TranslatableString &, bool) override
   { return 0; }
   bool DoOpenInDefaultBrowser(const wxString &) override { return false; }
   std::unique_ptr<BasicUI::WindowPlacement> DoFindFocus() override
   { return std::make_unique<BasicUI::WindowPlacement>(); }
   void DoSetFocus(const BasicUI::WindowPlacement &) override {}
   bool IsUsingRtlLayout() const override { return false; }
   bool IsUiThread() const override { return true; }
};

//! Capture meter fed by AudioIO from the PortAudio callback thread
class TestMeter final : public Meter
{
public:
   std::atomic<unsigned long> frames{ 0 };
   std::atomic<double> sumSq{ 0.0 };
   std::atomic<unsigned> channels{ 0 };
   std::atomic<int> resets{ 0 };
   void Clear() override {}
   void Reset(double, bool) override { ++resets; }
   void UpdateDisplay(unsigned numChannels, unsigned long numFrames, const float *sampleData) override
   {
      double s = 0;
      for (unsigned long i = 0; i < numFrames; ++i) {
         const double v = sampleData[i * numChannels];
         s += v * v;
      }
      double old = sumSq.load();
      while (!sumSq.compare_exchange_weak(old, old + s)) {
      }
      frames += numFrames;
      channels = numChannels;
   }
   bool IsMeterDisabled() const override { return false; }
   float GetMaxPeak() const override { return 0; }
   bool IsClipping() const override { return false; }
   int GetDBRange() const override { return 60; }
};

void SleepMs(int ms)
{
   std::this_thread::sleep_for(std::chrono::milliseconds(ms));
}

} // namespace

int main(int argc, char **argv)
{
   wxInitializer initializer(argc, argv);
   Check(initializer.IsOk(), "wxInitializer");
   HeadlessServices ui;
   BasicUI::Install(&ui);
   InitPreferences(std::make_unique<MemorySettings>());
   gPrefs->Write(wxT("/AudioIO/RecordChannels"), 1);
   gPrefs->Write(wxT("/AudioIO/LatencyDuration"), 50.0);

   AudioIO::Init();
   auto *io = AudioIO::Get();
   Check(io != nullptr, "AudioIO::Init");
   if (!io)
      return 1;
   Check(AudioIOHost.Read() == wxT(PA_NULL_HOST_API_NAME), "/AudioIO/Host = Null");
   Check(AudioIOPlaybackDevice.Read() == wxT(PA_AAUDIO_DEFAULT_OUTPUT_NAME), "/AudioIO/PlaybackDevice = Default Output");
   Check(AudioIORecordingDevice.Read() == wxT(PA_AAUDIO_DEFAULT_INPUT_NAME), "/AudioIO/RecordingDevice = Default Input");

   const auto playRates = AudioIOBase::GetSupportedPlaybackRates();
   const auto recRates = AudioIOBase::GetSupportedCaptureRates();
   Check(playRates.size() == 1 && playRates[0] == 48000, "playback rate probes report only the native 48000");
   Check(recRates.size() == 1 && recRates[0] == 48000, "capture rate probes report only the native 48000");
   Check(io->GetBestRate(false, true, 44100.0) == 48000.0, "GetBestRate(play, 44100) = 48000");
   Check(io->GetBestRate(true, false, 44100.0) == 48000.0, "GetBestRate(record, 44100) = 48000");
   Check(io->GetBestRate(true, true, 44100.0) == 48000.0, "GetBestRate(duplex, 44100) = 48000");

   auto project = AudacityProject::Create();
   for (int cycle = 0; cycle < 3; ++cycle) {
      auto meter = std::make_shared<TestMeter>();
      AudioIOStartStreamOptions options{ project, 44100.0 };
      options.captureMeter = meter;
      io->StartMonitoring(options);
      Check(io->IsMonitoring(), "StartMonitoring -> IsMonitoring");
      Check(io->IsStreamActive(), "monitor stream active");
      SleepMs(400);
      PaAAudioStreamStats st{};
      Check(PaAAudio_GetActiveStreamStats(&st) == 1 && st.running && st.hasInput && !st.hasOutput,
         "host API stats: running input stream");
      Check(st.sampleRate == 48000.0, "monitor runs at the device rate");
      const unsigned long frames = meter->frames.load();
      const double rms = frames ? std::sqrt(meter->sumSq.load() / frames) : 0.0;
      std::printf("     monitor cycle %d: %lu frames, %u ch, rms %.4f\n", cycle, frames, meter->channels.load(), rms);
      Check(frames > 12000 && frames < 26000, "capture meter fed from the callback");
      Check(meter->channels.load() == 1, "one capture channel (/AudioIO/RecordChannels)");
      Check(std::fabs(rms - 0.5 / std::sqrt(2.0)) < 0.02, "meter sees the simulated 440 Hz sine");
      const auto t0 = std::chrono::steady_clock::now();
      io->StopStream();
      const double stopSec = std::chrono::duration<double>(std::chrono::steady_clock::now() - t0).count();
      std::printf("     StopStream took %.3f s\n", stopSec);
      Check(!io->IsMonitoring() && !io->IsBusy() && !io->IsStreamActive(), "StopStream -> idle");
      Check(stopSec < 1.0, "StopStream returns promptly");
   }
   for (int i = 0; i < 100 && PaNull_GetOpenStreamCount() != 0; ++i)
      SleepMs(10);
   Check(PaNull_GetOpenStreamCount() == 0, "all AAudio streams closed");
   Check(ui.errorDialogs.load() == 0, "no error dialogs");
   project.reset();

   AudioIO::Deinit();
   Check(PaNull_GetMisuseCount() == 0, "no AAudio API misuse");
   BasicUI::Install(nullptr);
   std::printf("%d failure(s)\n", gFailures);
   return gFailures ? 1 : 0;
}
