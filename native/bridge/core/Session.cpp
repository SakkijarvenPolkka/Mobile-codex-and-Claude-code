/*  SPDX-License-Identifier: GPL-2.0-or-later */
/**********************************************************************

  Audacity Android port

  Session.cpp

**********************************************************************/
#include "Session.h"

#include <chrono>

#include "Events.h"
#include "ModuleRegistry.h"
#include "PendingTracks.h"
#include "Project.h"
#include "ProjectHistory.h"
#include "ProjectSession.h"
#include "Snapshot.h"

namespace aubridge {

namespace {
std::atomic<bool> sHasProject{ false };

int64_t NowMs()
{
   using namespace std::chrono;
   return duration_cast<milliseconds>(
      steady_clock::now().time_since_epoch()).count();
}

std::string StripSlash(std::string path)
{
   while (path.size() > 1 && path.back() == '/')
      path.pop_back();
   return path;
}

std::string RequirePath(const json &config, const char *key)
{
   auto path = StripSlash(ArgString(config, key));
   if (path.empty() || path[0] != '/')
      Fail(ErrorCode::INVALID_ARGS,
         std::string("start configuration: '") + key +
            "' must be an absolute path");
   return path;
}
} // namespace

StartConfig StartConfig::Parse(const std::string &configJson)
{
   json j;
   try {
      j = json::parse(configJson.empty() ? std::string("{}") : configJson);
   }
   catch (const std::exception &e) {
      Fail(ErrorCode::INVALID_ARGS,
         std::string("start configuration is not valid JSON: ") + e.what());
   }
   if (!j.is_object())
      Fail(ErrorCode::INVALID_ARGS, "start configuration must be an object");

   StartConfig config;
   auto &paths = config.paths;
   paths.filesDir = RequirePath(j, "filesDir");
   paths.noBackupDir = RequirePath(j, "noBackupDir");
   paths.cacheDir = RequirePath(j, "cacheDir");
   paths.configDir = paths.filesDir + "/audacity";
   paths.nyquistDir = StripSlash(OptString(j, "nyquistDir")
      .value_or(paths.configDir + "/nyquist"));
   paths.pluginsDir = StripSlash(OptString(j, "pluginsDir")
      .value_or(paths.configDir + "/plug-ins"));
   paths.sessionDir = paths.noBackupDir + "/SessionData";
   paths.tmpDir = paths.cacheDir + "/tmp";
   paths.importDir = paths.cacheDir + "/import";
   paths.exportDir = paths.cacheDir + "/export";
   paths.projectsDir = paths.filesDir + "/Projects";

   config.locale = OptString(j, "locale").value_or("en");
   config.deviceModel = OptString(j, "deviceModel").value_or("");
   config.audioOutputSampleRate =
      int(OptInt(j, "audioOutputSampleRate").value_or(0));
   config.audioFramesPerBuffer =
      int(OptInt(j, "audioFramesPerBuffer").value_or(0));
   config.recordPermission = OptBool(j, "recordPermission").value_or(false);
   config.raw = std::move(j);
   return config;
}

Session &Session::Get()
{
   static Session instance;
   return instance;
}

AudacityProject *Session::Project()
{
   return mCurrent && !mCurrent->IsClosed() ? &mCurrent->Project() : nullptr;
}

AudacityProject &Session::RequireProject()
{
   if (auto project = Project())
      return *project;
   Fail(ErrorCode::NO_PROJECT, "no project is open");
}

uint64_t Session::Generation() const
{
   return sHasProject.load() ? mGeneration.load() : 0;
}

void Session::Touch()
{
   ++mGeneration;
   mSnapshotPending = true;
}

void Session::ScheduleSnapshot()
{
   mSnapshotPending = true;
}

json Session::EmitSnapshot()
{
   mSnapshotPending = false;
   mLastSnapshotMs = NowMs();
   auto snapshot = BuildSnapshot();
   Events::Emit("snapshot", snapshot);
   return snapshot;
}

void Session::FlushPendingSnapshot(int minIntervalMs)
{
   if (mSnapshotPending && NowMs() - mLastSnapshotMs >= minIntervalMs)
      EmitSnapshot();
}

void Session::SetRecordPermission(bool granted)
{
   if (mRecordPermission.exchange(granted) != granted)
      ScheduleSnapshot();
}

void Session::SetConfig(StartConfig config)
{
   mConfig = std::move(config);
   mRecordPermission = mConfig.recordPermission;
}

void Session::SetCurrent(std::unique_ptr<ProjectSession> session)
{
   if (mCurrent)
      CloseCurrent();
   mCurrent = std::move(session);
   sHasProject = Project() != nullptr;
   Touch();
   if (auto project = Project()) {
      mCurrent->MarkAnnounced();
      ModuleRegistry::Get().RunProjectOpened(*project);
   }
}

std::unique_ptr<ProjectSession> Session::TakeCurrent()
{
   auto result = std::move(mCurrent);
   sHasProject = false;
   ScheduleSnapshot();
   return result;
}

void Session::CloseCurrent(bool)
{
   if (!mCurrent)
      return;
   // Hooks still see the project as current while it closes
   mCurrent->Close();
   mCurrent.reset();
   sHasProject = false;
   ++mGeneration;
   ScheduleSnapshot();
}

void Session::Reset()
{
   CloseCurrent();
   mSnapshotPending = false;
   mConfig = {};
}

void RollbackCurrentProject() noexcept
{
   try {
      if (auto project = Session::Get().Project()) {
         ProjectHistory::Get(*project).RollbackState();
         PendingTracks::Get(*project).ClearPendingTracks();
         Session::Get().Touch();
      }
   }
   catch (...) {
   }
}

} // namespace aubridge
