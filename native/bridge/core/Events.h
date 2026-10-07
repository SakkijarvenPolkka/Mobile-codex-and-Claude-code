/*  SPDX-License-Identifier: GPL-2.0-or-later */
/**********************************************************************

  Audacity Android port

  Events.h

  Native -> Kotlin events (API.md §4).  Thread-safe; events are normally
  emitted on the engine thread.

**********************************************************************/
#pragma once

#include <memory>
#include <string>

#include "Json.h"

namespace aubridge {
class EventSink;

namespace Events {

//! Deliver an event to the sink installed by Start().  Any thread; a no-op
//! when no sink is installed.  Must not be called while holding locks that
//! the sink's thread could need.
void Emit(const std::string &type, const json &payload);

enum class LogLevel { Debug, Info, Warning, Error };
//! `log` event (rate limited, API.md §4.6) + logcat on Android + stderr on
//! the host when AUBRIDGE_LOG_STDERR is set in the environment.
void Log(LogLevel level, const std::string &message);

// ---- spine internals --------------------------------------------------
void SetSink(std::shared_ptr<EventSink> sink);
std::shared_ptr<EventSink> GetSink();

} // namespace Events
} // namespace aubridge
