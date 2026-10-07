/*  SPDX-License-Identifier: GPL-2.0-or-later */
/**********************************************************************

  Audacity Android port

  Snapshot.cpp

  Flag predicates are ports of Audacity 3.7.9 src/CommonCommandFlags.cpp
  (AudioIONotBusy, TimeSelected, ... Dominic Mazzoni et al.) and of the
  flags defined in src/menus/EditMenus.cpp (CutCopyAvailable,
  JoinClipsAvailable), src/menus/LabelMenus.cpp (LabelsSelected),
  src/tracks/ui/Scrubbing.cpp (HasWaveData).

**********************************************************************/
#include "Snapshot.h"

#include "AudioIO.h"
#include "Clipboard.h"
#include "Hooks.h"
#include "LabelTrack.h"
#include "ModuleRegistry.h"
#include "PlayableTrack.h"
#include "Prefs.h"
#include "Project.h"
#include "ProjectFileIO.h"
#include "ProjectHistory.h"
#include "ProjectRate.h"
#include "QualitySettings.h"
#include "Session.h"
#include "SyncLock.h"
#include "TimeTrack.h"
#include "TrackFocus.h"
#include "UndoManager.h"
#include "ViewInfo.h"
#include "WaveClip.h"
#include "WaveTrack.h"
#include "Edit.h"

