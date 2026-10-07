/*  SPDX-License-Identifier: GPL-2.0-or-later */
/**********************************************************************

  Audacity Android port

  ModuleRegistry.cpp

**********************************************************************/
#include "ModuleRegistry.h"

#include "EngineThread.h"
#include "Events.h"

namespace aubridge {

namespace {
template<typename Fn, typename... Args>
void RunAll(const std::vector<Fn> &hooks, const char *what, Args &&...args)
{
   for (const auto &hook : hooks) {
      try {
         hook(std::forward<Args>(args)...);
      }
      catch (const std::exception &e) {
         Events::Log(Events::LogLevel::Error,
            std::string(what) + " hook failed: " + e.what());
      }
      catch (...) {
         Events::Log(Events::LogLevel::Error,
            std::string(what) + " hook failed");
      }
   }
}
} // namespace

ModuleRegistry &ModuleRegistry::Get()
{
   static ModuleRegistry registry;
   return registry;
}

void ModuleRegistry::AddCommand(const std::string &name, CommandHandler handler,
   unsigned flags)
{
   mCommands[name] = CommandSpec{ std::move(handler), flags };
}

void ModuleRegistry::AddBeforePluginManagerInit(std::function<void()> hook)
{ mBeforePluginManager.push_back(std::move(hook)); }

void ModuleRegistry::AddBeforeImportExportInit(std::function<void()> hook)
{ mBeforeImportExport.push_back(std::move(hook)); }

void ModuleRegistry::AddAfterBootstrap(std::function<void()> hook)
{ mAfterBootstrap.push_back(std::move(hook)); }

void ModuleRegistry::AddBeforeShutdown(std::function<void()> hook)
{ mBeforeShutdown.push_back(std::move(hook)); }

void ModuleRegistry::AddProjectOpened(ProjectHook hook)
{ mProjectOpened.push_back(std::move(hook)); }

void ModuleRegistry::AddProjectClosing(ProjectHook hook)
{ mProjectClosing.push_back(std::move(hook)); }

void ModuleRegistry::AddTickHandler(std::function<void()> handler)
{
   mTick.push_back(handler);
   // Registered after the loop started (e.g. from a command): add it now
   if (EngineThread::IsCurrent())
      EngineThread::Get().AddTickHandler(std::move(handler));
}

void ModuleRegistry::AddSnapshotContributor(SnapshotContributor contributor)
{ mContributors.push_back(std::move(contributor)); }

const CommandSpec *ModuleRegistry::FindCommand(const std::string &name) const
{
   auto it = mCommands.find(name);
   return it == mCommands.end() ? nullptr : &it->second;
}

std::vector<std::string> ModuleRegistry::CommandNames() const
{
   std::vector<std::string> names;
   for (const auto &[name, spec] : mCommands)
      names.push_back(name);
   return names;
}

void ModuleRegistry::RunBeforePluginManagerInit() const
{ RunAll(mBeforePluginManager, "BeforePluginManagerInit"); }

void ModuleRegistry::RunBeforeImportExportInit() const
{ RunAll(mBeforeImportExport, "BeforeImportExportInit"); }

void ModuleRegistry::RunAfterBootstrap() const
{ RunAll(mAfterBootstrap, "AfterBootstrap"); }

void ModuleRegistry::RunBeforeShutdown() const
{ RunAll(mBeforeShutdown, "BeforeShutdown"); }

void ModuleRegistry::RunProjectOpened(AudacityProject &project) const
{ RunAll(mProjectOpened, "ProjectOpened", project); }

void ModuleRegistry::RunProjectClosing(AudacityProject &project) const
{ RunAll(mProjectClosing, "ProjectClosing", project); }

void ModuleRegistry::RunSnapshotContributors(json &snapshot,
   AudacityProject &project) const
{ RunAll(mContributors, "SnapshotContributor", snapshot, project); }

void ModuleRegistry::Clear()
{
   mCommands.clear();
   mBeforePluginManager.clear();
   mBeforeImportExport.clear();
   mAfterBootstrap.clear();
   mBeforeShutdown.clear();
   mTick.clear();
   mProjectOpened.clear();
   mProjectClosing.clear();
   mContributors.clear();
}

} // namespace aubridge
