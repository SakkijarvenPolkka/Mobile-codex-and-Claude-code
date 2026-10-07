/*  SPDX-License-Identifier: GPL-2.0-or-later */
/**********************************************************************

  Audacity Android port

  Snapshot.h

  The `snapshot` payload (API.md §4.2) and the menu-enable flag bitset
  (port of 3.7.9 src/CommonCommandFlags.cpp and the flags defined in
  the src/menus sources, ui-reference.md §1.2).

**********************************************************************/
#pragma once

#include <cstdint>

#include "Json.h"

class AudacityProject;
class Track;
enum class sampleFormat : unsigned;

namespace aubridge {

//! Bits of snapshot.flags (API.md §4.2).  ABI: never reorder, only append.
namespace Flag {
inline constexpr uint64_t NB = 1ull << 0;
inline constexpr uint64_t BUSY = 1ull << 1;
inline constexpr uint64_t TS = 1ull << 2;
inline constexpr uint64_t WS = 1ull << 3;
inline constexpr uint64_t TE = 1ull << 4;
inline constexpr uint64_t ES = 1ull << 5;
inline constexpr uint64_t AS = 1ull << 6;
inline constexpr uint64_t CNB = 1ull << 7;
inline constexpr uint64_t LE = 1ull << 8;
inline constexpr uint64_t UA = 1ull << 9;
inline constexpr uint64_t RA = 1ull << 10;
inline constexpr uint64_t ZI = 1ull << 11;
inline constexpr uint64_t ZO = 1ull << 12;
inline constexpr uint64_t WE = 1ull << 13;
inline constexpr uint64_t SL = 1ull << 14;
inline constexpr uint64_t NSL = 1ull << 15;
inline constexpr uint64_t ST = 1ull << 16;
inline constexpr uint64_t PAUSED = 1ull << 17;
inline constexpr uint64_t CS = 1ull << 18;
inline constexpr uint64_t CC = 1ull << 19;
inline constexpr uint64_t JC = 1ull << 20;
inline constexpr uint64_t LS = 1ull << 21;
inline constexpr uint64_t HW = 1ull << 22;
inline constexpr uint64_t LAST_EFF = 1ull << 23;
inline constexpr uint64_t LAST_GEN = 1ull << 24;
inline constexpr uint64_t LAST_ANA = 1ull << 25;
inline constexpr uint64_t LAST_TOOL = 1ull << 26;
inline constexpr uint64_t TFOCUS = 1ull << 27;
inline constexpr uint64_t CLIPSEL = 1ull << 28;
inline constexpr uint64_t STRETCHSEL = 1ull << 29;
inline constexpr uint64_t PLAYABLE = 1ull << 30;
inline constexpr uint64_t NO_TIMETRACK = 1ull << 31;
inline constexpr uint64_t FOC = 1ull << 32;
inline constexpr uint64_t LABEL_TEXT_SEL = 1ull << 33;
inline constexpr uint64_t PROJECT_OPEN = 1ull << 34;
inline constexpr uint64_t CLIPBOARD = 1ull << 35;
inline constexpr uint64_t RECORD_PERMISSION = 1ull << 36;
}

//! Flag bits computed from the project (no LAST_* bits).  Engine thread.
uint64_t ComputeCommandFlags(AudacityProject *project);

//! Builds the whole snapshot of the current session (also without a
//! project: `project.open == false`, no tracks).  Engine thread.
json BuildSnapshot();

//! `ProjectInfo` (API.md §5.2) of the current session
json BuildProjectInfo();

//! /SamplingRate/DefaultProjectSampleRate without evaluating the setting's
//! default function (QualitySettings::DefaultSampleRate.Read() probes the
//! audio devices on every call when the key is unset)
long DefaultProjectRate();

//! Track kind string: "wave", "label", "time", "note", "other"
const char *TrackKind(const Track &track);

//! Sample format <-> API string ("int16", "int24", "float")
const char *FormatName(sampleFormat format);
//! @return false for an unknown name
bool ParseFormat(const std::string &name, sampleFormat &format);

} // namespace aubridge
