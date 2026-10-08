/*  SPDX-License-Identifier: GPL-2.0-or-later */
/**********************************************************************

  Audacity Android port

  ExportCommands.cpp

  export.formats / export.defaults / export.options / export.setOption /
  export.run (API.md §3.3 "import / export", §5.6).

  The rules for range, channels and sample rate are ports of Audacity
  3.7.9 src/export/ExportAudioDialog.cpp (OnExport, constructor) and
  src/export/ExportFilePanel.cpp (Init, UpdateSampleRateList,
  UpdateMaxChannels); the export driver replaces
  lib-import-export/ExportProgressUI.cpp (Vitaly Sverchinsky): the
  ExportTask is built on the engine thread, processed on a worker thread
  that is joined, and the engine thread waits draining only internal
  (CallAfter) work while it publishes `progress` events.

  Options sessions: one ExportOptionsEditor per format key, (re)loaded from
  the preferences by export.options; export.setOption validates the tagged
  value, sets it and stores the editor in the preferences (like Audacity 4,
  so nothing is lost when Kotlin switches formats); export.run takes the
  parameters from the session (ExportUtils::ParametersFromEditor).

  Android restrictions:
   * WavPack "Create Correction(.wvc) File" is always off (a second output
     file cannot reach a single SAF document): hidden, read-only, false.
   * MP3 files carry no tags (the port builds without libid3tag): the MP3
     format reports canMetaData = false.
   * channels are 1 or 2 (no custom channel mapping in the contract).
   * The staging path must not exist: ExportTaskBuilder::Build hands the
     target name to Initialize, so an existing file would be overwritten in
     place; any partial output is deleted on failure and on cancel.
   * ExportTaskBuilder::Build runs with AudacityProject::mBatchMode raised
     so that MP3 never opens its (stubbed) resample dialog; the rate is
     validated against the format's list first anyway.

**********************************************************************/
#include "IoModule.h"

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cmath>
#include <future>
#include <map>
#include <memory>
#include <mutex>
#include <set>
#include <system_error>
#include <thread>
#include <variant>

#include <wx/filename.h>
#include <wx/log.h>

#include "BasicUI.h"
#include "Edit.h"
#include "EngineThread.h"
#include "Export.h"
#include "ExportOptionsEditor.h"
#include "ExportPlugin.h"
#include "ExportPluginRegistry.h"
#include "ExportUtils.h"
#include "FileException.h"
#include "ImportExport.h"
#include "MemoryX.h"
#include "ModuleRegistry.h"
#include "Prefs.h"
#include "Project.h"
#include "ProjectRate.h"
#include "Session.h"
#include "Track.h"
#include "UiServices.h"
#include "ViewInfo.h"
#include "WaveTrack.h"
#include "wxFileNameWrapper.h"

