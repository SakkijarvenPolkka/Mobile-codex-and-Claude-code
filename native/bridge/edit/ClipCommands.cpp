/*  SPDX-License-Identifier: GPL-2.0-or-later */
/**********************************************************************

  Audacity Android port

  ClipCommands.cpp

  clips.move / clips.rename / clips.trim (API.md §3.3 "clips / labels").

  clips.move is the end of a clip drag: Compose previews the drag, the
  engine receives one command with the final start time.  It is a port of
  the WaveTrack time shifting of Audacity 3.7.9
  src/tracks/ui/TimeShiftHandle.cpp and
  src/tracks/playabletrack/wavetrack/ui/WaveTrackShifter.cpp for one clip:
   * same track: the offset is quantized to samples (QuantizeOffset) and
     reduced so that the clip stops at its neighbours
     (AdjustOffsetSmaller -> WaveTrack::CanOffsetClips)
   * another track (same channel count): CheckFit with the 3.7.9 tolerance
     of 20 pixels at the current zoom, then a second check without
     tolerance; the clip is resampled to the destination track's rate
     (FinishMigration).
  clips.rename: src/tracks/playabletrack/wavetrack/ui/
  WaveTrackAffordanceControls.cpp (OnRenameClip).
  clips.trim: the trim mode of src/tracks/playabletrack/wavetrack/ui/
  WaveClipAdjustBorderHandle.cpp (Vitaly Sverchinsky) -- the adjustment
  limits of GetLeftAdjustLimit/GetRightAdjustLimit (the clip's audio, the
  neighbouring clips, one sample at least), WaveClip::TrimLeftTo/TrimRightTo
  and the history entry of AdjustClipBorder::Finish.  A drag sends
  final:false updates (model change only, like the volume slider) and one
  final:true update that pushes a single entry for the whole drag.

**********************************************************************/
#include "EditUtil.h"

#include <algorithm>
#include <cmath>
#include <limits>
#include <memory>
#include <vector>

#include "Edit.h"
#include "ModuleRegistry.h"
#include "ProjectHistory.h"
#include "Session.h"
#include "Track.h"
#include "ViewInfo.h"
#include "WaveClip.h"
#include "WaveTrack.h"

