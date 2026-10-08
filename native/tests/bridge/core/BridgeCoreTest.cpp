/*  SPDX-License-Identifier: GPL-2.0-or-later */
/**********************************************************************

  Audacity Android port

  BridgeCoreTest.cpp

  Host test of the bridge spine, driven only through Bridge.h:
   * Invoke before Start -> NOT_READY; Start twice -> false
   * crash recovery: a child process creates an unsaved project with a
     track and dies without Stop(); the engine then reports it in
     engine.ready ("recoverable") / project.recoverable and recovers it
   * error envelopes (UNKNOWN_COMMAND, INVALID_ARGS, NO_PROJECT, NEEDS_PATH)
   * app.info, settings round trip (incl. audacity.cfg on disk)
   * project.new, debug.makeTestTrack, snapshot before response, flags,
     undo/redo with stable track ids, history list/goto, tags with undo
   * saveAs (Unicode file name) -> close -> open round trip, saveCopy,
     list, delete
   * blocking dialogs answered with ReplyDialog, multiChoice dialogs
     answered with ReplyDialogChoices, progress cancel/stop
   * settings added after phase 1 (latency trim, sync-lock, paste/record
     behaviour, dropout detection, language)
   * project.rename (with -wal/-shm), project.compactInfo, project.compact
     (undo history, freed space, dirty flag, reopen)
   * Stop() and a restart in the same process; a restart with locale ko_KR
     and the Korean catalog: engine strings in Korean, `language` setting

  Exit code 0 on success.  BRIDGE_TEST_VERBOSE=1 prints the events.

**********************************************************************/
#include "BridgeTestSupport.h"

#include <atomic>
#include <cmath>
#include <fstream>
#include <sstream>
#include <sys/stat.h>
#include <sys/wait.h>
#include <thread>

using namespace bridgetest;
using namespace std::chrono_literals;