namespace aubridge {
namespace io {

namespace {

//! ExportFilePanel.cpp DefaultRates: offered when a format has no list
const std::vector<int> kDefaultRates{ 8000, 11025, 16000, 22050, 32000,
   44100, 48000, 88200, 96000, 176400, 192000, 352800, 384000 };

constexpr const char *kMp3Key = "MP3 Files";
constexpr const char *kWavPackKey = "WavPack Files";
//! ExportPCM's second format: its FormatInfo (extension, channels) follows
//! the preferences that its options editor writes
constexpr const char *kPcmOtherKey = "Other uncompressed files";
//! ExportWavPack.cpp OptionIDCreateCorrection
constexpr ExportOptionID kWavPackCorrectionOption = 3;

//! The port builds with USE_LIBID3TAG OFF (native/CMakeLists.txt): MP3
//! exports get no ID3 tags, so the MP3 format does not offer metadata
constexpr bool kMp3WritesTags = false;

//! Contract restriction: mono or stereo
constexpr unsigned kMaxExportChannels = 2;

std::string FormatKey(const FormatInfo &info)
{
   return ToUtf8(info.description.MSGID().GET());
}

struct FormatRef {
   ExportPlugin *plugin = nullptr;
   int index = 0;
   std::string key;
};

FormatRef FindFormat(const std::string &key)
{
   for (auto [plugin, index] : ExportPluginRegistry::Get())
      if (FormatKey(plugin->GetFormatInfo(index)) == key)
         return { plugin, index, key };
   return {};
}

FormatRef RequireFormat(const json &args)
{
   const auto key = ArgString(args, "formatKey");
   auto ref = FindFormat(key);
   if (!ref.plugin)
      Fail(ErrorCode::NOT_FOUND, "no export format '" + key + "'");
   return ref;
}

bool CanMetaData(const FormatInfo &info, const std::string &key)
{
   if (key == kMp3Key && !kMp3WritesTags)
      return false;
   return info.canMetaData;
}

//! ExportFormat (API.md §5.6)
json FormatJson(const FormatInfo &info, const std::string &key)
{
   json extensions = json::array();
   for (const auto &ext : info.extensions)
      if (!ext.empty())
         extensions.push_back(ToUtf8(ext));
   return json{ { "key", key }, { "description", Translated(info.description) },
      { "extensions", std::move(extensions) },
      { "maxChannels", info.maxChannels },
      { "canMetaData", CanMetaData(info, key) } };
}

// ---- tagged values {t, v} --------------------------------------------------

json TaggedValue(const ExportValue &value)
{
   return std::visit([](const auto &v) -> json {
      using T = std::decay_t<decltype(v)>;
      if constexpr (std::is_same_v<T, bool>)
         return json{ { "t", "b" }, { "v", v } };
      else if constexpr (std::is_same_v<T, int>)
         return json{ { "t", "i" }, { "v", v } };
      else if constexpr (std::is_same_v<T, double>)
         return json{ { "t", "d" }, { "v", Finite(v) } };
      else
         return json{ { "t", "s" }, { "v", v } };
   }, value);
}

//! @throws INVALID_ARGS for a malformed tagged value
ExportValue ParseTagged(const json &j)
{
   if (!j.is_object() || !j.contains("t") || !j["t"].is_string() ||
       !j.contains("v"))
      Fail(ErrorCode::INVALID_ARGS,
         "argument 'value' must be a tagged value {t, v}");
   const auto tag = j["t"].get<std::string>();
   const auto &v = j["v"];
   if (tag == "b") {
      if (!v.is_boolean())
         Fail(ErrorCode::INVALID_ARGS, "a 'b' value must be a boolean");
      return v.get<bool>();
   }
   if (tag == "i") {
      if (v.is_number_integer()) {
         const auto i = v.get<int64_t>();
         if (i < INT32_MIN || i > INT32_MAX)
            Fail(ErrorCode::INVALID_ARGS, "an 'i' value must fit in 32 bits");
         return int(i);
      }
      if (v.is_number_float()) {
         const auto d = v.get<double>();
         if (std::isfinite(d) && d == std::floor(d) && d >= INT32_MIN &&
             d <= INT32_MAX)
            return int(d);
      }
      Fail(ErrorCode::INVALID_ARGS, "an 'i' value must be an integer");
   }
   if (tag == "d") {
      if (!v.is_number())
         Fail(ErrorCode::INVALID_ARGS, "a 'd' value must be a number");
      return v.get<double>();
   }
   if (tag == "s") {
      if (!v.is_string())
         Fail(ErrorCode::INVALID_ARGS, "an 's' value must be a string");
      return v.get<std::string>();
   }
   Fail(ErrorCode::INVALID_ARGS, "unknown value tag '" + tag + "'");
}

double Numeric(const ExportValue &value)
{
   if (auto i = std::get_if<int>(&value))
      return *i;
   if (auto d = std::get_if<double>(&value))
      return *d;
   if (auto b = std::get_if<bool>(&value))
      return *b ? 1 : 0;
   return NAN;
}

// ---------------------------------------------------------------------------
// Options session: one editor per format key (ExportOptionsHandler of
// src/export without the controls)
// ---------------------------------------------------------------------------
class OptionsSession final : public ExportOptionsEditor::Listener
{
public:
   explicit OptionsSession(const FormatRef &ref)
      : mPlugin{ *ref.plugin }, mIndex{ ref.index }, mKey{ ref.key }
   {
      mEditor = mPlugin.CreateOptionsEditor(mIndex, this);
      if (mEditor) {
         mEditor->Load(*gPrefs);
         if (IsWavPackCorrection(kWavPackCorrectionOption))
            mEditor->SetValue(kWavPackCorrectionOption, ExportValue{ false });
         // ExportPCM::GetFormatInfo(FMT_OTHER) reads the header type from
         // the preferences (default WAV), the editor defaults to the first
         // non-WAV header: store once so that both agree
         if (mKey == kPcmOtherKey) {
            mEditor->Store(*gPrefs);
            gPrefs->Flush();
         }
      }
   }
   ~OptionsSession() override = default;
   OptionsSession(const OptionsSession &) = delete;
   OptionsSession &operator=(const OptionsSession &) = delete;

