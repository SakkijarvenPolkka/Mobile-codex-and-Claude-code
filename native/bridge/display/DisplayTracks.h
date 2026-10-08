/*  SPDX-License-Identifier: GPL-2.0-or-later */
/**********************************************************************

  Audacity Android port

  DisplayTracks.h

  Track ids as the display entry points (API.md §7) and the snapshot see
  them, including the tracks that only exist while recording.  This header
  is the display module's interface for the other modules (the audio
  module includes it as "display/DisplayTracks.h"); engine thread only.

  Ids (API.md §7.1 "Track ids"):
  * a track of the TrackList has its TrackId value (>= 0);
  * a pending NEW track (recording into a new track: PendingTracks keeps
    it in the TrackList with the unassigned TrackId, value -1) has the
    synthetic id -(2 + k), k = its position among the pending new tracks
    in TrackList iteration order (the order of the snapshot's `tracks`).
    The ids are stable for the whole recording; ApplyPendingTracks gives
    the tracks real ids when the recording is committed.
  * A display request for a real id draws
    PendingTracks::SubstitutePendingChangedTrack(track): while recording
    into an existing track that is the growing shadow copy.
  * Recording targets are the pending tracks and every track with samples
    in an append buffer (3.7.9 gives a new stereo recording track a real
    id at once: TrackList::Temporary(nullptr, track) assigns it).

**********************************************************************/
#pragma once

#include <cstdint>

class AudacityProject;
class Track;
class TrackList;
class WaveTrack;

namespace aubridge::display {

//! The id of `track` in snapshots and display requests (see above): its
//! TrackId value, or the synthetic id (<= -2) of a pending new track
int64_t DisplayTrackId(const TrackList &tracks, const Track &track);

//! A display id resolved to the track whose data is drawn
struct DisplayTrack {
   const WaveTrack *track = nullptr;
   //! The track is a recording target (pending new track, the pending
   //! shadow copy of an existing track, or a track with samples in an
   //! append buffer): it may grow by Sequence::Append on the audio thread
   //! until the recording is committed
   bool recording = false;
   //! AudioIO captures into this track right now (it is one of
   //! AudioIO::mCaptureSequences): the AudioIO thread notifies its clips
   //! (WaveClip::MarkChanged iterates their attachments), so nothing may be
   //! attached to them (ClipDisplayCache::ForRequest).  Implies `recording`.
   bool liveCapture = false;
};

//! Resolves `id` (real or synthetic); {nullptr} when there is no such wave
//! track (also for -1 and for synthetic ids while nothing is recorded)
DisplayTrack ResolveDisplayTrack(AudacityProject &project, int64_t id);

//! Creates the display caches (clip attachments) of the recording targets'
//! clips: the pending changed tracks and the pending new tracks.  Used by
//! debug.recording.  Not needed for safety: while AudioIO captures, the
//! display requests never attach anything to a capture target's clips
//! (DisplayTrack::liveCapture), whether or not this ran before
//! AudioIO::StartStream.
void PrepareForRecording(AudacityProject &project);

} // namespace aubridge::display