namespace {

bool FileExists(const std::string &path)
{
   struct stat st {};
   return ::stat(path.c_str(), &st) == 0;
}

std::string ReadFile(const std::string &path)
{
   std::ifstream in(path);
   std::stringstream ss;
   ss << in.rdbuf();
   return ss.str();
}

bool WriteFile(const std::string &path, const std::string &contents)
{
   std::ofstream out(path, std::ios::binary | std::ios::trunc);
   out << contents;
   return bool(out);
}

bool CopyFile(const std::string &from, const std::string &to)
{
   std::ifstream in(from, std::ios::binary);
   if (!in)
      return false;
   std::ofstream out(to, std::ios::binary | std::ios::trunc);
   out << in.rdbuf();
   return bool(out);
}

bool MakeDirs(const std::string &path)
{
   return std::system(("mkdir -p '" + path + "'").c_str()) == 0;
}

bool EndsWith(const std::string &s, const std::string &suffix)
{
   return s.size() >= suffix.size() &&
      s.compare(s.size() - suffix.size(), suffix.size(), suffix) == 0;
}

uint64_t Flags(const json &snapshot)
{
   return snapshot.value("flags", uint64_t(0));
}

constexpr uint64_t NB = 1ull << 0, TE = 1ull << 4, UA = 1ull << 9,
   RA = 1ull << 10, WE = 1ull << 13, SL = 1ull << 14, NSL = 1ull << 15,
   HW = 1ull << 22, PROJECT_OPEN = 1ull << 34, FOC = 1ull << 32;

// Korean catalog strings (native/audacity/locale/ko.po)
const std::string kKoCreatedNewProject =
   "\xEC\x83\x88 \xED\x94\x84\xEB\xA1\x9C\xEC\xA0\x9D\xED\x8A\xB8\xEB\xA5\xBC "
   "\xEB\xA7\x8C\xEB\x93\xA4\xEC\x97\x88\xEC\x8A\xB5\xEB\x8B\x88\xEB\x8B\xA4";
   // "새 프로젝트를 만들었습니다" = "Created new project"
const std::string kKoMetadataTags =
   "\xEB\xA9\x94\xED\x83\x80\xEB\x8D\xB0\xEC\x9D\xB4\xED\x84\xB0 "
   "\xED\x83\x9C\xEA\xB7\xB8";
   // "메타데이터 태그" = "Metadata Tags"
const std::string kKoCouldNotOpenFile =
   "\xED\x8C\x8C\xEC\x9D\xBC\xEC\x9D\x84 \xEC\x97\xB4 \xEC\x88\x98 "
   "\xEC\x97\x86\xEC\x8A\xB5\xEB\x8B\x88\xEB\x8B\xA4";
   // "파일을 열 수 없습니다" = "Could not open file"

//! The snapshot emitted by the last call: must exist, be emitted before the
//! response, and carry the response's generation
json SnapshotOf(Sink &sink, size_t before, const json &envelope)
{
   auto snap = sink.Last("snapshot", before);
   CHECK_MSG(snap.has_value(), "no snapshot event before the response");
   if (!snap)
      return json::object();
   CHECK((*snap)["generation"] == envelope["generation"]);
   return *snap;
}

std::optional<json> StartAndWait(Sink &sink, std::shared_ptr<Sink> pSink,
   const TempDirs &dirs, const json &extraConfig = json::object())
{
   const auto from = sink.Count();
   if (!aubridge::Start(dirs.ConfigJson(extraConfig), pSink))
      return std::nullopt;
   auto ready = sink.WaitFor("engine.ready", 180s, from);
   if (!ready) {
      auto failed = sink.Last("engine.failed", from);
      std::fprintf(stderr, "engine failed: %s\n",
         failed ? failed->dump().c_str() : "(timeout)");
   }
   return ready;
}

// ---------------------------------------------------------------------------
// Crash child: create an unsaved project with a track, then die
// ---------------------------------------------------------------------------
int CrashChild(const std::string &root)
{
   TempDirs dirs{ root };
   auto sink = std::make_shared<Sink>();
   auto ready = StartAndWait(*sink, sink, dirs);
   if (!ready)
      return 2;
   if (!Ok(Call("project.info")))
      return 3;
   if (!Call("project.info")["result"].value("open", false) &&
       !Ok(Call("project.new")))
      return 4;
   auto r = Call("debug.makeTestTrack", { { "seconds", 2.0 },
      { "frequency", 220.0 }, { "channels", 1 } });
   if (!Ok(r))
      return 5;
   // Simulated crash: no Stop(), no destructors
   std::fflush(nullptr);
   std::_Exit(0);
}

// ---------------------------------------------------------------------------
void TestNoProjectErrors()
{
   auto r = Call("no.such.command");
   CHECK(ErrorCodeOf(r) == "UNKNOWN_COMMAND");
   CHECK(r.contains("generation"));

   auto text = aubridge::Invoke("project.info", "{not json");
   auto bad = json::parse(text, nullptr, false);
   CHECK(ErrorCodeOf(bad) == "INVALID_ARGS");

   auto arr = Call("project.info", json::array({ 1, 2 }));
   CHECK(ErrorCodeOf(arr) == "INVALID_ARGS");

   r = Call("debug.makeTestTrack");
   CHECK(ErrorCodeOf(r) == "NO_PROJECT");
   r = Call("history.undo");
   CHECK(ErrorCodeOf(r) == "NO_PROJECT");
   r = Call("project.info");
   CHECK(Ok(r) && !r["result"]["open"].get<bool>());
   CHECK(r["generation"].get<uint64_t>() == 0);
}

void TestAppInfo()
{
   auto r = Call("app.info");
   CHECK(Ok(r));
   const auto &info = r["result"];
   CHECK(info.value("audacityVersion", "") == "3.7.9");
   CHECK(!info.value("wxVersion", "").empty());
   CHECK(!info.value("sqliteVersion", "").empty());
   CHECK(info["libraries"].size() > 50);
   CHECK(info["importers"].size() >= 5);
   CHECK(info["exporters"].size() >= 5);
   // locale en_US, no catalog installed yet
   CHECK(info.value("language", "") == "en");
   CHECK(info["languages"] == json::array({ "en" }));
   std::fprintf(stderr, "app.info: %zu libraries, %zu importers, %zu exporters, "
      "%d effects, abi %s\n", info["libraries"].size(), info["importers"].size(),
      info["exporters"].size(), info.value("effectsCount", -1),
      info.value("abi", "").c_str());
}

void TestRecovery(Sink &sink, const TempDirs &dirs)
{
   auto r = Call("project.recoverable");
   CHECK(Ok(r));
   const auto &projects = r["result"]["projects"];
   CHECK_MSG(projects.size() == 1, r.dump());
   if (projects.size() != 1)
      return;
   const auto path = projects[0].value("path", "");
   CHECK(EndsWith(path, ".aup3unsaved"));
   CHECK(path.rfind(dirs.noBackupDir + "/SessionData/", 0) == 0);
   CHECK(projects[0].value("sizeBytes", 0ll) > 0);

   const auto before = sink.Count();
   r = Call("project.recover", { { "path", path } });
   CHECK_MSG(Ok(r), r.dump());
   auto snap = SnapshotOf(sink, before, r);
   CHECK(snap["project"].value("open", false));
   CHECK(snap["project"].value("temporary", false));
   CHECK(snap["project"].value("dirty", false));
   CHECK(snap["tracks"].size() == 1);
   if (snap["tracks"].size() == 1) {
      CHECK(snap["tracks"][0].value("name", "").rfind("Test Tone", 0) == 0);
      CHECK(std::fabs(snap["tracks"][0].value("end", 0.0) - 2.0) < 1e-6);
   }
   // "Project was recovered" replaced "Created new project"
   auto h = Call("history.list");
   CHECK(Ok(h));
   CHECK(h["result"]["states"].size() == 1);
   if (!h["result"]["states"].empty())
      CHECK(h["result"]["states"][0].value("shortDescription", "") == "Recover");

   // The open project is not offered for recovery
   r = Call("project.recoverable");
   CHECK(Ok(r) && r["result"]["projects"].empty());

   // Closing a temporary project deletes it: nothing left to recover
   CHECK(Ok(Call("project.close")));
   CHECK(!FileExists(path));
   r = Call("project.recoverable");
   CHECK(Ok(r) && r["result"]["projects"].empty());
}

void TestNewProject(Sink &sink)
{
   const auto before = sink.Count();
   auto r = Call("project.new");
   CHECK(Ok(r));
   auto snap = SnapshotOf(sink, before, r);
   CHECK(snap["project"].value("open", false));
   CHECK(snap["project"].value("temporary", false));
   CHECK(!snap["project"].value("dirty", true));
   CHECK(snap["project"]["path"].is_null());
   CHECK(snap["tracks"].empty());
   CHECK(Flags(snap) & PROJECT_OPEN);
   CHECK(Flags(snap) & NB);
   CHECK(Flags(snap) & FOC);
   CHECK(!(Flags(snap) & TE));
   CHECK(!(Flags(snap) & UA));
   CHECK(r["generation"].get<uint64_t>() > 0);

   r = Call("project.save");
   CHECK(ErrorCodeOf(r) == "NEEDS_PATH");
}

void TestTrackAndHistory(Sink &sink)
{
   auto before = sink.Count();
   auto r = Call("debug.makeTestTrack", { { "seconds", 1.0 },
      { "frequency", 440.0 }, { "channels", 2 }, { "rate", 44100 } });
   CHECK_MSG(Ok(r), r.dump());
   const auto id = r["result"].value("id", int64_t(-1));
   CHECK(id >= 0);
   auto snap = SnapshotOf(sink, before, r);
   const auto gen1 = r["generation"].get<uint64_t>();
   CHECK(snap["tracks"].size() == 1);
   if (snap["tracks"].size() != 1)
      return;
   const auto track = snap["tracks"][0];
   CHECK(track.value("id", int64_t(-2)) == id);
   CHECK(track.value("kind", "") == "wave");
   CHECK(track.value("channels", 0) == 2);
   CHECK(track.value("rate", 0.0) == 44100.0);
   CHECK(std::fabs(track.value("end", 0.0) - 1.0) < 1e-6);
   CHECK(track["clips"].size() == 1);
   CHECK(track.value("waveVersion", int64_t(-1)) >= 0);
   const auto waveVersion = track.value("waveVersion", int64_t(-1));
   CHECK(snap["history"].value("canUndo", false));
   CHECK(snap["history"].value("undo", "") == "Test Tone");
   const auto flags = Flags(snap);
   CHECK((flags & (TE | WE | UA | HW | PROJECT_OPEN | NB)) ==
      (TE | WE | UA | HW | PROJECT_OPEN | NB));
   CHECK(!(flags & RA));
   CHECK(snap["project"].value("dirty", false));

   // Undo: the track disappears
   before = sink.Count();
   r = Call("history.undo");
   CHECK(Ok(r));
   snap = SnapshotOf(sink, before, r);
   CHECK(snap["tracks"].empty());
   CHECK(Flags(snap) & RA);
   CHECK(r["generation"].get<uint64_t>() > gen1);

   // Redo: same TrackId (UndoTracks patch) and same waveVersion
   before = sink.Count();
   r = Call("history.redo");
   CHECK(Ok(r));
   snap = SnapshotOf(sink, before, r);
   CHECK(snap["tracks"].size() == 1);
   if (snap["tracks"].size() == 1) {
      CHECK_MSG(snap["tracks"][0].value("id", int64_t(-2)) == id,
         "track id changed across undo/redo");
      CHECK(snap["tracks"][0].value("waveVersion", int64_t(-2)) == waveVersion);
   }

   // History list
   r = Call("history.list");
   CHECK(Ok(r));
   const auto &states = r["result"]["states"];
   CHECK(states.size() == 2);
   CHECK(r["result"].value("current", -1) == 1);
   if (states.size() == 2) {
      CHECK(states[0].value("shortDescription", "x").empty() ||
            states[0].value("description", "") == "Created new project");
      CHECK(states[1].value("shortDescription", "") == "Test Tone");
      CHECK(states[1].value("sizeBytes", 0ll) > 0);
   }
   // goto 0 / 1
   CHECK(Ok(Call("history.goto", { { "index", 0 } })));
   r = Call("project.snapshot");
   CHECK(Ok(r) && r["result"]["tracks"].empty());
   CHECK(Ok(Call("history.goto", { { "index", 1 } })));
   r = Call("project.snapshot");
   CHECK(Ok(r) && r["result"]["tracks"].size() == 1);
   if (r["result"]["tracks"].size() == 1)
      CHECK(r["result"]["tracks"][0].value("id", int64_t(-2)) == id);
   CHECK(ErrorCodeOf(Call("history.goto", { { "index", 99 } })) == "INVALID_ARGS");

   // A second track, then purge the oldest state
   CHECK(Ok(Call("debug.makeTestTrack", { { "seconds", 0.5 } })));
   r = Call("history.purge", { { "keepFrom", 1 } });
   CHECK(Ok(r));
   r = Call("history.list");
   CHECK(Ok(r) && r["result"]["states"].size() == 2 &&
      r["result"].value("current", -1) == 1);
   r = Call("project.snapshot");
   CHECK(Ok(r) && r["result"]["tracks"].size() == 2);
}

void TestView(Sink &sink)
{
   const auto before = sink.Count();
   auto r = Call("view.set", { { "zoom", 100.0 }, { "hpos", 2.5 } });
   CHECK(Ok(r));
   auto snap = SnapshotOf(sink, before, r);
   CHECK(snap["view"].value("zoom", 0.0) == 100.0);
   CHECK(snap["view"].value("hpos", 0.0) == 2.5);
   CHECK(ErrorCodeOf(Call("view.set", { { "zoom", -1 } })) == "INVALID_ARGS");

   r = Call("project.setRate", { { "rate", 48000 } });
   CHECK(Ok(r));
   r = Call("project.snapshot");
   CHECK(r["result"]["project"].value("rate", 0.0) == 48000.0);
   CHECK(ErrorCodeOf(Call("project.setRate", { { "rate", "x" } })) == "INVALID_ARGS");
}

void TestTags()
{
   auto r = Call("project.tags.set", { { "tags", json::array({
      { { "name", "TITLE" }, { "value", "Hello \xF0\x9F\x8E\xB5" } },
      { { "name", "ARTIST" }, { "value", "Tester" } } }) } });
   CHECK_MSG(Ok(r), r.dump());
   const auto find = [](const json &tags, const std::string &name) {
      for (const auto &t : tags)
         if (t.value("name", "") == name)
            return t.value("value", "");
      return std::string("<missing>");
   };
   r = Call("project.tags.get");
   CHECK(Ok(r));
   CHECK(find(r["result"]["tags"], "TITLE") == "Hello \xF0\x9F\x8E\xB5");
   CHECK(find(r["result"]["tags"], "ARTIST") == "Tester");
   CHECK(Ok(Call("history.undo")));
   r = Call("project.tags.get");
   CHECK(find(r["result"]["tags"], "TITLE") == "<missing>");
   CHECK(Ok(Call("history.redo")));
   r = Call("project.tags.get");
   CHECK(find(r["result"]["tags"], "TITLE") == "Hello \xF0\x9F\x8E\xB5");
   CHECK(ErrorCodeOf(Call("project.tags.set", { { "tags", 3 } })) == "INVALID_ARGS");
}

void TestSettings(const TempDirs &dirs)
{
   auto r = Call("settings.get");
   CHECK(Ok(r));
   const auto original = r["result"]["settings"];
   CHECK(original.contains("defaultRate"));
   CHECK(original.value("defaultRate", 0) == 48000);   // mobile default
   CHECK(original.value("recordChannels", 0) == 1);    // mobile default
   CHECK(original.value("soloMode", "") == "Simple");  // mobile default

   r = Call("settings.set", { { "settings", {
      { "recordChannels", 2 }, { "latencyMs", 80.0 }, { "soloMode", "Multi" },
      { "realtimeDither", "triangle" }, { "editClipsCanMove", true },
      { "overdub", false }, { "effectsGroupBy", "groupby:publisher" } } } });
   CHECK_MSG(Ok(r), r.dump());
   const auto set = r["result"]["settings"];
   CHECK(set.value("recordChannels", 0) == 2);
   CHECK(set.value("latencyMs", 0.0) == 80.0);
   CHECK(set.value("soloMode", "") == "Multi");
   CHECK(set.value("realtimeDither", "") == "triangle");
   CHECK(set.value("editClipsCanMove", false));
   CHECK(!set.value("overdub", true));
   CHECK(set.value("effectsGroupBy", "") == "groupby:publisher");

   r = Call("settings.get");
   CHECK(Ok(r) && r["result"]["settings"] == set);

   // Invalid values: nothing is written
   CHECK(ErrorCodeOf(Call("settings.set", { { "settings", { { "bogus", 1 } } } })) ==
      "INVALID_ARGS");
   CHECK(ErrorCodeOf(Call("settings.set", { { "settings",
      { { "latencyMs", 50.0 }, { "recordChannels", 7 } } } })) == "INVALID_ARGS");
   CHECK(ErrorCodeOf(Call("settings.set", { { "settings",
      { { "hqDither", "pink" } } } })) == "INVALID_ARGS");
   CHECK(ErrorCodeOf(Call("settings.set", { { "settings",
      { { "soloMode", "None" } } } })) == "INVALID_ARGS");
   r = Call("settings.get");
   CHECK(r["result"]["settings"].value("latencyMs", 0.0) == 80.0);

   // Persisted in filesDir/audacity/audacity.cfg
   const auto cfg = ReadFile(dirs.filesDir + "/audacity/audacity.cfg");
   CHECK_MSG(cfg.find("RecordChannels=2") != std::string::npos, cfg.substr(0, 400));
   CHECK(cfg.find("TempDir=" + dirs.noBackupDir + "/SessionData") != std::string::npos);

   // Restore what the following tests rely on
   CHECK(Ok(Call("settings.set", { { "settings", {
      { "recordChannels", 1 }, { "soloMode", "Simple" } } } })));
}

void TestSaveOpen(Sink &sink, const TempDirs &dirs)
{
   // Unicode file name (JNI passes standard UTF-8)
   const std::string path = dirs.filesDir + "/Projects/\xED\x85\x8C\xEC\x8A\xA4\xED\x8A\xB8 \xF0\x9F\x8E\xB5.aup3";
   const std::string copyPath = dirs.filesDir + "/Projects/copy.aup3";

   CHECK(ErrorCodeOf(Call("project.saveAs", { { "path", "relative.aup3" } })) ==
      "INVALID_ARGS");
   CHECK(ErrorCodeOf(Call("project.saveAs", { { "path", dirs.filesDir + "/x.txt" } })) ==
      "INVALID_ARGS");

   auto before = sink.Count();
   auto r = Call("project.saveAs", { { "path", path } });
   CHECK_MSG(Ok(r), r.dump());
   CHECK(r["result"].value("path", "") == path);
   CHECK(FileExists(path));
   auto snap = SnapshotOf(sink, before, r);
   CHECK(!snap["project"].value("temporary", true));
   CHECK(snap["project"].value("path", "") == path);
   CHECK(!snap["project"].value("dirty", true));
   CHECK(snap["project"].value("name", "") ==
      "\xED\x85\x8C\xEC\x8A\xA4\xED\x8A\xB8 \xF0\x9F\x8E\xB5");
   const auto trackCount = snap["tracks"].size();
   CHECK(trackCount == 2);

   r = Call("project.save");
   CHECK_MSG(Ok(r), r.dump());
   CHECK(r["result"].value("path", "") == path);

   r = Call("project.saveCopy", { { "path", copyPath } });
   CHECK_MSG(Ok(r), r.dump());
   CHECK(FileExists(copyPath));
   CHECK(ErrorCodeOf(Call("project.saveCopy", { { "path", copyPath } })) == "FAILED");

   r = Call("project.list");
   CHECK(Ok(r));
   bool listed = false;
   for (const auto &p : r["result"]["projects"])
      if (p.value("path", "") == path)
         listed = p.value("sizeBytes", 0ll) > 0;
   CHECK(listed);
   CHECK(r["result"]["projects"].size() == 2);

   // A saved project cannot be deleted while open
   CHECK(ErrorCodeOf(Call("project.delete", { { "path", path } })) == "INVALID_ARGS");

   before = sink.Count();
   r = Call("project.close");
   CHECK(Ok(r));
   CHECK(r["generation"].get<uint64_t>() == 0);
   snap = SnapshotOf(sink, before, r);
   CHECK(!snap["project"].value("open", true));
   CHECK(FileExists(path));

   CHECK(ErrorCodeOf(Call("project.open", { { "path", dirs.filesDir + "/nope.aup3" } })) ==
      "NOT_FOUND");
   {
      std::ofstream junk(dirs.filesDir + "/junk.aup3");
      junk << "this is not a database";
   }
   CHECK(ErrorCodeOf(Call("project.open", { { "path", dirs.filesDir + "/junk.aup3" } })) ==
      "INVALID_ARGS");

   before = sink.Count();
   r = Call("project.open", { { "path", path } });
   CHECK_MSG(Ok(r), r.dump());
   snap = SnapshotOf(sink, before, r);
   CHECK(snap["project"].value("path", "") == path);
   CHECK(!snap["project"].value("dirty", true));
   CHECK(!snap["project"].value("temporary", true));
   CHECK(snap["tracks"].size() == trackCount);
   if (!snap["tracks"].empty()) {
      // TrackList::MakeUniqueTrackName: "Test Tone 1"
      CHECK_MSG(snap["tracks"][0].value("name", "") == "Test Tone 1",
         snap["tracks"][0].dump());
      CHECK(snap["tracks"][0].value("channels", 0) == 2);
      CHECK(std::fabs(snap["tracks"][0].value("end", 0.0) - 1.0) < 1e-6);
   }
   r = Call("project.tags.get");
   bool hasTitle = false;
   for (const auto &t : r["result"]["tags"])
      hasTitle |= t.value("name", "") == "TITLE";
   CHECK(hasTitle);
   CHECK(ErrorCodeOf(Call("project.open", { { "path", path } })) == "FAILED");

   r = Call("project.delete", { { "path", copyPath } });
   CHECK(Ok(r));
   CHECK(!FileExists(copyPath));
}

void TestDialogs(Sink &sink)
{
   std::atomic<int> reply{ 1 };
   sink.onBlockingDialog = [&reply](const json &dialog) {
      const int id = dialog.value("id", -1);
      const int button = reply.load();
      std::thread([id, button] {
         std::this_thread::sleep_for(50ms);
         aubridge::ReplyDialog(id, button);
      }).detach();
   };
   auto before = sink.Count();
   auto r = Call("debug.ask", { { "message", "Proceed?" } });
   CHECK(Ok(r));
   CHECK(r["result"].value("result", "") == "no");
   auto dialog = sink.Last("dialog", before);
   CHECK(dialog.has_value());
   if (dialog) {
      CHECK(dialog->value("blocking", false));
      CHECK(dialog->value("message", "") == "Proceed?");
      CHECK((*dialog)["buttons"].size() == 2);
   }
   reply = 0;
   r = Call("debug.ask", { { "message", "Proceed?" } });
   CHECK(r["result"].value("result", "") == "yes");
   reply = 2;
   r = Call("debug.ask", { { "message", "Proceed?" }, { "cancel", true } });
   CHECK(r["result"].value("result", "") == "cancel");
   reply = -1;
   r = Call("debug.ask", { { "message", "Proceed?" }, { "cancel", true } });
   CHECK(r["result"].value("result", "") == "cancel");
   reply = 2;
   r = Call("debug.ask", { { "message", "Pick" },
      { "choices", json::array({ "a", "b", "c" }) } });
   CHECK(r["result"].value("choice", -9) == 2);
   sink.onBlockingDialog = {};
}

void TestProgress(Sink &sink)
{
   std::atomic<bool> stop{ false };
   sink.onProgress = [&stop](const json &progress) {
      if (progress.value("phase", "") == "update")
         aubridge::CancelProgress(progress.value("id", -1), stop.load());
   };
   const auto t0 = std::chrono::steady_clock::now();
   auto before = sink.Count();
   auto r = Call("debug.progress", { { "seconds", 20.0 } });
   CHECK_MSG(ErrorCodeOf(r) == "CANCELLED", r.dump());
   CHECK(std::chrono::steady_clock::now() - t0 < 10s);
   auto begin = sink.WaitFor("progress", 1s, before,
      [](const json &p) { return p.value("phase", "") == "begin"; });
   auto end = sink.WaitFor("progress", 1s, before,
      [](const json &p) { return p.value("phase", "") == "end"; });
   CHECK(begin.has_value() && end.has_value());
   if (begin) {
      CHECK(begin->value("cancellable", false));
      CHECK(begin->value("stoppable", false));
   }
   stop = true;
   r = Call("debug.progress", { { "seconds", 20.0 } });
   CHECK(Ok(r) && r["result"].value("stopped", false));
   sink.onProgress = {};
   // Without a cancel request it completes
   r = Call("debug.progress", { { "seconds", 0.1 } });
   CHECK(Ok(r) && !r["result"].value("stopped", true));
}

void TestMultiChoice(Sink &sink)
{
   // How the "UI" answers the next blocking dialog
   enum Mode { Choices, Cancel, OkDefaults, NoneChecked, OneIndex };
   std::atomic<int> mode{ Choices };
   sink.onBlockingDialog = [&mode](const json &dialog) {
      const int id = dialog.value("id", -1);
      const int m = mode.load();
      std::thread([id, m] {
         std::this_thread::sleep_for(20ms);
         switch (m) {
         case Choices:
            // out of range and duplicate indices are dropped
            aubridge::ReplyDialogChoices(id, { 2, 0, 2, 9, -1 });
            break;
         case Cancel: aubridge::ReplyDialog(id, -1); break;
         case OkDefaults: aubridge::ReplyDialog(id, 0); break;
         case NoneChecked: aubridge::ReplyDialogChoices(id, {}); break;
         case OneIndex: aubridge::ReplyDialogChoices(id, { 1 }); break;
         }
      }).detach();
   };
   const json args{ { "multiChoice", true }, { "title", "Streams" },
      { "message", "Import which streams?" },
      { "choices", json::array({ "Stream 1", "Stream 2", "Stream 3" }) },
      { "defaultChecked", json::array({ true, false, true }) } };

   auto before = sink.Count();
   auto r = Call("debug.ask", args);
   CHECK_MSG(Ok(r), r.dump());
   CHECK_MSG(r["result"]["choices"] == json::array({ 0, 2 }), r.dump());
   auto dialog = sink.Last("dialog", before);
   CHECK(dialog.has_value());
   int dialogId = -1;
   if (dialog) {
      dialogId = dialog->value("id", -1);
      CHECK(dialog->value("kind", "") == "multiChoice");
      CHECK(dialog->value("blocking", false));
      CHECK(dialog->value("title", "") == "Streams");
      CHECK(dialog->value("message", "") == "Import which streams?");
      CHECK((*dialog)["choices"].size() == 3);
      CHECK((*dialog)["defaultChecked"] == json::array({ true, false, true }));
      CHECK((*dialog)["buttons"].size() == 2);
   }

   mode = Cancel;
   r = Call("debug.ask", args);
   CHECK(Ok(r) && r["result"].value("result", "") == "cancel" &&
      !r["result"].contains("choices"));

   mode = OkDefaults;
   r = Call("debug.ask", args);
   CHECK_MSG(Ok(r) && r["result"]["choices"] == json::array({ 0, 2 }), r.dump());

   mode = NoneChecked;
   r = Call("debug.ask", args);
   CHECK_MSG(Ok(r) && r["result"]["choices"] == json::array(), r.dump());

   // defaultChecked shorter than choices: the rest is unchecked
   mode = OkDefaults;
   before = sink.Count();
   auto shorter = args;
   shorter["defaultChecked"] = json::array({ false, true });
   r = Call("debug.ask", shorter);
   CHECK_MSG(Ok(r) && r["result"]["choices"] == json::array({ 1 }), r.dump());
   dialog = sink.Last("dialog", before);
   CHECK(dialog && (*dialog)["defaultChecked"] == json::array({ false, true, false }));

   // ReplyDialogChoices on an ordinary question: one index = that button
   mode = OneIndex;
   r = Call("debug.ask", { { "message", "Proceed?" } });
   CHECK(Ok(r) && r["result"].value("result", "") == "no");
   sink.onBlockingDialog = {};

   // Late replies to finished dialogs are ignored
   aubridge::ReplyDialogChoices(dialogId, { 0 });
   aubridge::ReplyDialog(dialogId, 0);
   aubridge::ReplyDialogChoices(123456, { 0 });

   CHECK(ErrorCodeOf(Call("debug.ask", { { "multiChoice", true },
      { "choices", json::array({ "a" }) }, { "defaultChecked", 3 } })) ==
      "INVALID_ARGS");
   CHECK(ErrorCodeOf(Call("debug.ask", { { "multiChoice", true } })) ==
      "INVALID_ARGS");
}

void TestSettingsAdditions(Sink &sink, const TempDirs &dirs)
{
   auto r = Call("settings.get");
   CHECK(Ok(r));
   auto s = r["result"]["settings"];
   CHECK(s.value("latencyCorrectionMs", -1.0) == 0.0);
   CHECK(s.contains("syncLock") && !s.value("syncLock", true));
   CHECK(s.contains("pasteAsNewClips") && !s.value("pasteAsNewClips", true));
   CHECK(s.contains("moveSelectionWithTracks") &&
      !s.value("moveSelectionWithTracks", true));
   CHECK(s.contains("preferNewTrackRecord") && !s.value("preferNewTrackRecord", true));
   CHECK(s.value("dropoutDetection", false));
   CHECK(s.value("language", "") == "system");
   CHECK(s.value("hqDither", "") == "shaped");

   auto before = sink.Count();
   r = Call("settings.set", { { "settings", {
      { "latencyCorrectionMs", 12.5 }, { "syncLock", true },
      { "pasteAsNewClips", true }, { "moveSelectionWithTracks", true },
      { "preferNewTrackRecord", true }, { "dropoutDetection", false },
      { "hqDither", "rectangle" }, { "realtimeDither", "none" },
      { "language", "en" } } } });
   CHECK_MSG(Ok(r), r.dump());
   s = r["result"]["settings"];
   CHECK(s.value("latencyCorrectionMs", 0.0) == 12.5);
   CHECK(s.value("syncLock", false));
   CHECK(s.value("pasteAsNewClips", false));
   CHECK(s.value("moveSelectionWithTracks", false));
   CHECK(s.value("preferNewTrackRecord", false));
   CHECK(!s.value("dropoutDetection", true));
   CHECK(s.value("hqDither", "") == "rectangle");
   CHECK(s.value("realtimeDither", "") == "none");
   CHECK(s.value("language", "") == "en");
   // Sync-lock also applies to the open project: SL in the snapshot that
   // precedes the response
   auto snap = sink.Last("snapshot", before);
   CHECK_MSG(snap && (Flags(*snap) & SL) && !(Flags(*snap) & NSL),
      snap ? snap->dump().substr(0, 200) : "no snapshot");
   CHECK(Call("settings.get")["result"]["settings"] == s);

   const auto cfg = ReadFile(dirs.filesDir + "/audacity/audacity.cfg");
   for (const char *line : { "UserLatencyTrimMs=12.5", "SyncLockTracks=1",
           "PasteAsNewClips=1", "MoveSelectionWithTracks=1",
           "PreferNewTrackRecord=1", "DropoutDetected=0",
           "HQDitherAlgorithmChoice=Rectangle", "DitherAlgorithmChoice=None",
           "[Android]", "Language=en" })
      CHECK_MSG(cfg.find(line) != std::string::npos, line);

   CHECK(ErrorCodeOf(Call("settings.set", { { "settings",
      { { "language", "xx" } } } })) == "INVALID_ARGS");
   CHECK(ErrorCodeOf(Call("settings.set", { { "settings",
      { { "dropoutDetection", "yes" } } } })) == "INVALID_ARGS");
   CHECK(ErrorCodeOf(Call("settings.set", { { "settings",
      { { "latencyCorrectionMs", 1e6 } } } })) == "INVALID_ARGS");
   CHECK(ErrorCodeOf(Call("settings.set", { { "settings",
      { { "syncLock", 1 } } } })) == "INVALID_ARGS");

   // Back to the defaults the other tests expect
   before = sink.Count();
   r = Call("settings.set", { { "settings", {
      { "latencyCorrectionMs", 0.0 }, { "syncLock", false },
      { "pasteAsNewClips", false }, { "moveSelectionWithTracks", false },
      { "preferNewTrackRecord", false }, { "dropoutDetection", true },
      { "hqDither", "shaped" }, { "language", "system" } } } });
   CHECK_MSG(Ok(r), r.dump());
   snap = sink.Last("snapshot", before);
   CHECK(snap && (Flags(*snap) & NSL) && !(Flags(*snap) & SL));
}

void TestRename(const TempDirs &dirs, const std::string &openPath)
{
   const auto projects = dirs.filesDir + "/Projects/";
   const auto first = projects + "first.aup3";
   CHECK(Ok(Call("project.saveCopy", { { "path", first } })));
   auto r = Call("project.rename", { { "path", first }, { "newName", "second" } });
   CHECK_MSG(Ok(r), r.dump());
   const auto second = projects + "second.aup3";
   CHECK(r["result"].value("path", "") == second);
   CHECK(FileExists(second) && !FileExists(first));

   // Blanks and ".aup3" are stripped; Unicode names work ("세 번째")
   r = Call("project.rename", { { "path", second },
      { "newName", "  \xEC\x84\xB8 \xEB\xB2\x88\xEC\xA7\xB8.aup3 " } });
   CHECK_MSG(Ok(r), r.dump());
   const auto third = projects + "\xEC\x84\xB8 \xEB\xB2\x88\xEC\xA7\xB8.aup3";
   CHECK(r["result"].value("path", "") == third);
   CHECK(FileExists(third) && !FileExists(second));

   // -wal/-shm move with the database; leftovers at the target are removed
   CHECK(WriteFile(third + "-wal", "wal") && WriteFile(third + "-shm", "shm"));
   const auto fourth = projects + "fourth.aup3";
   CHECK(WriteFile(fourth + "-shm", "stale"));
   r = Call("project.rename", { { "path", third }, { "newName", "fourth" } });
   CHECK_MSG(Ok(r), r.dump());
   CHECK(FileExists(fourth) && !FileExists(third));
   CHECK(ReadFile(fourth + "-wal") == "wal" && ReadFile(fourth + "-shm") == "shm");
   CHECK(!FileExists(third + "-wal") && !FileExists(third + "-shm"));
   // (not real SQLite files: remove them before the database is opened)
   std::remove((fourth + "-wal").c_str());
   std::remove((fourth + "-shm").c_str());

   // Same name: nothing to do
   r = Call("project.rename", { { "path", fourth }, { "newName", "fourth" } });
   CHECK(Ok(r) && r["result"].value("path", "") == fourth);

   // Errors
   for (const char *bad : { "", "   ", "a/b", ".hidden", "backup~", "x\ty" })
      CHECK_MSG(ErrorCodeOf(Call("project.rename", { { "path", fourth },
         { "newName", bad } })) == "INVALID_ARGS", bad);
   CHECK(ErrorCodeOf(Call("project.rename", { { "path", fourth } })) ==
      "INVALID_ARGS");
   CHECK(ErrorCodeOf(Call("project.rename", { { "path", "rel.aup3" },
      { "newName", "x" } })) == "INVALID_ARGS");
   CHECK(ErrorCodeOf(Call("project.rename", { { "path", dirs.filesDir + "/x.txt" },
      { "newName", "x" } })) == "INVALID_ARGS");
   CHECK(ErrorCodeOf(Call("project.rename", { { "path", projects + "missing.aup3" },
      { "newName", "x" } })) == "NOT_FOUND");
   const auto openName = openPath.substr(projects.size(),
      openPath.size() - projects.size() - 5);
   CHECK(ErrorCodeOf(Call("project.rename", { { "path", fourth },
      { "newName", openName } })) == "FAILED");   // target exists
   CHECK(ErrorCodeOf(Call("project.rename", { { "path", openPath },
      { "newName", "x" } })) == "FAILED");        // the open project
   CHECK(FileExists(openPath) && !FileExists(projects + "x.aup3"));

   r = Call("project.list");
   bool listed = false;
   for (const auto &p : r["result"]["projects"])
      listed |= p.value("path", "") == fourth && p.value("name", "") == "fourth";
   CHECK(listed);

   // The renamed database opens
   r = Call("project.open", { { "path", fourth } });
   CHECK_MSG(Ok(r), r.dump());
   r = Call("project.snapshot");
   CHECK(r["result"]["project"].value("name", "") == "fourth");
   CHECK(r["result"]["tracks"].size() == 2);
   CHECK_MSG(Ok(Call("project.open", { { "path", openPath } })), openPath);
   CHECK(Ok(Call("project.delete", { { "path", fourth } })));
   CHECK(!FileExists(fourth));
}

void TestCompact(Sink &sink, const std::string &openPath)
{
   // An undone edit leaves its audio in the database (the redo state)
   auto r = Call("debug.makeTestTrack", { { "seconds", 20.0 },
      { "channels", 2 }, { "rate", 44100 } });
   CHECK_MSG(Ok(r), r.dump());
   CHECK(Ok(Call("history.undo")));

   r = Call("project.compactInfo");
   CHECK_MSG(Ok(r), r.dump());
   const auto info = r["result"];
   const auto total = info.value("totalBytes", int64_t(-1));
   const auto used = info.value("usedBytes", int64_t(-1));
   const auto fileBytes = info.value("fileBytes", int64_t(-1));
   std::fprintf(stderr, "compactInfo: %s\n", info.dump().c_str());
   CHECK(used > 0 && total > used);
   // 20 s of 32-bit float stereo at 44.1 kHz
   CHECK(total - used > 5000000);
   CHECK(fileBytes > total);
   CHECK(info.value("freeBytes", int64_t(-1)) > 0);

   auto before = sink.Count();
   const auto gen0 = Call("project.info")["generation"].get<uint64_t>();
   r = Call("project.compact");
   CHECK_MSG(Ok(r), r.dump());
   const auto freed = r["result"].value("freedBytes", int64_t(-1));
   std::fprintf(stderr, "compact freed %lld bytes\n", (long long)freed);
   CHECK(freed > 5000000);
   CHECK(r["generation"].get<uint64_t>() > gen0);
   auto snap = SnapshotOf(sink, before, r);
   CHECK(snap["tracks"].size() == 2);
   CHECK(!snap["history"].value("canUndo", true));
   CHECK(!snap["history"].value("canRedo", true));
   CHECK(!snap["project"].value("dirty", true));
   r = Call("history.list");
   CHECK(Ok(r) && r["result"]["states"].size() == 1);
   if (r["result"]["states"].size() == 1) {
      CHECK(r["result"]["states"][0].value("description", "") ==
         "Compacted project file");
      CHECK(r["result"]["states"][0].value("shortDescription", "") == "Compact");
   }
   r = Call("project.compactInfo");
   CHECK(Ok(r));
   CHECK(r["result"].value("totalBytes", int64_t(-1)) < total - 5000000);
   CHECK(r["result"].value("fileBytes", int64_t(-1)) < fileBytes - 5000000);

   // A modified project stays modified, and undo back to the saved state
   // still works
   CHECK(Ok(Call("debug.makeTestTrack", { { "seconds", 1.0 } })));
   before = sink.Count();
   r = Call("project.compact");
   CHECK_MSG(Ok(r), r.dump());
   snap = SnapshotOf(sink, before, r);
   CHECK(snap["project"].value("dirty", false));
   CHECK(snap["tracks"].size() == 3);
   CHECK(snap["history"].value("canUndo", false));
   r = Call("history.list");
   CHECK(Ok(r) && r["result"]["states"].size() == 2 &&
      r["result"].value("current", -1) == 1);
   CHECK(Ok(Call("history.undo")));
   snap = Call("project.snapshot")["result"];
   CHECK(snap["tracks"].size() == 2 && !snap["project"].value("dirty", true));

   // The compacted file reopens intact
   CHECK(Ok(Call("project.close")));
   r = Call("project.open", { { "path", openPath } });
   CHECK_MSG(Ok(r), r.dump());
   snap = Call("project.snapshot")["result"];
   CHECK(snap["tracks"].size() == 2);
   if (snap["tracks"].size() == 2)
      CHECK(std::fabs(snap["tracks"][0].value("end", 0.0) - 1.0) < 1e-6);

   // A temporary project: compaction works and it stays modified
   CHECK(Ok(Call("project.new")));
   CHECK(Ok(Call("debug.makeTestTrack", { { "seconds", 2.0 } })));
   CHECK(Ok(Call("history.undo")));
   CHECK(Ok(Call("debug.makeTestTrack", { { "seconds", 1.0 } })));
   r = Call("project.compact");
   CHECK_MSG(Ok(r), r.dump());
   snap = Call("project.snapshot")["result"];
   CHECK(snap["project"].value("temporary", false));
   CHECK(snap["project"].value("dirty", false));
   CHECK(snap["tracks"].size() == 1);
   CHECK(Ok(Call("project.close")));
   CHECK(ErrorCodeOf(Call("project.compact")) == "NO_PROJECT");
   CHECK(ErrorCodeOf(Call("project.compactInfo")) == "NO_PROJECT");
   CHECK(Ok(Call("project.open", { { "path", openPath } })));
}

//! Engine started with locale ko_KR and the Korean catalog installed
void TestKorean(Sink &sink)
{
   auto r = Call("app.info");
   CHECK(Ok(r));
   CHECK_MSG(r["result"].value("language", "") == "ko", r["result"].dump().substr(0, 100));
   CHECK(r["result"]["languages"] == json::array({ "en", "ko" }));
   r = Call("settings.get");
   CHECK(r["result"]["settings"].value("language", "") == "system");

   const auto list = Call("project.list");
   CHECK(Ok(list) && !list["result"]["projects"].empty());
   if (list["result"]["projects"].empty())
      return;
   CHECK(Ok(Call("project.close")));
   r = Call("project.open",
      { { "path", list["result"]["projects"][0].value("path", "") } });
   CHECK_MSG(Ok(r), r.dump());
   const auto firstDescription = [] {
      auto h = Call("history.list");
      if (!Ok(h) || h["result"]["states"].empty())
         return std::string("<none>");
      return h["result"]["states"][0].value("description", "");
   };
   // "Created new project" (ProjectHistory::InitialState) in Korean
   CHECK_MSG(firstDescription() == kKoCreatedNewProject, firstDescription());
   // A history label pushed by a command
   auto before = sink.Count();
   r = Call("project.tags.set", { { "tags", json::array({
      { { "name", "TITLE" }, { "value", "Korean" } } }) } });
   CHECK(Ok(r));
   auto snap = SnapshotOf(sink, before, r);
   CHECK_MSG(snap["history"].value("undo", "") == kKoMetadataTags,
      snap["history"].dump());
   // Error messages of the libraries
   r = Call("project.open", { { "path", "/nonexistent/x.aup3" } });
   CHECK(ErrorCodeOf(r) == "NOT_FOUND");
   CHECK_MSG(r["error"].value("message", "").rfind(kKoCouldNotOpenFile, 0) == 0,
      r.dump());

   // The `language` setting switches at runtime (and re-emits the snapshot)
   before = sink.Count();
   r = Call("settings.set", { { "settings", { { "language", "en" } } } });
   CHECK_MSG(Ok(r), r.dump());
   snap = SnapshotOf(sink, before, r);
   CHECK(snap["history"].value("undo", "") == "Metadata Tags");
   CHECK(firstDescription() == "Created new project");
   CHECK(Call("app.info")["result"].value("language", "") == "en");
   r = Call("settings.set", { { "settings", { { "language", "ko" } } } });
   CHECK(Ok(r));
   CHECK(firstDescription() == kKoCreatedNewProject);
   CHECK(Call("app.info")["result"].value("language", "") == "ko");
   r = Call("settings.set", { { "settings", { { "language", "en" } } } });
   r = Call("settings.set", { { "settings", { { "language", "system" } } } });
   CHECK(Ok(r) && r["result"]["settings"].value("language", "") == "system");
   CHECK(firstDescription() == kKoCreatedNewProject);
   CHECK(ErrorCodeOf(Call("settings.set", { { "settings",
      { { "language", "../ko" } } } })) == "INVALID_ARGS");
   CHECK(Ok(Call("history.undo")));
}

void TestRealtimeEntryPoints()
{
   // No audio/display module installed yet: "not ready" answers
   double transport[16];
   CHECK(!aubridge::ReadTransport(transport, 16));
   float meters[14];
   CHECK(!aubridge::ReadMeters(meters, 14));
   float columns[3 * 256];
   CHECK(aubridge::WaveColumns(1, 0, 0, 0, 256, columns, 3 * 256) == -3);
   CHECK(aubridge::WaveSamples(1, 0, 0, 1).empty());
   CHECK(aubridge::PpsForLevel(0) == 1.0);
   CHECK(aubridge::PpsForLevel(8) == 2.0);
}

} // namespace

