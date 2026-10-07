/*  SPDX-License-Identifier: GPL-2.0-or-later */
/**********************************************************************

  Audacity Android port

  ModuleRegistry.h

  What a feature module can register (MODULES.md "Module hooks"):
  commands, bootstrap hooks, project hooks, tick handlers and snapshot
  contributors.  Everything is called on the engine thread.

**********************************************************************/
#pragma once

#include <functional>
#include <map>
#include <string>
#include <vector>

#include "Json.h"

class AudacityProject;

namespace aubridge {

//! Command flags (MODULES.md); combine with |
enum CommandFlag : unsigned {
   NoFlags        = 0,
   //! Fails with NO_PROJECT unless a project is open
   NeedsProject   = 1u << 0,
   //! Fails with AUDIO_BUSY while the project plays/records (API "not I")
   NeedsIdleAudio = 1u << 1,
   //! Changes the model: the spine bumps the generation and emits a
   //! snapshot after the handler (also after a failure with rollback)
   Mutates        = 1u << 2,
   //! Selection/view change: snapshot after the handler, no generation bump
   SelectionOnly  = 1u << 3,
   //! Informational: may emit progress events
   LongRunning    = 1u << 4,
};

//! Handler: args is the JSON object sent by Kotlin; return the `result`
//! object (return json::object() for `{}`).  Throw BridgeError for error
//! envelopes; AudacityException/std::exception are mapped by the spine.
using CommandHandler = std::function<json(const json &args)>;
using ProjectHook = std::function<void(AudacityProject &)>;
//! Called when a snapshot is built (only while a project is open), after
//! the spine filled every field (incl. "flags": a contributor may OR in
//! bits).  Set e.g. snapshot["lastEffect"] = {{"id",..},{"name",..}}; the
//! spine derives the LAST_* flag bits from lastEffect/lastGenerator/
//! lastAnalyzer/lastTool afterwards.
using SnapshotContributor = std::function<void(json &snapshot, AudacityProject &)>;

struct CommandSpec {
   CommandHandler handler;
   unsigned flags = NoFlags;
};

class ModuleRegistry final {
public:
   static ModuleRegistry &Get();

   //! Registers (or replaces) a command
   void AddCommand(const std::string &name, CommandHandler handler,
      unsigned flags = NoFlags);

   //! Bootstrap hooks, in the order they run:
   //! before PluginManager::Initialize (register effect providers/effects)
   void AddBeforePluginManagerInit(std::function<void()> hook);
   //! before Importer::Initialize / ExportPluginRegistry::Initialize
   //! (register Android codec import/export plug-ins)
   void AddBeforeImportExportInit(std::function<void()> hook);
   //! after everything is initialized, before `engine.ready`
   void AddAfterBootstrap(std::function<void()> hook);
   //! at Stop(), after the project was closed, before library teardown
   void AddBeforeShutdown(std::function<void()> hook);

   //! After a project was created/opened (session fully set up)
   void AddProjectOpened(ProjectHook hook);
   //! Before a project is closed (still fully usable)
   void AddProjectClosing(ProjectHook hook);

   //! Called every ~50 ms on the outermost engine loop
   void AddTickHandler(std::function<void()> handler);

   void AddSnapshotContributor(SnapshotContributor contributor);

   // ---- spine -----------------------------------------------------------
   const CommandSpec *FindCommand(const std::string &name) const;
   std::vector<std::string> CommandNames() const;
   void RunBeforePluginManagerInit() const;
   void RunBeforeImportExportInit() const;
   void RunAfterBootstrap() const;
   void RunBeforeShutdown() const;
   void RunProjectOpened(AudacityProject &project) const;
   void RunProjectClosing(AudacityProject &project) const;
   void RunSnapshotContributors(json &snapshot, AudacityProject &project) const;
   const std::vector<std::function<void()>> &TickHandlers() const
   { return mTick; }
   void Clear();

private:
   ModuleRegistry() = default;
   std::map<std::string, CommandSpec> mCommands;
   std::vector<std::function<void()>> mBeforePluginManager, mBeforeImportExport,
      mAfterBootstrap, mBeforeShutdown, mTick;
   std::vector<ProjectHook> mProjectOpened, mProjectClosing;
   std::vector<SnapshotContributor> mContributors;
};

} // namespace aubridge
