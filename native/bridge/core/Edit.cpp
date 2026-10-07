/*  SPDX-License-Identifier: GPL-2.0-or-later */
/**********************************************************************

  Audacity Android port

  Edit.cpp

**********************************************************************/
#include "Edit.h"

#include <cstring>
#include <type_traits>

#include "AudioIOBase.h"
#include "PendingTracks.h"
#include "ProjectAudioIO.h"
#include "ProjectHistory.h"
#include "Session.h"
#include "Track.h"
#include "WaveClip.h"
#include "WaveTrack.h"

namespace aubridge {

void RunEditImpl(AudacityProject &project, const TranslatableString &longDesc,
   const TranslatableString &shortDesc, const std::function<bool()> &fn,
   UndoPush flags)
{
   auto &history = ProjectHistory::Get(project);
   try {
      if (fn()) {
         // Model mutation first, PushState last (it also autosaves and may
         // throw; that is rolled back too)
         history.PushState(longDesc, shortDesc, flags);
      }
      Session::Get().Touch();
   }
   catch (...) {
      // Same as AudacityApp::OnExceptionInMainLoop, synchronously
      try {
         history.RollbackState();
         PendingTracks::Get(project).ClearPendingTracks();
      }
      catch (...) {
      }
      Session::Get().Touch();
      throw;
   }
}

void RunEditSelf(AudacityProject &project, const std::function<bool()> &fn)
{
   try {
      if (fn())
         Session::Get().Touch();
   }
   catch (...) {
      try {
         ProjectHistory::Get(project).RollbackState();
         PendingTracks::Get(project).ClearPendingTracks();
      }
      catch (...) {
      }
      Session::Get().Touch();
      throw;
   }
}

void ModifyState(AudacityProject &project, bool wantsAutoSave)
{
   ProjectHistory::Get(project).ModifyState(wantsAutoSave);
   Session::Get().ScheduleSnapshot();
}

// TrackId has no accessor for its value in 3.7.9 (lib-track/Track.h); it is
// a trivially copyable wrapper of one `long`, so copy the bits instead of
// patching the widely included Track.h (which would rebuild everything).
static_assert(std::is_trivially_copyable_v<TrackId>);
static_assert(sizeof(TrackId) == sizeof(long));

int64_t TrackIdValue(const Track &track)
{
   long value;
   const TrackId id = track.GetId();
   std::memcpy(&value, &id, sizeof value);
   return value;
}

Track *TrackById(AudacityProject &project, int64_t id)
{
   if (id < 0)
      return nullptr;
   return TrackList::Get(project).FindById(TrackId{ long(id) });
}

Track &RequireTrack(AudacityProject &project, int64_t id)
{
   if (auto track = TrackById(project, id))
      return *track;
   Fail(ErrorCode::NOT_FOUND, "no track with id " + std::to_string(id));
}

WaveTrack &RequireWaveTrack(AudacityProject &project, int64_t id)
{
   if (auto track = dynamic_cast<WaveTrack *>(TrackById(project, id)))
      return *track;
   Fail(ErrorCode::NOT_FOUND, "no audio track with id " + std::to_string(id));
}

ClipRef ResolveClipRef(AudacityProject &project, const json &args)
{
   const auto trackId = ArgInt(args, "trackId");
   const auto clipIndex = ArgInt(args, "clipIndex");
   const auto generation = ArgInt(args, "generation");
   if (uint64_t(generation) != Session::Get().Generation())
      Fail(ErrorCode::STALE, "clip reference of generation " +
         std::to_string(generation) + ", current is " +
         std::to_string(Session::Get().Generation()));
   auto &track = RequireWaveTrack(project, trackId);
   const auto clips = track.SortedIntervalArray();
   if (clipIndex < 0 || clipIndex >= int64_t(clips.size()))
      Fail(ErrorCode::NOT_FOUND, "no clip " + std::to_string(clipIndex) +
         " in track " + std::to_string(trackId));
   return ClipRef{ &track, clips[size_t(clipIndex)], int(clipIndex) };
}

bool AudioBusy(const AudacityProject &project)
{
   auto audioIO = AudioIOBase::Get();
   if (!audioIO)
      return false;
   // AudioIONotBusyFlag (CommonCommandFlags.cpp) uses IsAudioTokenActive;
   // IsBusy() also covers a stream whose token is not the project's (effect
   // preview) and a stream being started.  Monitoring (token 0) is not busy.
   return audioIO->IsAudioTokenActive(
      ProjectAudioIO::Get(project).GetAudioIOToken()) || audioIO->IsBusy();
}

} // namespace aubridge
