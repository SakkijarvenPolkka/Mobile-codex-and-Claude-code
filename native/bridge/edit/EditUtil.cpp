/*  SPDX-License-Identifier: GPL-2.0-or-later */
/**********************************************************************

  Audacity Android port

  EditUtil.cpp

  The precondition checks are ports of the predicates of Audacity 3.7.9
  src/CommonCommandFlags.cpp and of the "select all if none" recovery of
  lib-menus/CommandManager.cpp (TryToMakeActionAllowed) with the enablers
  of src/menus/EditMenus.cpp; DoSelectAllAudio and SelectNone come from
  src/SelectUtilities.cpp (Paul Licameli split from SelectMenus.cpp).

**********************************************************************/
#include "EditUtil.h"

#include <cmath>

#include "Edit.h"
#include "Prefs.h"
#include "Session.h"
#include "Track.h"
#include "ViewInfo.h"
#include "WaveTrack.h"

namespace aubridge {
namespace edit {

unsigned MissingRequirements(AudacityProject &project, unsigned requirements)
{
   auto &tracks = TrackList::Get(project);
   unsigned missing = 0;
   if ((requirements & NeedTime) &&
       ViewInfo::Get(project).selectedRegion.isPoint())
      missing |= NeedTime;
   if (requirements & NeedEditableTracks) {
      auto range = tracks.Selected() + &Track::SupportsBasicEditing;
      if (range.empty())
         missing |= NeedEditableTracks;
   }
   if ((requirements & NeedWaveTracks) &&
       tracks.Selected<const WaveTrack>().empty())
      missing |= NeedWaveTracks;
   if ((requirements & NeedAnyTracks) && tracks.Selected().empty())
      missing |= NeedAnyTracks;
   return missing;
}

void DoSelectAllAudio(AudacityProject &project)
{
   auto &tracks = TrackList::Get(project);
   auto &selectedRegion = ViewInfo::Get(project).selectedRegion;
   selectedRegion.setTimes(tracks.GetStartTime(), tracks.GetEndTime());
   for (auto t : tracks)
      t->SetSelected(false);
   for (auto t : tracks.Any<WaveTrack>())
      t->SetSelected(true);
   ModifyState(project, false);
}

void SelectNoTracks(AudacityProject &project)
{
   for (auto t : TrackList::Get(project))
      t->SetSelected(false);
}

bool ReadPrefBool(const wxString &path, bool def)
{
   return gPrefs ? gPrefs->ReadBool(path, def) : def;
}

void RequireSelection(AudacityProject &project, unsigned requirements,
   bool autoSelect, const TranslatableString &commandName)
{
   auto missing = MissingRequirements(project, requirements);
   if (missing && autoSelect &&
       ReadPrefBool(wxT("/GUI/SelectAllOnNone"), false)) {
      // The enablers of EditMenus.cpp: "TracksExist" can produce
      // (Editable|Any)TracksSelected, "WaveTracksExist" can produce
      // TimeSelected | WaveTracksSelected; each one runs DoSelectAllAudio
      auto &tracks = TrackList::Get(project);
      const bool tracksExist = !tracks.empty();
      const bool wavesExist = !tracks.Any<const WaveTrack>().empty();
      if (((missing & (NeedEditableTracks | NeedAnyTracks)) && tracksExist) ||
          ((missing & (NeedTime | NeedWaveTracks)) && wavesExist)) {
         DoSelectAllAudio(project);
         missing = MissingRequirements(project, requirements);
      }
   }
   if (!missing)
      return;

   // Messages of CommonCommandFlags.cpp
   TranslatableString message;
   if (missing & (NeedTime | NeedEditableTracks))
      message = XO(
"Select the audio for %s to use (for example, Ctrl + A to Select All) then try again.")
         .Format(commandName);
   else if (missing & NeedWaveTracks)
      message = XO(
"You must first select some audio to perform this action.\n(Selecting other kinds of track won't work.)");
   else
      message = XO("\"%s\" requires one or more tracks to be selected.")
         .Format(commandName);
   Fail(ErrorCode::NO_SELECTION, Translated(message));
}

double ArgTime(const json &args, const char *key)
{
   const double value = ArgDouble(args, key);
   if (!std::isfinite(value))
      Fail(ErrorCode::INVALID_ARGS,
         std::string("argument '") + key + "' must be a finite number");
   return value;
}

std::optional<double> OptTime(const json &args, const char *key)
{
   auto value = OptDouble(args, key);
   if (value && !std::isfinite(*value))
      Fail(ErrorCode::INVALID_ARGS,
         std::string("argument '") + key + "' must be a finite number");
   return value;
}

void CheckOptionalGeneration(const json &args)
{
   if (auto generation = OptInt(args, "generation"))
      if (uint64_t(*generation) != Session::Get().Generation())
         Fail(ErrorCode::STALE, "reference of generation " +
            std::to_string(*generation) + ", current is " +
            std::to_string(Session::Get().Generation()));
}

wxString FormatRate(double rate)
{
   // "Separate conversion of "rate" enables changing the decimals without
   // affecting i18n" (WaveTrackControls.cpp RateMenuTable::SetRate)
   return wxString::Format(wxT("%.3f"), rate);
}

} // namespace edit
} // namespace aubridge