   FormatInfo Info() const { return mPlugin.GetFormatInfo(mIndex); }

   //! The editor's rate list, sorted (empty = any rate)
   std::vector<int> Rates() const
   {
      std::vector<int> rates;
      if (mEditor)
         rates = mEditor->GetSampleRateList();
      std::sort(rates.begin(), rates.end());
      rates.erase(std::unique(rates.begin(), rates.end()), rates.end());
      return rates;
   }

   //! ExportOptions (API.md §5.6)
   json Snapshot() const
   {
      json options = json::array();
      for (int i = 0, n = mEditor ? mEditor->GetOptionsCount() : 0; i < n; ++i) {
         ExportOption option;
         if (!mEditor->GetOption(i, option))
            continue;
         ExportValue value = option.defaultValue;
         mEditor->GetValue(option.id, value);
         options.push_back(OptionJson(Restricted(option), value));
      }
      json rates = json::array();
      for (const auto rate : Rates())
         rates.push_back(rate);
      return json{ { "format", FormatJson(Info(), mKey) },
         { "sampleRates", std::move(rates) }, { "options", std::move(options) } };
   }

   //! Validated SetValue + Store in the preferences
   void Set(ExportOptionID id, const ExportValue &value)
   {
      if (!mEditor)
         Fail(ErrorCode::INVALID_ARGS, "the format '" + mKey + "' has no options");
      ExportOption option;
      bool found = false;
      for (int i = 0, n = mEditor->GetOptionsCount(); i < n && !found; ++i)
         if (mEditor->GetOption(i, option) && option.id == id)
            found = true;
      if (!found)
         Fail(ErrorCode::INVALID_ARGS,
            "no export option with id " + std::to_string(id));
      option = Restricted(option);
      const auto title = Translated(option.title);
      if (option.flags & ExportOption::ReadOnly)
         Fail(ErrorCode::INVALID_ARGS, "option '" + title + "' is read-only");
      ExportValue current = option.defaultValue;
      mEditor->GetValue(id, current);
      if (current.index() != value.index())
         Fail(ErrorCode::INVALID_ARGS, "option '" + title +
            "' expects a value tagged '" +
            TaggedValue(current)["t"].get<std::string>() + "'");
      switch (option.flags & ExportOption::TypeMask) {
      case ExportOption::TypeEnum:
         if (std::find(option.values.begin(), option.values.end(), value) ==
             option.values.end())
            Fail(ErrorCode::INVALID_ARGS,
               "the value is not a choice of option '" + title + "'");
         break;
      case ExportOption::TypeRange:
         if (option.values.size() >= 2) {
            const auto x = Numeric(value);
            const auto lo = Numeric(option.values[0]);
            const auto hi = Numeric(option.values[1]);
            if (!(x >= lo && x <= hi))
               Fail(ErrorCode::INVALID_ARGS, "option '" + title +
                  "' must be in [" + std::to_string(lo) + ", " +
                  std::to_string(hi) + "]");
         }
         break;
      default:
         break;
      }
      if (!mEditor->SetValue(id, value))
         Fail(ErrorCode::INVALID_ARGS,
            "the value was rejected by option '" + title + "'");
      mEditor->Store(*gPrefs);
      gPrefs->Flush();
   }

   //! Parameters for ExportTaskBuilder (stores the options, like the
   //! desktop dialog does when exporting)
   ExportProcessor::Parameters Commit()
   {
      if (!mEditor)
         return {};
      mEditor->Store(*gPrefs);
      gPrefs->Flush();
      auto parameters = ExportUtils::ParametersFromEditor(*mEditor);
      for (auto &[id, value] : parameters)
         if (IsWavPackCorrection(id))
            value = false;
      return parameters;
   }