namespace aubridge {

const char *FormatName(sampleFormat format)
{
   switch (format) {
   case int16Sample: return "int16";
   case int24Sample: return "int24";
   default: return "float";
   }
}

bool ParseFormat(const std::string &name, sampleFormat &format)
{
   if (name == "int16")
      format = int16Sample;
   else if (name == "int24")
      format = int24Sample;
   else if (name == "float")
      format = floatSample;
   else
      return false;
   return true;
}

long DefaultProjectRate()
{
   long rate = 0;
   if (gPrefs && gPrefs->Read(QualitySettings::DefaultSampleRate.GetPath(), &rate)
       && rate > 0)
      return rate;
   return 44100;
}

const char *TrackKind(const Track &track)
{
   if (dynamic_cast<const WaveTrack *>(&track))
      return "wave";
   if (dynamic_cast<const LabelTrack *>(&track))
      return "label";
   if (dynamic_cast<const TimeTrack *>(&track))
      return "time";
   // NoteTrack only exists with USE_MIDI (off in the port)
   if (track.GetTypeInfo().names.info == wxT("note"))
      return "note";
   return "other";
}

uint64_t ComputeCommandFlags(AudacityProject *project)
{
   using namespace Flag;
   auto &session = Session::Get();
   // Single project, no keyboard focus model: always "focused", the stream
   // can always be stopped
   uint64_t f = FOC | CS;
   if (session.RecordPermission())
      f |= RECORD_PERMISSION;
   if (!Clipboard::Get().GetTracks().empty())
      f |= CLIPBOARD;

   auto *audioIO = AudioIO::Get();
   if (audioIO && audioIO->IsPaused())
      f |= PAUSED;
   if (!(audioIO && audioIO->IsBusy() && audioIO->GetNumCaptureChannels() > 0))
      f |= CNB;

   if (!project) {
      f |= NB | NSL | NO_TIMETRACK;
      return f;
   }
   auto &p = *project;
   f |= PROJECT_OPEN;

   auto &tracks = TrackList::Get(p);
   auto &viewInfo = ViewInfo::Get(p);
   const auto &region = viewInfo.selectedRegion;

   const bool busy = AudioBusy(p);
   f |= busy ? BUSY : NB;
   if (!region.isPoint())
      f |= TS;
   if (!tracks.Selected<const WaveTrack>().empty())
      f |= WS;
   if (!tracks.Any().empty())
      f |= TE;
   {
      auto range = tracks.Selected() -
         [](const Track *pTrack) { return !pTrack->SupportsBasicEditing(); };
      if (!range.empty())
         f |= ES;
   }
   if (!tracks.Selected().empty())
      f |= AS;
   if (!tracks.Any<const LabelTrack>().empty())
      f |= LE;
   auto &history = ProjectHistory::Get(p);
   if (history.UndoAvailable())
      f |= UA;
   if (history.RedoAvailable())
      f |= RA;
   if (f & TE) {
      if (viewInfo.ZoomInAvailable())
         f |= ZI;
      if (viewInfo.ZoomOutAvailable())
         f |= ZO;
   }
   if (!tracks.Any<const WaveTrack>().empty())
      f |= WE;
   f |= SyncLockState::Get(p).IsSyncLocked() ? SL : NSL;
   for (auto pTrack : tracks.Selected<const WaveTrack>())
      if (pTrack->NChannels() > 1) {
         f |= ST;
         break;
      }
   // Label text selection does not exist on Android (LABEL_TEXT_SEL = 0)
   if ((f & TS) && (f & ES))
      f |= CC;
   if (!busy && (f & TS))
      for (auto pTrack : tracks.Selected<const WaveTrack>())
         if (pTrack->GetSortedClipsIntersecting(region.t0(), region.t1())
               .size() > 1) {
            f |= JC;
            break;
         }
   for (auto pTrack : tracks.Selected<const LabelTrack>()) {
      bool found = false;
      for (const auto &label : pTrack->GetLabels())
         if (label.getT0() >= region.t0() && label.getT1() <= region.t1()) {
            found = true;
            break;
         }
      if (found) {
         f |= LS;
         break;
      }
   }
   for (auto pTrack : tracks.Any<const WaveTrack>())
      if (pTrack->GetEndTime() > pTrack->GetStartTime()) {
         f |= HW;
         break;
      }
   if (TrackFocus::Get(p).PeekFocus())
      f |= TFOCUS;
   if (!tracks.Any<const PlayableTrack>().empty())
      f |= PLAYABLE;
   if (tracks.Any<const TimeTrack>().empty())
      f |= NO_TIMETRACK;
   return f;
}

namespace {

json ProjectSection(AudacityProject *project)
{
   json j{ { "open", project != nullptr }, { "name", "" }, { "path", nullptr },
      { "temporary", true }, { "dirty", false },
      { "rate", double(DefaultProjectRate()) },
      { "defaultFormat", FormatName(QualitySettings::SampleFormatChoice()) } };
   if (!project)
      return j;
   auto &p = *project;
   auto &fileIO = ProjectFileIO::Get(p);
   const auto name = p.GetProjectName();
   j["name"] = name.empty() ? std::string("Untitled") : ToUtf8(name);
   if (!fileIO.IsTemporary())
      j["path"] = ToUtf8(fileIO.GetFileName());
   j["temporary"] = fileIO.IsTemporary();
   // A recovered unsaved project counts as dirty although its undo history
   // is "saved" (ProjectManager::OpenProject replaces the initial state):
   // closing it without saving deletes it (API.md project.recover)
   j["dirty"] = UndoManager::Get(p).UnsavedChanges() ||
      (fileIO.IsTemporary() && fileIO.IsRecovered());
   j["rate"] = ProjectRate::Get(p).GetRate();
   return j;
}

json TrackJson(Track &track, const Track *focus)
{
   json t{ { "id", TrackIdValue(track) }, { "kind", TrackKind(track) },
      { "name", ToUtf8(track.GetName()) }, { "selected", track.GetSelected() },
      { "focused", &track == focus } };
   if (auto wt = dynamic_cast<WaveTrack *>(&track)) {
      t["channels"] = wt->NChannels();
      t["rate"] = wt->GetRate();
      t["format"] = FormatName(wt->GetSampleFormat());
      t["gain"] = Finite(wt->GetVolume(), 1.0);
      t["pan"] = Finite(wt->GetPan());
      t["mute"] = wt->GetMute();
      t["solo"] = wt->GetSolo();
      const auto clips = wt->SortedIntervalArray();
      t["start"] = clips.empty() ? 0.0 : Finite(wt->GetStartTime());
      t["end"] = clips.empty() ? 0.0 : Finite(wt->GetEndTime());
      t["waveVersion"] = WaveVersion(*wt);
      json array = json::array();
      int index = 0;
      for (const auto &clip : clips)
         array.push_back(json{ { "index", index++ },
            { "name", ToUtf8(clip->GetName()) },
            { "start", Finite(clip->GetPlayStartTime()) },
            { "end", Finite(clip->GetPlayEndTime()) },
            { "trimLeft", Finite(clip->GetTrimLeft()) },
            { "trimRight", Finite(clip->GetTrimRight()) },
            { "stretchRatio", Finite(clip->GetStretchRatio(), 1.0) },
            { "rate", clip->GetRate() } });
      t["clips"] = std::move(array);
   }
   else if (auto lt = dynamic_cast<LabelTrack *>(&track)) {
      json array = json::array();
      int index = 0;
      for (const auto &label : lt->GetLabels())
         array.push_back(json{ { "index", index++ },
            { "t0", Finite(label.getT0()) }, { "t1", Finite(label.getT1()) },
            { "title", ToUtf8(label.title) } });
      t["labels"] = std::move(array);
   }
   else if (auto pt = dynamic_cast<PlayableTrack *>(&track)) {
      t["mute"] = pt->GetMute();
      t["solo"] = pt->GetSolo();
   }
   return t;
}

std::string Describe(UndoManager &manager, unsigned n)
{
   TranslatableString desc;
   manager.GetShortDescription(n, &desc);
   return Translated(desc);
}

} // namespace

json BuildSnapshot()
{
   auto &session = Session::Get();
   auto *project = session.Project();
   const auto &clipboard = Clipboard::Get();

   json s;
   s["generation"] = session.Generation();
   s["project"] = ProjectSection(project);
   s["tracks"] = json::array();
   s["selection"] = json{ { "t0", 0.0 }, { "t1", 0.0 } };
   s["playRegion"] = json{ { "active", false }, { "t0", 0.0 }, { "t1", 0.0 } };
   s["history"] = json{ { "canUndo", false }, { "canRedo", false },
      { "undo", "" }, { "redo", "" } };
   s["view"] = json{ { "zoom", ZoomInfo::GetDefaultZoom() }, { "hpos", 0.0 } };
   s["audio"] = json{ { "busy", false } };
   s["clipboard"] = json{ { "empty", clipboard.GetTracks().empty() },
      { "duration", Finite(clipboard.Duration()) } };
   s["lastEffect"] = nullptr;
   s["lastGenerator"] = nullptr;
   s["lastAnalyzer"] = nullptr;
   s["lastTool"] = nullptr;
   s["flags"] = ComputeCommandFlags(project);

   if (project) {
      auto &p = *project;
      auto &tracks = TrackList::Get(p);
      auto &viewInfo = ViewInfo::Get(p);
      const auto focus = TrackFocus::Get(p).PeekFocus();
      auto &array = s["tracks"];
      for (auto pTrack : tracks)
         array.push_back(TrackJson(*pTrack, focus.get()));
      s["selection"] = json{ { "t0", Finite(viewInfo.selectedRegion.t0()) },
         { "t1", Finite(viewInfo.selectedRegion.t1()) } };
      const auto &playRegion = viewInfo.playRegion;
      s["playRegion"] = json{ { "active", playRegion.Active() },
         { "t0", Finite(playRegion.GetStart()) },
         { "t1", Finite(playRegion.GetEnd()) } };
      auto &history = ProjectHistory::Get(p);
      auto &manager = UndoManager::Get(p);
      const auto current = manager.GetCurrentState();
      const bool canUndo = history.UndoAvailable();
      const bool canRedo = history.RedoAvailable();
      s["history"] = json{ { "canUndo", canUndo }, { "canRedo", canRedo },
         { "undo", canUndo ? Describe(manager, current) : std::string() },
         { "redo", canRedo ? Describe(manager, current + 1) : std::string() } };
      s["view"] = json{ { "zoom", Finite(viewInfo.GetZoom(),
            ZoomInfo::GetDefaultZoom()) },
         { "hpos", Finite(viewInfo.hpos) } };
      s["audio"] = json{ { "busy", AudioBusy(p) } };

      ModuleRegistry::Get().RunSnapshotContributors(s, p);
   }

   // LAST_* bits follow the contributed "last*" fields
   uint64_t flags = s["flags"].is_number_unsigned()
      ? s["flags"].get<uint64_t>()
      : uint64_t(s["flags"].get<int64_t>());
   const auto has = [&](const char *key) {
      auto it = s.find(key);
      return it != s.end() && !it->is_null();
   };
   flags &= ~(Flag::LAST_EFF | Flag::LAST_GEN | Flag::LAST_ANA | Flag::LAST_TOOL);
   if (has("lastEffect")) flags |= Flag::LAST_EFF;
   if (has("lastGenerator")) flags |= Flag::LAST_GEN;
   if (has("lastAnalyzer")) flags |= Flag::LAST_ANA;
   if (has("lastTool")) flags |= Flag::LAST_TOOL;
   s["flags"] = flags;
   return s;
}

json BuildProjectInfo()
{
   auto *project = Session::Get().Project();
   json info = ProjectSection(project);
   info["durationSec"] = 0.0;
   info["tracks"] = 0;
   if (project) {
      auto &tracks = TrackList::Get(*project);
      info["durationSec"] = tracks.empty() ? 0.0 : Finite(tracks.GetEndTime());
      info["tracks"] = tracks.Size();
   }
   return info;
}

} // namespace aubridge
