/*  SPDX-License-Identifier: GPL-2.0-or-later */
/**********************************************************************

  Audacity Android port

  TrackCommands.cpp

  tracks.* (API.md §3.3 "tracks").  Handler bodies are ports of Audacity
  3.7.9:
   * src/tracks/playabletrack/wavetrack/ui/WaveTrackMenuItems.cpp
     (OnNewWaveTrack, OnNewStereoTrack), src/menus/LabelMenus.cpp
     (OnNewLabelTrack)
   * src/TrackUtilities.cpp (Paul Licameli split from TrackMenus.cpp):
     DoRemoveTracks, DoRemoveTrack, DoTrackMute, DoTrackSolo, DoMoveTrack
   * src/menus/TrackMenus.cpp: DoMixAndRender, OnResample, MuteTracks,
     DoAlign, DoSortTracks
   * src/tracks/playabletrack/wavetrack/ui/WaveTrackControls.cpp:
     OnMergeStereo, SplitStereo, OnSwapChannels, RateMenuTable::SetRate,
     FormatMenuTable::OnFormatChange
   * src/tracks/ui/CommonTrackControls.cpp: OnSetName
   * src/tracks/playabletrack/wavetrack/ui/WaveTrackSliderHandles.cpp:
     the volume/pan slider undo policy
  without view heights, scrolling and dialogs (their choices are command
  arguments).

**********************************************************************/
#include "EditUtil.h"

#include <algorithm>
#include <chrono>
#include <cmath>
#include <optional>
#include <vector>

#include "BasicUI.h"
#include "Edit.h"
#include "Internat.h"
#include "LabelTrack.h"
#include "Mix.h"
#include "MixAndRender.h"
#include "ModuleRegistry.h"
#include "PlayableTrack.h"
#include "Project.h"
#include "ProjectHistory.h"
#include "ProjectRate.h"
#include "QualitySettings.h"
#include "RealtimeEffectList.h"
#include "SampleFormat.h"
#include "Session.h"
#include "Snapshot.h"
#include "SyncLock.h"
#include "Track.h"
#include "TrackFocus.h"
#include "UndoManager.h"
#include "UserException.h"
#include "ViewInfo.h"
#include "WaveClip.h"
#include "WaveTrack.h"
#include "WaveTrackUtilities.h"

