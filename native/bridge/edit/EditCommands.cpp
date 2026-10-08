/*  SPDX-License-Identifier: GPL-2.0-or-later */
/**********************************************************************

  Audacity Android port

  EditCommands.cpp

  edit.* (API.md §3.3 "edit"): clipboard, region and clip edits on the
  current selection.  Handler bodies are ports of Audacity 3.7.9
  src/menus/EditMenus.cpp (OnCut ... OnDisjoin, DoPasteNothingSelected,
  FitsInto, FindCorrespondence) without the GUI-only parts: label/clip text
  editing, the system clipboard, AudioPasteDialog, scrolling.

  Every mutating command runs inside RunEdit (rollback on any exception)
  with the 3.7.9 undo descriptions; edit.copy only changes the clipboard.

**********************************************************************/
#include "EditUtil.h"

#include <algorithm>
#include <vector>

#include "BasicUI.h"
#include "Clipboard.h"
#include "Edit.h"
#include "LabelTrack.h"
#include "ModuleRegistry.h"
#include "Prefs.h"
#include "Project.h"
#include "ProjectRate.h"
#include "ProjectTimeSignature.h"
#include "Session.h"
#include "SyncLock.h"
#include "TempoChange.h"
#include "TimeStretching.h"
#include "TimeWarper.h"
#include "Track.h"
#include "TrackFocus.h"
#include "ViewInfo.h"
#include "WaveClip.h"
#include "WaveTrack.h"

