/*  SPDX-License-Identifier: GPL-2.0-or-later */
/**********************************************************************

  Audacity Android port

  DebugCommands.cpp

  Test-only commands of the display module (API.md "debug" table):
  debug.addEnvelopePoint, debug.stretchClip and debug.recording, which
  simulates a recording on the engine thread (pending tracks as
  ProjectAudioManager::DoRecord makes them, samples appended without
  flushing) so the display data of recording targets can be tested without
  an audio device.

**********************************************************************/
#include "Modules.h"

#include <cmath>
#include <memory>
#include <vector>

#include "DisplayInternal.h"
#include "DisplayTracks.h"
#include "Edit.h"
#include "Envelope.h"
#include "PendingTracks.h"
#include "Session.h"
#include "Track.h"
#include "WaveClip.h"
#include "WaveTrack.h"

namespace aubridge::display {

namespace {

//! debug.addEnvelopePoint {trackId, t, value} -> {clipIndex}: a point of the
//! envelope of the clip at time t (absolute), as the envelope tool would add
json AddEnvelopePoint(const json &args)
{
   auto &project = Session::Get().RequireProject();
   auto &track = RequireWaveTrack(project, ArgInt(args, "trackId"));
   const double t = ArgDouble(args, "t");
   const double value = ArgDouble(args, "value");
   RequireRange("value", value, 0.0, 2.0);
   const auto clip = track.GetIntervalAtTime(t);
   if (!clip)
      Fail(ErrorCode::NOT_FOUND, "no clip at that time");
   int index = 0;
   for (const auto &c : track.SortedIntervalArray()) {
      if (c == clip)
         break;
      ++index;
   }
   // EnvelopeHandle's history strings (src/tracks/ui/EnvelopeHandle.cpp)
   RunEdit(project, XO("Adjusted envelope."), XO("Envelope"), [&] {
      clip->GetEnvelope().InsertOrReplace(t, value);
   });
   return json{ { "clipIndex", index } };
}

//! debug.stretchClip {trackId, clipIndex, ratio}: WaveClip::StretchBy (the
//! clip keeps its start; it may overlap the next clip)
json StretchClip(const json &args)
{
   auto &project = Session::Get().RequireProject();
   auto &track = RequireWaveTrack(project, ArgInt(args, "trackId"));
   const auto clipIndex = ArgInt(args, "clipIndex");
   const double ratio = ArgDouble(args, "ratio");
   RequireRange("ratio", ratio, 0.1, 10.0);
   auto clips = track.SortedIntervalArray();
   if (clipIndex < 0 || size_t(clipIndex) >= clips.size())
      Fail(ErrorCode::NOT_FOUND, "no clip with index " + std::to_string(clipIndex));
   auto clip = clips[size_t(clipIndex)];
   RunEdit(project, XO("Stretched clip"), XO("Stretch Clip"), [&] {
      clip->StretchBy(ratio);
   });
   return json::object();
}

//! The tracks the simulated recording appends to (pending copies of
//! existing tracks, new tracks), as the audio module's capture sequences
std::vector<std::weak_ptr<WaveTrack>> sTargets;

std::vector<std::shared_ptr<WaveTrack>> LiveTargets()
{
   std::vector<std::shared_ptr<WaveTrack>> result;
   for (const auto &weak : sTargets)
      if (auto track = weak.lock())
         result.push_back(track);
   return result;
}

//! An empty clip at t0 that the recording appends to (DoRecord's
//! insertEmptyInterval)
void InsertRecordingClip(WaveTrack &track, double t0)
{
   wxString name;
   for (int i = 1;; ++i) {
      name = wxString::Format(wxT("%s.%d"), track.GetName(), i);
      if (!track.HasClipNamed(name))
         break;
   }
   auto clip = track.CreateClip(t0, name);
   track.InsertInterval(clip, true, true);
}

//! debug.recording {action:"start", trackId?, newChannels?=1, t0?=0}
//!   | {action:"append", seconds, frequency?=440}
//!   | {action:"commit"} | {action:"cancel"}
json Recording(const json &args)
{
   auto &project = Session::Get().RequireProject();
   auto &pending = PendingTracks::Get(project);
   auto &tracks = TrackList::Get(project);
   const auto action = ArgString(args, "action");
   if (action == "start") {
      if (!LiveTargets().empty() || pending.HasPendingTracks())
         Fail(ErrorCode::INVALID_ARGS, "a recording is already simulated");
      const double t0 = OptDouble(args, "t0").value_or(0.0);
      RequireRange("t0", t0, 0, 1e6);
      const auto newChannels = OptInt(args, "newChannels").value_or(1);
      RequireRange("newChannels", double(newChannels), 0, 4);
      const auto trackId = OptInt(args, "trackId");
      WaveTrack *existing = nullptr;
      if (trackId)
         existing = &RequireWaveTrack(project, *trackId);
      sTargets.clear();
      if (existing) {
         auto copy = static_cast<WaveTrack *>(
            pending.RegisterPendingChangedTrack({}, existing));
         InsertRecordingClip(*copy, t0);
         sTargets.push_back(copy->SharedPointer<WaveTrack>());
      }
      if (newChannels > 0) {
         // As DoRecord: 2 = one stereo track (which 3.7.9's
         // TrackList::Temporary gives a real TrackId at once), else mono
         auto list = WaveTrackFactory::Get(project).CreateMany(size_t(newChannels));
         for (auto track : list->Any<WaveTrack>()) {
            track->SetName(tracks.MakeUniqueTrackName(wxT("Recording")));
            InsertRecordingClip(*track, t0);
            sTargets.push_back(track->SharedPointer<WaveTrack>());
         }
         pending.RegisterPendingNewTracks(std::move(*list));
      }
      PrepareForRecording(project);
      return json::object();
   }
   const auto targets = LiveTargets();
   if (targets.empty())
      Fail(ErrorCode::INVALID_ARGS, "no simulated recording");
   if (action == "append") {
      const double seconds = ArgDouble(args, "seconds");
      RequireRange("seconds", seconds, 0, 600);
      const double frequency = OptDouble(args, "frequency").value_or(440.0);
      for (const auto &track : targets) {
         const double rate = track->GetRate();
         const auto clip = track->RightmostOrNewClip();
         // Continue the tone of debug.makeTestTrack at the absolute sample
         const auto first = std::llround(clip->GetPlayEndTime() * rate);
         const auto n = size_t(std::llround(seconds * rate));
         std::vector<float> buffer(n);
         for (size_t ii = 0; ii < track->NChannels(); ++ii) {
            for (size_t i = 0; i < n; ++i)
               buffer[i] = float(0.5 * std::sin(2 * M_PI * frequency *
                  double(first + int64_t(i)) / rate + ii * M_PI / 2));
            // No Flush: the tail stays in the append buffer, as while
            // recording (full blocks are committed by Sequence::Append)
            track->Append(ii, reinterpret_cast<constSamplePtr>(buffer.data()),
               floatSample, n);
         }
      }
      return json::object();
   }
   if (action == "commit") {
      sTargets.clear();
      RunEdit(project, XO("Recorded Audio"), XO("Record"), [&] {
         for (const auto &track : targets)
            track->Flush();
         pending.ApplyPendingTracks();
      });
      return json::object();
   }
   if (action == "cancel") {
      sTargets.clear();
      pending.ClearPendingTracks();
      // New tracks that got a real id (stereo) are not pending: remove them
      for (const auto &track : targets)
         if (track->GetOwner().get() == &tracks)
            tracks.Remove(*track);
      return json::object();
   }
   Fail(ErrorCode::INVALID_ARGS, "unknown action '" + action + "'");
}

} // namespace

void RegisterDebugCommands(ModuleRegistry &registry)
{
   const unsigned m = NeedsProject | NeedsIdleAudio | Mutates;
   registry.AddCommand("debug.addEnvelopePoint", AddEnvelopePoint, m);
   registry.AddCommand("debug.stretchClip", StretchClip, m);
   registry.AddCommand("debug.recording", Recording, m);
}

} // namespace aubridge::display