namespace aubridge {
namespace edit {

namespace {

AudacityProject &Project()
{
   return Session::Get().RequireProject();
}

// ---------------------------------------------------------------------------
// tracks.add / tracks.remove
// ---------------------------------------------------------------------------

json TracksAdd(const json &args)
{
   auto &project = Project();
   const auto kind = ArgString(args, "kind");
   auto &tracks = TrackList::Get(project);
   Track *pNew = nullptr;
   if (kind == "mono") {
      RunEdit(project, XO("Created new audio track"), XO("New Track"), [&] {
         auto &trackFactory = WaveTrackFactory::Get(project);
         auto track = trackFactory.Create(QualitySettings::SampleFormatChoice(),
            ProjectRate::Get(project).GetRate());
         track->SetName(tracks.MakeUniqueTrackName(
            WaveTrack::GetDefaultAudioTrackNamePreference()));
         tracks.Add(track);
         SelectNoTracks(project);
         track->SetSelected(true);
         pNew = track.get();
      });
   }
   else if (kind == "stereo") {
      RunEdit(project, XO("Created new stereo audio track"), XO("New Track"),
         [&] {
            auto &trackFactory = WaveTrackFactory::Get(project);
            SelectNoTracks(project);
            tracks.Add(trackFactory.Create(2,
               QualitySettings::SampleFormatChoice(),
               ProjectRate::Get(project).GetRate()));
            auto &newTrack = **tracks.rbegin();
            newTrack.SetSelected(true);
            newTrack.SetName(tracks.MakeUniqueTrackName(
               WaveTrack::GetDefaultAudioTrackNamePreference()));
            pNew = &newTrack;
         });
   }
   else if (kind == "label") {
      RunEdit(project, XO("Created new label track"), XO("New Track"), [&] {
         auto track = LabelTrack::Create(tracks);
         SelectNoTracks(project);
         track->SetSelected(true);
         pNew = track;
      });
   }
   else
      Fail(ErrorCode::INVALID_ARGS,
         "argument 'kind' must be mono, stereo or label");
   TrackFocus::Get(project).Set(pNew);
   return json{ { "id", TrackIdValue(*pNew) } };
}

json TracksRemove(const json &args)
{
   auto &project = Project();
   auto &tracks = TrackList::Get(project);
   const auto ids = ArgIntArray(args, "ids");
   if (ids.empty())
      Fail(ErrorCode::INVALID_ARGS, "argument 'ids' must not be empty");
   std::vector<Track *> toRemove;
   for (auto id : ids) {
      auto &track = RequireTrack(project, id);
      if (std::find(toRemove.begin(), toRemove.end(), &track) ==
          toRemove.end())
         toRemove.push_back(&track);
   }

   auto &trackFocus = TrackFocus::Get(project);
   if (toRemove.size() == 1) {
      // TrackUtilities::DoRemoveTrack
      auto &target = *toRemove.front();
      const wxString name = target.GetName();
      RunEdit(project, XO("Removed track '%s'.").Format(name),
         XO("Track Remove"), [&] {
            const auto iter = tracks.Find(&target);
            // If it was focused, then NEW focus is the next or, if
            // unavailable, the previous track. (The NEW focus is set
            // after the track has been removed.)
            const bool toRemoveWasFocused =
               trackFocus.PeekFocus().get() == &target;
            std::optional<decltype(iter)> newFocus{};
            if (toRemoveWasFocused) {
               auto iterNext = iter, iterPrev = iter;
               newFocus.emplace(++iterNext);
               if (!**newFocus)
                  newFocus.emplace(--iterPrev);
            }
            tracks.Remove(**iter);
            if (toRemoveWasFocused)
               trackFocus.Set(**newFocus);
         });
      return json::object();
   }

   // TrackUtilities::DoRemoveTracks, for the given tracks instead of the
   // selected ones
   RunEdit(project, XO("Removed audio track(s)"), XO("Remove Track"), [&] {
      // Find the track preceding the first removed track (in list order)
      Track *first = nullptr;
      for (auto t : tracks)
         if (std::find(toRemove.begin(), toRemove.end(), t) !=
             toRemove.end()) {
            first = t;
            break;
         }
      using Iter = decltype(tracks.Find(first));
      std::optional<Iter> focus;
      if (first) {
         auto iter = tracks.Find(first);
         // TrackIter allows decrement even of begin iterators; skip tracks
         // that are removed too
         --iter;
         while (*iter && std::find(toRemove.begin(), toRemove.end(), *iter)
                != toRemove.end())
            --iter;
         focus.emplace(iter);
      }
      for (auto t : toRemove)
         tracks.Remove(*t);
      if (!(focus.has_value() && **focus))
         // try to use the last track
         focus.emplace(tracks.end().advance(-1));
      Track *f = **focus;
      // Try to use the first track after the removal
      // TrackIter allows increment even of end iterators
      if (const auto nextF = * ++ *focus)
         f = nextF;
      if (f)
         trackFocus.Set(f);
   });
   return json::object();
}

// ---------------------------------------------------------------------------
// tracks.mixAndRender / tracks.resample
// ---------------------------------------------------------------------------

json TracksMixAndRender(const json &args)
{
   auto &project = Project();
   const bool toNewTrack = OptBool(args, "toNewTrack").value_or(false);
   RequireSelection(project, NeedWaveTracks, true,
      toNewTrack ? XO("Mix and Render to New Track") : XO("Mix and Render"));
   auto &tracks = TrackList::Get(project);
   auto &trackFactory = WaveTrackFactory::Get(project);
   const auto rate = ProjectRate::Get(project).GetRate();
   const auto defaultFormat = QualitySettings::SampleFormatChoice();

   Track *pNewTrack = nullptr;
   RunEditSelf(project, [&] {
      auto trackRange = tracks.Selected<WaveTrack>();
      auto newTrack = ::MixAndRender(trackRange.Filter<const WaveTrack>(),
         Mixer::WarpOptions{ tracks.GetOwner() },
         tracks.MakeUniqueTrackName(_("Mix")),
         &trackFactory, rate, defaultFormat, 0.0, 0.0);
      if (!newTrack)
         // Cancelled by the user
         throw UserException{};

      // Remove originals, get stats on what tracks were mixed

      // But before removing, determine the first track after the removal
      auto last = *trackRange.rbegin();
      auto insertionPoint = * ++ tracks.Find(last);

      auto selectedCount = trackRange.size();
      if (!toNewTrack) {
         // Beware iterator invalidation!
         while (!trackRange.empty())
            tracks.Remove(**trackRange.first++);
      }

      // Add new tracks
      const bool stereo = newTrack->NChannels() > 1;
      const auto firstName = newTrack->GetName();
      tracks.Add(newTrack);
      pNewTrack = *tracks.Any<WaveTrack>().rbegin();

      // Bug 2218, remember more things...
      if (selectedCount >= 1)
         pNewTrack->SetSelected(!toNewTrack);

      // Permute the tracks as needed
      // The new track appears after the old tracks (or where the old tracks
      // had been) so that they are in the same sync-lock group
      if (insertionPoint) {
         std::vector<Track *> arr;
         arr.reserve(tracks.Size());
         size_t iBegin = 0, ii = 0;
         for (const auto pTrack : tracks) {
            arr.push_back(pTrack);
            if (pTrack == insertionPoint)
               iBegin = ii;
            ++ii;
         }
         const auto end = arr.end(), mid = end - 1;
         std::rotate(arr.begin() + iBegin, mid, end);
         tracks.Permute(arr);
      }

      // Smart history/undo message
      auto &history = ProjectHistory::Get(project);
      if (selectedCount == 1) {
         auto msg = XO("Rendered all audio in track '%s'").Format(firstName);
         /* i18n-hint: Convert the audio into a more usable form, so apply
          * panning and amplification and write to some external file.*/
         history.PushState(msg, XO("Render"));
      }
      else {
         auto msg = (stereo
            ? XO("Mixed and rendered %d tracks into one new stereo track")
            : XO("Mixed and rendered %d tracks into one new mono track")
         ).Format((int)selectedCount);
         history.PushState(msg, XO("Mix and Render"));
      }
      return true;
   });
   TrackFocus::Get(project).Set(pNewTrack);
   return json{ { "id", pNewTrack ? TrackIdValue(*pNewTrack) : int64_t(-1) } };
}

json TracksResample(const json &args)
{
   auto &project = Project();
   const double rateArg = ArgDouble(args, "rate");
   RequireRange("rate", rateArg, 1, 1000000);
   const int newRate = int(std::lround(rateArg));
   RequireSelection(project, NeedWaveTracks, true, XO("Resample"));
   auto &tracks = TrackList::Get(project);
   auto &undoManager = UndoManager::Get(project);

   RunEditSelf(project, [&] {
      // One history entry for all tracks; stop consolidating afterwards
      // also when a track was cancelled
      struct StopConsolidating {
         UndoManager &manager;
         ~StopConsolidating() { manager.StopConsolidating(); }
      } stop{ undoManager };
      int ndx = 0;
      auto flags = UndoPush::NONE;
      for (auto wt : tracks.Selected<WaveTrack>()) {
         auto msg = XO("Resampling track %d").Format(++ndx);
         auto progress = BasicUI::MakeProgress(XO("Resample"), msg);
         // The resampling of a track may be stopped by the user.  This might
         // leave a track with multiple clips in a partially resampled state.
         // But the thrown exception will cause rollback (RunEditSelf).
         wt->Resample(newRate, progress.get());
         // Each time a track is successfully, completely resampled,
         // commit that to the undo stack.  The second and later times,
         // consolidate.
         ProjectHistory::Get(project).PushState(
            XO("Resampled audio track(s)"), XO("Resample Track"), flags);
         flags = flags | UndoPush::CONSOLIDATE;
      }
      return ndx > 0;
   });
   return json::object();
}

// ---------------------------------------------------------------------------
// Volume / pan / mute / solo
// ---------------------------------------------------------------------------

int64_t NowMs()
{
   using namespace std::chrono;
   return duration_cast<milliseconds>(
      steady_clock::now().time_since_epoch()).count();
}

//! Snapshots of slider drags (final:false) at most every 100 ms
struct SliderThrottle {
   int64_t lastMs = 0;
   bool pending = false;
};
SliderThrottle &Throttle()
{
   static SliderThrottle throttle;
   return throttle;
}

void ThrottledSnapshot()
{
   auto &throttle = Throttle();
   const auto now = NowMs();
   if (now - throttle.lastMs >= 100) {
      throttle.lastMs = now;
      throttle.pending = false;
      Session::Get().ScheduleSnapshot();
   }
   else
      // Trailing snapshot from the tick handler
      throttle.pending = true;
}

void ThrottleTick()
{
   auto &throttle = Throttle();
   if (!throttle.pending)
      return;
   const auto now = NowMs();
   if (now - throttle.lastMs < 100)
      return;
   throttle.pending = false;
   throttle.lastMs = now;
   if (Session::Get().Project())
      Session::Get().EmitSnapshot();
}

// The volume slider of 3.7.9 spans -36 dB ... +36 dB
constexpr double kMaxGain = 63.1;

json SetGainOrPan(const json &args, bool pan)
{
   auto &project = Project();
   auto &track = RequireWaveTrack(project, ArgInt(args, "id"));
   const char *key = pan ? "pan" : "gain";
   const double value = ArgDouble(args, key);
   if (pan)
      RequireRange(key, value, -1.0, 1.0);
   else
      RequireRange(key, value, 0.0, kMaxGain);
   const bool isFinal = OptBool(args, "final").value_or(true);
   const auto apply = [&] {
      if (pan)
         track.SetPan(float(value));
      else
         track.SetVolume(float(value));
   };

   if (!isFinal) {
      // While dragging: model change only, no history entry
      apply();
      ThrottledSnapshot();
      return json::object();
   }
   // WaveTrackSliderHandles.cpp: one consolidated history entry per drag
   RunEditSelf(project, [&] {
      apply();
      if (pan)
         ProjectHistory::Get(project).PushState(XO("Moved pan slider"),
            XO("Pan"), UndoPush::CONSOLIDATE);
      else
         ProjectHistory::Get(project).PushState(XO("Moved volume slider"),
            XO("Volume"), UndoPush::CONSOLIDATE);
      return true;
   });
   Throttle().pending = false;
   return json::object();
}

PlayableTrack &RequirePlayableTrack(AudacityProject &project, int64_t id)
{
   if (auto pt = dynamic_cast<PlayableTrack *>(&RequireTrack(project, id)))
      return *pt;
   Fail(ErrorCode::NOT_FOUND, "no playable track with id " + std::to_string(id));
}

//! TrackUtilities::DoTrackMute (exclusive = false): toggles the mute
void DoTrackMute(AudacityProject &project, PlayableTrack &pt)
{
   auto &tracks = TrackList::Get(project);
   const bool wasMute = pt.GetMute();
   pt.SetMute(!wasMute);
   if (TracksBehaviorsSolo.ReadEnum() == SoloBehaviorSimple) {
      // We also set a solo indicator if we have just one track / stereo pair
      // playing in a group of more than one playable tracks.
      // otherwise clear solo on everything.
      auto range = tracks.Any<PlayableTrack>();
      auto nPlayableTracks = range.size();
      auto nPlaying = (range - &PlayableTrack::GetMute).size();
      for (auto track : range)
         track->SetSolo((nPlaying == 1) &&
            (nPlayableTracks > 1) && !track->GetMute());
   }
}

//! TrackUtilities::DoTrackSolo (exclusive = false): toggles the solo
void DoTrackSolo(AudacityProject &project, PlayableTrack &pt)
{
   auto &tracks = TrackList::Get(project);
   const bool bWasSolo = pt.GetSolo();
   const bool simple = (TracksBehaviorsSolo.ReadEnum() == SoloBehaviorSimple);
   const bool exclusive = false;
   const bool bSoloMultiple = !simple ^ exclusive;

   // Standard and Simple solo have opposite defaults:
   //   Standard - Behaves as individual buttons, shift=radio buttons
   //   Simple   - Behaves as radio buttons, shift=individual
   // In addition, Simple solo will mute/unmute tracks
   // when in standard radio button mode.
   if (bSoloMultiple)
      pt.SetSolo(!bWasSolo);
   else {
      // Normal click solo this track only, mute everything else.
      // OR unmute and unsolo everything.
      for (auto playable : tracks.Any<PlayableTrack>()) {
         const bool chosen = (&pt == playable);
         if (chosen) {
            playable->SetSolo(!bWasSolo);
            if (simple)
               playable->SetMute(false);
         }
         else {
            playable->SetSolo(false);
            if (simple)
               playable->SetMute(!bWasSolo);
         }
      }
   }
}

json SetMute(const json &args)
{
   auto &project = Project();
   auto &pt = RequirePlayableTrack(project, ArgInt(args, "id"));
   const bool mute = ArgBool(args, "mute");
   if (pt.GetMute() != mute) {
      DoTrackMute(project, pt);
      ModifyState(project, true);
   }
   return json::object();
}

json SetSolo(const json &args)
{
   auto &project = Project();
   auto &pt = RequirePlayableTrack(project, ArgInt(args, "id"));
   const bool solo = ArgBool(args, "solo");
   if (pt.GetSolo() != solo) {
      DoTrackSolo(project, pt);
      ModifyState(project, true);
   }
   return json::object();
}

// TrackMenus.cpp MuteTracks(mute, selected = false)
json MuteAll(const json &args)
{
   auto &project = Project();
   const bool mute = ArgBool(args, "mute");
   const auto soloSimple =
      (TracksBehaviorsSolo.ReadEnum() == SoloBehaviorSimple);
   for (auto pt : TrackList::Get(project).Any<PlayableTrack>()) {
      pt->SetMute(mute);
      if (soloSimple)
         pt->SetSolo(false);
   }
   ModifyState(project, true);
   return json::object();
}

// ---------------------------------------------------------------------------
// Name, order
// ---------------------------------------------------------------------------

json Rename(const json &args)
{
   auto &project = Project();
   auto &track = RequireTrack(project, ArgInt(args, "id"));
   const wxString newName = FromUtf8(ArgString(args, "name"));
   const wxString oldName = track.GetName();
   if (newName == oldName)
      return json::object();
   RunEdit(project, XO("Renamed '%s' to '%s'").Format(oldName, newName),
      XO("Name Change"), [&] { track.SetName(newName); });
   return json::object();
}

json Move(const json &args)
{
   auto &project = Project();
   auto &target = RequireTrack(project, ArgInt(args, "id"));
   const auto to = ArgString(args, "to");
   auto &tracks = TrackList::Get(project);
   const wxString name = target.GetName();

   // TrackUtilities::DoMoveTrack; nothing is pushed when the track cannot
   // move
   if (to == "top") {
      RunEdit(project, XO("Moved '%s' to Top").Format(name),
         XO("Move Track to Top"), [&] {
            if (!tracks.CanMoveUp(target))
               return false;
            while (tracks.CanMoveUp(target))
               tracks.Move(target, true);
            return true;
         });
   }
   else if (to == "bottom") {
      RunEdit(project, XO("Moved '%s' to Bottom").Format(name),
         XO("Move Track to Bottom"), [&] {
            if (!tracks.CanMoveDown(target))
               return false;
            while (tracks.CanMoveDown(target))
               tracks.Move(target, false);
            return true;
         });
   }
   else if (to == "up" || to == "down") {
      const bool bUp = (to == "up");
      RunEdit(project,
         (bUp ? XO("Moved '%s' Up") : XO("Moved '%s' Down")).Format(name),
         bUp ? XO("Move Track Up") : XO("Move Track Down"),
         [&] { return tracks.Move(target, bUp); });
   }
   else
      Fail(ErrorCode::INVALID_ARGS,
         "argument 'to' must be up, down, top or bottom");
   return json::object();
}

// TrackMenus.cpp DoSortTracks
json Sort(const json &args)
{
   auto &project = Project();
   const auto by = ArgString(args, "by");
   if (by != "time" && by != "name")
      Fail(ErrorCode::INVALID_ARGS, "argument 'by' must be time or name");
   const bool byName = by == "name";
   auto &tracks = TrackList::Get(project);

   auto GetTime = [](const Track &t) {
      if (auto w = dynamic_cast<const WaveTrack *>(&t)) {
         auto stime = w->GetEndTime();
         for (const auto &c : w->Intervals()) {
            if (c->GetVisibleSampleCount() == 0)
               continue;
            stime = std::min(stime, c->GetPlayStartTime());
         }
         return stime;
      }
      if (auto l = dynamic_cast<const LabelTrack *>(&t))
         return l->GetStartTime();
      return 0.0;
   };

   RunEdit(project,
      byName ? XO("Tracks sorted by name") : XO("Tracks sorted by time"),
      byName ? XO("Sort by Name") : XO("Sort by Time"), [&] {
         std::vector<Track *> arr;
         arr.reserve(tracks.Size());
         // First find the permutation.
         for (const auto pTrack : tracks) {
            auto &track = *pTrack;
            const auto size = arr.size();
            size_t ndx = 0;
            for (; ndx < size; ++ndx) {
               Track &arrTrack = *arr[ndx];
               if (byName) {
                  // do case insensitive sort - cmpNoCase returns less than
                  // zero if the string is 'less than' its argument; with
                  // case insensitive equality, sort 'b' before 'B'
                  auto cmpValue = track.GetName().CmpNoCase(arrTrack.GetName());
                  if (cmpValue < 0 ||
                      (0 == cmpValue &&
                       track.GetName().CompareTo(arrTrack.GetName()) > 0))
                     break;
               }
               // sort by time otherwise
               else if (GetTime(track) < GetTime(arrTrack))
                  break;
            }
            arr.insert(arr.begin() + ndx, &track);
         }
         // Now apply the permutation
         tracks.Permute(arr);
      });
   return json::object();
}

// ---------------------------------------------------------------------------
// Channels
// ---------------------------------------------------------------------------

json MakeStereo(const json &args)
{
   auto &project = Project();
   auto &tracks = TrackList::Get(project);
   auto &left = RequireWaveTrack(project, ArgInt(args, "id"));
   auto right = dynamic_cast<WaveTrack *>(* ++ tracks.Find(&left));
   if (left.NChannels() != 1 || !right || right->NChannels() != 1)
      Fail(ErrorCode::INVALID_ARGS,
         "Make Stereo needs a mono audio track directly below a mono audio "
         "track");

   const auto checkAligned = [](const WaveTrack &left, const WaveTrack &right)
   {
      auto eqTrims = [](double a, double b) {
         return std::abs(a - b) <=
            std::numeric_limits<double>::epsilon() * std::max(a, b);
      };
      const auto eps = 0.5 / left.GetRate();
      const auto &rightIntervals = right.Intervals();
      for (const auto &a : left.Intervals()) {
         auto it = std::find_if(rightIntervals.begin(), rightIntervals.end(),
            [&](const auto &b) {
               // Start() and End() are always snapped to a sample grid
               return std::abs(a->Start() - b->Start()) < eps &&
                  std::abs(a->End() - b->End()) < eps &&
                  eqTrims(a->GetTrimLeft(), b->GetTrimLeft()) &&
                  eqTrims(a->GetTrimRight(), b->GetTrimRight()) &&
                  a->HasEqualPitchAndSpeed(*b);
            });
         if (it == rightIntervals.end())
            return false;
      }
      return true;
   };

   if (RealtimeEffectList::Get(left).GetStatesCount() != 0 ||
       RealtimeEffectList::Get(*right).GetStatesCount() != 0 ||
       !checkAligned(left, *right)) {
      const auto answer = BasicUI::ShowMessageBox(XO(
"The tracks you are attempting to merge to stereo contain clips at\n"
"different positions, or otherwise mismatching clips. Merging them\n"
"will render the tracks.\n\n"
"This causes any realtime effects to be applied to the waveform and\n"
"hidden data to be removed. Additionally, the entire track will\n"
"become one large clip.\n\n"
"Do you wish to continue?"),
         BasicUI::MessageBoxOptions{}
            .ButtonStyle(BasicUI::Button::YesNo)
            .Caption(XO("Combine mono to stereo")));
      if (answer != BasicUI::MessageBoxResult::Yes)
         Fail(ErrorCode::CANCELLED, "Cancelled");
   }

   // MixAndRender names the result after the first track
   const wxString name = left.GetName();
   int64_t id = -1;
   RunEdit(project, XO("Made '%s' a stereo track").Format(name),
      XO("Make Stereo"), [&] {
         left.SetPan(-1.0f);
         right->SetPan(1.0f);
         auto mix = MixAndRender(
            TrackIterRange{
               tracks.Any<const WaveTrack>().find(&left),
               ++tracks.Any<const WaveTrack>().find(right)
            },
            Mixer::WarpOptions{ tracks.GetOwner() },
            name,
            &WaveTrackFactory::Get(project),
            //use highest sample rate
            std::max(left.GetRate(), right->GetRate()),
            //use widest sample format
            std::max(left.GetSampleFormat(), right->GetSampleFormat()),
            0.0, 0.0);
         if (!mix)
            // Cancelled: RunEdit rolls the pan changes back
            throw UserException{};
         // The mix keeps the left track's id (EmptyCopy), Insert does not
         // assign a new one
         tracks.Insert(&left, mix);
         tracks.Remove(left);
         tracks.Remove(*right);
         id = TrackIdValue(*mix);
      });
   if (auto pTrack = TrackById(project, id))
      TrackFocus::Get(project).Set(pTrack);
   return json{ { "id", id } };
}

json SplitStereo(const json &args, bool stereo)
{
   auto &project = Project();
   auto &track = RequireWaveTrack(project, ArgInt(args, "id"));
   if (track.NChannels() != 2)
      Fail(ErrorCode::INVALID_ARGS, "the track is not a stereo track");
   const wxString name = track.GetName();
   json ids = json::array();
   RunEdit(project,
      stereo ? XO("Split stereo track '%s'").Format(name)
             : XO("Split Stereo to Mono '%s'").Format(name),
      stereo ? XO("Split") : XO("Split to Mono"), [&] {
         const std::vector<WaveTrack::Holder> unlinkedTracks =
            track.SplitChannels();
         if (stereo) {
            unlinkedTracks[0]->SetPan(-1.0f);
            unlinkedTracks[1]->SetPan(1.0f);
         }
         for (const auto &pTrack : unlinkedTracks)
            ids.push_back(TrackIdValue(*pTrack));
      });
   return json{ { "ids", std::move(ids) } };
}

json SwapChannels(const json &args)
{
   auto &project = Project();
   auto &track = RequireWaveTrack(project, ArgInt(args, "id"));
   if (track.NChannels() != 2)
      Fail(ErrorCode::INVALID_ARGS, "the track is not a stereo track");
   RunEdit(project,
      XO("Swapped Channels in '%s'").Format(track.GetName()),
      XO("Swap Channels"), [&] { track.SwapChannels(); });
   return json::object();
}

// ---------------------------------------------------------------------------
// Rate, format
// ---------------------------------------------------------------------------

// RateMenuTable::SetRate (no resampling)
json SetRate(const json &args)
{
   auto &project = Project();
   auto &track = RequireWaveTrack(project, ArgInt(args, "id"));
   const double rate = ArgDouble(args, "rate");
   RequireRange("rate", rate, 1, 1000000);
   if (rate == track.GetRate())
      return json::object();
   RunEdit(project,
      XO("Changed '%s' to %s Hz").Format(track.GetName(), FormatRate(rate)),
      XO("Rate Change"), [&] {
         auto end1 = track.GetEndTime();
         track.SetRate(rate);
         if (SyncLockState::Get(project).IsSyncLocked()) {
            auto end2 = track.GetEndTime();
            for (auto pLocked : SyncLock::Group(track))
               if (pLocked != &track)
                  pLocked->SyncLockAdjust(end1, end2);
         }
      });
   return json::object();
}

// FormatMenuTable::OnFormatChange
json SetFormat(const json &args)
{
   auto &project = Project();
   auto &track = RequireWaveTrack(project, ArgInt(args, "id"));
   sampleFormat newFormat;
   if (!ParseFormat(ArgString(args, "format"), newFormat))
      Fail(ErrorCode::INVALID_ARGS,
         "argument 'format' must be int16, int24 or float");
   if (newFormat == track.GetSampleFormat())
      return json::object(); // Nothing to do.

   RunEdit(project,
      /* i18n-hint: The strings name a track and a format */
      XO("Changed '%s' to %s")
         .Format(track.GetName(), GetSampleFormatStr(newFormat)),
      XO("Format Change"), [&] {
         auto progress = BasicUI::MakeProgress(XO("Changing sample format"),
            XO("Processing...   0%%"), BasicUI::ProgressShowCancel);
         // Hidden samples are processed too, they should be counted as well
         const sampleCount totalSamples =
            WaveTrackUtilities::GetSequenceSamplesCount(track);
         sampleCount processedSamples{ 0 };
         auto progressUpdate = [&](size_t newlyProcessedCount) {
            processedSamples += newlyProcessedCount;
            const double d_processed = processedSamples.as_double();
            const double d_total =
               std::max(1.0, totalSamples.as_double());
            const int percentage = static_cast<int>(
               std::clamp(d_processed / d_total, 0.0, 1.0) * 100);
            const auto progressStatus = progress->Poll(
               (unsigned long long)d_processed, (unsigned long long)d_total,
               XO("Processing...   %i%%").Format(percentage));
            if (progressStatus != BasicUI::ProgressResult::Success)
               throw UserException{};
         };
         track.ConvertToSampleFormat(newFormat, progressUpdate);
      });
   return json::object();
}

// ---------------------------------------------------------------------------
// Align (TrackMenus.cpp DoAlign)
// ---------------------------------------------------------------------------

enum {
   kAlignStartZero = 0,
   kAlignStartSelStart,
   kAlignStartSelEnd,
   kAlignEndSelStart,
   kAlignEndSelEnd,
   // The next two are only in one subMenu, so more easily handled at the end.
   kAlignEndToEnd,
   kAlignTogether
};

json Align(const json &args)
{
   auto &project = Project();
   const auto mode = ArgString(args, "mode");
   int index;
   if (mode == "startToZero")
      index = kAlignStartZero;
   else if (mode == "startToCursor")
      index = kAlignStartSelStart;
   else if (mode == "startToSelEnd")
      index = kAlignStartSelEnd;
   else if (mode == "endToCursor")
      index = kAlignEndSelStart;
   else if (mode == "endToSelEnd")
      index = kAlignEndSelEnd;
   else if (mode == "endToEnd")
      index = kAlignEndToEnd;
   else if (mode == "together")
      index = kAlignTogether;
   else
      Fail(ErrorCode::INVALID_ARGS, "unknown align mode '" + mode + "'");
   // OnAlignNoSync (end to end, together) never moves the selection;
   // OnAlign reads /GUI/MoveSelectionWithTracks
   const bool moveSel = index >= kAlignEndToEnd ? false
      : OptBool(args, "moveSelection").value_or(
         ReadPrefBool(wxT("/GUI/MoveSelectionWithTracks"), false));

   // AudioIONotBusy | EditableTracksSelected
   RequireSelection(project, NeedEditableTracks, true, XO("Align Tracks"));
   auto &tracks = TrackList::Get(project);
   auto &selectedRegion = ViewInfo::Get(project).selectedRegion;
   auto trackRange = tracks.Selected<AudioTrack>();
   if (trackRange.empty())
      // (3.7.9 would compute with an empty range: offsets of +-DBL_MAX)
      Fail(ErrorCode::NO_SELECTION, Translated(XO(
"You must first select some audio to perform this action.\n(Selecting other kinds of track won't work.)")));

   TranslatableString action, shortAction;
   double delta = 0.0;
   double newPos = -1.0;

   auto FindOffset =
      [](const Track *pTrack) { return pTrack->GetStartTime(); };
   auto firstTrackOffset = [&]{ return FindOffset(*trackRange.begin()); };
   auto minOffset = [&]{ return trackRange.min(FindOffset); };
   auto avgOffset = [&]{
      return trackRange.sum(FindOffset) /
         std::max(size_t(1), trackRange.size()); };
   auto maxEndOffset = [&]{
      return std::max(0.0, trackRange.max(&Track::GetEndTime)); };

   switch (index) {
   case kAlignStartZero:
      delta = -minOffset();
      action = moveSel
         ? XO("Aligned/Moved start to zero")
         : XO("Aligned start to zero");
      shortAction = moveSel
         ? XO("Align/Move Start")
         : XO("Align Start");
      break;
   case kAlignStartSelStart:
      delta = selectedRegion.t0() - minOffset();
      action = moveSel
         ? XO("Aligned/Moved start to cursor/selection start")
         : XO("Aligned start to cursor/selection start");
      shortAction = moveSel
         ? XO("Align/Move Start")
         : XO("Align Start");
      break;
   case kAlignStartSelEnd:
      delta = selectedRegion.t1() - minOffset();
      action = moveSel
         ? XO("Aligned/Moved start to selection end")
         : XO("Aligned start to selection end");
      shortAction = moveSel
         ? XO("Align/Move Start")
         : XO("Align Start");
      break;
   case kAlignEndSelStart:
      delta = selectedRegion.t0() - maxEndOffset();
      action = moveSel
         ? XO("Aligned/Moved end to cursor/selection start")
         : XO("Aligned end to cursor/selection start");
      shortAction = moveSel
         ? XO("Align/Move End")
         : XO("Align End");
      break;
   case kAlignEndSelEnd:
      delta = selectedRegion.t1() - maxEndOffset();
      action = moveSel
         ? XO("Aligned/Moved end to selection end")
         : XO("Aligned end to selection end");
      shortAction = moveSel
         ? XO("Align/Move End")
         : XO("Align End");
      break;
   case kAlignEndToEnd:
      newPos = firstTrackOffset();
      action = moveSel
         ? XO("Aligned/Moved end to end")
         : XO("Aligned end to end");
      shortAction = moveSel
         ? XO("Align/Move End to End")
         : XO("Align End to End");
      break;
   case kAlignTogether:
      newPos = avgOffset();
      action = moveSel
         ? XO("Aligned/Moved together")
         : XO("Aligned together");
      shortAction = moveSel
         ? XO("Align/Move Together")
         : XO("Align Together");
   }

   RunEdit(project, action, shortAction, [&] {
      if (index >= kAlignEndToEnd) {
         // This shifts different tracks in different ways, so no sync-lock
         // move.  Only align Wave and Note tracks end to end.
         for (auto t : tracks.Selected<AudioTrack>()) {
            t->MoveTo(newPos);
            if (index == kAlignEndToEnd)
               newPos += (t->GetEndTime() - t->GetStartTime());
         }
      }
      if (delta != 0.0) {
         // For a fixed-distance shift move sync-lock selected tracks also.
         for (auto t : tracks.Any() + &SyncLock::IsSelectedOrSyncLockSelectedP)
            t->MoveTo(t->GetStartTime() + delta);
      }
      if (moveSel)
         selectedRegion.move(delta);
   });
   return json::object();
}

} // namespace

void RegisterTrackCommands(ModuleRegistry &registry)
{
   Throttle() = {};
   registry.AddTickHandler(ThrottleTick);

   const unsigned m = NeedsProject | NeedsIdleAudio | Mutates;
   registry.AddCommand("tracks.add", TracksAdd, m);
   registry.AddCommand("tracks.remove", TracksRemove, m);
   registry.AddCommand("tracks.mixAndRender", TracksMixAndRender,
      m | LongRunning);
   registry.AddCommand("tracks.resample", TracksResample, m | LongRunning);
   // I; final:false is no M (no generation bump), final:true pushes and
   // touches itself (RunEditSelf)
   registry.AddCommand("tracks.setGain",
      [](const json &args) { return SetGainOrPan(args, false); }, NeedsProject);
   registry.AddCommand("tracks.setPan",
      [](const json &args) { return SetGainOrPan(args, true); }, NeedsProject);
   // U, I: ModifyState(true) + generation bump + snapshot
   registry.AddCommand("tracks.setMute", SetMute, NeedsProject | Mutates);
   registry.AddCommand("tracks.setSolo", SetSolo, NeedsProject | Mutates);
   registry.AddCommand("tracks.muteAll", MuteAll, NeedsProject | Mutates);
   registry.AddCommand("tracks.rename", Rename, m);
   registry.AddCommand("tracks.move", Move, m);
   registry.AddCommand("tracks.makeStereo", MakeStereo, m);
   registry.AddCommand("tracks.splitStereo",
      [](const json &args) { return SplitStereo(args, true); }, m);
   registry.AddCommand("tracks.splitStereoToMono",
      [](const json &args) { return SplitStereo(args, false); }, m);
   registry.AddCommand("tracks.swapChannels", SwapChannels, m);
   registry.AddCommand("tracks.setRate", SetRate, m);
   registry.AddCommand("tracks.setFormat", SetFormat, m | LongRunning);
   registry.AddCommand("tracks.align", Align, m);
   registry.AddCommand("tracks.sort", Sort, m);
}

} // namespace edit
} // namespace aubridge