   // ExportOptionsEditor::Listener: every response carries a full snapshot
   void OnExportOptionChangeBegin() override {}
   void OnExportOptionChangeEnd() override {}
   void OnExportOptionChange(const ExportOption &) override {}
   void OnFormatInfoChange() override {}
   void OnSampleRateListChange() override {}

private:
   bool IsWavPackCorrection(ExportOptionID id) const
   {
      return mKey == kWavPackKey && id == kWavPackCorrectionOption;
   }

   ExportOption Restricted(ExportOption option) const
   {
      if (IsWavPackCorrection(option.id))
         option.flags |= ExportOption::ReadOnly | ExportOption::Hidden;
      return option;
   }

   static json OptionJson(const ExportOption &option, const ExportValue &value)
   {
      std::string type;
      switch (option.flags & ExportOption::TypeMask) {
      case ExportOption::TypeRange: type = "range"; break;
      case ExportOption::TypeEnum: type = "enum"; break;
      default:
         type = std::visit([](const auto &v) -> std::string {
            using T = std::decay_t<decltype(v)>;
            if constexpr (std::is_same_v<T, bool>) return "bool";
            else if constexpr (std::is_same_v<T, int>) return "int";
            else if constexpr (std::is_same_v<T, double>) return "double";
            else return "string";
         }, option.defaultValue);
      }
      json values = json::array();
      for (const auto &v : option.values)
         values.push_back(TaggedValue(v));
      json names = json::array();
      for (const auto &name : option.names)
         names.push_back(Translated(name));
      return json{ { "id", option.id }, { "title", Translated(option.title) },
         { "type", type },
         { "readOnly", (option.flags & ExportOption::ReadOnly) != 0 },
         { "hidden", (option.flags & ExportOption::Hidden) != 0 },
         { "value", TaggedValue(value) }, { "values", std::move(values) },
         { "names", std::move(names) } };
   }

   ExportPlugin &mPlugin;
   const int mIndex;
   const std::string mKey;
   std::unique_ptr<ExportOptionsEditor> mEditor;
};

std::map<std::string, std::unique_ptr<OptionsSession>> &Sessions()
{
   static std::map<std::string, std::unique_ptr<OptionsSession>> sessions;
   return sessions;
}

//! The format's session; `reload` re-creates it from the preferences
OptionsSession &SessionFor(const FormatRef &ref, bool reload = false)
{
   auto &slot = Sessions()[ref.key];
   if (!slot || reload) {
      slot.reset();   // the old editor's listener goes first
      slot = std::make_unique<OptionsSession>(ref);
   }
   return *slot;
}

// ---- ExportAudioDialog / ExportFilePanel rules --------------------------

//! ExportAudioDialog constructor: the preferred export rate of the project,
//! else the highest track rate (else the project rate)
int WantedRate(AudacityProject &project)
{
   double rate = ImportExport::Get(project).GetPreferredExportRate();
   if (rate == ImportExport::InvalidRate) {
      rate = 0;
      for (const auto track : TrackList::Get(project).Any<WaveTrack>())
         rate = std::max(rate, track->GetRate());
      if (rate <= 0)
         rate = ProjectRate::Get(project).GetRate();
   }
   return int(std::lround(rate));
}

//! ExportFilePanel::UpdateSampleRateList: `wanted` if offered, else the
//! lowest rate above it, else the highest
int PickRate(const std::vector<int> &rates, int wanted)
{
   if (rates.empty())
      return wanted;
   if (std::find(rates.begin(), rates.end(), wanted) != rates.end())
      return wanted;
   for (const auto rate : rates)
      if (rate >= wanted)
         return rate;
   return rates.back();
}

unsigned MaxChannels(const FormatInfo &info)
{
   return std::clamp(info.maxChannels, 1u, kMaxExportChannels);
}

// ---------------------------------------------------------------------------
// Export driver
// ---------------------------------------------------------------------------

//! ExportProcessorDelegate; the worker calls the overrides, the engine
//! thread the rest
class BridgeExportDelegate final : public ExportProcessorDelegate
{
public:
   bool IsCancelled() const override { return mCancelled.load(); }
   bool IsStopped() const override { return mStopped.load(); }
   void SetStatusString(const TranslatableString &str) override
   {
      std::lock_guard lock{ mMutex };
      mStatus = str;
   }
   void OnProgress(double progress) override { mProgress.store(progress); }