int main(int argc, char **argv)
{
   if (argc >= 3 && std::string(argv[1]) == "--crash-child")
      return CrashChild(argv[2]);

   // Before Start
   CHECK(!aubridge::IsReady());
   CHECK(ErrorCodeOf(Call("app.info")) == "NOT_READY");

   TempDirs dirs;
   std::fprintf(stderr, "test root: %s\n", dirs.root.c_str());

   // 1. Crash in a child process (leaves an unsaved project behind)
   {
      char exe[4096] = {};
      const auto n = ::readlink("/proc/self/exe", exe, sizeof exe - 1);
      const std::string self = n > 0 ? std::string(exe, size_t(n)) : argv[0];
      const std::string cmd = "'" + self + "' --crash-child '" + dirs.root + "'";
      const int rc = std::system(cmd.c_str());
      CHECK_MSG(WIFEXITED(rc) && WEXITSTATUS(rc) == 0,
         "crash child failed: " + std::to_string(rc));
   }

   auto sink = std::make_shared<Sink>();
   auto ready = StartAndWait(*sink, sink, dirs);
   CHECK(ready.has_value());
   if (!ready) {
      aubridge::Stop();
      return 1;
   }
   CHECK(aubridge::IsReady());
   CHECK(!aubridge::Start(dirs.ConfigJson(), sink));   // already started
   CHECK(ready->value("audacityVersion", "") == "3.7.9");
   for (const auto &check : (*ready)["selfChecks"]) {
      const auto name = check.value("name", "");
      std::fprintf(stderr, "self check %-20s %s %s\n", name.c_str(),
         check.value("ok", false) ? "ok  " : "FAIL",
         check.value("message", "").c_str());
      if (name == "sampleBlockFactory" || name == "importers" ||
          name == "exporters" || name == "projectAttachments" ||
          name == "tempDir" || name == "configDir")
         CHECK_MSG(check.value("ok", false), name);
   }
   CHECK_MSG(ready->value("recoverable", 0) == 1, ready->dump());
   CHECK(FileExists(dirs.filesDir + "/audacity/audacity.cfg"));

   TestNoProjectErrors();
   TestAppInfo();
   TestRealtimeEntryPoints();
   TestRecovery(*sink, dirs);
   TestNewProject(*sink);
   TestSettings(dirs);
   TestSettingsAdditions(*sink, dirs);
   TestTrackAndHistory(*sink);
   TestView(*sink);
   TestTags();
   TestSaveOpen(*sink, dirs);
   {
      const std::string openPath = dirs.filesDir + "/Projects/\xED\x85\x8C\xEC\x8A\xA4\xED\x8A\xB8 \xF0\x9F\x8E\xB5.aup3";
      TestRename(dirs, openPath);
      TestCompact(*sink, openPath);
   }
   TestDialogs(*sink);
   TestMultiChoice(*sink);
   TestProgress(*sink);

   // Stop while the engine thread waits for an unanswered question: Stop()
   // answers it (-1) and the pending Invoke returns
   {
      const auto before = sink->Count();
      json answer;
      std::thread asker([&answer] {
         answer = Call("debug.ask", { { "message", "Nobody answers" } });
      });
      auto dialog = sink->WaitFor("dialog", 10s, before);
      CHECK(dialog.has_value());
      aubridge::Stop();
      asker.join();
      CHECK_MSG(Ok(answer) || ErrorCodeOf(answer) == "NOT_READY", answer.dump());
      if (Ok(answer))
         CHECK(answer["result"].value("result", "") == "no");
   }

   // Stop, then restart in the same process with the same directories,
   // now with a Korean locale and the Korean catalog where the app installs
   // it (assets/audacity/locale -> filesDir/audacity/locale)
   aubridge::Stop();   // idempotent
   CHECK(!aubridge::IsReady());
   CHECK(ErrorCodeOf(Call("app.info")) == "NOT_READY");
   {
      const std::string moDir = dirs.filesDir + "/audacity/locale/ko/LC_MESSAGES";
      CHECK(MakeDirs(moDir));
      CHECK_MSG(CopyFile(AUBRIDGE_TEST_LOCALE_DIR "/ko/LC_MESSAGES/audacity.mo",
         moDir + "/audacity.mo"), AUBRIDGE_TEST_LOCALE_DIR);
      auto sink2 = std::make_shared<Sink>();
      auto ready2 = StartAndWait(*sink2, sink2, dirs, { { "locale", "ko_KR" } });
      CHECK_MSG(ready2.has_value(), "restart failed");
      if (ready2) {
         CHECK(ready2->value("recoverable", -1) == 0);
         CHECK(Ok(Call("app.info")));
         auto r = Call("settings.get");
         CHECK(Ok(r) && r["result"]["settings"].value("latencyMs", 0.0) == 80.0);
         auto list = Call("project.list");
         CHECK(Ok(list) && list["result"]["projects"].size() == 1);
         if (Ok(list) && !list["result"]["projects"].empty()) {
            r = Call("project.open",
               { { "path", list["result"]["projects"][0].value("path", "") } });
            CHECK_MSG(Ok(r), r.dump());
            auto snap = Call("project.snapshot");
            CHECK(snap["result"]["tracks"].size() == 2);
         }
         CHECK(Ok(Call("debug.makeTestTrack")));
         CHECK(Ok(Call("history.undo")));
         TestKorean(*sink2);
      }
      aubridge::Stop();
   }

   std::fprintf(stderr, "\nbridge-test-core: %s (%d failure%s)\n",
      Failures() ? "FAILED" : "PASSED", Failures(), Failures() == 1 ? "" : "s");
   return Failures() ? 1 : 0;
}
