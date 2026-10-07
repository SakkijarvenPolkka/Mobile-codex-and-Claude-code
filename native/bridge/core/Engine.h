/*  SPDX-License-Identifier: GPL-2.0-or-later */
/**********************************************************************

  Audacity Android port

  Engine.h

  Engine lifecycle: Start() creates the engine thread, whose bootstrap is
  the headless replacement of AudacityApp::OnInit (init-and-project.md §6);
  Stop() closes the project and tears the libraries down
  (AudacityApp::OnExit order).

**********************************************************************/
#pragma once

#include <memory>
#include <string>

#include "Json.h"

namespace aubridge {
class EventSink;

namespace Engine {

bool Start(const std::string &configJson, std::shared_ptr<EventSink> sink);
void Stop();
bool IsReady();

//! Posts the command to the engine thread and waits (API.md §3.1)
std::string Invoke(const std::string &command, const std::string &argsJson);

//! Executes a command on the engine thread; returns the envelope
std::string Dispatch(const std::string &command, const std::string &argsJson);

//! AppEvents idle handlers (BasicUI::ProcessIdle, engine tick)
void HandleIdle();

//! `{"ok":false,...}` envelope
json ErrorEnvelope(const std::string &code, const std::string &message);

//! Self checks of the last bootstrap (engine.ready payload "selfChecks")
const json &SelfChecks();

} // namespace Engine
} // namespace aubridge
