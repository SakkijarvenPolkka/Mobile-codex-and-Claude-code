/*  SPDX-License-Identifier: GPL-2.0-or-later */
/**********************************************************************

  Audacity Android port

  SelectCommands.cpp

  select.* and playRegion.* (API.md §3.3 "select / playRegion").  All are
  selection-only: they end with ProjectHistory::ModifyState(false) (keeps
  the selection in the current undo state) and never push a history entry.

  Handler bodies are ports of Audacity 3.7.9:
   * src/menus/SelectMenus.cpp (OnSelectAll ... OnZeroCrossing,
     NearestZeroCrossing, OnSelToStart/End, OnCursorTrackStart/End)
   * src/menus/ClipMenus.cpp (clip boundary and clip search:
     AdjustForFindingStartTimes ... FindClips, DoSelectClip,
     DoCursorClipBoundary), without the screen reader messages
   * src/SelectUtilities.cpp (Paul Licameli split from SelectMenus.cpp):
     DoSelectTimeAndTracks, DoListSelection, the play region functions
   * lib-viewport Viewport::ScrollToStart/ScrollToEnd (selection part)
   * src/tracks/ui/AffordanceHandle.cpp + WaveTrackAffordanceHandle.cpp
     (select.clip = a tap on a clip's title bar)

**********************************************************************/
#include "EditUtil.h"

#include <algorithm>
#include <cmath>
#include <limits>
#include <vector>

#include "Edit.h"
#include "ModuleRegistry.h"
#include "ProjectRate.h"
#include "SelectionState.h"
#include "Session.h"
#include "SyncLock.h"
#include "Track.h"
#include "TrackFocus.h"
#include "ViewInfo.h"
#include "WaveClip.h"
#include "WaveClipUtilities.h"
#include "WaveTrack.h"

