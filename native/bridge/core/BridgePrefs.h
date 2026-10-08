/*  SPDX-License-Identifier: GPL-2.0-or-later */
/**********************************************************************

  Audacity Android port

  BridgePrefs.h

  Preferences of the Android port that have no Setting object in the
  libraries.  A Setting<T> caches its value: every reader of these keys must
  use the objects below (or read gPrefs directly), never a second Setting
  object for the same key.

**********************************************************************/
#pragma once

#include "Prefs.h"

namespace aubridge {

//! /Android/AAudio/UserLatencyTrimMs (default 0): the user's trim in
//! milliseconds, added by the audio module to the measured duplex offset
//! when it writes /AudioIO/LatencyCorrection before a recording
//! (API.md §5.1 `latencyCorrectionMs`)
extern DoubleSetting AudioUserLatencyTrimMs;

} // namespace aubridge
