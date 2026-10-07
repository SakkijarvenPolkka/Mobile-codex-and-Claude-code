/*  SPDX-License-Identifier: GPL-2.0-or-later */
/**********************************************************************

  Audacity Android port

  Session.h

  The engine's state on the engine thread: start configuration, paths,
  the (single) open project, the model generation and deferred snapshots.

**********************************************************************/
#pragma once

#include <atomic>
#include <cstdint>
#include <memory>
#include <string>

#include "Json.h"

class AudacityProject;

namespace aubridge {

class ProjectSession;

//! Directories derived from the start configuration (API.md §2.1).
//! All are absolute, UTF-8, without trailing slash.
struct Paths {
   std::string filesDir, noBackupDir, cacheDir;
   std::string nyquistDir, pluginsDir;
   std::string configDir;    //!< filesDir/audacity: audacity.cfg, plug-in registry (= FileNames::ConfigDir/DataDir)
   std::string sessionDir;   //!< noBackupDir/SessionData: unsaved projects, autosave (= TempDirectory::TempDir)
   std::string tmpDir;       //!< cacheDir/tmp: TMPDIR, SQLITE_TMPDIR
   std::string importDir;    //!< cacheDir/import: staged import files
   std::string exportDir;    //!< cacheDir/export: export staging
   std::string projectsDir;  //!< filesDir/Projects: saved projects (project.list)
};

//! Parsed start configuration (API.md §2.1)
struct StartConfig {
   Paths paths;
   std::string locale = "en";
   std::string deviceModel;
   int audioOutputSampleRate = 0;
   int audioFramesPerBuffer = 0;
   bool recordPermission = false;
   json raw;               //!< the whole configuration object, for modules

   //! @throws BridgeError{INVALID_ARGS} on a malformed configuration
   static StartConfig Parse(const std::string &configJson);
};

class Session final {
public:
   static Session &Get();

   //! The open project, or null.  Engine thread.
   AudacityProject *Project();
   //! @throws BridgeError{NO_PROJECT}
   AudacityProject &RequireProject();
   ProjectSession *Current() { return mCurrent.get(); }

   //! Model generation (API.md §3.1); 0 when no project is open.  Any thread.
   uint64_t Generation() const;
   //! Bump the generation and schedule a `snapshot` event (emitted after the
   //! running command, or within ~200 ms when called outside a command).
   //! Engine thread.
   void Touch();
   //! Schedule a `snapshot` event without bumping the generation
   void ScheduleSnapshot();
   bool SnapshotPending() const { return mSnapshotPending; }
   //! Build and emit the `snapshot` event now; returns it
   json EmitSnapshot();

   const Paths &GetPaths() const { return mConfig.paths; }
   const StartConfig &Config() const { return mConfig; }

   //! RECORD_AUDIO permission (start config, then `audio.permission`)
   bool RecordPermission() const { return mRecordPermission.load(); }
   void SetRecordPermission(bool granted);

   // ---- spine internals ------------------------------------------------
   void SetConfig(StartConfig config);
   //! Closes the current project (if any) and makes `session` current; runs
   //! the modules' ProjectOpened hooks
   void SetCurrent(std::unique_ptr<ProjectSession> session);
   std::unique_ptr<ProjectSession> TakeCurrent();
   //! Close the current project without saving (no-op without one)
   void CloseCurrent(bool force = true);
   //! Emits a pending snapshot if at least `minIntervalMs` passed since the
   //! last one (used by the tick)
   void FlushPendingSnapshot(int minIntervalMs);
   void Reset();

private:
   Session() = default;
   StartConfig mConfig;
   std::unique_ptr<ProjectSession> mCurrent;
   std::atomic<uint64_t> mGeneration{ 0 };
   std::atomic<bool> mRecordPermission{ false };
   bool mSnapshotPending = false;
   int64_t mLastSnapshotMs = 0;
};

//! Rolls back the current project to its last undo state (after a failed
//! operation), like AudacityApp::OnExceptionInMainLoop.  Engine thread.
void RollbackCurrentProject() noexcept;

} // namespace aubridge
