/*  SPDX-License-Identifier: GPL-2.0-or-later */
/**********************************************************************

  Audacity Android port

  SettingsCommands.cpp

  settings.get / settings.set (API.md §5.1).  Values go through the
  libraries' Setting objects where they exist (their caches stay valid),
  else raw keys as the desktop preference pages write them; then
  gPrefs->Flush() and PrefsListener::Broadcast() like PrefsDialog::OnOK.

**********************************************************************/
#include "SpineCommands.h"

#include <algorithm>
#include <cctype>
#include <functional>
#include <optional>
#include <vector>

#include "AudioIOBase.h"
#include "BridgePrefs.h"
#include "Dither.h"
#include "Language.h"
#include "PlayableTrack.h"
#include "Prefs.h"
#include "QualitySettings.h"
#include "SampleFormat.h"
#include "Session.h"
#include "Snapshot.h"
#include "SyncLock.h"
#include "WaveTrack.h"

namespace aubridge {

DoubleSetting AudioUserLatencyTrimMs{ L"/Android/AAudio/UserLatencyTrimMs", 0.0 };

namespace {

// Dither::FastSetting / BestSetting symbols None/Rectangle/Triangle/Shaped,
// in the order of DitherType
const char *const kDitherNames[] = { "none", "rectangle", "triangle", "shaped" };
const char *const kGroupBy[] = { "sortby:name", "sortby:publisher:name",
   "sortby:type:name", "groupby:publisher", "groupby:type", "default",
   "groupby:type:publisher" };

// Raw keys read by the desktop's src/ layer (now by the bridge modules)
const wxChar *const kPasteAsNewClips = wxT("/GUI/PasteAsNewClips");          // EditMenus.cpp
const wxChar *const kMoveSelectionWithTracks = wxT("/GUI/MoveSelectionWithTracks"); // TrackMenus.cpp
const wxChar *const kPreferNewTrackRecord = wxT("/GUI/PreferNewTrackRecord"); // ProjectAudioManager.cpp

wxString DropoutKey()
{
   // AudioIO::StartStream reads WarningDialogKey("DropoutDetected")
   return WarningDialogKey(wxT("DropoutDetected"));
}

std::string Lower(std::string s)
{
   for (auto &c : s)
      c = char(std::tolower((unsigned char)c));
   return s;
}

std::string DitherName(EnumSetting<DitherType> &setting)
{
   return Lower(ToUtf8(setting.Read()));
}

json GetSettings()
{
   auto &p = *gPrefs;
   return json{
      { "defaultRate", DefaultProjectRate() },
      { "defaultFormat", FormatName(QualitySettings::SampleFormatChoice()) },
      { "recordChannels", AudioIORecordChannels.Read() },
      { "outputDevice", ToUtf8(AudioIOPlaybackDevice.Read()) },
      { "inputDevice", ToUtf8(AudioIORecordingDevice.Read()) },
      { "latencyMs", AudioIOLatencyDuration.Read() },
      { "latencyCorrectionMs", AudioUserLatencyTrimMs.Read() },
      { "overdub", p.ReadBool(wxT("/AudioIO/Duplex"), true) },
      { "swPlaythrough", p.ReadBool(wxT("/AudioIO/SWPlaythrough"), false) },
      { "preRollSec", p.ReadDouble(wxT("/AudioIO/PreRoll"), 5.0) },
      { "crossfadeMs", p.ReadDouble(wxT("/AudioIO/Crossfade"), 10.0) },
      { "realtimeDither", DitherName(Dither::FastSetting) },
      { "hqDither", DitherName(Dither::BestSetting) },
      { "effectsGroupBy", ToUtf8(p.Read(wxT("/Effects/GroupBy"), wxT("default"))) },
      { "soloMode", ToUtf8(TracksBehaviorsSolo.Read()) },
      { "editClipsCanMove", EditClipsCanMove.Read() },
      { "selectAllOnNone", p.ReadBool(wxT("/GUI/SelectAllOnNone"), false) },
      { "syncLock", SyncLockTracks.Read() },
      { "pasteAsNewClips", p.ReadBool(kPasteAsNewClips, false) },
      { "moveSelectionWithTracks", p.ReadBool(kMoveSelectionWithTracks, false) },
      { "preferNewTrackRecord", p.ReadBool(kPreferNewTrackRecord, false) },
      { "dropoutDetection", p.ReadBool(DropoutKey(), true) },
      { "language", Language::GetSetting() },
   };
}

json SettingsGet(const json &)
{
   return json{ { "settings", GetSettings() } };
}

json SettingsSet(const json &args)
{
   const auto &settings = args.find("settings") != args.end()
      ? args["settings"] : json();
   if (!settings.is_object())
      Fail(ErrorCode::INVALID_ARGS, "argument 'settings' must be an object");

   // Validate everything first, then write (all or nothing)
   std::vector<std::function<void()>> writes;
   auto &p = *gPrefs;
   bool dither = false, language = false;
   std::optional<bool> syncLock;
   // Boolean settings stored under raw keys
   const std::pair<const char *, wxString> rawBools[] = {
      { "overdub", wxT("/AudioIO/Duplex") },
      { "swPlaythrough", wxT("/AudioIO/SWPlaythrough") },
      { "selectAllOnNone", wxT("/GUI/SelectAllOnNone") },
      { "pasteAsNewClips", kPasteAsNewClips },
      { "moveSelectionWithTracks", kMoveSelectionWithTracks },
      { "preferNewTrackRecord", kPreferNewTrackRecord },
      { "dropoutDetection", DropoutKey() },
   };
   for (auto it = settings.begin(); it != settings.end(); ++it) {
      const auto &key = it.key();
      if (it->is_null())
         continue;
      const json one{ { key, *it } };
      const char *k = key.c_str();
      const auto raw = std::find_if(std::begin(rawBools), std::end(rawBools),
         [&](const auto &entry) { return key == entry.first; });
      if (raw != std::end(rawBools)) {
         const auto v = ArgBool(one, k);
         const wxString path = raw->second;
         writes.push_back([&p, path, v] { p.Write(path, v); });
      }
      else if (key == "defaultRate") {
         const auto v = ArgInt(one, k);
         RequireRange(k, double(v), 1000, 768000);
         writes.push_back([v] { QualitySettings::DefaultSampleRate.Write(int(v)); });
      }
      else if (key == "defaultFormat") {
         sampleFormat format;
         if (!ParseFormat(ArgString(one, k), format))
            Fail(ErrorCode::INVALID_ARGS, "unknown sample format");
         writes.push_back([format] {
            QualitySettings::SampleFormatSetting.WriteEnum(format); });
      }
      else if (key == "recordChannels") {
         const auto v = ArgInt(one, k);
         RequireRange(k, double(v), 1, 2);
         writes.push_back([v] { AudioIORecordChannels.Write(int(v)); });
      }
      else if (key == "outputDevice") {
         const auto v = FromUtf8(ArgString(one, k));
         writes.push_back([v] { AudioIOPlaybackDevice.Write(v); });
      }
      else if (key == "inputDevice") {
         const auto v = FromUtf8(ArgString(one, k));
         writes.push_back([v] { AudioIORecordingDevice.Write(v); });
      }
      else if (key == "latencyMs") {
         const auto v = ArgDouble(one, k);
         RequireRange(k, v, 0, 10000);
         writes.push_back([v] { AudioIOLatencyDuration.Write(v); });
      }
      else if (key == "latencyCorrectionMs") {
         // The user trim; the audio module adds it to the measured duplex
         // offset and writes /AudioIO/LatencyCorrection itself
         const auto v = ArgDouble(one, k);
         RequireRange(k, v, -10000, 10000);
         writes.push_back([v] { AudioUserLatencyTrimMs.Write(v); });
      }
      else if (key == "preRollSec") {
         const auto v = ArgDouble(one, k);
         RequireRange(k, v, 0, 60);
         writes.push_back([&p, v] { p.Write(wxT("/AudioIO/PreRoll"), v); });
      }
      else if (key == "crossfadeMs") {
         const auto v = ArgDouble(one, k);
         RequireRange(k, v, 0, 1000);
         writes.push_back([&p, v] { p.Write(wxT("/AudioIO/Crossfade"), v); });
      }
      else if (key == "realtimeDither" || key == "hqDither") {
         const auto v = Lower(ArgString(one, k));
         const auto end = std::end(kDitherNames);
         const auto found = std::find_if(std::begin(kDitherNames), end,
            [&](const char *name) { return v == name; });
         if (found == end)
            Fail(ErrorCode::INVALID_ARGS, "unknown dither '" + v + "'");
         const auto type = DitherType(found - std::begin(kDitherNames));
         auto *setting = key == "realtimeDither"
            ? &Dither::FastSetting : &Dither::BestSetting;
         writes.push_back([setting, type] { setting->WriteEnum(type); });
         dither = true;
      }
      else if (key == "effectsGroupBy") {
         const auto v = ArgString(one, k);
         if (std::find(std::begin(kGroupBy), std::end(kGroupBy), v) ==
             std::end(kGroupBy))
            Fail(ErrorCode::INVALID_ARGS, "unknown effectsGroupBy '" + v + "'");
         writes.push_back([&p, v] {
            p.Write(wxT("/Effects/GroupBy"), FromUtf8(v)); });
      }
      else if (key == "soloMode") {
         const auto v = ArgString(one, k);
         // TracksBehaviorsSolo (PlayableTrack.cpp) knows only these two
         if (v != "Simple" && v != "Multi")
            Fail(ErrorCode::INVALID_ARGS, "soloMode must be Simple or Multi");
         writes.push_back([v] { TracksBehaviorsSolo.Write(FromUtf8(v)); });
      }
      else if (key == "editClipsCanMove") {
         const auto v = ArgBool(one, k);
         writes.push_back([v] { EditClipsCanMove.Write(v); });
      }
      else if (key == "syncLock") {
         const auto v = ArgBool(one, k);
         writes.push_back([v] { SyncLockTracks.Write(v); });
         syncLock = v;
      }
      else if (key == "language") {
         const auto v = ArgString(one, k);
         if (!Language::IsValidSetting(v))
            Fail(ErrorCode::INVALID_ARGS, "unknown language '" + v +
               "' (system, en or an installed catalog)");
         writes.push_back([v] { Language::SetSetting(v); });
         language = true;
      }
      else
         Fail(ErrorCode::INVALID_ARGS, "unknown setting '" + key + "'");
   }

   for (auto &write : writes)
      write();
   p.Flush();
   // The dither choices are copied into globals once (SampleFormat.cpp)
   if (dither)
      InitDitherers();
   auto &session = Session::Get();
   // Tracks ▸ Sync-Lock Tracks (TrackMenus.cpp OnSyncLock): the pref and the
   // open project's state; the SL/NSL snapshot flags follow
   if (syncLock) {
      if (auto project = session.Project())
         SyncLockState::Get(*project).SetSyncLock(*syncLock);
      session.ScheduleSnapshot();
   }
   // Engine strings in the new language; the snapshot carries translated
   // history labels
   if (language && Language::Apply())
      session.ScheduleSnapshot();
   // Attached PrefsListeners (ProjectFileIO, ViewInfo, ...) refresh
   PrefsListener::Broadcast();
   return json{ { "settings", GetSettings() } };
}

} // namespace

void RegisterSettingsCommands(ModuleRegistry &registry)
{
   registry.AddCommand("settings.get", SettingsGet);
   registry.AddCommand("settings.set", SettingsSet);
}

} // namespace aubridge
