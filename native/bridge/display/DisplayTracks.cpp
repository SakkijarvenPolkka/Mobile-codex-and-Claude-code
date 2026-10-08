/*  SPDX-License-Identifier: GPL-2.0-or-later */
/**********************************************************************

  Audacity Android port

  DisplayTracks.cpp

  Display ids of tracks, pending (recording) tracks included; see
  DisplayTracks.h.  3.7.9 draws PendingTracks::SubstitutePendingChangedTrack
  of every track (src/TrackPanel.cpp, TrackArtist).

**********************************************************************/
#include "DisplayTracks.h"

#include <cstring>

#include "AudioIO.h"
#include "ClipDisplayCache.h"
#include "DisplayInternal.h"
#include "Edit.h"            // TrackIdValue
#include "Hooks.h"
#include "PendingTracks.h"
#include "Sequence.h"
#include "Session.h"
#include "Track.h"
#include "WaveClip.h"
#include "WaveTrack.h"

namespace aubridge::display {

namespace {
//! TrackId value of the tracks PendingTracks::RegisterPendingNewTracks adds
constexpr int64_t kUnassignedId = -1;
//! Synthetic id of the first pending new track
constexpr int64_t kFirstSyntheticId = -2;
}

int64_t DisplayTrackId(const TrackList &tracks, const Track &track)
{
   const auto value = TrackIdValue(track);
   if (value != kUnassignedId)
      return value;
   int64_t k = 0;
   for (const Track *t : tracks) {
      if (t == &track)
         return kFirstSyntheticId - k;
      if (TrackIdValue(*t) == kUnassignedId)
         ++k;
   }
   return kUnassignedId;
}

namespace {
//! A clip of the track holds samples in an append buffer: something (the
//! AudioIO thread) is appending to it.  Catches recording targets that are
//! not pending tracks (3.7.9 gives a new STEREO recording track a real id:
//! TrackList::Temporary(nullptr, track) adds before it stops assigning ids)
bool IsGrowing(const WaveTrack &track)
{
   for (const auto &clip : track.SortedIntervalArray())
      for (size_t ii = 0; ii < clip->NChannels(); ++ii)
         if (clip->GetAppendBufferLen(ii) > 0)
            return true;
   return false;
}
}

bool CaptureRunning()
{
   const auto audioIO = AudioIO::Get();
   return audioIO && !audioIO->mCaptureSequences.empty();
}

namespace {
//! `track` is one of the sequences AudioIO captures into right now
bool IsLiveCaptureTarget(const WaveTrack &track)
{
   const auto audioIO = AudioIO::Get();
   if (!audioIO)
      return false;
   const RecordableSequence *const sequence = &track;
   for (const auto &pSequence : audioIO->mCaptureSequences)
      if (pSequence.get() == sequence)
         return true;
   return false;
}

DisplayTrack Resolved(const WaveTrack &track, bool recording)
{
   const bool live = IsLiveCaptureTarget(track);
   return { &track, recording || live, live };
}
}

DisplayTrack ResolveDisplayTrack(AudacityProject &project, int64_t id)
{
   auto &tracks = TrackList::Get(project);
   const auto &pending = PendingTracks::Get(project);
   if (id >= 0) {
      Track *track = TrackById(project, id);
      if (!track)
         return {};
      const Track &drawn = pending.SubstitutePendingChangedTrack(*track);
      auto wave = dynamic_cast<const WaveTrack *>(&drawn);
      if (!wave)
         return {};
      return Resolved(*wave, &drawn != track || IsGrowing(*wave));
   }
   if (id <= kFirstSyntheticId) {
      const int64_t k = kFirstSyntheticId - id;
      int64_t i = 0;
      for (const Track *t : tracks) {
         if (TrackIdValue(*t) != kUnassignedId)
            continue;
         if (i++ == k) {
            auto wave = dynamic_cast<const WaveTrack *>(t);
            if (!wave)
               return {};
            return Resolved(*wave, true);
         }
      }
   }
   return {};
}

void PrepareForRecording(AudacityProject &project)
{
   // Too late once AudioIO captures (see DisplayTrack::liveCapture)
   if (CaptureRunning())
      return;
   auto &tracks = TrackList::Get(project);
   const auto &pending = PendingTracks::Get(project);
   for (Track *track : tracks) {
      const Track &drawn = pending.SubstitutePendingChangedTrack(*track);
      const bool target = &drawn != track || TrackIdValue(*track) == kUnassignedId;
      if (!target)
         continue;
      if (auto wave = dynamic_cast<const WaveTrack *>(&drawn))
         for (const auto &clip : wave->SortedIntervalArray())
            ClipDisplayCache::Get(*clip);
   }
}

namespace {

bool IsRecordingTarget(const WaveTrack &track)
{
   if (IsGrowing(track) || IsLiveCaptureTarget(track))
      return true;
   auto *project = Session::Get().Project();
   if (!project)
      return false;
   const auto &pending = PendingTracks::Get(*project);
   if (!pending.HasPendingTracks())
      return false;
   return TrackIdValue(track) == kUnassignedId ||
      &pending.SubstituteOriginalTrack(track) != &track;
}

//! waveVersion of a recording target without walking
//! Sequence::GetBlockArray() (a std::deque the AudioIO thread appends to).
//! Keep identical to RecordingWaveVersion of audio/TransportCommands.cpp,
//! which the audio module's snapshot uses for the same tracks.
int64_t RecordingWaveVersion(const WaveTrack &track)
{
   uint64_t h = 1469598103934665603ULL;
   const auto mix = [&h](uint64_t v) {
      for (int i = 0; i < 8; ++i) {
         h ^= (v >> (8 * i)) & 0xff;
         h *= 1099511628211ULL;
      }
   };
   const auto mixDouble = [&mix](double d) {
      uint64_t v;
      static_assert(sizeof v == sizeof d);
      std::memcpy(&v, &d, sizeof v);
      mix(v);
   };
   mix(0x7265636f7264ULL);   // "record": differs from the committed hash
   mix(track.NChannels());
   for (const auto &pClip : track.SortedIntervalArray()) {
      mixDouble(pClip->GetPlayStartTime());
      mixDouble(pClip->GetPlayEndTime());
      for (size_t ii = 0; ii < pClip->NChannels(); ++ii)
         if (auto seq = pClip->GetSequence(ii))
            mix(uint64_t(seq->GetNumSamples().as_long_long()));
   }
   return int64_t(h & ((uint64_t(1) << 62) - 1));
}

} // namespace

int64_t DisplayWaveVersion(const WaveTrack &track)
{
   return IsRecordingTarget(track)
      ? RecordingWaveVersion(track) : DefaultWaveVersion(track);
}

} // namespace aubridge::display