namespace aubridge {
namespace edit {

namespace {

//! Index of `clip` in the track's SortedIntervalArray(), or -1
int SortedIndexOf(const WaveTrack &track, const WaveClip *clip)
{
   const auto clips = track.SortedIntervalArray();
   for (size_t i = 0; i < clips.size(); ++i)
      if (clips[i].get() == clip)
         return int(i);
   return -1;
}

json ClipsMove(const json &args)
{
   auto &project = Session::Get().RequireProject();
   auto ref = ResolveClipRef(project, args);
   const double newStart = ArgTime(args, "newStart");
   WaveTrack *dst = ref.track;
   if (auto toId = OptInt(args, "toTrackId"))
      dst = &RequireWaveTrack(project, *toId);
   auto &src = *ref.track;
   const auto clip = ref.clip;

   // WaveTrackShifter::QuantizeOffset: a whole number of samples (of the
   // track the drag started in)
   const double rate = src.GetRate();
   double offset =
      std::rint((newStart - clip->GetPlayStartTime()) * rate) / rate;

   if (dst == &src) {
      // AdjustOffsetSmaller: stop at the neighbouring clips
      if (offset != 0.0) {
         double allowed = 0;
         std::vector<WaveTrack::Interval *> moving{ clip.get() };
         src.CanOffsetClips(moving, offset, &allowed);
         offset = allowed;
      }
      if (offset != 0.0)
         RunEdit(project,
            (offset > 0
               ? XO("Time shifted tracks/clips right %.02f seconds")
               : XO("Time shifted tracks/clips left %.02f seconds")
            ).Format(std::fabs(offset)),
            XO("Move Clip"), [&] { clip->ShiftBy(offset); });
   }
   else {
      // TrackShifter::CommonMayMigrateTo: same kind, same channel count
      if (dst->NChannels() != src.NChannels())
         Fail(ErrorCode::INVALID_ARGS,
            "the destination track has another number of channels");
      // TimeShiftHandle::DoSlideVertical / CheckFit
      double slideBy = offset;
      const double zoom = ViewInfo::Get(project).GetZoom();
      const double tolerance = zoom > 0 ? 20.0 / zoom : 0.0;
      bool ok = dst->CanInsertClip(*clip, slideBy, tolerance);
      if (ok && tolerance != 0.0)
         // Check again, in the new position, this time with zero tolerance
         ok = dst->CanInsertClip(*clip, slideBy, 0.0);
      if (!ok)
         Fail(ErrorCode::FAILED,
            "There is not enough room available to place the clip there");
      RunEdit(project, XO("Moved clips to another track"), XO("Move Clip"),
         [&] {
            src.RemoveInterval(clip);
            dst->InsertInterval(clip, false);
            if (slideBy != 0.0)
               clip->ShiftBy(slideBy);
            // WaveTrackShifter::FinishMigration: the clip takes the rate of
            // the destination track
            clip->Resample(int(dst->GetRate()));
         });
   }
   return json{ { "trackId", TrackIdValue(*dst) },
      { "clipIndex", SortedIndexOf(*dst, clip.get()) },
      { "start", Finite(clip->GetPlayStartTime()) } };
}

json ClipsRename(const json &args)
{
   auto &project = Session::Get().RequireProject();
   auto ref = ResolveClipRef(project, args);
   const wxString name = FromUtf8(ArgString(args, "name"));
   if (ref.clip->GetName() == name)
      return json::object();
   RunEdit(project, XO("Modified Clip Name"), XO("Clip Name Edit"),
      [&] { ref.clip->SetName(name); });
   return json::object();
}

// ---------------------------------------------------------------------------
// clips.trim
// ---------------------------------------------------------------------------

//! The trim drag in progress: the trims of its clip when it started.  It is
//! valid while the project generation does not change (final:false updates
//! do not bump it; any other mutation, undo or redo does).
struct TrimDrag {
   std::weak_ptr<WaveClip> clip;
   uint64_t generation = 0;
   double trimLeft = 0, trimRight = 0;
   bool active = false;
};

TrimDrag &Drag()
{
   static TrimDrag drag;
   return drag;
}

json ClipsTrim(const json &args)
{
   auto &project = Session::Get().RequireProject();
   auto ref = ResolveClipRef(project, args);
   const auto trimLeft = OptTime(args, "trimLeft");
   const auto trimRight = OptTime(args, "trimRight");
   if (!trimLeft && !trimRight)
      Fail(ErrorCode::INVALID_ARGS, "trimLeft and/or trimRight is required");
   const bool isFinal = OptBool(args, "final").value_or(true);
   auto &track = *ref.track;
   auto &clip = *ref.clip;

   // The drag this update belongs to.  An unfinished live drag of another
   // clip is cancelled (AdjustClipBorder::Cancel): its clip gets its trims
   // back, so the entry of this drag does not carry it.
   auto &drag = Drag();
   const auto generation = Session::Get().Generation();
   if (!(drag.active && drag.generation == generation &&
         drag.clip.lock() == ref.clip)) {
      if (drag.active && drag.generation == generation)
         if (auto other = drag.clip.lock()) {
            other->SetTrimLeft(drag.trimLeft);
            other->SetTrimRight(drag.trimRight);
         }
      drag = TrimDrag{ ref.clip, generation, clip.GetTrimLeft(),
         clip.GetTrimRight(), true };
   }
   const double initialLeft = drag.trimLeft;
   const double initialRight = drag.trimRight;

   // WaveClipAdjustBorderHandle: whole samples of the track's rate, within
   // the clip's audio (GetSequenceStartTime ... GetSequenceEndTime) and the
   // neighbouring clips, one sample at least
   const double rate = track.GetRate();
   const double period = 1.0 / rate;
   const auto quantize = [rate](double seconds) {
      return std::rint(seconds * rate) / rate;
   };
   const double currentLeft = clip.GetTrimLeft();
   const double currentRight = clip.GetTrimRight();
   const double seqStart = clip.GetSequenceStartTime();
   const double seqEnd = clip.GetSequenceEndTime();
   double lo = seqStart, hi = seqEnd;
   if (auto prev = track.GetNextInterval(clip, PlaybackDirection::backward))
      lo = std::max(lo, prev->End());
   if (auto next = track.GetNextInterval(clip, PlaybackDirection::forward))
      hi = std::min(hi, next->Start());
   // The borders (absolute times)
   double left = seqStart + (trimLeft ? quantize(*trimLeft) : currentLeft);
   double right = seqEnd - (trimRight ? quantize(*trimRight) : currentRight);
   if (trimLeft)
      left = std::clamp(left, lo, std::max(lo, hi));
   if (trimRight)
      right = std::clamp(right, lo, std::max(lo, hi));
   if (right - left < period) {
      if (trimLeft && !trimRight)
         left = std::max(lo, right - period);
      else {
         right = std::min(hi, left + period);
         left = std::max(lo, std::min(left, right - period));
      }
   }
   // Back to trims; the border arithmetic must not leave sub-sample noise
   // (an unchanged border keeps its exact value)
   const auto settle = [period](double value, double a, double b) {
      if (std::fabs(value - a) < period / 2)
         return a;
      if (std::fabs(value - b) < period / 2)
         return b;
      return value;
   };
   const double newLeft =
      std::max(0.0, settle(left - seqStart, initialLeft, currentLeft));
   const double newRight =
      std::max(0.0, settle(seqEnd - right, initialRight, currentRight));
   const auto apply = [&] {
      clip.SetTrimLeft(newLeft);
      clip.SetTrimRight(newRight);
   };
   const auto result = [&] {
      return json{ { "trimLeft", Finite(clip.GetTrimLeft()) },
         { "trimRight", Finite(clip.GetTrimRight()) },
         { "start", Finite(clip.GetPlayStartTime()) },
         { "end", Finite(clip.GetPlayEndTime()) } };
   };

   if (!isFinal) {
      // While dragging: model change only, no history entry, no generation
      // bump (the clip reference stays valid)
      apply();
      ThrottledSnapshot();
      return result();
   }

   drag = {};
   CancelThrottledSnapshot();
   bool pushed = false;
   RunEditSelf(project, [&] {
      apply();
      const double dl = std::fabs(newLeft - initialLeft);
      const double dr = std::fabs(newRight - initialRight);
      if (dl == 0 && dr == 0)
         return false;
      // AdjustClipBorder::Finish (one border per drag; an update moving
      // both borders is named after the larger movement)
      auto &history = ProjectHistory::Get(project);
      if (dl >= dr)
         history.PushState(
            /*i18n-hint: This is about trimming a clip, a length in seconds like "2.4 seconds" is shown*/
            XO("Adjust left trim by %.02f seconds").Format(dl),
            /*i18n-hint: This is about trimming a clip, a length in seconds like "2.4s" is shown*/
            XO("Trim by %.02fs").Format(dl));
      else
         history.PushState(
            /*i18n-hint: This is about trimming a clip, a length in seconds like "2.4 seconds" is shown*/
            XO("Adjust right trim by %.02f seconds").Format(dr),
            /*i18n-hint: This is about trimming a clip, a length in seconds like "2.4s" is shown*/
            XO("Trim by %.02fs").Format(dr));
      pushed = true;
      return true;
   });
   if (!pushed)
      // The drag ended where it started: no entry, but the last snapshot
      // may show a live position
      Session::Get().ScheduleSnapshot();
   return result();
}

} // namespace

void RegisterClipCommands(ModuleRegistry &registry)
{
   Drag() = {};
   const unsigned m = NeedsProject | NeedsIdleAudio | Mutates;
   registry.AddCommand("clips.move", ClipsMove, m);
   registry.AddCommand("clips.rename", ClipsRename, m);
   // final:false is no M (no generation bump, no history entry);
   // final:true pushes and touches itself (RunEditSelf)
   registry.AddCommand("clips.trim", ClipsTrim, NeedsProject | NeedsIdleAudio);
}

} // namespace edit
} // namespace aubridge
