/*  SPDX-License-Identifier: GPL-2.0-or-later */
/**********************************************************************

  Audacity Android port

  HistoryCommands.cpp

  history.undo / redo / list / goto / purge.  Ports of Audacity 3.7.9
  src/menus/EditMenus.cpp (OnUndo, OnRedo) and src/HistoryWindow.cpp
  (SpaceUsageCalculator, OnItemSelected, OnDiscard).

**********************************************************************/
#include "SpineCommands.h"

#include "Clipboard.h"
#include "Edit.h"
#include "ProjectHistory.h"
#include "SampleBlock.h"
#include "Session.h"
#include "Track.h"
#include "TrackFocus.h"
#include "UndoManager.h"
#include "UndoTracks.h"
#include "WaveTrackUtilities.h"

namespace aubridge {

namespace {

// EditMenus.cpp OnUndo/OnRedo: focus the first selected track, else the first
void RefocusAfterHistoryJump(AudacityProject &project)
{
   auto &tracks = TrackList::Get(project);
   Track *t = *tracks.Selected().begin();
   if (!t)
      t = *tracks.begin();
   TrackFocus::Get(project).Set(t);
}

json Undo(const json &)
{
   auto &project = Session::Get().RequireProject();
   auto &history = ProjectHistory::Get(project);
   if (!history.UndoAvailable())
      Fail(ErrorCode::FAILED, "Nothing to undo");
   UndoManager::Get(project).Undo(
      [&](const UndoStackElem &elem) { history.PopState(elem.state); });
   RefocusAfterHistoryJump(project);
   return json::object();
}

json Redo(const json &)
{
   auto &project = Session::Get().RequireProject();
   auto &history = ProjectHistory::Get(project);
   if (!history.RedoAvailable())
      Fail(ErrorCode::FAILED, "Nothing to redo");
   UndoManager::Get(project).Redo(
      [&](const UndoStackElem &elem) { history.PopState(elem.state); });
   RefocusAfterHistoryJump(project);
   return json::object();
}

// HistoryWindow.cpp SpaceUsageCalculator: count each block once, in the
// newest state that uses it
std::vector<unsigned long long> SpaceUsage(UndoManager &manager)
{
   std::vector<unsigned long long> space;   // newest first
   WaveTrackUtilities::SampleBlockIDSet seen;
   manager.VisitStates([&](const UndoStackElem &elem) {
      unsigned long long result = 0;
      if (auto pTracks = UndoTracks::Find(elem))
         WaveTrackUtilities::InspectBlocks(*pTracks,
            BlockSpaceUsageAccumulator(result), &seen);
      space.push_back(result);
   }, true);
   return space;
}

json List(const json &)
{
   auto &project = Session::Get().RequireProject();
   auto &manager = UndoManager::Get(project);
   const auto space = SpaceUsage(manager);
   auto iter = space.rbegin();   // oldest state first
   json states = json::array();
   int index = 0;
   manager.VisitStates([&](const UndoStackElem &elem) {
      const auto size = iter != space.rend() ? *iter++ : 0ull;
      states.push_back(json{ { "index", index++ },
         { "description", Translated(elem.description) },
         { "shortDescription", Translated(elem.shortDescription) },
         { "sizeBytes", size } });
   }, false);
   return json{ { "current", manager.GetCurrentState() },
      { "states", std::move(states) } };
}

json Goto(const json &args)
{
   auto &project = Session::Get().RequireProject();
   auto &manager = UndoManager::Get(project);
   const auto index = ArgInt(args, "index");
   if (index < 0 || index >= int64_t(manager.GetNumStates()))
      Fail(ErrorCode::INVALID_ARGS, "no history state " + std::to_string(index));
   if (unsigned(index) != manager.GetCurrentState()) {
      ProjectHistory::Get(project).SetStateTo(unsigned(index));
      RefocusAfterHistoryJump(project);
   }
   return json::object();
}

// HistoryWindow.cpp OnDiscard: discard the `keepFrom` oldest states (never
// the current one)
json Purge(const json &args)
{
   auto &project = Session::Get().RequireProject();
   auto &manager = UndoManager::Get(project);
   const auto keepFrom = ArgInt(args, "keepFrom");
   if (keepFrom < 0)
      Fail(ErrorCode::INVALID_ARGS, "argument 'keepFrom' must be >= 0");
   const unsigned current = manager.GetCurrentState();
   const unsigned count = std::min<unsigned>(unsigned(keepFrom), current);
   if (count > 0) {
      manager.RemoveStates(0, count);
      ProjectHistory::Get(project).SetStateTo(current - count);
   }
   return json::object();
}

} // namespace

void RegisterHistoryCommands(ModuleRegistry &registry)
{
   const unsigned edit = NeedsProject | NeedsIdleAudio | Mutates;
   registry.AddCommand("history.undo", Undo, edit);
   registry.AddCommand("history.redo", Redo, edit);
   registry.AddCommand("history.list", List, NeedsProject);
   registry.AddCommand("history.goto", Goto, edit);
   registry.AddCommand("history.purge", Purge, edit);
}

} // namespace aubridge