namespace aubridge {
namespace edit {

namespace {

AudacityProject &Project()
{
   return Session::Get().RequireProject();
}

//! Copies of the selected editable tracks, for the clipboard
std::shared_ptr<TrackList> CopySelected(
   TrackList &tracks, double t0, double t1)
{
   auto pNewClipboard = TrackList::Create(nullptr);
   for (auto n : tracks.Selected())
      if (n->SupportsBasicEditing())
         pNewClipboard->Add(n->Copy(t0, t1));
   return pNewClipboard;
}

json Cut(const json &)
{
   auto &project = Project();
   // AudioIONotBusy | CutCopyAvailable | NoAutoSelect
   RequireSelection(project, NeedTime | NeedEditableTracks, false, XO("Cut"));
   auto &tracks = TrackList::Get(project);
   auto &selectedRegion = ViewInfo::Get(project).selectedRegion;
   const double t0 = selectedRegion.t0(), t1 = selectedRegion.t1();

   RunEdit(project, XO("Cut to the clipboard"), XO("Cut"), [&] {
      auto &clipboard = Clipboard::Get();
      clipboard.Clear();
      auto pNewClipboard = CopySelected(tracks, t0, t1);
      // Survived possibility of exceptions.  Commit changes to the
      // clipboard now.
      clipboard.Assign(std::move(*pNewClipboard), t0, t1,
         project.shared_from_this());

      // Proceed to change the project.  If this throws, the project is
      // rolled back by RunEdit.
      const bool cutLines =
         gPrefs && gPrefs->Read(wxT("/GUI/EnableCutLines"), (long)0) != 0;
      for (auto n : tracks.Any() + &SyncLock::IsSelectedOrSyncLockSelectedP) {
         if (auto wt = dynamic_cast<WaveTrack *>(n); wt && cutLines)
            wt->ClearAndAddCutLine(t0, t1);
         else if (n->SupportsBasicEditing())
            n->Clear(t0, t1);
      }
      selectedRegion.collapseToT0();
   });
   return json::object();
}

json Delete(const json &)
{
   auto &project = Project();
   // AudioIONotBusy | EditableTracksSelected | TimeSelected | NoAutoSelect
   RequireSelection(project, NeedTime | NeedEditableTracks, false,
      XO("Delete"));
   auto &tracks = TrackList::Get(project);
   auto &selectedRegion = ViewInfo::Get(project).selectedRegion;
   const double t0 = selectedRegion.t0(), t1 = selectedRegion.t1();
   const double seconds = selectedRegion.duration();

   RunEdit(project,
      XO("Deleted %.2f seconds at t=%.2f").Format(seconds, t0), XO("Delete"),
      [&] {
         for (auto n : tracks) {
            if (!n->SupportsBasicEditing())
               continue;
            if (SyncLock::IsSelectedOrSyncLockSelected(*n))
               n->Clear(t0, t1);
         }
         selectedRegion.collapseToT0();
      });
   return json::object();
}

json Copy(const json &)
{
   auto &project = Project();
   // AudioIONotBusy | CutCopyAvailable (auto-select allowed)
   RequireSelection(project, NeedTime | NeedEditableTracks, true, XO("Copy"));
   auto &tracks = TrackList::Get(project);
   const auto &selectedRegion = ViewInfo::Get(project).selectedRegion;
   const double t0 = selectedRegion.t0(), t1 = selectedRegion.t1();

   auto &clipboard = Clipboard::Get();
   clipboard.Clear();
   auto pNewClipboard = CopySelected(tracks, t0, t1);
   // Survived possibility of exceptions.  Commit changes to the clipboard now.
   clipboard.Assign(std::move(*pNewClipboard), t0, t1,
      project.shared_from_this());
   // The model did not change: snapshot only (the clipboard is in it)
   Session::Get().ScheduleSnapshot();
   return json::object();
}

// ---------------------------------------------------------------------------
// Paste
// ---------------------------------------------------------------------------

//! Whether the source track may be pasted into the destination track
bool FitsInto(const Track &src, const Track &dst)
{
   if (!src.SameKindAs(dst))
      return false;
   // Mono can "fit" into stereo, by duplication of the channel
   // Otherwise non-wave tracks always have just one "channel"
   // Future:  Fit stereo into mono too, using mix-down
   return src.NChannels() <= dst.NChannels();
}

// First, destination track; second, source
using Correspondence = std::vector<std::pair<Track *, const Track *>>;

Correspondence FindCorrespondence(
   TrackList &dstTracks, const TrackList &srcTracks)
{
   Correspondence result;
   auto dstRange = dstTracks.Selected();
   if (dstRange.size() == 1)
      // Special rule when only one track is selected interprets the user's
      // intent as pasting into that track and following ones
      dstRange = dstTracks.Any().StartingWith(*dstRange.begin());
   auto srcRange = srcTracks.Any();
   while (!(dstRange.empty() || srcRange.empty())) {
      auto &dst = **dstRange.begin();
      auto &src = **srcRange.begin();
      if (!FitsInto(src, dst)) {
         // Skip selected track of inappropriate type and try again
         ++dstRange.first;
         continue;
      }
      result.emplace_back(&dst, &src);
      ++srcRange.first;
      ++dstRange.first;
   }

   if (!srcRange.empty())
      // Could not fit all source tracks into the selected tracks
      return {};
   else
      return result;
}

// Create and paste into NEW tracks (no track selected)
void DoPasteNothingSelected(AudacityProject &project, const TrackList &src,
   double t0, double t1)
{
   auto &tracks = TrackList::Get(project);
   auto &selectedRegion = ViewInfo::Get(project).selectedRegion;

   Track *pFirstNewTrack = nullptr;
   for (auto pClip : src) {
      auto pNewTrack = pClip->PasteInto(project, tracks);
      if (!pFirstNewTrack)
         pFirstNewTrack = pNewTrack.get();
      pNewTrack->SetSelected(true);
   }

   // Select some pasted samples, which is probably impossible to get right
   // with various project and track sample rates.
   // So do it at the sample rate of the project
   const double projRate = ProjectRate::Get(project).GetRate();
   const double projTempo = ProjectTimeSignature::Get(project).GetTempo();
   const double srcTempo = pFirstNewTrack
      ? GetProjectTempo(*pFirstNewTrack).value_or(projTempo)
      : projTempo;
   // Apply adequate stretching to the selection. A selection of 10 seconds of
   // audio in project A should become 5 seconds in project B if tempo in B is
   // twice as fast.
   const double quantT0 = QUANTIZED_TIME(t0 * srcTempo / projTempo, projRate);
   const double quantT1 = QUANTIZED_TIME(t1 * srcTempo / projTempo, projRate);
   selectedRegion.setTimes(
      0.0,   // anywhere else and this should be
             // half a sample earlier
      quantT1 - quantT0);

   if (pFirstNewTrack)
      TrackFocus::Get(project).Set(pFirstNewTrack);
}

json Paste(const json &)
{
   auto &project = Project();
   const auto &clipboard = Clipboard::Get();
   if (clipboard.GetTracks().empty())
      Fail(ErrorCode::FAILED, "The clipboard is empty");

   // The clipboard always comes from this project (it is cleared when its
   // project closes), so the cross-project paste policy of FindSourceTracks
   // (AudioPasteDialog, DuplicateDiscardTrimmed) does not apply
   const auto srcTracks = clipboard.GetTracks().shared_from_this();

   auto &tracks = TrackList::Get(project);
   // If nothing's selected, we just insert new tracks.
   if (tracks.Selected().empty()) {
      RunEdit(project, XO("Pasted from the clipboard"), XO("Paste"), [&] {
         DoPasteNothingSelected(
            project, *srcTracks, clipboard.T0(), clipboard.T1());
      });
      return json::object();
   }

   // Otherwise, paste into the selected tracks.
   auto &selectedRegion = ViewInfo::Get(project).selectedRegion;
   const double t0 = selectedRegion.t0(), t1 = selectedRegion.t1();
   const auto newT1 = t0 + clipboard.Duration();
   const auto isSyncLocked = SyncLockState::Get(project).IsSyncLocked();

   // Find tracks to paste in
   auto correspondence = FindCorrespondence(tracks, *srcTracks);
   if (correspondence.empty()) {
      if (tracks.Selected().size() == 1)
         Fail(ErrorCode::FAILED, Translated(XO(
"The content you are trying to paste will span across more tracks than you "
"currently have available. Add more tracks and try again.")));
      else
         Fail(ErrorCode::FAILED, Translated(XO(
"There are not enough tracks selected to accommodate your copied content. "
"Select additional tracks and try again.")));
   }

   Track *ff = nullptr;
   RunEdit(project, XO("Pasted from the clipboard"), XO("Paste"), [&] {
      bool bPastedSomething = false;
      auto iPair = correspondence.begin();
      const auto endPair = correspondence.cend();

      // Outer loop by sync-lock groups
      auto next = tracks.begin();
      for (auto range = tracks.Any(); !range.empty();
         // Skip to next sync lock group
         range.first = next
      ) {
         if (iPair == endPair)
            // Nothing more to paste
            break;
         auto group = SyncLock::Group(**range.first);
         next = tracks.Find(*group.rbegin());
         ++next;

         if (!group.contains(iPair->first))
            // Nothing to paste into this group
            continue;

         // Inner loop over the sync-lock group by tracks
         for (auto member : group) {
            if (iPair == endPair || member != iPair->first) {
               if (isSyncLocked) {
                  // Track is not pasted into but must be adjusted
                  if (t1 != newT1 && t1 <= member->GetEndTime()) {
                     member->SyncLockAdjust(t1, newT1);
                     bPastedSomething = true;
                  }
               }
            }
            else {
               // Remember first pasted-into track, to focus it
               if (!ff)
                  ff = member;
               // Do the pasting!
               const auto src = (iPair++)->second;
               if (auto wn = dynamic_cast<WaveTrack *>(member)) {
                  bPastedSomething = true;
                  const auto srcWaveTrack = static_cast<const WaveTrack *>(src);
                  // If the destination track is still empty, preserve the
                  // source sample rate and format rather than forcing
                  // project defaults.
                  if (wn->GetNumClips() == 0) {
                     wn->SetRate(srcWaveTrack->GetRate());
                     wn->ConvertToSampleFormat(srcWaveTrack->GetSampleFormat());
                  }
                  // For correct remapping of preserved split lines:
                  PasteTimeWarper warper{ t1, t0 + src->GetEndTime() };
                  const auto newClipOnPaste =
                     ReadPrefBool(wxT("/GUI/PasteAsNewClips"), false);
                  // The new-clip-on-paste behavior means: not merging the
                  // clipboard data with data at the selection borders; not
                  // reproducing boundaries within the selected region in the
                  // new clip; preserving the data underneath by trimming
                  // (rather than deleting).
                  // The legacy behavior is the opposite.
                  const auto merge = !newClipOnPaste;
                  const auto preserveExistingBoundaries = !newClipOnPaste;
                  const auto clearByTrimming = newClipOnPaste;
                  if (src->NChannels() == 1 && wn->NChannels() == 2) {
                     // When the source is mono, may paste its only channel
                     // repeatedly into a stereo track
                     const auto pastedTrack = std::static_pointer_cast<WaveTrack>(
                        srcWaveTrack->Duplicate());
                     pastedTrack->MonoToStereo();
                     wn->ClearAndPaste(t0, t1, *pastedTrack,
                        preserveExistingBoundaries, merge, &warper,
                        clearByTrimming);
                  }
                  else
                     wn->ClearAndPaste(t0, t1, *srcWaveTrack,
                        preserveExistingBoundaries, merge, &warper,
                        clearByTrimming);
               }
               else if (auto ln = dynamic_cast<LabelTrack *>(member)) {
                  // Per Bug 293, users expect labels to move on a paste into
                  // a label track.
                  ln->Clear(t0, t1);
                  ln->ShiftLabelsOnInsert(clipboard.Duration(), t0);
                  bPastedSomething |= ln->PasteOver(t0, *src);
               }
               else {
                  bPastedSomething = true;
                  member->Clear(t0, t1);
                  member->Paste(t0, *src);
               }
            }
         }
      }

      if (!bPastedSomething)
         return false;

      if (!isSyncLocked && GetEditClipsCanMove()) {
         // Special case when pasting without sync lock and "...move other
         // clips" option is on: also shift all intervals in all other
         // selected tracks that start after t0
         const auto offset = srcTracks->GetEndTime() - (t1 - t0);
         for (auto track : tracks.Selected<Track>()) {
            const auto it = std::find_if(
               correspondence.begin(), correspondence.end(),
               [=](auto &p) { return p.first == track; });
            if (it != correspondence.end())
               continue;
            track->ShiftBy(t0, offset);
         }
      }

      selectedRegion.setTimes(t0, t0 + clipboard.Duration());
      return true;
   });
   if (ff)
      TrackFocus::Get(project).Set(ff);
   return json::object();
}

// ---------------------------------------------------------------------------
// Remove Special, Duplicate, Audio Clips
// ---------------------------------------------------------------------------

json Duplicate(const json &)
{
   auto &project = Project();
   RequireSelection(project, NeedTime | NeedEditableTracks, true,
      XO("Duplicate"));
   auto &tracks = TrackList::Get(project);
   const auto &selectedRegion = ViewInfo::Get(project).selectedRegion;
   const double t0 = selectedRegion.t0(), t1 = selectedRegion.t1();

   RunEdit(project, XO("Duplicated"), XO("Duplicate"), [&] {
      // We add to the list inside the loop: iterate over a copy
      const auto range = tracks.Selected();
      const std::vector<Track *> sources(range.begin(), range.end());
      for (auto n : sources) {
         if (!n->SupportsBasicEditing())
            continue;
         // Make copies not for clipboard but for direct addition to the
         // project
         auto dest = n->Copy(t0, t1, false);
         dest->MoveTo(std::max(t0, n->GetStartTime()));
         tracks.Add(dest);
      }
   });
   return json::object();
}

json SplitCut(const json &)
{
   auto &project = Project();
   RequireSelection(project, NeedTime | NeedEditableTracks, true,
      XO("Cut and leave gap"));
   auto &tracks = TrackList::Get(project);
   const auto &selectedRegion = ViewInfo::Get(project).selectedRegion;
   const double t0 = selectedRegion.t0(), t1 = selectedRegion.t1();

   RunEdit(project, XO("Cut to clipboard and leave gap"),
      XO("Cut and leave gap"), [&] {
         auto &clipboard = Clipboard::Get();
         clipboard.Clear();
         auto pNewClipboard = TrackList::Create(nullptr);
         for (auto n : tracks.Selected()) {
            if (auto wt = dynamic_cast<WaveTrack *>(n))
               pNewClipboard->Add(wt->SplitCut(t0, t1));
            else if (n->SupportsBasicEditing()) {
               auto dest = n->Copy(t0, t1);
               n->Silence(t0, t1);
               pNewClipboard->Add(dest);
            }
         }
         // Survived possibility of exceptions.  Commit changes to the
         // clipboard now.
         clipboard.Assign(std::move(*pNewClipboard), t0, t1,
            project.shared_from_this());
      });
   return json::object();
}

json SplitDelete(const json &)
{
   auto &project = Project();
   RequireSelection(project, NeedTime | NeedEditableTracks, true,
      XO("Delete and leave gap"));
   auto &tracks = TrackList::Get(project);
   const auto &selectedRegion = ViewInfo::Get(project).selectedRegion;
   const double t0 = selectedRegion.t0(), t1 = selectedRegion.t1();

   RunEdit(project,
      XO("Split-deleted %.2f seconds at t=%.2f")
         .Format(selectedRegion.duration(), t0),
      XO("Split Delete"), [&] {
         for (auto n : tracks.Selected()) {
            if (auto wt = dynamic_cast<WaveTrack *>(n))
               wt->SplitDelete(t0, t1);
            else if (n->SupportsBasicEditing())
               n->Silence(t0, t1);
         }
      });
   return json::object();
}

json Silence(const json &)
{
   auto &project = Project();
   RequireSelection(project, NeedTime | NeedWaveTracks, true,
      XO("Silence Audio"));
   auto &tracks = TrackList::Get(project);
   const auto &selectedRegion = ViewInfo::Get(project).selectedRegion;
   const double t0 = selectedRegion.t0(), t1 = selectedRegion.t1();

   RunEdit(project,
      XO("Silenced selected tracks for %.2f seconds at %.2f")
         .Format(selectedRegion.duration(), t0),
      /* i18n-hint: verb */
      XC("Silence", "command"), [&] {
         const auto selectedWaveTracks = tracks.Selected<WaveTrack>();
         TimeStretching::WithClipRenderingProgress(
            [&](const ProgressReporter &parent) {
               BasicUI::SplitProgress(
                  selectedWaveTracks.begin(), selectedWaveTracks.end(),
                  [&](WaveTrack *n, const ProgressReporter &child) {
                     n->Silence(t0, t1, child);
                  },
                  parent);
            });
      });
   return json::object();
}

json Trim(const json &)
{
   auto &project = Project();
   RequireSelection(project, NeedTime | NeedWaveTracks, true,
      XO("Trim Audio"));
   auto &tracks = TrackList::Get(project);
   const auto &selectedRegion = ViewInfo::Get(project).selectedRegion;
   const double t0 = selectedRegion.t0(), t1 = selectedRegion.t1();

   RunEdit(project,
      XO("Trim selected audio tracks from %.2f seconds to %.2f seconds")
         .Format(t0, t1),
      XO("Trim Audio"), [&] {
         for (auto wt : tracks.Selected<WaveTrack>())
            // Hide the section before the left selector
            wt->Trim(t0, t1);
      });
   return json::object();
}

json Split(const json &)
{
   auto &project = Project();
   RequireSelection(project, NeedWaveTracks, true, XO("Split"));
   auto &tracks = TrackList::Get(project);
   const auto &selectedRegion = ViewInfo::Get(project).selectedRegion;
   const double sel0 = selectedRegion.t0(), sel1 = selectedRegion.t1();

   RunEdit(project, XO("Split"), XO("Split"), [&] {
      for (auto wt : tracks.Selected<WaveTrack>())
         wt->Split(sel0, sel1);
   });
   return json::object();
}

json SplitNew(const json &)
{
   auto &project = Project();
   RequireSelection(project, NeedTime | NeedWaveTracks, true,
      XO("Split New"));
   auto &tracks = TrackList::Get(project);
   const auto &selectedRegion = ViewInfo::Get(project).selectedRegion;
   const double t0 = selectedRegion.t0(), t1 = selectedRegion.t1();

   RunEdit(project, XO("Split to new track"), XO("Split New"), [&] {
      // We add to the list inside the loop: iterate over a copy
      const auto range = tracks.Selected<WaveTrack>();
      const std::vector<WaveTrack *> sources(range.begin(), range.end());
      for (auto wt : sources) {
         // Clips must be aligned to sample positions or the NEW clip will
         // not fit in the gap where it came from
         const double newt0 = wt->SnapToSample(t0);
         const double newt1 = wt->SnapToSample(t1);
         // Fix issue 2846 by calling copy with forClipboard = false.
         // This avoids creating the blank placeholder clips
         const auto dest = wt->Copy(newt0, newt1, false);
         if (dest) {
            // The copy function normally puts the clip at time 0
            // This offset lines it up with the original track's timing
            dest->MoveTo(newt0);
            tracks.Add(dest);
         }
         wt->SplitDelete(newt0, newt1);
      }
   });
   return json::object();
}

json Join(const json &)
{
   auto &project = Project();
   auto &tracks = TrackList::Get(project);
   const auto &selectedRegion = ViewInfo::Get(project).selectedRegion;
   const double t0 = selectedRegion.t0(), t1 = selectedRegion.t1();

   // JoinClipsAvailableFlag (no auto-select)
   {
      bool available = false;
      if (!selectedRegion.isPoint())
         for (const auto track : tracks.Selected<const WaveTrack>())
            if (track->GetSortedClipsIntersecting(t0, t1).size() > 1) {
               available = true;
               break;
            }
      if (!available)
         Fail(ErrorCode::NO_SELECTION,
            "Select a time range that covers at least two clips of a "
            "selected audio track");
   }

   RunEdit(project,
      XO("Joined %.2f seconds at t=%.2f")
         .Format(selectedRegion.duration(), t0),
      XO("Join"), [&] {
         const auto selectedTracks = tracks.Selected<WaveTrack>();
         TimeStretching::WithClipRenderingProgress(
            [&](const ProgressReporter &reportProgress) {
               BasicUI::SplitProgress(
                  selectedTracks.begin(), selectedTracks.end(),
                  [&](WaveTrack *wt, const ProgressReporter &childProgress) {
                     wt->Join(t0, t1, childProgress);
                  },
                  reportProgress);
            });
      });
   return json::object();
}

json DetachAtSilences(const json &)
{
   auto &project = Project();
   RequireSelection(project, NeedTime | NeedEditableTracks, true,
      XO("Detach at Silences"));
   auto &tracks = TrackList::Get(project);
   const auto &selectedRegion = ViewInfo::Get(project).selectedRegion;
   const double t0 = selectedRegion.t0(), t1 = selectedRegion.t1();

   RunEdit(project,
      XO("Detached %.2f seconds at t=%.2f")
         .Format(selectedRegion.duration(), t0),
      XO("Detach"), [&] {
         for (auto wt : tracks.Selected<WaveTrack>())
            wt->Disjoin(t0, t1);
      });
   return json::object();
}

json ClipboardInfo(const json &)
{
   const auto &clipboard = Clipboard::Get();
   const bool empty = clipboard.GetTracks().empty();
   return json{ { "empty", empty },
      { "t0", empty ? 0.0 : Finite(clipboard.T0()) },
      { "t1", empty ? 0.0 : Finite(clipboard.T1()) },
      { "trackCount", clipboard.GetTracks().Size() } };
}

} // namespace

void RegisterEditCommands(ModuleRegistry &registry)
{
   const unsigned m = NeedsProject | NeedsIdleAudio | Mutates;
   registry.AddCommand("edit.cut", Cut, m);
   // S: no undo entry, no generation bump (the clipboard is in the snapshot)
   registry.AddCommand("edit.copy", Copy,
      NeedsProject | NeedsIdleAudio | SelectionOnly);
   registry.AddCommand("edit.paste", Paste, m);
   registry.AddCommand("edit.delete", Delete, m);
   registry.AddCommand("edit.splitCut", SplitCut, m);
   registry.AddCommand("edit.splitDelete", SplitDelete, m);
   registry.AddCommand("edit.silence", Silence, m | LongRunning);
   registry.AddCommand("edit.trim", Trim, m);
   registry.AddCommand("edit.duplicate", Duplicate, m);
   registry.AddCommand("edit.split", Split, m);
   registry.AddCommand("edit.splitNew", SplitNew, m);
   registry.AddCommand("edit.join", Join, m | LongRunning);
   registry.AddCommand("edit.detachAtSilences", DetachAtSilences, m);
   registry.AddCommand("edit.clipboardInfo", ClipboardInfo);
}

} // namespace edit
} // namespace aubridge
