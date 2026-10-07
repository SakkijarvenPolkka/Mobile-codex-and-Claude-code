/*  SPDX-License-Identifier: GPL-2.0-or-later */
/**********************************************************************

  Audacity Android port

  Edit.h

  Helpers for command handlers that touch the model (engine thread only):
  the RunEdit transaction (port of the exception handling of
  AudacityApp::OnExceptionInMainLoop around a menu command), selection-only
  state updates, TrackId <-> int64 mapping, clip references and the
  AudioIONotBusy predicate.

**********************************************************************/
#pragma once

#include <cstdint>
#include <functional>
#include <memory>
#include <type_traits>

#include "Json.h"
#include "TranslatableString.h"
#include "UndoManager.h"   // UndoPush

class AudacityProject;
class Track;
class WaveClip;
class WaveTrack;

namespace aubridge {

void RunEditImpl(AudacityProject &project, const TranslatableString &longDesc,
   const TranslatableString &shortDesc, const std::function<bool()> &fn,
   UndoPush flags);

//! Runs `fn`, then ProjectHistory::PushState(longDesc, shortDesc, flags) and
//! Session::Touch().  `fn` may return void, or bool (false = nothing changed:
//! no undo state is pushed).  On ANY exception: RollbackState(),
//! PendingTracks::ClearPendingTracks(), Touch(), and the exception is
//! rethrown (the dispatcher maps it to the error envelope).
template<typename Fn>
void RunEdit(AudacityProject &project, const TranslatableString &longDesc,
   const TranslatableString &shortDesc, Fn &&fn, UndoPush flags = UndoPush::NONE)
{
   if constexpr (std::is_same_v<std::invoke_result_t<Fn &>, void>)
      RunEditImpl(project, longDesc, shortDesc,
         [&]{ fn(); return true; }, flags);
   else
      RunEditImpl(project, longDesc, shortDesc,
         [&]{ return static_cast<bool>(fn()); }, flags);
}

//! Variant for handlers that push (or not) themselves, e.g. with
//! UndoPush::CONSOLIDATE, several states, or EffectManager's kSkipState:
//! `fn` returns whether it changed the model.  Same rollback as above;
//! Touch() when `fn` returned true.
void RunEditSelf(AudacityProject &project, const std::function<bool()> &fn);

//! Selection-only change finished: ProjectHistory::ModifyState(wantsAutoSave)
//! (keeps the selection in the current undo state) + schedule a snapshot
void ModifyState(AudacityProject &project, bool wantsAutoSave = false);

//! Numeric value of a track's TrackId (stable across undo/redo)
int64_t TrackIdValue(const Track &track);
//! The track with that id in the project's TrackList, or null
Track *TrackById(AudacityProject &project, int64_t id);
//! @throws BridgeError{NOT_FOUND}
Track &RequireTrack(AudacityProject &project, int64_t id);
//! @throws BridgeError{NOT_FOUND} (also when the track is not a WaveTrack)
WaveTrack &RequireWaveTrack(AudacityProject &project, int64_t id);

//! A resolved `{trackId, clipIndex, generation}` reference (API.md §3.2)
struct ClipRef {
   WaveTrack *track = nullptr;
   std::shared_ptr<WaveClip> clip;
   int index = -1;
};
//! Reads keys "trackId", "clipIndex", "generation" from `args`.
//! @throws INVALID_ARGS (malformed), STALE (generation != current),
//!   NOT_FOUND (no such track / clip index)
ClipRef ResolveClipRef(AudacityProject &project, const json &args);

//! AudioIONotBusy negated: a stream for this project is active (its token is
//! still AudioIO's stream token), or any stream (e.g. effect preview) is
//! open.  Monitoring does not count.
bool AudioBusy(const AudacityProject &project);

} // namespace aubridge
