/*  SPDX-License-Identifier: GPL-2.0-or-later */
/**********************************************************************

  Audacity Android port

  SettingsCommands.cpp

  settings.get / settings.set (API.md §5.1).  Values go through the
  libraries' Setting objects where they exist (their caches stay valid),
  else raw keys as the desktop preference pages write them.

**********************************************************************/
#include "SpineCommands.h"

#include <algorithm>
#include <functional>
#include <vector>

#include "AudioIOBase.h"
#include "Dither.h"
#include "PlayableTrack.h"
#include "Prefs.h"
#include "QualitySettings.h"
#include "SampleFormat.h"
#include "Snapshot.h"
#include "WaveTrack.h"

namespace aubridge {

namespace {

const char *const kDitherNames[] = { "none", "rectangle", "triangle", "shaped" };
const char *const kGroupBy[] = { "sortby:name", "sortby:publisher:name",
   "sortby:type:name", "groupby:publisher", "groupby:type", "default",
   "groupby:type:publisher" };

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
      { "latencyCorrectionMs", AudioIOLatencyCorrection.Read() },
      { "overdub", p.ReadBool(wxT("/AudioIO/Duplex"), true) },
      { "swPlaythrough", p.ReadBool(wxT("/AudioIO/SWPlaythrough"), false) },
      { "preRollSec", p.ReadDouble(wxT("/AudioIO/PreRoll"), 5.0) },
      { "crossfadeMs", p.ReadDouble(wxT("/AudioIO/Crossfade"), 10.0) },
      { "realtimeDither", DitherName(Dither::FastSetting) },
      { "hqDither", DitherName(Dither::BestSetting) },
      { "effectsGroupBy", ToUtf8(p.Read(wxT("/Effects/GroupBy"), wxT("default"))) },
      { "soloMode", ToUtf8(p.Read(wxT("/GUI/Solo"), wxT("Multi"))) },
      { "editClipsCanMove", EditClipsCanMove.Read() },
      { "selectAllOnNone", p.ReadBool(wxT("/GUI/SelectAllOnNone"), false) },
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
   for (auto it = settings.begin(); it != settings.end(); ++it) {
      const auto &key = it.key();
      if (it->is_null())
         continue;
      const json one{ { key, *it } };
      const char *k = key.c_str();
      if (key == "defaultRate") {
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
         const auto v = ArgDouble(one, k);
         RequireRange(k, v, -10000, 10000);
         writes.push_back([v] { AudioIOLatencyCorrection.Write(v); });
      }
      else if (key == "overdub" || key == "swPlaythrough" ||
               key == "selectAllOnNone") {
         const auto v = ArgBool(one, k);
         const wxString path = key == "overdub" ? wxT("/AudioIO/Duplex")
            : key == "swPlaythrough" ? wxT("/AudioIO/SWPlaythrough")
            : wxT("/GUI/SelectAllOnNone");
         writes.push_back([&p, path, v] { p.Write(path, v); });
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
         const auto index = found - std::begin(kDitherNames);
         auto *setting = key == "realtimeDither"
            ? &Dither::FastSetting : &Dither::BestSetting;
         writes.push_back([setting, index] {
            setting->Write(setting->GetSymbols()[index].Internal()); });
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
      else
         Fail(ErrorCode::INVALID_ARGS, "unknown setting '" + key + "'");
   }

   for (auto &write : writes)
      write();
   p.Flush();
   // The dither choices are copied into globals once (SampleFormat.cpp)
   if (settings.contains("realtimeDither") || settings.contains("hqDither"))
      InitDitherers();
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