   TranslatableString Status() const
   {
      std::lock_guard lock{ mMutex };
      return mStatus;
   }
   double Progress() const { return mProgress.load(); }
   // DialogExportProgressDelegate::UpdateUI: cancel and stop exclude each
   // other
   void RequestCancel() { if (!mStopped) mCancelled = true; }
   void RequestStop() { if (!mCancelled) mStopped = true; }

private:
   std::atomic<bool> mCancelled{ false };
   std::atomic<bool> mStopped{ false };
   std::atomic<double> mProgress{ 0.0 };
   mutable std::mutex mMutex;
   TranslatableString mStatus;
};

//! ExportProgressUI::ExceptionWrappedCall, answering with an error envelope
//! instead of a dialog; other exceptions go to the dispatcher
[[noreturn]] void RethrowExportError(std::exception_ptr error)
{
   try {
      std::rethrow_exception(error);
   }
   catch (const ExportDiskFullError &e) {
      Fail(ErrorCode::FAILED,
         Translated(FileException::WriteFailureMessage(e.GetFileName())));
   }
   catch (const ExportErrorException &e) {
      Fail(ErrorCode::FAILED, Translated(e.GetMessage()));
   }
   catch (const ExportException &e) {
      Fail(ErrorCode::FAILED, ToUtf8(e.What()));
   }
}

//! Deletes the (partial) output; `sidecar`: also the WavPack correction
//! file "<path>c" (only when it did not exist before the export)
void RemoveOutput(const FilePath &path, bool sidecar)
{
   wxLogNull noLog;
   if (wxFileExists(path))
      wxRemoveFile(path);
   if (sidecar && wxFileExists(path + wxT("c")))
      wxRemoveFile(path + wxT("c"));
}

// ---------------------------------------------------------------------------
// Commands
// ---------------------------------------------------------------------------

//! export.formats -> {formats:[ExportFormat]}
json ExportFormats(const json &)
{
   json formats = json::array();
   std::set<std::string> seen;
   for (auto [plugin, index] : ExportPluginRegistry::Get()) {
      const auto info = plugin->GetFormatInfo(index);
      const auto key = FormatKey(info);
      if (seen.insert(key).second)
         formats.push_back(FormatJson(info, key));
   }
   return json{ { "formats", std::move(formats) } };
}

//! export.defaults {formatKey}
json ExportDefaults(const json &args)
{
   auto &project = Session::Get().RequireProject();
   const auto ref = RequireFormat(args);
   auto &session = SessionFor(ref);
   const auto maxChannels = MaxChannels(session.Info());

   // ExportFilePanel::Init: stereo when an exported track is stereo or panned
   unsigned channels = 1;
   for (const auto track :
        ExportUtils::FindExportWaveTracks(TrackList::Get(project), false))
      if (track->NChannels() >= 2 || track->GetPan() != .0f) {
         channels = 2;
         break;
      }
   channels = std::min(channels, maxChannels);

   const int wanted = WantedRate(project);
   auto rates = session.Rates();
   int defaultRate;
   if (rates.empty()) {
      // DefaultRates, plus the wanted rate as a "custom" entry
      rates = kDefaultRates;
      if (std::find(rates.begin(), rates.end(), wanted) == rates.end()) {
         rates.push_back(wanted);
         std::sort(rates.begin(), rates.end());
      }
      defaultRate = wanted;
   }
   else
      defaultRate = PickRate(rates, wanted);

   json list = json::array();
   for (const auto rate : rates)
      list.push_back(rate);
   return json{ { "hasSelection", ExportUtils::HasSelectedAudio(project) },
      { "defaultChannels", channels }, { "maxChannels", maxChannels },
      { "defaultRate", defaultRate }, { "rates", std::move(list) } };
}

//! export.options {formatKey}: (re)opens the session from the preferences
json ExportOptions(const json &args)
{
   return SessionFor(RequireFormat(args), true).Snapshot();
}

//! export.setOption {formatKey, id, value:{t, v}}
json ExportSetOption(const json &args)
{
   auto &session = SessionFor(RequireFormat(args));
   const auto id = ArgInt(args, "id");
   if (id < INT32_MIN || id > INT32_MAX)
      Fail(ErrorCode::INVALID_ARGS, "argument 'id' is out of range");
   if (!args.contains("value"))
      Fail(ErrorCode::INVALID_ARGS, "missing argument 'value'");
   session.Set(ExportOptionID(id), ParseTagged(args["value"]));
   return session.Snapshot();
}

//! export.run {path, formatKey, range, channels, rate, skipSilenceAtStart?}
json ExportRun(const json &args)
{
   auto &project = Session::Get().RequireProject();
   const auto ref = RequireFormat(args);
   auto &session = SessionFor(ref);
   const auto info = session.Info();

   // Staging path: absolute, in an existing directory, not existing yet
   const auto pathArg = FromUtf8(ArgString(args, "path"));
   if (pathArg.empty() || !wxFileName(pathArg).IsAbsolute())
      Fail(ErrorCode::INVALID_ARGS, "argument 'path' must be an absolute path");
   const wxFileName fileName{ pathArg };
   const FilePath path = fileName.GetFullPath();
   if (fileName.GetFullName().empty())
      Fail(ErrorCode::INVALID_ARGS, "argument 'path' must name a file");
   if (wxFileExists(path) || wxDirExists(path))
      Fail(ErrorCode::INVALID_ARGS, "the staging path must not exist yet");
   if (!wxDirExists(fileName.GetPath()))
      Fail(ErrorCode::INVALID_ARGS, "the directory of the path does not exist");
   const bool sidecar = !wxFileExists(path + wxT("c"));

   const auto range = ArgString(args, "range");
   if (range != "project" && range != "selection")
      Fail(ErrorCode::INVALID_ARGS, "argument 'range' must be project or selection");
   const bool selectedOnly = range == "selection";

   const auto channels = ArgInt(args, "channels");
   const auto maxChannels = MaxChannels(info);
   if (channels < 1 || channels > int64_t(maxChannels))
      Fail(ErrorCode::INVALID_ARGS, "argument 'channels' must be in 1.." +
         std::to_string(maxChannels));

   const auto rate = ArgInt(args, "rate");
   if (const auto rates = session.Rates(); !rates.empty()) {
      if (std::find(rates.begin(), rates.end(), rate) == rates.end())
         Fail(ErrorCode::INVALID_ARGS, "the format '" + ref.key +
            "' does not support the sample rate " + std::to_string(rate));
   }
   else
      RequireRange("rate", double(rate), 1000, 768000);

   const bool skipSilence = OptBool(args, "skipSilenceAtStart").value_or(false);

   // ExportAudioDialog::OnExport
   auto &tracks = TrackList::Get(project);
   if (tracks.Any<const WaveTrack>().empty())
      Fail(ErrorCode::FAILED, "There is no audio to export");
   if (selectedOnly && !ExportUtils::HasSelectedAudio(project))
      Fail(ErrorCode::NO_SELECTION,
         "select the audio (time and tracks) to export first");
   const auto &viewInfo = ViewInfo::Get(project);
   double t0 = selectedOnly ? std::max(.0, viewInfo.selectedRegion.t0()) : .0;
   const double t1 = selectedOnly
      ? std::min(tracks.GetEndTime(), viewInfo.selectedRegion.t1())
      : tracks.GetEndTime();
   const auto exportedTracks =
      ExportUtils::FindExportWaveTracks(tracks, selectedOnly);
   if (exportedTracks.empty())
      Fail(ErrorCode::FAILED, Translated(selectedOnly
         ? XO("All selected audio is muted.") : XO("All audio is muted.")));
   if (skipSilence)
      t0 = std::max(t0, exportedTracks.min(&Track::GetStartTime));
   if (!(t1 > t0))
      Fail(ErrorCode::FAILED, "There is no audio to export");

   auto parameters = session.Commit();

   ExportTaskBuilder builder;
   builder.SetFileName(fileName)
      .SetPlugin(ref.plugin, ref.index)
      .SetParameters(std::move(parameters))
      .SetSampleRate(double(rate))
      .SetRange(t0, t1, selectedOnly)
      .SetNumChannels(unsigned(channels));
   // No SetTags: the processors write the project's Tags

   ProgressScope scope{ Translated(XO("Export")), {}, true, true };

   ExportTask task;
   try {
      // Never AskResample (MP3) or any other batch-mode question
      auto batch = valueRestorer(project.mBatchMode, project.mBatchMode + 1);
      // Initialize() runs here, on the engine thread
      task = builder.Build(project);
   }
   catch (...) {
      RemoveOutput(path, sidecar);
      RethrowExportError(std::current_exception());
   }

   BridgeExportDelegate delegate;
   auto future = task.get_future();
   std::thread worker;
   try {
      worker = std::thread{ std::move(task), std::ref(delegate) };
   }
   catch (const std::system_error &e) {
      RemoveOutput(path, sidecar);
      Fail(ErrorCode::FAILED, std::string("cannot start the export: ") + e.what());
   }
   // Whatever happens below, the worker (which uses `delegate` and reads
   // the project) is finished before this frame goes away; a joinable
   // std::thread would also terminate the process when destroyed
   auto joinWorker = finally([&] {
      if (worker.joinable()) {
         delegate.RequestCancel();
         worker.join();
      }
   });

   // ExportProgressUI::Show, but the worker is joined and the engine thread
   // runs only internal (CallAfter) work meanwhile: no command can touch the
   // project while Process() reads it
   bool userCancelled = false;
   using namespace std::chrono_literals;
   while (future.wait_for(40ms) != std::future_status::ready) {
      EngineThread::Get().DrainInternal(16);
      const auto status = Translated(delegate.Status());
      switch (scope.Update(delegate.Progress(), &status)) {
      case BasicUI::ProgressResult::Cancelled:
         userCancelled = true;
         delegate.RequestCancel();
         break;
      case BasicUI::ProgressResult::Stopped:
         delegate.RequestStop();
         break;
      default:
         break;
      }
   }
   worker.join();

   ExportResult result = ExportResult::Error;
   try {
      result = future.get();   // rethrows the exceptions of Process()
   }
   catch (...) {
      RemoveOutput(path, sidecar);
      RethrowExportError(std::current_exception());
   }

   switch (result) {
   case ExportResult::Success:
   case ExportResult::Stopped:
      break;
   case ExportResult::Cancelled:
      RemoveOutput(path, sidecar);
      if (userCancelled || scope.IsCancelled())
         Fail(ErrorCode::CANCELLED, Translated(XO("Cancelled")));
      // Initialize() returned false without the user asking
      Fail(ErrorCode::FAILED, Translated(XO("Export error")));
   case ExportResult::Error:
   default:
      // ExportProgressUI::Show's message
      RemoveOutput(path, sidecar);
      Fail(ErrorCode::FAILED, Translated(XO("Export completed with error.")));
   }
   if (!wxFileExists(path))
      Fail(ErrorCode::FAILED,
         Translated(FileException::WriteFailureMessage(fileName)));

   // ExportAudioDialog::OnExport after success
   ImportExport::Get(project).SetPreferredExportRate(double(rate));
   gPrefs->Write(wxT("/ExportAudioDialog/Format"), info.format);
   gPrefs->Flush();

   return json{ { "path", ToUtf8(path) },
      { "stopped", result == ExportResult::Stopped } };
}

} // namespace

void RegisterExportCommands(ModuleRegistry &registry)
{
   registry.AddCommand("export.formats", ExportFormats);
   registry.AddCommand("export.defaults", ExportDefaults, NeedsProject);
   registry.AddCommand("export.options", ExportOptions);
   registry.AddCommand("export.setOption", ExportSetOption);
   registry.AddCommand("export.run", ExportRun,
      NeedsProject | NeedsIdleAudio | LongRunning);
}

void ResetExportSessions()
{
   Sessions().clear();
}

std::vector<std::string> ExportFormatKeys()
{
   std::vector<std::string> keys;
   for (auto [plugin, index] : ExportPluginRegistry::Get())
      keys.push_back(FormatKey(plugin->GetFormatInfo(index)));
   return keys;
}

} // namespace io
} // namespace aubridge
