/*  SPDX-License-Identifier: GPL-2.0-or-later */
/**********************************************************************

  Audacity Android port

  ClipCommands.cpp

  clips.move / clips.rename (API.md §3.3 "clips / labels").

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

**********************************************************************/
#include "EditUtil.h"

#include <cmath>
#include <vector>

#include "Edit.h"
#include "ModuleRegistry.h"
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

} // namespace

void RegisterClipCommands(ModuleRegistry &registry)
{
   const unsigned m = NeedsProject | NeedsIdleAudio | Mutates;
   registry.AddCommand("clips.move", ClipsMove, m);
   registry.AddCommand("clips.rename", ClipsRename, m);
}

} // namespace edit
} // namespace aubridge
