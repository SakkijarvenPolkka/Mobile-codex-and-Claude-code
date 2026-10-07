/*  SPDX-License-Identifier: GPL-2.0-or-later */
/**********************************************************************

  Audacity Android port

  Dispatcher.cpp

  Command execution on the engine thread: argument parsing, precondition
  flags, exception -> envelope mapping (API.md §3.1), snapshot emission.

  Exceptions (like AudacityApp::OnExceptionInMainLoop, but synchronous and
  reported in the envelope instead of a message box):
   * BridgeError         -> its code (no rollback by the dispatcher; RunEdit
                            already rolled back if it was inside one)
   * UserException       -> CANCELLED (+ rollback for Mutates commands)
   * AudacityException   -> FAILED with the exception's message
                            (+ rollback for Mutates commands)
   * std::exception, ... -> INTERNAL (+ rollback for Mutates commands)

**********************************************************************/
#include "Engine.h"

#include "AudacityException.h"
#include "Edit.h"
#include "Hooks.h"
#include "EngineThread.h"
#include "ModuleRegistry.h"
#include "Session.h"
#include "UserException.h"

namespace aubridge {
namespace Engine {

namespace {

// MessageBoxException::ErrorMessage() and ::caption are protected; reach
// them through a pointer to member named via a derived class
struct MessageAccess : MessageBoxException {
   static TranslatableString Message(const MessageBoxException &e)
   {
      auto pm = &MessageAccess::ErrorMessage;
      return (e.*pm)();
   }
};

std::string ExceptionMessage(const AudacityException &e)
{
   if (auto p = dynamic_cast<const MessageBoxException *>(&e)) {
      auto message = Translated(MessageAccess::Message(*p));
      if (!message.empty())
         return message;
   }
   return "The operation failed";
}

void RollbackIfMutating(unsigned flags)
{
   if (flags & Mutates)
      RollbackCurrentProject();
}

} // namespace

std::string Dispatch(const std::string &command, const std::string &argsJson)
{
   auto &session = Session::Get();
   const auto *spec = ModuleRegistry::Get().FindCommand(command);
   const unsigned flags = spec ? spec->flags : 0;
   json envelope;
   try {
      if (!spec)
         Fail(ErrorCode::UNKNOWN_COMMAND, "unknown command '" + command + "'");
      json args;
      try {
         args = argsJson.empty() ? json::object() : json::parse(argsJson);
      }
      catch (const std::exception &e) {
         Fail(ErrorCode::INVALID_ARGS,
            std::string("arguments are not valid JSON: ") + e.what());
      }
      if (args.is_null())
         args = json::object();
      if (!args.is_object())
         Fail(ErrorCode::INVALID_ARGS, "arguments must be a JSON object");

      auto *project = session.Project();
      if (project && (flags & NeedsIdleAudio))
         FinalizeDrainedStream(*project);
      if ((flags & NeedsProject) && !project)
         Fail(ErrorCode::NO_PROJECT, "no project is open");
      if ((flags & NeedsIdleAudio) && project && AudioBusy(*project))
         Fail(ErrorCode::AUDIO_BUSY,
            "You can only do this when playing and recording are stopped.");

      json result = spec->handler(args);
      if (result.is_null())
         result = json::object();
      if (flags & Mutates)
         session.Touch();
      else if (flags & SelectionOnly)
         session.ScheduleSnapshot();
      envelope = json{ { "ok", true }, { "result", std::move(result) } };
   }
   catch (const BridgeError &e) {
      envelope = ErrorEnvelope(e.code, e.what());
   }
   catch (const UserException &) {
      RollbackIfMutating(flags);
      envelope = ErrorEnvelope(ErrorCode::CANCELLED, "Cancelled");
   }
   catch (const AudacityException &e) {
      RollbackIfMutating(flags);
      envelope = ErrorEnvelope(ErrorCode::FAILED, ExceptionMessage(e));
   }
   catch (const std::exception &e) {
      RollbackIfMutating(flags);
      envelope = ErrorEnvelope(ErrorCode::INTERNAL, e.what());
   }
   catch (...) {
      RollbackIfMutating(flags);
      envelope = ErrorEnvelope(ErrorCode::INTERNAL, "unknown exception");
   }

   // Work the command queued with CallAfter (TrackList/UndoManager
   // messages, ...), then the snapshot BEFORE the response (API.md §3.1)
   EngineThread::Get().DrainInternal();
   if (session.SnapshotPending())
      session.EmitSnapshot();
   envelope["generation"] = session.Generation();
   return Dump(envelope);
}

} // namespace Engine
} // namespace aubridge
