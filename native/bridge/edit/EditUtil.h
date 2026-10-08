/*  SPDX-License-Identifier: GPL-2.0-or-later */
/**********************************************************************

  Audacity Android port

  EditUtil.h

  Shared helpers of the bridge "edit" module (selection, edit, tracks,
  clips, labels -- API.md §3.3): command preconditions with 3.7.9's
  "select all if none" recovery, selection utilities, preference reads
  and time arguments.  Engine thread only.

**********************************************************************/
#pragma once

#include <optional>
#include <string>

#include "Json.h"
#include "TranslatableString.h"

class AudacityProject;
class Track;

namespace aubridge {

class ModuleRegistry;

namespace edit {

// One registration function per source file
void RegisterSelectCommands(ModuleRegistry &registry);
void RegisterEditCommands(ModuleRegistry &registry);
void RegisterTrackCommands(ModuleRegistry &registry);
void RegisterClipCommands(ModuleRegistry &registry);
void RegisterLabelCommands(ModuleRegistry &registry);

//! Flags of 3.7.9 src/CommonCommandFlags.cpp that edit commands require
enum Requirement : unsigned {
   //! TimeSelectedFlag: the time selection is not a point
   NeedTime = 1u << 0,
   //! EditableTracksSelectedFlag: a selected track supports basic editing
   NeedEditableTracks = 1u << 1,
   //! WaveTracksSelectedFlag
   NeedWaveTracks = 1u << 2,
   //! AnyTracksSelectedFlag
   NeedAnyTracks = 1u << 3,
};

//! The requirements in `requirements` that the project does not satisfy
unsigned MissingRequirements(AudacityProject &project, unsigned requirements);

//! Port of CommandManager::TryToMakeActionAllowed for the edit commands:
//! when requirements are missing, `autoSelect` is true (the 3.7.9 menu item
//! has no NoAutoSelect() flag) and the preference /GUI/SelectAllOnNone is
//! set, selects all audio first (DoSelectAllAudio, like the enablers of
//! src/menus/EditMenus.cpp).
//! @param commandName the menu name, for the error message
//! @throws BridgeError{NO_SELECTION} when still missing
void RequireSelection(AudacityProject &project, unsigned requirements,
   bool autoSelect, const TranslatableString &commandName);

//! SelectUtilities::DoSelectAllAudio: whole project time, all wave tracks
//! (ModifyState(false))
void DoSelectAllAudio(AudacityProject &project);

//! SelectUtilities::SelectNone (tracks only, the time selection is kept)
void SelectNoTracks(AudacityProject &project);

//! gPrefs->ReadBool(path, def) (false when there are no preferences)
bool ReadPrefBool(const wxString &path, bool def);

//! A required/optional time argument: finite number (INVALID_ARGS else)
double ArgTime(const json &args, const char *key);
std::optional<double> OptTime(const json &args, const char *key);

//! Rejects a `generation` argument that is present and not current
//! (STALE), like ResolveClipRef does for clip references
void CheckOptionalGeneration(const json &args);

//! A rate for 3.7.9's "Changed '%s' to %s Hz" ("%.3f")
wxString FormatRate(double rate);

} // namespace edit
} // namespace aubridge