namespace aubridge {
namespace edit {

namespace {

AudacityProject &Project()
{
   return Session::Get().RequireProject();
}

// ---------------------------------------------------------------------------
// Clip search (src/menus/ClipMenus.cpp)
// ---------------------------------------------------------------------------

struct FoundClip {
   bool found{};
   double startTime{};
   double endTime{};
};

struct FoundClipBoundary {
   int nFound{};    // 0, 1, or 2
   double time{};
};

// When two clips are immediately next to each other, the GetPlayEndTime() of
// the first clip and the GetPlayStartTime() of the second clip may not be
// exactly equal due to rounding errors.  When searching for the next/prev
// start time from a given time, adjust that time if it is the end time of
// the first of two clips which are next to each other.
double AdjustForFindingStartTimes(
   const WaveTrack::IntervalConstHolders &clips, double time)
{
   auto q = std::find_if(clips.begin(), clips.end(),
      [&](const auto &clip) { return clip->GetPlayEndTime() == time; });
   if (q != clips.end() && q + 1 != clips.end() &&
       WaveClipUtilities::SharesBoundaryWithNextClip(**q, **(q + 1)))
      time = (*(q + 1))->GetPlayStartTime();
   return time;
}

// The converse: adjust a time that is the start time of the second of two
// adjacent clips to the end time of the first
double AdjustForFindingEndTimes(
   const WaveTrack::IntervalConstHolders &clips, double time)
{
   auto q = std::find_if(clips.begin(), clips.end(),
      [&](const auto &clip) { return clip->GetPlayStartTime() == time; });
   if (q != clips.end() && q != clips.begin() &&
       WaveClipUtilities::SharesBoundaryWithNextClip(**(q - 1), **q))
      time = (*(q - 1))->GetPlayEndTime();
   return time;
}

FoundClipBoundary FindNextClipBoundary(const WaveTrack *wt, double time)
{
   FoundClipBoundary result{};
   const auto clips = wt->SortedIntervalArray();
   const double timeStart = AdjustForFindingStartTimes(clips, time);
   const double timeEnd = AdjustForFindingEndTimes(clips, time);

   auto pStart = std::find_if(clips.begin(), clips.end(),
      [&](const auto &clip) { return clip->GetPlayStartTime() > timeStart; });
   auto pEnd = std::find_if(clips.begin(), clips.end(),
      [&](const auto &clip) { return clip->GetPlayEndTime() > timeEnd; });

   if (pStart != clips.end() && pEnd != clips.end()) {
      if (WaveClipUtilities::SharesBoundaryWithNextClip(**pEnd, **pStart)) {
         // boundary between two clips which are immediately next to each other
         result.nFound = 2;
         result.time = (*pEnd)->GetPlayEndTime();
      }
      else if ((*pStart)->GetPlayStartTime() < (*pEnd)->GetPlayEndTime()) {
         result.nFound = 1;
         result.time = (*pStart)->GetPlayStartTime();
      }
      else {
         result.nFound = 1;
         result.time = (*pEnd)->GetPlayEndTime();
      }
   }
   else if (pEnd != clips.end()) {
      result.nFound = 1;
      result.time = (*pEnd)->GetPlayEndTime();
   }
   return result;
}

FoundClipBoundary FindPrevClipBoundary(const WaveTrack *wt, double time)
{
   FoundClipBoundary result{};
   const auto clips = wt->SortedIntervalArray();
   const double timeStart = AdjustForFindingStartTimes(clips, time);
   const double timeEnd = AdjustForFindingEndTimes(clips, time);

   auto pStart = std::find_if(clips.rbegin(), clips.rend(),
      [&](const auto &clip) { return clip->GetPlayStartTime() < timeStart; });
   auto pEnd = std::find_if(clips.rbegin(), clips.rend(),
      [&](const auto &clip) { return clip->GetPlayEndTime() < timeEnd; });

   if (pStart != clips.rend() && pEnd != clips.rend()) {
      if (WaveClipUtilities::SharesBoundaryWithNextClip(**pEnd, **pStart)) {
         result.nFound = 2;
         result.time = (*pStart)->GetPlayStartTime();
      }
      else if ((*pStart)->GetPlayStartTime() > (*pEnd)->GetPlayEndTime()) {
         result.nFound = 1;
         result.time = (*pStart)->GetPlayStartTime();
      }
      else {
         result.nFound = 1;
         result.time = (*pEnd)->GetPlayEndTime();
      }
   }
   else if (pStart != clips.rend()) {
      result.nFound = 1;
      result.time = (*pStart)->GetPlayStartTime();
   }
   return result;
}

//! The selected wave tracks, or all wave tracks if none is selected
template<typename Fn> void ForSearchedWaveTracks(AudacityProject &project, Fn fn)
{
   auto &tracks = TrackList::Get(project);
   const bool anyWaveTracksSelected =
      !tracks.Selected<const WaveTrack>().empty();
   for (auto waveTrack : tracks.Any<const WaveTrack>())
      if (!anyWaveTracksSelected || waveTrack->GetSelected())
         fn(waveTrack);
}

std::vector<FoundClipBoundary> FindClipBoundaries(
   AudacityProject &project, double time, bool next)
{
   std::vector<FoundClipBoundary> results;
   ForSearchedWaveTracks(project, [&](const WaveTrack *waveTrack) {
      auto result = next ? FindNextClipBoundary(waveTrack, time)
                         : FindPrevClipBoundary(waveTrack, time);
      if (result.nFound > 0)
         results.push_back(result);
   });
   std::vector<FoundClipBoundary> finalResults;
   if (!results.empty()) {
      auto compare = [](const FoundClipBoundary &a, const FoundClipBoundary &b)
         { return a.time < b.time; };
      auto p = next
         ? std::min_element(results.begin(), results.end(), compare)
         : std::max_element(results.begin(), results.end(), compare);
      for (auto &r : results)
         if (r.time == p->time)
            finalResults.push_back(r);
   }
   return finalResults;
}

FoundClip FindNextClip(const WaveTrack *wt, double t0, double t1)
{
   FoundClip result{};
   const auto clips = wt->SortedIntervalArray();
   t0 = AdjustForFindingStartTimes(clips, t0);
   {
      auto p = std::find_if(clips.begin(), clips.end(),
         [&](const auto &clip) { return clip->GetPlayStartTime() == t0; });
      if (p != clips.end() && (*p)->GetPlayEndTime() > t1) {
         result.found = true;
         result.startTime = (*p)->GetPlayStartTime();
         result.endTime = (*p)->GetPlayEndTime();
         return result;
      }
   }
   {
      auto p = std::find_if(clips.begin(), clips.end(),
         [&](const auto &clip) { return clip->GetPlayStartTime() > t0; });
      if (p != clips.end()) {
         result.found = true;
         result.startTime = (*p)->GetPlayStartTime();
         result.endTime = (*p)->GetPlayEndTime();
         return result;
      }
   }
   return result;
}

FoundClip FindPrevClip(const WaveTrack *wt, double t0, double t1)
{
   FoundClip result{};
   const auto clips = wt->SortedIntervalArray();
   t0 = AdjustForFindingStartTimes(clips, t0);
   {
      auto p = std::find_if(clips.begin(), clips.end(),
         [&](const auto &clip) { return clip->GetPlayStartTime() == t0; });
      if (p != clips.end() && (*p)->GetPlayEndTime() < t1) {
         result.found = true;
         result.startTime = (*p)->GetPlayStartTime();
         result.endTime = (*p)->GetPlayEndTime();
         return result;
      }
   }
   {
      auto p = std::find_if(clips.rbegin(), clips.rend(),
         [&](const auto &clip) { return clip->GetPlayStartTime() < t0; });
      if (p != clips.rend()) {
         result.found = true;
         result.startTime = (*p)->GetPlayStartTime();
         result.endTime = (*p)->GetPlayEndTime();
         return result;
      }
   }
   return result;
}

std::vector<FoundClip> FindClips(
   AudacityProject &project, double t0, double t1, bool next)
{
   std::vector<FoundClip> results;
   ForSearchedWaveTracks(project, [&](const WaveTrack *waveTrack) {
      auto result = next ? FindNextClip(waveTrack, t0, t1)
                         : FindPrevClip(waveTrack, t0, t1);
      if (result.found)
         results.push_back(result);
   });
   std::vector<FoundClip> finalResults;
   if (!results.empty()) {
      // the clip(s) with the min/max start time
      auto compareStart = [](const FoundClip &a, const FoundClip &b)
         { return a.startTime < b.startTime; };
      auto pStart = next
         ? std::min_element(results.begin(), results.end(), compareStart)
         : std::max_element(results.begin(), results.end(), compareStart);
      std::vector<FoundClip> resultsStartTime;
      for (auto &r : results)
         if (r.startTime == pStart->startTime)
            resultsStartTime.push_back(r);
      if (resultsStartTime.size() > 1) {
         // more than one clip with the same start time: min/max end time
         auto compareEnd = [](const FoundClip &a, const FoundClip &b)
            { return a.endTime < b.endTime; };
         auto pEnd = next
            ? std::min_element(resultsStartTime.begin(),
               resultsStartTime.end(), compareEnd)
            : std::max_element(resultsStartTime.begin(),
               resultsStartTime.end(), compareEnd);
         for (auto &r : resultsStartTime)
            if (r.endTime == pEnd->endTime)
               finalResults.push_back(r);
      }
      else
         finalResults = resultsStartTime;
   }
   return finalResults;
}

// ---------------------------------------------------------------------------
// Zero crossings (src/menus/SelectMenus.cpp)
// ---------------------------------------------------------------------------

constexpr size_t GetWindowSize(double projectRate)
{
   return size_t(std::max(1.0, projectRate / 100));
}

double NearestZeroCrossing(AudacityProject &project, double t0)
{
   const auto rate = ProjectRate::Get(project).GetRate();
   auto &tracks = TrackList::Get(project);

   // Window is 1/100th of a second.
   const auto windowSize = GetWindowSize(rate);
   std::vector<float> dist(windowSize, 0.0f);

   int nTracks = 0;
   for (auto one : tracks.Selected<const WaveTrack>()) {
      const auto nChannels = std::min<size_t>(one->NChannels(), 2);
      const auto oneWindowSize = size_t(std::max(1.0, one->GetRate() / 100));
      std::vector<float> buffer1(oneWindowSize), buffer2(oneWindowSize);
      float *const buffers[]{ buffer1.data(), buffer2.data() };
      const auto s = one->TimeToLongSamples(t0);

      // fillTwo to ensure that missing values are treated as 2, and hence do
      // not get used as zero crossings.
      one->GetFloats(0, nChannels, buffers,
         s - (int)oneWindowSize / 2, oneWindowSize, false,
         FillFormat::fillTwo);

      // Looking for actual crossings.  Update dist
      for (size_t iChannel = 0; iChannel < nChannels; ++iChannel) {
         const auto oneDist = buffers[iChannel];
         double prev = 2.0;
         for (size_t i = 0; i < oneWindowSize; ++i) {
            float fDist = std::fabs(oneDist[i]); // score is absolute value
            if (prev * oneDist[i] > 0) // both same sign?  No good.
               fDist = fDist + 0.4; // No good if same sign.
            else if (prev > 0.0)
               fDist = fDist + 0.1; // medium penalty for downward crossing.
            prev = oneDist[i];
            oneDist[i] = fDist;
         }

         // TODO (3.7.9): The mixed rate zero crossing code is broken,
         // if oneWindowSize > windowSize we'll miss out some
         // samples - so they will still be zero, so we'll use them.
         for (size_t i = 0; i < windowSize; i++) {
            size_t j;
            if (windowSize != oneWindowSize)
               // (guard of the port: windowSize 1 would divide by zero)
               j = windowSize > 1
                  ? i * (oneWindowSize - 1) / (windowSize - 1) : 0;
            else
               j = i;
            dist[i] += oneDist[j];
            // Apply a small penalty for distance from the original endpoint
            // We'll always prefer an upward
            dist[i] += 0.1 * (std::abs(int(i) - int(windowSize / 2))) /
               float(std::max<size_t>(1, windowSize / 2));
         }
      }
      nTracks++;
   }

   // Find minimum
   int argmin = 0;
   float min = 3.0;
   for (size_t i = 0; i < windowSize; ++i) {
      if (dist[i] < min) {
         argmin = int(i);
         min = dist[i];
      }
   }

   // If we're worse than 0.2 on average, on one track, then no good.
   if ((nTracks == 1) && (min > (0.2 * nTracks)))
      return t0;
   // If we're worse than 0.6 on average, on multi-track, then no good.
   if ((nTracks > 1) && (min > (0.6 * nTracks)))
      return t0;

   return t0 + (argmin - (int)windowSize / 2) / rate;
}

// ---------------------------------------------------------------------------
// select.*
// ---------------------------------------------------------------------------

std::vector<Track *> TracksOfIds(AudacityProject &project, const json &args,
   const char *key)
{
   std::vector<Track *> result;
   for (auto id : ArgIntArray(args, key))
      result.push_back(&RequireTrack(project, id));
   return result;
}

json SelectSet(const json &args)
{
   auto &project = Project();
   const double t0 = ArgTime(args, "t0");
   const double t1 = ArgTime(args, "t1");
   // Validate everything before changing anything
   std::optional<std::vector<Track *>> chosen;
   if (auto it = args.find("trackIds"); it != args.end() && !it->is_null())
      chosen = TracksOfIds(project, args, "trackIds");
   Track *focus = nullptr;
   if (auto id = OptInt(args, "focus"))
      focus = &RequireTrack(project, *id);

   // ProjectSelectionManager::ModifySelection (setTimes orders the times)
   ViewInfo::Get(project).selectedRegion.setTimes(t0, t1);
   if (chosen) {
      SelectNoTracks(project);
      for (auto track : *chosen)
         track->SetSelected(true);
   }
   if (focus)
      TrackFocus::Get(project).Set(focus);
   ModifyState(project, false);
   return json::object();
}

json SelectAll(const json &)
{
   // SelectUtilities::DoSelectAll = DoSelectTimeAndTracks(true, true)
   auto &project = Project();
   auto &tracks = TrackList::Get(project);
   ViewInfo::Get(project).selectedRegion.setTimes(
      tracks.GetStartTime(), tracks.GetEndTime());
   for (auto t : tracks)
      t->SetSelected(true);
   ModifyState(project, false);
   return json::object();
}

json SelectNone(const json &)
{
   auto &project = Project();
   ViewInfo::Get(project).selectedRegion.collapseToT0();
   SelectNoTracks(project);
   ModifyState(project, false);
   return json::object();
}

json SelectTracks(const json &args)
{
   auto &project = Project();
   const auto chosen = TracksOfIds(project, args, "ids");
   const auto mode = OptString(args, "mode").value_or("set");
   if (mode != "set" && mode != "add" && mode != "remove" && mode != "toggle")
      Fail(ErrorCode::INVALID_ARGS,
         "argument 'mode' must be set, add, remove or toggle");
   if (mode == "set")
      SelectNoTracks(project);
   for (auto track : chosen) {
      if (mode == "remove")
         track->SetSelected(false);
      else if (mode == "toggle")
         track->SetSelected(!track->GetSelected());
      else
         track->SetSelected(true);
   }
   ModifyState(project, false);
   return json::object();
}

json SelectTrackHeader(const json &args)
{
   // SelectUtilities::DoListSelection
   auto &project = Project();
   auto &track = RequireTrack(project, ArgInt(args, "id"));
   const bool shift = OptBool(args, "shift").value_or(false);
   const bool ctrl = OptBool(args, "ctrl").value_or(false);
   SelectionState::Get(project).HandleListSelection(TrackList::Get(project),
      ViewInfo::Get(project), track, shift, ctrl,
      SyncLockState::Get(project).IsSyncLocked());
   if (!ctrl)
      TrackFocus::Get(project).Set(&track);
   ModifyState(project, false);
   return json::object();
}

json SelectAllTracks(const json &)
{
   // DoSelectTimeAndTracks(false, true)
   auto &project = Project();
   for (auto t : TrackList::Get(project))
      t->SetSelected(true);
   ModifyState(project, false);
   return json::object();
}

// SelTrackStartToCursor (AlwaysEnabled: silently does nothing without
// selected tracks)
json SelectStartToCursor(const json &)
{
   auto &project = Project();
   auto range = TrackList::Get(project).Selected();
   if (range.empty())
      return json::object();
   const double kWayOverToRight = std::numeric_limits<double>::max();
   const double minOffset = range.min(&Track::GetStartTime);
   if (minOffset >=
       (kWayOverToRight * (1 - std::numeric_limits<double>::epsilon())))
      return json::object();
   ViewInfo::Get(project).selectedRegion.setT0(minOffset);
   ModifyState(project, false);
   return json::object();
}

// SelCursorToTrackEnd
json SelectCursorToEnd(const json &)
{
   auto &project = Project();
   auto range = TrackList::Get(project).Selected();
   if (range.empty())
      return json::object();
   const double kWayOverToLeft = std::numeric_limits<double>::lowest();
   const double maxEndOffset = range.max(&Track::GetEndTime);
   if (maxEndOffset <=
       (kWayOverToLeft * (1 - std::numeric_limits<double>::epsilon())))
      return json::object();
   ViewInfo::Get(project).selectedRegion.setT1(maxEndOffset);
   ModifyState(project, false);
   return json::object();
}

// SelTrackStartToEnd
json SelectTrackStartToEnd(const json &)
{
   auto &project = Project();
   auto range = TrackList::Get(project).Selected();
   if (range.empty())
      return json::object();
   const double maxEndOffset = range.max(&Track::GetEndTime);
   const double minOffset = range.min(&Track::GetStartTime);
   if (maxEndOffset < minOffset)
      return json::object();
   ViewInfo::Get(project).selectedRegion.setTimes(minOffset, maxEndOffset);
   ModifyState(project, false);
   return json::object();
}

// SelStart (Shift+Home): Viewport::ScrollToStart(true)
json SelectToProjectStart(const json &)
{
   auto &project = Project();
   ViewInfo::Get(project).selectedRegion.setT0(0, false);
   ModifyState(project, false);
   return json::object();
}

// SelEnd (Shift+End): Viewport::ScrollToEnd(true)
json SelectToProjectEnd(const json &)
{
   auto &project = Project();
   const double len = TrackList::Get(project).GetEndTime();
   ViewInfo::Get(project).selectedRegion.setT1(len, false);
   ModifyState(project, false);
   return json::object();
}

// CursTrackStart (J), EditableTracksSelectedFlag
json CursorToTrackStart(const json &)
{
   auto &project = Project();
   RequireSelection(project, NeedEditableTracks, true,
      XO("Cursor to Track Start"));
   auto trackRange = TrackList::Get(project).Selected() +
      &Track::SupportsBasicEditing;
   const double kWayOverToRight = std::numeric_limits<double>::max();
   const auto minOffset =
      std::max(0.0, trackRange.min(&Track::GetStartTime));
   if (minOffset >=
       (kWayOverToRight * (1 - std::numeric_limits<double>::epsilon())))
      return json::object();
   ViewInfo::Get(project).selectedRegion.setTimes(minOffset, minOffset);
   ModifyState(project, false);
   return json::object();
}

// CursTrackEnd (K), EditableTracksSelectedFlag
json CursorToTrackEnd(const json &)
{
   auto &project = Project();
   RequireSelection(project, NeedEditableTracks, true,
      XO("Cursor to Track End"));
   auto trackRange = TrackList::Get(project).Selected() +
      &Track::SupportsBasicEditing;
   const double kWayOverToLeft = std::numeric_limits<double>::lowest();
   const auto maxEndOffset = trackRange.max(&Track::GetEndTime);
   if (maxEndOffset <
       (kWayOverToLeft * (1 - std::numeric_limits<double>::epsilon())))
      return json::object();
   ViewInfo::Get(project).selectedRegion.setTimes(maxEndOffset, maxEndOffset);
   ModifyState(project, false);
   return json::object();
}

// A tap on a clip's title bar: only that track selected (and focused), the
// time selection becomes the clip's play region
json SelectClip(const json &args)
{
   auto &project = Project();
   auto ref = ResolveClipRef(project, args);
   auto &tracks = TrackList::Get(project);
   auto &selectionState = SelectionState::Get(project);
   selectionState.SelectNone(tracks);
   selectionState.SelectTrack(*ref.track, true, true);
   TrackFocus::Get(project).Set(ref.track);
   ViewInfo::Get(project).selectedRegion.setTimes(
      ref.clip->GetPlayStartTime(), ref.clip->GetPlayEndTime());
   ModifyState(project, false);
   return json::object();
}

// SelPrevClip / SelNextClip (DoSelectClip)
json SelectClipStep(bool next)
{
   auto &project = Project();
   auto &selectedRegion = ViewInfo::Get(project).selectedRegion;
   const auto results =
      FindClips(project, selectedRegion.t0(), selectedRegion.t1(), next);
   if (!results.empty()) {
      // if there is more than one result, each has the same start and end
      selectedRegion.setTimes(results[0].startTime, results[0].endTime);
      ModifyState(project, false);
   }
   return json::object();
}

// CursPrevClipBoundary / CursNextClipBoundary (DoCursorClipBoundary)
json CursorClipBoundary(bool next)
{
   auto &project = Project();
   auto &selectedRegion = ViewInfo::Get(project).selectedRegion;
   const auto results = FindClipBoundaries(project,
      next ? selectedRegion.t1() : selectedRegion.t0(), next);
   if (!results.empty()) {
      // if there is more than one result, each has the same time
      const double time = results[0].time;
      selectedRegion.setTimes(time, time);
      ModifyState(project, false);
   }
   return json::object();
}

// ZeroCross (Z), EditableTracksSelectedFlag
json SelectZeroCrossing(const json &)
{
   auto &project = Project();
   RequireSelection(project, NeedEditableTracks, true,
      XO("Select Zero Crossing"));
   auto &selectedRegion = ViewInfo::Get(project).selectedRegion;
   const auto &tracks = TrackList::Get(project);

   // Selecting precise sample indices across tracks that may have clips with
   // various stretch ratios in itself is not possible. ... Hence we disallow
   // it if any stretched clip is involved.
   const auto projectRate = ProjectRate::Get(project).GetRate();
   const auto searchWindowDuration = GetWindowSize(projectRate) / projectRate;
   const auto wouldSearchClipWithPitchOrSpeed =
      [searchWindowDuration](const WaveTrack &track, double t) {
         const auto clips = track.GetSortedClipsIntersecting(
            t - searchWindowDuration / 2, t + searchWindowDuration / 2);
         return std::any_of(clips.begin(), clips.end(),
            [](const auto &clip) { return clip->HasPitchOrSpeed(); });
      };
   const auto selected = tracks.Selected<const WaveTrack>();
   if (std::any_of(selected.begin(), selected.end(),
          [&](const WaveTrack *track) {
             return wouldSearchClipWithPitchOrSpeed(*track, selectedRegion.t0())
                || wouldSearchClipWithPitchOrSpeed(*track, selectedRegion.t1());
          }))
      Fail(ErrorCode::FAILED, Translated(
         XO("Zero-crossing search regions intersect stretched clip(s).")));

   const double t0 = NearestZeroCrossing(project, selectedRegion.t0());
   if (selectedRegion.isPoint())
      selectedRegion.setTimes(t0, t0);
   else {
      const double t1 = NearestZeroCrossing(project, selectedRegion.t1());
      // Empty selection is generally not much use, so do not make it if empty.
      if (std::fabs(t1 - t0) * projectRate > 1.5)
         selectedRegion.setTimes(t0, t1);
   }
   ModifyState(project, false);
   return json::object();
}

json SelectFocus(const json &args)
{
   auto &project = Project();
   auto &track = RequireTrack(project, ArgInt(args, "id"));
   TrackFocus::Get(project).Set(&track);
   // Focus is not part of the undo state: snapshot only
   Session::Get().ScheduleSnapshot();
   return json::object();
}

// ---------------------------------------------------------------------------
// playRegion.* (SelectUtilities.cpp)
// ---------------------------------------------------------------------------

void ActivatePlayRegion(AudacityProject &project)
{
   auto &viewInfo = ViewInfo::Get(project);
   auto &playRegion = viewInfo.playRegion;
   playRegion.SetActive(true);
   if (playRegion.Empty()) {
      auto &selectedRegion = viewInfo.selectedRegion;
      if (!selectedRegion.isPoint())
         playRegion.SetTimes(selectedRegion.t0(), selectedRegion.t1());
      else
         // Arbitrary first four seconds
         playRegion.SetTimes(0.0, 4.0);
   }
}

void InactivatePlayRegion(AudacityProject &project)
{
   auto &viewInfo = ViewInfo::Get(project);
   auto &playRegion = viewInfo.playRegion;
   auto &selectedRegion = viewInfo.selectedRegion;
   // Set only the times that are fetched by the playback engine, but not
   // the last-active times that are used for display.
   playRegion.SetActive(false);
   playRegion.SetTimes(selectedRegion.t0(), selectedRegion.t1());
}

json PlayRegionSet(const json &args)
{
   auto &project = Project();
   double t0 = ArgTime(args, "t0");
   double t1 = ArgTime(args, "t1");
   const bool active = ArgBool(args, "active");
   if (t1 < t0)
      std::swap(t0, t1);
   if (t0 < 0)
      Fail(ErrorCode::INVALID_ARGS, "the play region must not start before 0");
   auto &playRegion = ViewInfo::Get(project).playRegion;
   // Like dragging the region in the timeline ruler, then (in)activating it
   playRegion.SetAllTimes(t0, t1);
   if (active)
      ActivatePlayRegion(project);
   else
      playRegion.SetActive(false);
   return json::object();
}

json PlayRegionClear(const json &)
{
   // SelectUtilities::ClearPlayRegion
   auto &project = Project();
   auto &playRegion = ViewInfo::Get(project).playRegion;
   playRegion.Clear();
   if (playRegion.Active())
      InactivatePlayRegion(project);
   return json::object();
}

json PlayRegionToggle(const json &)
{
   // SelectUtilities::TogglePlayRegion
   auto &project = Project();
   if (ViewInfo::Get(project).playRegion.Active())
      InactivatePlayRegion(project);
   else
      ActivatePlayRegion(project);
   return json::object();
}

} // namespace

void RegisterSelectCommands(ModuleRegistry &registry)
{
   // S, I: selection only, allowed while audio is busy
   const unsigned s = NeedsProject | SelectionOnly;
   registry.AddCommand("select.set", SelectSet, s);
   registry.AddCommand("select.all", SelectAll, s);
   registry.AddCommand("select.none", SelectNone, s);
   registry.AddCommand("select.tracks", SelectTracks, s);
   registry.AddCommand("select.trackHeader", SelectTrackHeader, s);
   registry.AddCommand("select.allTracks", SelectAllTracks, s);
   registry.AddCommand("select.startToCursor", SelectStartToCursor, s);
   registry.AddCommand("select.cursorToEnd", SelectCursorToEnd, s);
   registry.AddCommand("select.trackStartToEnd", SelectTrackStartToEnd, s);
   registry.AddCommand("select.toProjectStart", SelectToProjectStart, s);
   registry.AddCommand("select.toProjectEnd", SelectToProjectEnd, s);
   registry.AddCommand("select.cursorToTrackStart", CursorToTrackStart, s);
   registry.AddCommand("select.cursorToTrackEnd", CursorToTrackEnd, s);
   registry.AddCommand("select.clip", SelectClip, s);
   registry.AddCommand("select.prevClip",
      [](const json &) { return SelectClipStep(false); }, s);
   registry.AddCommand("select.nextClip",
      [](const json &) { return SelectClipStep(true); }, s);
   registry.AddCommand("select.prevClipBoundary",
      [](const json &) { return CursorClipBoundary(false); }, s);
   registry.AddCommand("select.nextClipBoundary",
      [](const json &) { return CursorClipBoundary(true); }, s);
   // S without I: reads sample data
   registry.AddCommand("select.zeroCrossing", SelectZeroCrossing,
      s | NeedsIdleAudio);
   registry.AddCommand("select.focus", SelectFocus, s);
   registry.AddCommand("playRegion.set", PlayRegionSet, s);
   registry.AddCommand("playRegion.clear", PlayRegionClear, s);
   registry.AddCommand("playRegion.toggle", PlayRegionToggle, s);
}

} // namespace edit
} // namespace aubridge
