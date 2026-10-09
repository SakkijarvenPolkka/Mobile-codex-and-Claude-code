/*  SPDX-License-Identifier: GPL-2.0-or-later */
/**********************************************************************

  Audacity Android port

  EditTest.cpp

  Host test of the bridge "edit" module, driven only through Bridge.h:
   * select.* / playRegion.*: selection, track selection, focus, clip and
     clip-boundary navigation, zero crossings on a sine, no generation bump
   * edit.*: clipboard (copy/cut/paste, paste into new tracks, several
     tracks, mono into stereo, "not enough tracks"), delete, split cut /
     delete, silence, trim, duplicate, split, split new, join, detach at
     silences, select-all-on-none; undo/redo restore the snapshot exactly
   * tracks.*: add/remove/rename/move/sort/align, gain/pan (final:false
     without history entry, throttled snapshots; final:true consolidated),
     mute/solo/muteAll (U: no history entry), make stereo (with the
     render question), split stereo, swap channels (checked with zero
     crossings of the sine/cosine channels), rate, format, resample, mix
     and render
   * clips.*: move (clamped at neighbours, to another track, resampling),
     rename, STALE references; trim (live final:false updates without
     history entry, one entry per drag, clamping to the audio, the
     neighbours and one sample, cancelled drags, undo)
   * edit.splitAt: selected tracks / explicit tracks / tracks at t when
     nothing is selected, one undo entry, no empty clips, errors
   * labels.*: add (also at an explicit position)/edit (re-sort)/remove,
     STALE, import/export round trips
     (text, SubRip), WebVTT/JSON export, Unicode titles

  Exit code 0 on success.  BRIDGE_TEST_VERBOSE=1 prints the events.

**********************************************************************/
#include "BridgeTestSupport.h"

#include <cmath>
#include <fstream>
#include <sstream>
#include <thread>

using namespace bridgetest;
using namespace std::chrono_literals;

namespace {

std::shared_ptr<Sink> gSink;
TempDirs *gDirs = nullptr;

constexpr double kEps = 1e-6;

bool Near(double a, double b, double eps = kEps)
{
   return std::fabs(a - b) <= eps;
}

std::string ReadFile(const std::string &path)
{
   std::ifstream in(path, std::ios::binary);
   std::stringstream ss;
   ss << in.rdbuf();
   return ss.str();
}

void WriteFile(const std::string &path, const std::string &text)
{
   std::ofstream out(path, std::ios::binary);
   out << text;
}

//! Invoke; CHECK success; return the result
json ReqAt(int line, const std::string &command,
   const json &args = json::object())
{
   auto r = Call(command, args);
   CHECK_MSG(Ok(r), command + " (line " + std::to_string(line) + "): " +
      r.dump());
   return Ok(r) ? r["result"] : json::object();
}
#define REQ(...) ReqAt(__LINE__, __VA_ARGS__)

//! Invoke; return the error code ("" on success)
std::string Err(const std::string &command, const json &args = json::object())
{
   return ErrorCodeOf(Call(command, args));
}

json Snap()
{
   auto r = Call("project.snapshot");
   CHECK(Ok(r));
   return Ok(r) ? r["result"] : json::object();
}

uint64_t Gen()
{
   return Snap().value("generation", uint64_t(0));
}

json TrackOf(const json &snap, int64_t id)
{
   for (const auto &t : snap["tracks"])
      if (t.value("id", int64_t(-2)) == id)
         return t;
   return json();
}

std::vector<int64_t> TrackIds(const json &snap)
{
   std::vector<int64_t> ids;
   for (const auto &t : snap["tracks"])
      ids.push_back(t.value("id", int64_t(-2)));
   return ids;
}

size_t ClipCount(const json &snap, int64_t id)
{
   auto t = TrackOf(snap, id);
   return t.is_object() && t.contains("clips") ? t["clips"].size() : 0;
}

json Clip(const json &snap, int64_t id, size_t index)
{
   auto t = TrackOf(snap, id);
   if (!t.is_object() || !t.contains("clips") || index >= t["clips"].size())
      return json::object();
   return t["clips"][index];
}

bool ClipIs(const json &snap, int64_t id, size_t index, double start,
   double end, double eps = 1e-4)
{
   auto c = Clip(snap, id, index);
   const bool ok = c.contains("start") &&
      Near(c["start"].get<double>(), start, eps) &&
      Near(c["end"].get<double>(), end, eps);
   if (!ok)
      std::fprintf(stderr, "  clip %zu of track %lld: %s, expected [%g, %g]\n",
         index, (long long)id, c.dump().c_str(), start, end);
   return ok;
}

bool SelectionIs(const json &snap, double t0, double t1, double eps = 1e-6)
{
   const auto &s = snap["selection"];
   const bool ok = Near(s["t0"].get<double>(), t0, eps) &&
      Near(s["t1"].get<double>(), t1, eps);
   if (!ok)
      std::fprintf(stderr, "  selection %s, expected [%g, %g]\n",
         s.dump().c_str(), t0, t1);
   return ok;
}

bool Selected(const json &snap, int64_t id)
{
   return TrackOf(snap, id).value("selected", false);
}

bool Focused(const json &snap, int64_t id)
{
   return TrackOf(snap, id).value("focused", false);
}

size_t HistoryCount()
{
   auto r = Call("history.list");
   CHECK(Ok(r));
   return Ok(r) ? r["result"]["states"].size() : 0;
}

std::string UndoName()
{
   return Snap()["history"].value("undo", "");
}

//! The parts of a snapshot that undo/redo must restore exactly (focus is
//! not part of the undo state)
json Canon(json snap)
{
   for (auto &t : snap["tracks"])
      t.erase("focused");
   return json{ { "tracks", snap["tracks"] }, { "selection", snap["selection"] } };
}

void CheckUndoRedo(const std::string &what, const json &before,
   const json &after)
{
   CHECK_MSG(Ok(Call("history.undo")), what + ": undo");
   const auto undone = Snap();
   CHECK_MSG(Canon(undone) == Canon(before),
      what + ": undo did not restore\n  got      " + Canon(undone).dump() +
      "\n  expected " + Canon(before).dump());
   CHECK_MSG(Ok(Call("history.redo")), what + ": redo");
   const auto redone = Snap();
   CHECK_MSG(Canon(redone) == Canon(after),
      what + ": redo did not restore\n  got      " + Canon(redone).dump() +
      "\n  expected " + Canon(after).dump());
}

int64_t Tone(double seconds, int channels = 1, double frequency = 440.0)
{
   auto r = REQ("debug.makeTestTrack", { { "seconds", seconds },
      { "channels", channels }, { "frequency", frequency } });
   return r.value("id", int64_t(-1));
}

void NewProject()
{
   REQ("project.new");
}

void SelectOnly(std::vector<int64_t> ids)
{
   REQ("select.tracks", { { "ids", ids }, { "mode", "set" } });
}

void SetSel(double t0, double t1)
{
   REQ("select.set", { { "t0", t0 }, { "t1", t1 } });
}

//! The snapshot event emitted by the last call (before its response)
json SnapshotEventSince(size_t before, const json &envelope)
{
   auto snap = gSink->Last("snapshot", before);
   CHECK_MSG(snap.has_value(), "no snapshot event before the response");
   if (!snap)
      return json::object();
   CHECK((*snap)["generation"] == envelope["generation"]);
   return *snap;
}

// ---------------------------------------------------------------------------
void TestSelection()
{
   std::fprintf(stderr, "== selection\n");
   NewProject();
   const auto a = Tone(2.0);
   const auto b = Tone(3.0);
   const auto c = Tone(1.0);
   // c starts at 1.0
   {
      auto g = Gen();
      REQ("clips.move", { { "trackId", c }, { "clipIndex", 0 },
         { "generation", g }, { "newStart", 1.0 } });
      CHECK(ClipIs(Snap(), c, 0, 1.0, 2.0));
   }
   const auto gen0 = Gen();
   const auto hist0 = HistoryCount();

   // select.set: times are ordered, track part replaces, focus
   {
      const auto before = gSink->Count();
      auto r = Call("select.set", { { "t0", 1.5 }, { "t1", 0.5 },
         { "trackIds", { a } }, { "focus", a } });
      CHECK(Ok(r));
      auto ev = SnapshotEventSince(before, r);
      CHECK(SelectionIs(ev, 0.5, 1.5));
      auto s = Snap();
      CHECK(SelectionIs(s, 0.5, 1.5));
      CHECK(Selected(s, a) && !Selected(s, b) && !Selected(s, c));
      CHECK(Focused(s, a));
      CHECK(s["generation"].get<uint64_t>() == gen0);
   }
   CHECK(Err("select.set", { { "t0", 0 }, { "t1", 1 },
      { "trackIds", { 987654321 } } }) == "NOT_FOUND");
   CHECK(SelectionIs(Snap(), 0.5, 1.5));
   CHECK(Err("select.set", { { "t0", "x" }, { "t1", 1 } }) == "INVALID_ARGS");
   CHECK(Err("select.set", { { "t1", 1 } }) == "INVALID_ARGS");
   CHECK(Err("select.set", { { "t0", 0 }, { "t1", 1 }, { "focus", 987654 } })
      == "NOT_FOUND");

   // select.all / none / tracks
   REQ("select.all");
   {
      auto s = Snap();
      CHECK(SelectionIs(s, 0.0, 3.0));
      CHECK(Selected(s, a) && Selected(s, b) && Selected(s, c));
   }
   REQ("select.none");
   {
      auto s = Snap();
      CHECK(SelectionIs(s, 0.0, 0.0));
      CHECK(!Selected(s, a) && !Selected(s, b) && !Selected(s, c));
   }
   REQ("select.tracks", { { "ids", { a, b } }, { "mode", "set" } });
   CHECK(Selected(Snap(), a) && Selected(Snap(), b));
   REQ("select.tracks", { { "ids", { a } }, { "mode", "remove" } });
   CHECK(!Selected(Snap(), a) && Selected(Snap(), b));
   REQ("select.tracks", { { "ids", { a, b } }, { "mode", "toggle" } });
   CHECK(Selected(Snap(), a) && !Selected(Snap(), b));
   REQ("select.tracks", { { "ids", { c } }, { "mode", "add" } });
   CHECK(Selected(Snap(), a) && !Selected(Snap(), b) && Selected(Snap(), c));
   CHECK(Err("select.tracks", { { "ids", { a } }, { "mode", "bogus" } }) ==
      "INVALID_ARGS");

   // select.trackHeader: tap selects the track and its extent, ctrl toggles
   SetSel(0, 0);
   REQ("select.trackHeader", { { "id", b }, { "shift", false }, { "ctrl", false } });
   {
      auto s = Snap();
      CHECK(Selected(s, b) && !Selected(s, a) && !Selected(s, c));
      CHECK(SelectionIs(s, 0.0, 3.0));
      CHECK(Focused(s, b));
   }
   REQ("select.trackHeader", { { "id", a }, { "shift", false }, { "ctrl", true } });
   CHECK(Selected(Snap(), a) && Selected(Snap(), b) && Focused(Snap(), b));
   REQ("select.trackHeader", { { "id", a }, { "shift", false }, { "ctrl", true } });
   CHECK(!Selected(Snap(), a) && Selected(Snap(), b));
   // shift extends from the last picked track to c
   // (the ctrl taps made a the last picked track)
   REQ("select.trackHeader", { { "id", c }, { "shift", true }, { "ctrl", false } });
   CHECK(Selected(Snap(), a) && Selected(Snap(), b) && Selected(Snap(), c));
   REQ("select.trackHeader", { { "id", b }, { "shift", false }, { "ctrl", false } });
   REQ("select.trackHeader", { { "id", c }, { "shift", true }, { "ctrl", false } });
   CHECK(!Selected(Snap(), a) && Selected(Snap(), b) && Selected(Snap(), c));

   // select.allTracks keeps the time selection
   SetSel(0.25, 0.75);
   REQ("select.allTracks");
   CHECK(Selected(Snap(), a) && Selected(Snap(), b) && Selected(Snap(), c));
   CHECK(SelectionIs(Snap(), 0.25, 0.75));

   // Region commands (c spans [1, 2])
   SelectOnly({ c });
   SetSel(1.5, 1.5);
   REQ("select.startToCursor");
   CHECK(SelectionIs(Snap(), 1.0, 1.5));
   SetSel(1.5, 1.5);
   REQ("select.cursorToEnd");
   CHECK(SelectionIs(Snap(), 1.5, 2.0));
   SetSel(1.2, 1.3);
   REQ("select.trackStartToEnd");
   CHECK(SelectionIs(Snap(), 1.0, 2.0));
   SetSel(0.5, 0.5);
   REQ("select.toProjectEnd");
   CHECK(SelectionIs(Snap(), 0.5, 3.0));
   SetSel(1.5, 2.5);
   REQ("select.toProjectStart");
   CHECK(SelectionIs(Snap(), 0.0, 2.5));
   REQ("select.cursorToTrackStart");
   CHECK(SelectionIs(Snap(), 1.0, 1.0));
   REQ("select.cursorToTrackEnd");
   CHECK(SelectionIs(Snap(), 2.0, 2.0));
   REQ("select.none");
   CHECK(Err("select.cursorToTrackStart") == "NO_SELECTION");
   CHECK(Err("select.cursorToTrackEnd") == "NO_SELECTION");
   // AlwaysEnabled in 3.7.9: no-op without selected tracks
   SetSel(1.5, 1.5);
   REQ("select.startToCursor");
   CHECK(SelectionIs(Snap(), 1.5, 1.5));

   // select.focus
   REQ("select.focus", { { "id", c } });
   CHECK(Focused(Snap(), c) && !Focused(Snap(), a));
   CHECK(Err("select.focus", { { "id", 987654 } }) == "NOT_FOUND");

   // play region
   REQ("playRegion.set", { { "t0", 0.5 }, { "t1", 1.5 }, { "active", true } });
   {
      auto pr = Snap()["playRegion"];
      CHECK(pr.value("active", false));
      CHECK(Near(pr["t0"].get<double>(), 0.5) && Near(pr["t1"].get<double>(), 1.5));
   }
   REQ("playRegion.toggle");
   CHECK(!Snap()["playRegion"].value("active", true));
   REQ("playRegion.toggle");
   {
      auto pr = Snap()["playRegion"];
      CHECK(pr.value("active", false));
      CHECK(Near(pr["t0"].get<double>(), 0.5) && Near(pr["t1"].get<double>(), 1.5));
   }
   REQ("playRegion.clear");
   CHECK(!Snap()["playRegion"].value("active", true));
   REQ("playRegion.set", { { "t0", 2.0 }, { "t1", 1.0 }, { "active", false } });
   {
      auto pr = Snap()["playRegion"];
      CHECK(!pr.value("active", true));
      CHECK(Near(pr["t0"].get<double>(), 1.0) && Near(pr["t1"].get<double>(), 2.0));
   }
   CHECK(Err("playRegion.set", { { "t0", -1.0 }, { "t1", 1.0 },
      { "active", true } }) == "INVALID_ARGS");

   // Selection commands neither bump the generation nor add history
   CHECK(Gen() == gen0);
   CHECK(HistoryCount() == hist0);

   // The selection is part of the undo state
   SelectOnly({ a });
   SetSel(0.5, 1.5);
   REQ("edit.delete");
   CHECK(SelectionIs(Snap(), 0.5, 0.5));
   REQ("history.undo");
   {
      auto s = Snap();
      CHECK(SelectionIs(s, 0.5, 1.5));
      CHECK(Selected(s, a) && !Selected(s, b));
      CHECK(Near(TrackOf(s, a)["end"].get<double>(), 2.0));
   }

   // NO_PROJECT
   REQ("project.close");
   CHECK(Err("select.all") == "NO_PROJECT");
   CHECK(Err("edit.cut") == "NO_PROJECT");
   CHECK(Err("tracks.add", { { "kind", "mono" } }) == "NO_PROJECT");
   NewProject();
}

void TestClipNavigation()
{
   std::fprintf(stderr, "== clip navigation\n");
   NewProject();
   const auto a = Tone(3.0);
   SelectOnly({ a });
   SetSel(1.0, 1.5);
   REQ("edit.splitDelete");
   {
      auto s = Snap();
      CHECK(ClipCount(s, a) == 2);
      CHECK(ClipIs(s, a, 0, 0.0, 1.0));
      CHECK(ClipIs(s, a, 1, 1.5, 3.0));
   }
   SetSel(0.2, 0.2);
   REQ("select.nextClipBoundary");
   CHECK(SelectionIs(Snap(), 1.0, 1.0, 1e-4));
   REQ("select.nextClipBoundary");
   CHECK(SelectionIs(Snap(), 1.5, 1.5, 1e-4));
   REQ("select.nextClipBoundary");
   CHECK(SelectionIs(Snap(), 3.0, 3.0, 1e-4));
   REQ("select.nextClipBoundary");
   CHECK(SelectionIs(Snap(), 3.0, 3.0, 1e-4));
   REQ("select.prevClipBoundary");
   CHECK(SelectionIs(Snap(), 1.5, 1.5, 1e-4));
   REQ("select.prevClipBoundary");
   CHECK(SelectionIs(Snap(), 1.0, 1.0, 1e-4));
   REQ("select.prevClipBoundary");
   CHECK(SelectionIs(Snap(), 0.0, 0.0, 1e-4));

   SetSel(0.2, 0.2);
   REQ("select.nextClip");
   CHECK(SelectionIs(Snap(), 1.5, 3.0, 1e-4));
   REQ("select.prevClip");
   CHECK(SelectionIs(Snap(), 0.0, 1.0, 1e-4));
   REQ("select.nextClip");
   CHECK(SelectionIs(Snap(), 1.5, 3.0, 1e-4));

   // Without selected wave tracks all wave tracks are searched
   REQ("select.none");   // cursor at 1.5
   REQ("select.prevClipBoundary");
   CHECK(SelectionIs(Snap(), 1.0, 1.0, 1e-4));

   // select.clip
   const auto b = Tone(1.0);
   SelectOnly({ b });
   const auto g = Gen();
   REQ("select.clip", { { "trackId", a }, { "clipIndex", 1 }, { "generation", g } });
   {
      auto s = Snap();
      CHECK(SelectionIs(s, 1.5, 3.0, 1e-4));
      CHECK(Selected(s, a) && !Selected(s, b));
      CHECK(Focused(s, a));
      CHECK(s["generation"].get<uint64_t>() == g);
   }
   CHECK(Err("select.clip", { { "trackId", a }, { "clipIndex", 1 },
      { "generation", g - 1 } }) == "STALE");
   CHECK(Err("select.clip", { { "trackId", a }, { "clipIndex", 5 },
      { "generation", g } }) == "NOT_FOUND");
   CHECK(Err("select.clip", { { "trackId", 987654 }, { "clipIndex", 0 },
      { "generation", g } }) == "NOT_FOUND");
   CHECK(Err("select.clip", { { "trackId", a }, { "clipIndex", 0 } }) ==
      "INVALID_ARGS");
}

//! Zero crossing near `probe` of the selected tracks
double ZeroCrossingAt(double probe)
{
   SetSel(probe, probe);
   REQ("select.zeroCrossing");
   return Snap()["selection"]["t0"].get<double>();
}

void TestZeroCrossingAndSwap()
{
   std::fprintf(stderr, "== zero crossing, swap channels\n");
   NewProject();
   const double rate = Snap()["project"]["rate"].get<double>();
   const double tol = 3.0 / rate;
   // 100 Hz sine: upward zero crossings at 0, 10, 20 ms ..., downward at 5,
   // 15, ... ms; the search prefers upward crossings close to the probe
   const auto z = Tone(1.0, 1, 100.0);
   SelectOnly({ z });
   const auto gen = Gen();
   {
      const double t = ZeroCrossingAt(0.0123);
      CHECK_MSG(Near(t, 0.010, tol), std::to_string(t));
   }
   SetSel(0.0123, 0.0277);
   REQ("select.zeroCrossing");
   {
      auto s = Snap();
      CHECK_MSG(Near(s["selection"]["t0"].get<double>(), 0.010, tol),
         s["selection"].dump());
      CHECK_MSG(Near(s["selection"]["t1"].get<double>(), 0.030, tol),
         s["selection"].dump());
   }
   CHECK(Gen() == gen);
   REQ("select.none");
   CHECK(Err("select.zeroCrossing") == "NO_SELECTION");

   // Stereo test tone: channel 0 = sine, channel 1 = cosine (zero crossings
   // at 2.5, 7.5 (upward), 12.5 ms ...).  Near 9 ms: sine -> 10 ms,
   // cosine -> 7.5 ms
   const auto st = Tone(1.0, 2, 100.0);
   {
      auto r = REQ("tracks.splitStereoToMono", { { "id", st } });
      CHECK(r["ids"].size() == 2);
      if (r["ids"].size() == 2) {
         SelectOnly({ r["ids"][0].get<int64_t>() });
         CHECK(Near(ZeroCrossingAt(0.009), 0.010, tol));
         SelectOnly({ r["ids"][1].get<int64_t>() });
         CHECK(Near(ZeroCrossingAt(0.009), 0.0075, tol));
      }
      REQ("history.undo");
   }
   const auto before = Snap();
   REQ("tracks.swapChannels", { { "id", st } });
   const auto after = Snap();
   CHECK(UndoName() == "Swap Channels");
   CheckUndoRedo("swapChannels", before, after);
   {
      auto r = REQ("tracks.splitStereoToMono", { { "id", st } });
      CHECK(r["ids"].size() == 2);
      if (r["ids"].size() == 2) {
         SelectOnly({ r["ids"][0].get<int64_t>() });
         CHECK_MSG(Near(ZeroCrossingAt(0.009), 0.0075, tol), "left after swap");
         SelectOnly({ r["ids"][1].get<int64_t>() });
         CHECK_MSG(Near(ZeroCrossingAt(0.009), 0.010, tol), "right after swap");
      }
   }
   CHECK(Err("tracks.swapChannels", { { "id", z } }) == "INVALID_ARGS");
}

// ---------------------------------------------------------------------------
void TestClipboard()
{
   std::fprintf(stderr, "== clipboard\n");
   NewProject();
   // The clipboard was cleared when the previous project closed
   CHECK(Err("edit.paste") == "FAILED");
   CHECK(REQ("edit.clipboardInfo").value("empty", false));

   const auto a = Tone(3.0);
   SelectOnly({ a });
   SetSel(1.0, 2.0);
   const auto g = Gen();
   const auto h = HistoryCount();
   {
      const auto before = gSink->Count();
      auto r = Call("edit.copy");
      CHECK(Ok(r));
      auto ev = SnapshotEventSince(before, r);
      CHECK(!ev["clipboard"].value("empty", true));
      CHECK(Near(ev["clipboard"]["duration"].get<double>(), 1.0));
   }
   CHECK(Gen() == g);
   CHECK(HistoryCount() == h);
   {
      auto info = REQ("edit.clipboardInfo");
      CHECK(!info.value("empty", true));
      CHECK(Near(info["t0"].get<double>(), 1.0));
      CHECK(Near(info["t1"].get<double>(), 2.0));
      CHECK(info.value("trackCount", 0) == 1);
   }

   // Cut
   {
      const auto before = Snap();
      const auto r = Call("edit.cut");
      CHECK(Ok(r));
      CHECK(r["generation"].get<uint64_t>() > g);
      const auto after = Snap();
      CHECK(Near(TrackOf(after, a)["end"].get<double>(), 2.0));
      CHECK(SelectionIs(after, 1.0, 1.0));
      CHECK(UndoName() == "Cut");
      CHECK(Near(after["clipboard"]["duration"].get<double>(), 1.0));
      CheckUndoRedo("cut", before, after);
   }
   // Paste at the cursor
   {
      const auto before = Snap();
      REQ("edit.paste");
      const auto after = Snap();
      CHECK(Near(TrackOf(after, a)["end"].get<double>(), 3.0));
      CHECK(ClipCount(after, a) == 1);
      CHECK(SelectionIs(after, 1.0, 2.0));
      CHECK(UndoName() == "Paste");
      CheckUndoRedo("paste", before, after);
   }
   // Delete
   SetSel(0.5, 1.0);
   {
      const auto before = Snap();
      REQ("edit.delete");
      const auto after = Snap();
      CHECK(Near(TrackOf(after, a)["end"].get<double>(), 2.5));
      CHECK(SelectionIs(after, 0.5, 0.5));
      CHECK(UndoName() == "Delete");
      CHECK(after["history"].value("undo", "") == "Delete");
      CheckUndoRedo("delete", before, after);
   }
   // Point selection: NO_SELECTION
   CHECK(Err("edit.delete") == "NO_SELECTION");
   CHECK(Err("edit.cut") == "NO_SELECTION");
   CHECK(Err("edit.copy") == "NO_SELECTION");
   CHECK(Err("edit.trim") == "NO_SELECTION");
   CHECK(Err("edit.duplicate") == "NO_SELECTION");

   // Paste into new tracks when no track is selected
   {
      REQ("select.none");
      const auto before = Snap();
      REQ("edit.paste");
      const auto after = Snap();
      CHECK(after["tracks"].size() == before["tracks"].size() + 1);
      const auto &nt = after["tracks"].back();
      CHECK(nt.value("selected", false));
      CHECK(nt.value("focused", false));
      CHECK(ClipIs(after, nt["id"].get<int64_t>(), 0, 0.0, 1.0));
      CHECK(SelectionIs(after, 0.0, 1.0));
      CHECK(!Selected(after, a));
      CheckUndoRedo("paste into new track", before, after);
      REQ("history.undo");
   }

   // Several tracks
   NewProject();
   const auto p = Tone(2.0);
   const auto q = Tone(2.0);
   SelectOnly({ p, q });
   SetSel(0.0, 1.0);
   REQ("edit.copy");
   CHECK(REQ("edit.clipboardInfo").value("trackCount", 0) == 2);
   SetSel(2.0, 2.0);
   {
      const auto before = Snap();
      REQ("edit.paste");
      const auto after = Snap();
      CHECK(Near(TrackOf(after, p)["end"].get<double>(), 3.0));
      CHECK(Near(TrackOf(after, q)["end"].get<double>(), 3.0));
      CheckUndoRedo("paste into two tracks", before, after);
   }
   // The paste at the end of the clips made new clips [2, 3].  With
   // /GUI/EditClipCanMove off (3.7.9 default), a paste into [0, 2] has no
   // room (the next clip follows directly): FAILED, rolled back
   SelectOnly({ p });
   SetSel(0.0, 0.0);
   {
      const auto before = Snap();
      const auto h = HistoryCount();
      auto r = Call("edit.paste");
      CHECK(ErrorCodeOf(r) == "FAILED");
      CHECK(r["error"].value("message", "").find("not enough room") !=
         std::string::npos);
      CHECK(Canon(Snap()) == Canon(before));
      CHECK(HistoryCount() == h);
   }
   // One selected track: pastes into it and the following ones
   SetSel(2.5, 2.5);
   REQ("edit.paste");
   CHECK(Near(TrackOf(Snap(), p)["end"].get<double>(), 4.0));
   CHECK(Near(TrackOf(Snap(), q)["end"].get<double>(), 4.0));
   // ... but not past the last track
   SelectOnly({ q });
   {
      const auto g2 = Gen();
      auto r = Call("edit.paste");
      CHECK(ErrorCodeOf(r) == "FAILED");
      CHECK(r["error"].value("message", "").find("span across more tracks") !=
         std::string::npos);
      CHECK(Gen() == g2);
   }
   // Mono clipboard into a stereo track
   const auto st = Tone(2.0, 2);
   SelectOnly({ p });
   SetSel(0.0, 0.5);
   REQ("edit.copy");
   SelectOnly({ st });
   SetSel(1.0, 1.0);
   REQ("edit.paste");
   {
      auto s = Snap();
      CHECK(Near(TrackOf(s, st)["end"].get<double>(), 2.5));
      CHECK(TrackOf(s, st).value("channels", 0) == 2);
   }

   // /GUI/SelectAllOnNone: commands without NoAutoSelect select all audio
   CHECK(Ok(Call("settings.set", { { "settings", { { "selectAllOnNone", true } } } })));
   REQ("select.none");
   REQ("edit.copy");
   {
      auto s = Snap();
      CHECK(Selected(s, p) && Selected(s, q) && Selected(s, st));
      CHECK(SelectionIs(s, 0.0, 4.0));
      CHECK(REQ("edit.clipboardInfo").value("trackCount", 0) == 3);
   }
   REQ("select.none");
   CHECK(Err("edit.delete") == "NO_SELECTION");   // NoAutoSelect
   CHECK(Err("edit.cut") == "NO_SELECTION");      // NoAutoSelect
   CHECK(Ok(Call("settings.set", { { "settings", { { "selectAllOnNone", false } } } })));
   CHECK(Err("edit.copy") == "NO_SELECTION");
}

void TestRegionEdits()
{
   std::fprintf(stderr, "== region edits\n");
   NewProject();
   const auto a = Tone(3.0);
   SelectOnly({ a });

   // Split cut
   SetSel(0.5, 1.0);
   {
      const auto before = Snap();
      REQ("edit.splitCut");
      const auto after = Snap();
      CHECK(ClipCount(after, a) == 2);
      CHECK(ClipIs(after, a, 0, 0.0, 0.5));
      CHECK(ClipIs(after, a, 1, 1.0, 3.0));
      CHECK(Near(after["clipboard"]["duration"].get<double>(), 0.5));
      CHECK(UndoName() == "Cut and leave gap");
      CheckUndoRedo("splitCut", before, after);
   }
   // Split delete
   SetSel(1.5, 2.0);
   {
      const auto before = Snap();
      REQ("edit.splitDelete");
      const auto after = Snap();
      CHECK(ClipCount(after, a) == 3);
      CHECK(ClipIs(after, a, 1, 1.0, 1.5));
      CHECK(ClipIs(after, a, 2, 2.0, 3.0));
      CHECK(UndoName() == "Split Delete");
      CheckUndoRedo("splitDelete", before, after);
   }
   // Silence: same geometry, new samples
   SetSel(2.1, 2.2);
   {
      const auto before = Snap();
      REQ("edit.silence");
      const auto after = Snap();
      CHECK(TrackOf(after, a)["clips"] == TrackOf(before, a)["clips"]);
      CHECK(TrackOf(after, a)["waveVersion"] != TrackOf(before, a)["waveVersion"]);
      CHECK(UndoName() == "Silence");
      CheckUndoRedo("silence", before, after);
   }

   // Trim
   const auto t = Tone(3.0);
   SelectOnly({ t });
   SetSel(0.5, 2.5);
   {
      const auto before = Snap();
      REQ("edit.trim");
      const auto after = Snap();
      CHECK(ClipCount(after, t) == 1);
      CHECK(ClipIs(after, t, 0, 0.5, 2.5));
      CHECK(Near(Clip(after, t, 0)["trimLeft"].get<double>(), 0.5, 1e-4));
      CHECK(UndoName() == "Trim Audio");
      CheckUndoRedo("trim", before, after);
   }
   // Duplicate
   SetSel(1.0, 2.0);
   {
      const auto before = Snap();
      REQ("edit.duplicate");
      const auto after = Snap();
      CHECK(after["tracks"].size() == before["tracks"].size() + 1);
      const auto dup = after["tracks"].back()["id"].get<int64_t>();
      CHECK(dup != t);
      CHECK(ClipIs(after, dup, 0, 1.0, 2.0));
      CHECK(UndoName() == "Duplicate");
      CheckUndoRedo("duplicate", before, after);
   }

   // Split (point and range)
   const auto u = Tone(3.0);
   SelectOnly({ u });
   SetSel(1.5, 1.5);
   {
      const auto before = Snap();
      REQ("edit.split");
      const auto after = Snap();
      CHECK(ClipCount(after, u) == 2);
      CHECK(ClipIs(after, u, 0, 0.0, 1.5));
      CHECK(UndoName() == "Split");
      CheckUndoRedo("split", before, after);
   }
   SetSel(0.5, 1.0);
   REQ("edit.split");
   CHECK(ClipCount(Snap(), u) == 4);
   // Join
   SetSel(0.75, 0.75);
   CHECK(Err("edit.join") == "NO_SELECTION");
   SetSel(0.25, 2.0);
   {
      const auto before = Snap();
      REQ("edit.join");
      const auto after = Snap();
      CHECK(ClipCount(after, u) == 1);
      CHECK(ClipIs(after, u, 0, 0.0, 3.0));
      CHECK(UndoName() == "Join");
      CheckUndoRedo("join", before, after);
   }
   CHECK(Err("edit.join") == "NO_SELECTION");   // one clip only

   // Split new
   const auto v = Tone(3.0);
   SelectOnly({ v });
   SetSel(1.0, 2.0);
   {
      const auto before = Snap();
      REQ("edit.splitNew");
      const auto after = Snap();
      CHECK(ClipCount(after, v) == 2);
      CHECK(ClipIs(after, v, 0, 0.0, 1.0));
      CHECK(ClipIs(after, v, 1, 2.0, 3.0));
      const auto nt = after["tracks"].back()["id"].get<int64_t>();
      CHECK(nt != v);
      CHECK(ClipIs(after, nt, 0, 1.0, 2.0));
      CHECK(UndoName() == "Split New");
      CheckUndoRedo("splitNew", before, after);
   }

   // Detach at silences
   const auto w = Tone(3.0);
   SelectOnly({ w });
   SetSel(1.0, 2.0);
   REQ("edit.silence");
   SetSel(0.0, 3.0);
   {
      const auto before = Snap();
      REQ("edit.detachAtSilences");
      const auto after = Snap();
      CHECK(ClipCount(after, w) == 2);
      CHECK(ClipIs(after, w, 0, 0.0, 1.0, 1e-3));
      CHECK(ClipIs(after, w, 1, 2.0, 3.0, 1e-3));
      CHECK(UndoName() == "Detach");
      CheckUndoRedo("detach", before, after);
   }

   // Wave-track commands without a selected wave track
   const auto lres = REQ("tracks.add", { { "kind", "label" } });
   SelectOnly({ lres.value("id", int64_t(-1)) });
   SetSel(0.5, 1.0);
   CHECK(Err("edit.silence") == "NO_SELECTION");
   CHECK(Err("edit.split") == "NO_SELECTION");
   CHECK(Err("edit.splitNew") == "NO_SELECTION");
}

// ---------------------------------------------------------------------------
void TestTrackManagement()
{
   std::fprintf(stderr, "== track management\n");
   NewProject();
   int64_t n, s, l;
   {
      const auto before = Snap();
      n = REQ("tracks.add", { { "kind", "mono" } }).value("id", int64_t(-1));
      const auto after = Snap();
      auto t = TrackOf(after, n);
      CHECK(t.value("kind", "") == "wave");
      CHECK(t.value("channels", 0) == 1);
      CHECK(t.value("selected", false) && t.value("focused", false));
      CHECK(UndoName() == "New Track");
      CheckUndoRedo("tracks.add mono", before, after);
   }
   s = REQ("tracks.add", { { "kind", "stereo" } }).value("id", int64_t(-1));
   {
      auto snap = Snap();
      CHECK(TrackOf(snap, s).value("channels", 0) == 2);
      CHECK(!Selected(snap, n) && Selected(snap, s) && Focused(snap, s));
   }
   l = REQ("tracks.add", { { "kind", "label" } }).value("id", int64_t(-1));
   CHECK(TrackOf(Snap(), l).value("kind", "") == "label");
   CHECK(Err("tracks.add", { { "kind", "midi" } }) == "INVALID_ARGS");
   // Stable ids across undo/redo
   REQ("history.undo");
   CHECK(TrackOf(Snap(), l).is_null());
   REQ("history.redo");
   CHECK(TrackOf(Snap(), l).value("kind", "") == "label");
   CHECK((TrackIds(Snap()) == std::vector<int64_t>{ n, s, l }));

   // Rename
   {
      const auto before = Snap();
      REQ("tracks.rename", { { "id", n }, { "name", "Vocals 한국어" } });
      const auto after = Snap();
      CHECK(TrackOf(after, n).value("name", "") == "Vocals 한국어");
      CHECK(UndoName() == "Name Change");
      CheckUndoRedo("rename", before, after);
      const auto h = HistoryCount();
      REQ("tracks.rename", { { "id", n }, { "name", "Vocals 한국어" } });
      CHECK(HistoryCount() == h);
   }
   CHECK(Err("tracks.rename", { { "id", 987654 }, { "name", "x" } }) == "NOT_FOUND");

   // Move
   REQ("tracks.move", { { "id", l }, { "to", "top" } });
   CHECK((TrackIds(Snap()) == std::vector<int64_t>{ l, n, s }));
   CHECK(UndoName() == "Move Track to Top");
   REQ("tracks.move", { { "id", l }, { "to", "down" } });
   CHECK((TrackIds(Snap()) == std::vector<int64_t>{ n, l, s }));
   REQ("tracks.move", { { "id", l }, { "to", "bottom" } });
   CHECK((TrackIds(Snap()) == std::vector<int64_t>{ n, s, l }));
   {
      const auto before = Snap();
      REQ("tracks.move", { { "id", l }, { "to", "up" } });
      const auto after = Snap();
      CHECK((TrackIds(after) == std::vector<int64_t>{ n, l, s }));
      CHECK(UndoName() == "Move Track Up");
      CheckUndoRedo("move up", before, after);
   }
   {
      const auto h = HistoryCount();
      REQ("tracks.move", { { "id", n }, { "to", "up" } });   // already first
      REQ("tracks.move", { { "id", n }, { "to", "top" } });
      CHECK(HistoryCount() == h);
   }
   CHECK(Err("tracks.move", { { "id", n }, { "to", "left" } }) == "INVALID_ARGS");

   // Remove one / several
   {
      const auto before = Snap();
      REQ("tracks.remove", { { "ids", { l } } });
      const auto after = Snap();
      CHECK((TrackIds(after) == std::vector<int64_t>{ n, s }));
      CHECK(UndoName() == "Track Remove");
      CheckUndoRedo("remove one", before, after);
      REQ("history.undo");
   }
   {
      const auto before = Snap();
      REQ("tracks.remove", { { "ids", { n, s } } });
      const auto after = Snap();
      CHECK((TrackIds(after) == std::vector<int64_t>{ l }));
      CHECK(Focused(after, l));
      CHECK(UndoName() == "Remove Track");
      CheckUndoRedo("remove several", before, after);
      REQ("history.undo");
      CHECK((TrackIds(Snap()) == std::vector<int64_t>{ n, l, s }));
   }
   CHECK(Err("tracks.remove", { { "ids", { 987654 } } }) == "NOT_FOUND");
   CHECK(Err("tracks.remove", { { "ids", json::array() } }) == "INVALID_ARGS");

   // Sort
   NewProject();
   const auto a = Tone(1.0);
   const auto b = Tone(1.0);
   const auto c = Tone(1.0);
   REQ("tracks.rename", { { "id", a }, { "name", "beta" } });
   REQ("tracks.rename", { { "id", b }, { "name", "Alpha" } });
   REQ("tracks.rename", { { "id", c }, { "name", "gamma" } });
   {
      const auto g = Gen();
      REQ("clips.move", { { "trackId", b }, { "clipIndex", 0 },
         { "generation", g }, { "newStart", 2.0 } });
      const auto g2 = Gen();
      REQ("clips.move", { { "trackId", c }, { "clipIndex", 0 },
         { "generation", g2 }, { "newStart", 1.0 } });
   }
   {
      const auto before = Snap();
      REQ("tracks.sort", { { "by", "name" } });
      const auto after = Snap();
      CHECK((TrackIds(after) == std::vector<int64_t>{ b, a, c }));
      CHECK(UndoName() == "Sort by Name");
      CheckUndoRedo("sort by name", before, after);
   }
   REQ("tracks.sort", { { "by", "time" } });
   CHECK((TrackIds(Snap()) == std::vector<int64_t>{ a, c, b }));
   CHECK(UndoName() == "Sort by Time");
   CHECK(Err("tracks.sort", { { "by", "size" } }) == "INVALID_ARGS");
}

void TestMixer()
{
   std::fprintf(stderr, "== gain, pan, mute, solo\n");
   NewProject();
   const auto a = Tone(1.0);
   const auto g0 = Gen();
   const auto h0 = HistoryCount();

   // final:false: model change only, throttled snapshots
   REQ("tracks.setGain", { { "id", a }, { "gain", 0.5 }, { "final", false } });
   CHECK(Gen() == g0);
   CHECK(HistoryCount() == h0);
   CHECK(Near(TrackOf(Snap(), a)["gain"].get<double>(), 0.5));
   {
      const auto from = gSink->Count();
      REQ("tracks.setGain", { { "id", a }, { "gain", 0.25 }, { "final", false } });
      REQ("tracks.setGain", { { "id", a }, { "gain", 0.125 }, { "final", false } });
      // the last value arrives in a snapshot event (trailing, <= 10 Hz)
      auto ev = gSink->WaitFor("snapshot", 3s, from, [&](const json &snap) {
         auto t = TrackOf(snap, a);
         return t.is_object() && Near(t["gain"].get<double>(), 0.125);
      });
      CHECK_MSG(ev.has_value(), "no snapshot with the last dragged gain");
   }
   CHECK(Gen() == g0);
   // final:true: one consolidated history entry
   REQ("tracks.setGain", { { "id", a }, { "gain", 0.3 }, { "final", true } });
   CHECK(Gen() > g0);
   CHECK(HistoryCount() == h0 + 1);
   CHECK(UndoName() == "Volume");
   REQ("tracks.setGain", { { "id", a }, { "gain", 0.4 }, { "final", true } });
   CHECK(HistoryCount() == h0 + 1);
   CHECK(Near(TrackOf(Snap(), a)["gain"].get<double>(), 0.4, 1e-6));
   REQ("history.undo");
   CHECK(Near(TrackOf(Snap(), a)["gain"].get<double>(), 1.0));
   REQ("history.redo");
   CHECK(Near(TrackOf(Snap(), a)["gain"].get<double>(), 0.4, 1e-6));
   CHECK(Err("tracks.setGain", { { "id", a }, { "gain", -1.0 }, { "final", true } })
      == "INVALID_ARGS");
   const auto lt = REQ("tracks.add", { { "kind", "label" } }).value("id", int64_t(-1));
   CHECK(Err("tracks.setGain", { { "id", lt }, { "gain", 1.0 }, { "final", true } })
      == "NOT_FOUND");
   REQ("tracks.remove", { { "ids", { lt } } });

   REQ("tracks.setPan", { { "id", a }, { "pan", -0.5 }, { "final", true } });
   CHECK(Near(TrackOf(Snap(), a)["pan"].get<double>(), -0.5));
   CHECK(UndoName() == "Pan");
   CHECK(Err("tracks.setPan", { { "id", a }, { "pan", 2.0 }, { "final", true } })
      == "INVALID_ARGS");

   // Mute / solo: U (generation bump, no history entry)
   CHECK(Ok(Call("settings.set", { { "settings", { { "soloMode", "Simple" } } } })));
   const auto b = Tone(1.0);
   const auto c = Tone(1.0);
   const auto g1 = Gen();
   const auto h1 = HistoryCount();
   REQ("tracks.setMute", { { "id", a }, { "mute", true } });
   CHECK(Gen() > g1);
   CHECK(HistoryCount() == h1);
   {
      auto snap = Snap();
      CHECK(TrackOf(snap, a).value("mute", false));
      CHECK(!TrackOf(snap, c).value("solo", true));
   }
   // Simple solo: one playing track of several shows "solo"
   REQ("tracks.setMute", { { "id", b }, { "mute", true } });
   CHECK(TrackOf(Snap(), c).value("solo", false));
   REQ("tracks.muteAll", { { "mute", false } });
   {
      auto snap = Snap();
      for (auto id : { a, b, c }) {
         CHECK(!TrackOf(snap, id).value("mute", true));
         CHECK(!TrackOf(snap, id).value("solo", true));
      }
   }
   REQ("tracks.setSolo", { { "id", a }, { "solo", true } });
   {
      auto snap = Snap();
      CHECK(TrackOf(snap, a).value("solo", false) && !TrackOf(snap, a).value("mute", true));
      CHECK(TrackOf(snap, b).value("mute", false) && !TrackOf(snap, b).value("solo", true));
      CHECK(TrackOf(snap, c).value("mute", false));
   }
   REQ("tracks.setSolo", { { "id", a }, { "solo", false } });
   {
      auto snap = Snap();
      for (auto id : { a, b, c }) {
         CHECK(!TrackOf(snap, id).value("mute", true));
         CHECK(!TrackOf(snap, id).value("solo", true));
      }
   }
   REQ("tracks.muteAll", { { "mute", true } });
   {
      auto snap = Snap();
      for (auto id : { a, b, c })
         CHECK(TrackOf(snap, id).value("mute", false));
   }
   CHECK(HistoryCount() == h1);
   // Multi solo: independent buttons
   CHECK(Ok(Call("settings.set", { { "settings", { { "soloMode", "Multi" } } } })));
   REQ("tracks.muteAll", { { "mute", false } });
   REQ("tracks.setSolo", { { "id", a }, { "solo", true } });
   REQ("tracks.setSolo", { { "id", b }, { "solo", true } });
   {
      auto snap = Snap();
      CHECK(TrackOf(snap, a).value("solo", false) && TrackOf(snap, b).value("solo", false));
      CHECK(!TrackOf(snap, c).value("solo", true) && !TrackOf(snap, c).value("mute", true));
   }
   CHECK(Ok(Call("settings.set", { { "settings", { { "soloMode", "Simple" } } } })));
   CHECK(Err("tracks.setMute", { { "id", 987654 }, { "mute", true } }) == "NOT_FOUND");
}

void TestStereo()
{
   std::fprintf(stderr, "== stereo\n");
   NewProject();
   const auto l = Tone(1.0, 1, 100.0);
   const auto r = Tone(1.0, 1, 200.0);
   const auto before = Snap();
   const auto res = REQ("tracks.makeStereo", { { "id", l } });
   const auto after = Snap();
   CHECK(res.value("id", int64_t(-1)) == l);
   CHECK(after["tracks"].size() == 1);
   CHECK(TrackOf(after, l).value("channels", 0) == 2);
   CHECK(Near(TrackOf(after, l)["end"].get<double>(), 1.0, 1e-4));
   CHECK(UndoName() == "Make Stereo");
   CheckUndoRedo("makeStereo", before, after);
   CHECK(Err("tracks.makeStereo", { { "id", l } }) == "INVALID_ARGS");

   {
      const auto b2 = Snap();
      const auto ids = REQ("tracks.splitStereo", { { "id", l } })["ids"];
      const auto a2 = Snap();
      CHECK(ids.size() == 2 && a2["tracks"].size() == 2);
      if (ids.size() == 2) {
         CHECK(ids[0].get<int64_t>() == l);
         CHECK(Near(TrackOf(a2, l)["pan"].get<double>(), -1.0));
         CHECK(Near(TrackOf(a2, ids[1].get<int64_t>())["pan"].get<double>(), 1.0));
         CHECK(TrackOf(a2, ids[1].get<int64_t>()).value("channels", 0) == 1);
      }
      CHECK(UndoName() == "Split");
      CheckUndoRedo("splitStereo", b2, a2);
      REQ("history.undo");
   }
   {
      const auto ids = REQ("tracks.splitStereoToMono", { { "id", l } })["ids"];
      const auto a2 = Snap();
      CHECK(ids.size() == 2);
      if (ids.size() == 2) {
         CHECK(Near(TrackOf(a2, l)["pan"].get<double>(), 0.0));
         CHECK(Near(TrackOf(a2, ids[1].get<int64_t>())["pan"].get<double>(), 0.0));
      }
      CHECK(UndoName() == "Split to Mono");
   }
   CHECK(Err("tracks.splitStereo", { { "id", l } }) == "INVALID_ARGS");

   // Mismatching clips: the engine asks before rendering
   NewProject();
   const auto x = Tone(1.0);
   const auto y = Tone(2.0);
   const auto lab = REQ("tracks.add", { { "kind", "label" } }).value("id", int64_t(-1));
   int asked = 0;
   int answer = 1;   // "No"
   gSink->onBlockingDialog = [&](const json &dialog) {
      ++asked;
      aubridge::ReplyDialog(dialog.value("id", 0), answer);
   };
   CHECK(Err("tracks.makeStereo", { { "id", x } }) == "CANCELLED");
   CHECK(asked == 1);
   CHECK(Snap()["tracks"].size() == 3);
   answer = 0;   // "Yes"
   REQ("tracks.makeStereo", { { "id", x } });
   CHECK(asked == 2);
   {
      auto snap = Snap();
      CHECK(snap["tracks"].size() == 2);
      CHECK(TrackOf(snap, x).value("channels", 0) == 2);
      CHECK(Near(TrackOf(snap, x)["end"].get<double>(), 2.0, 1e-4));
      CHECK(TrackOf(snap, y).is_null());
   }
   gSink->onBlockingDialog = nullptr;
   // The track below is a label track / there is none
   CHECK(Err("tracks.makeStereo", { { "id", x } }) == "INVALID_ARGS");
   const auto m = Tone(1.0);
   CHECK(Err("tracks.makeStereo", { { "id", m } }) == "INVALID_ARGS");
   CHECK(Err("tracks.makeStereo", { { "id", lab } }) == "NOT_FOUND");
}

void TestRateFormatMix()
{
   std::fprintf(stderr, "== rate, format, resample, mix and render\n");
   NewProject();
   const auto a = Tone(2.0);
   const double rate = TrackOf(Snap(), a)["rate"].get<double>();
   {
      const auto before = Snap();
      REQ("tracks.setRate", { { "id", a }, { "rate", rate / 2 } });
      const auto after = Snap();
      CHECK(Near(TrackOf(after, a)["rate"].get<double>(), rate / 2));
      CHECK(Near(TrackOf(after, a)["end"].get<double>(), 4.0, 1e-4));
      CHECK(UndoName() == "Rate Change");
      CheckUndoRedo("setRate", before, after);
      REQ("history.undo");
   }
   CHECK(Err("tracks.setRate", { { "id", a }, { "rate", 0 } }) == "INVALID_ARGS");
   {
      const auto before = Snap();
      REQ("tracks.setFormat", { { "id", a }, { "format", "int16" } });
      const auto after = Snap();
      CHECK(TrackOf(after, a).value("format", "") == "int16");
      CHECK(UndoName() == "Format Change");
      CheckUndoRedo("setFormat", before, after);
      const auto h = HistoryCount();
      REQ("tracks.setFormat", { { "id", a }, { "format", "int16" } });
      CHECK(HistoryCount() == h);
   }
   CHECK(Err("tracks.setFormat", { { "id", a }, { "format", "int8" } }) ==
      "INVALID_ARGS");

   // Resample two tracks: one (consolidated) history entry
   const auto b = Tone(1.0);
   const auto c = Tone(1.0);
   SelectOnly({ b, c });
   {
      const auto before = Snap();
      const auto h = HistoryCount();
      REQ("tracks.resample", { { "rate", 22050 } });
      const auto after = Snap();
      CHECK(HistoryCount() == h + 1);
      for (auto id : { b, c }) {
         CHECK(Near(TrackOf(after, id)["rate"].get<double>(), 22050));
         CHECK(Near(TrackOf(after, id)["end"].get<double>(), 1.0, 1e-3));
      }
      CHECK(UndoName() == "Resample Track");
      CheckUndoRedo("resample", before, after);
   }
   CHECK(Err("tracks.resample", { { "rate", 0 } }) == "INVALID_ARGS");
   REQ("select.none");
   CHECK(Err("tracks.resample", { { "rate", 8000 } }) == "NO_SELECTION");

   // Mix and render
   NewProject();
   const auto p = Tone(1.0);
   const auto q = Tone(2.0);
   SelectOnly({ p, q });
   {
      const auto before = Snap();
      const auto res = REQ("tracks.mixAndRender", { { "toNewTrack", false } });
      const auto after = Snap();
      CHECK(after["tracks"].size() == 1);
      const auto mix = res.value("id", int64_t(-1));
      auto t = TrackOf(after, mix);
      CHECK(t.value("channels", 0) == 1);
      CHECK(Near(t["end"].get<double>(), 2.0, 1e-3));
      CHECK(t.value("selected", false));
      CHECK(t.value("name", "").rfind("Mix", 0) == 0);
      CHECK(UndoName() == "Mix and Render");
      CheckUndoRedo("mixAndRender", before, after);
      REQ("history.undo");
   }
   {
      const auto res = REQ("tracks.mixAndRender", { { "toNewTrack", true } });
      const auto after = Snap();
      CHECK(after["tracks"].size() == 3);
      const auto mix = res.value("id", int64_t(-1));
      CHECK(!TrackOf(after, mix).value("selected", true));
      CHECK(after["tracks"].back().value("id", int64_t(-2)) == mix);
      REQ("history.undo");
   }
   SelectOnly({ p });
   REQ("tracks.mixAndRender", { { "toNewTrack", false } });
   CHECK(UndoName() == "Render");
   REQ("select.none");
   CHECK(Err("tracks.mixAndRender", { { "toNewTrack", false } }) == "NO_SELECTION");
}

void TestAlign()
{
   std::fprintf(stderr, "== align\n");
   NewProject();
   const auto a = Tone(1.0);
   const auto b = Tone(2.0);
   SelectOnly({ a, b });
   {
      const auto before = Snap();
      REQ("tracks.align", { { "mode", "endToEnd" } });
      const auto after = Snap();
      CHECK(ClipIs(after, a, 0, 0.0, 1.0));
      CHECK(ClipIs(after, b, 0, 1.0, 3.0));
      CHECK(UndoName() == "Align End to End");
      CheckUndoRedo("align endToEnd", before, after);
   }
   REQ("tracks.align", { { "mode", "together" } });
   CHECK(ClipIs(Snap(), a, 0, 0.5, 1.5));
   CHECK(ClipIs(Snap(), b, 0, 0.5, 2.5));
   CHECK(UndoName() == "Align Together");
   SetSel(2.0, 2.0);
   REQ("tracks.align", { { "mode", "startToCursor" }, { "moveSelection", false } });
   CHECK(ClipIs(Snap(), a, 0, 2.0, 3.0));
   CHECK(ClipIs(Snap(), b, 0, 2.0, 4.0));
   CHECK(UndoName() == "Align Start");
   REQ("tracks.align", { { "mode", "startToZero" }, { "moveSelection", false } });
   CHECK(ClipIs(Snap(), a, 0, 0.0, 1.0));
   CHECK(ClipIs(Snap(), b, 0, 0.0, 2.0));
   SetSel(0.0, 4.0);
   REQ("tracks.align", { { "mode", "endToSelEnd" }, { "moveSelection", false } });
   CHECK(ClipIs(Snap(), a, 0, 2.0, 3.0));
   CHECK(ClipIs(Snap(), b, 0, 2.0, 4.0));
   CHECK(UndoName() == "Align End");
   SetSel(3.0, 3.5);
   REQ("tracks.align", { { "mode", "startToSelEnd" }, { "moveSelection", true } });
   {
      auto s = Snap();
      CHECK(ClipIs(s, a, 0, 3.5, 4.5));
      CHECK(SelectionIs(s, 4.5, 5.0));
      CHECK(UndoName() == "Align/Move Start");
   }
   SetSel(1.0, 1.0);
   REQ("tracks.align", { { "mode", "endToCursor" }, { "moveSelection", false } });
   CHECK(ClipIs(Snap(), b, 0, -1.0, 1.0));
   CHECK(Err("tracks.align", { { "mode", "bogus" } }) == "INVALID_ARGS");
   REQ("select.none");
   CHECK(Err("tracks.align", { { "mode", "startToZero" } }) == "NO_SELECTION");
   const auto lab = REQ("tracks.add", { { "kind", "label" } }).value("id", int64_t(-1));
   SelectOnly({ lab });
   CHECK(Err("tracks.align", { { "mode", "startToZero" } }) == "NO_SELECTION");
}

// ---------------------------------------------------------------------------
void TestClips()
{
   std::fprintf(stderr, "== clips\n");
   NewProject();
   const auto a = Tone(3.0);
   SelectOnly({ a });
   SetSel(1.0, 2.0);
   REQ("edit.splitDelete");
   CHECK(ClipCount(Snap(), a) == 2);
   // Same track
   {
      const auto before = Snap();
      const auto g = before["generation"].get<uint64_t>();
      auto r = REQ("clips.move", { { "trackId", a }, { "clipIndex", 0 },
         { "generation", g }, { "newStart", 0.5 } });
      CHECK(r.value("trackId", int64_t(-1)) == a);
      CHECK(r.value("clipIndex", -1) == 0);
      CHECK(Near(r["start"].get<double>(), 0.5, 1e-4));
      const auto after = Snap();
      CHECK(ClipIs(after, a, 0, 0.5, 1.5));
      CHECK(UndoName() == "Move Clip");
      CheckUndoRedo("clips.move", before, after);
      // STALE: the old generation
      CHECK(Err("clips.move", { { "trackId", a }, { "clipIndex", 0 },
         { "generation", g }, { "newStart", 0.0 } }) == "STALE");
   }
   // Clamped at the next clip ([2, 3])
   {
      auto r = REQ("clips.move", { { "trackId", a }, { "clipIndex", 0 },
         { "generation", Gen() }, { "newStart", 1.5 } });
      CHECK(Near(r["start"].get<double>(), 1.0, 1e-4));
      CHECK(ClipIs(Snap(), a, 0, 1.0, 2.0));
   }
   // No room at all: nothing changes, no history entry
   {
      const auto h = HistoryCount();
      auto r = REQ("clips.move", { { "trackId", a }, { "clipIndex", 0 },
         { "generation", Gen() }, { "newStart", 2.5 } });
      CHECK(Near(r["start"].get<double>(), 1.0, 1e-4));
      CHECK(HistoryCount() == h);
   }
   CHECK(Err("clips.move", { { "trackId", a }, { "clipIndex", 7 },
      { "generation", Gen() }, { "newStart", 0.0 } }) == "NOT_FOUND");
   CHECK(Err("clips.move", { { "trackId", a }, { "clipIndex", 0 },
      { "generation", Gen() } }) == "INVALID_ARGS");

   // To another track
   const auto b = REQ("tracks.add", { { "kind", "mono" } }).value("id", int64_t(-1));
   {
      const auto before = Snap();
      auto r = REQ("clips.move", { { "trackId", a }, { "clipIndex", 1 },
         { "generation", before["generation"] }, { "newStart", 2.0 },
         { "toTrackId", b } });
      CHECK(r.value("trackId", int64_t(-1)) == b);
      CHECK(r.value("clipIndex", -1) == 0);
      const auto after = Snap();
      CHECK(ClipCount(after, a) == 1);
      CHECK(ClipCount(after, b) == 1);
      CHECK(ClipIs(after, b, 0, 2.0, 3.0));
      CHECK(UndoName() == "Move Clip");
      CheckUndoRedo("clips.move to another track", before, after);
   }
   // Overlap in the destination
   CHECK(Err("clips.move", { { "trackId", a }, { "clipIndex", 0 },
      { "generation", Gen() }, { "newStart", 2.5 }, { "toTrackId", b } }) ==
      "FAILED");
   // Other channel count
   const auto st = REQ("tracks.add", { { "kind", "stereo" } }).value("id", int64_t(-1));
   CHECK(Err("clips.move", { { "trackId", a }, { "clipIndex", 0 },
      { "generation", Gen() }, { "newStart", 0.0 }, { "toTrackId", st } }) ==
      "INVALID_ARGS");
   // Other rate: the clip is resampled
   const auto c = REQ("tracks.add", { { "kind", "mono" } }).value("id", int64_t(-1));
   REQ("tracks.setRate", { { "id", c }, { "rate", 22050 } });
   REQ("clips.move", { { "trackId", a }, { "clipIndex", 0 },
      { "generation", Gen() }, { "newStart", 0.0 }, { "toTrackId", c } });
   {
      auto snap = Snap();
      CHECK(ClipCount(snap, c) == 1);
      CHECK(Clip(snap, c, 0).value("rate", 0) == 22050);
      CHECK(ClipIs(snap, c, 0, 0.0, 1.0, 1e-3));
   }

   // Rename
   {
      const auto before = Snap();
      REQ("clips.rename", { { "trackId", b }, { "clipIndex", 0 },
         { "generation", before["generation"] }, { "name", "Chorus 🎵" } });
      const auto after = Snap();
      CHECK(Clip(after, b, 0).value("name", "") == "Chorus 🎵");
      CHECK(UndoName() == "Clip Name Edit");
      CheckUndoRedo("clips.rename", before, after);
      CHECK(Err("clips.rename", { { "trackId", b }, { "clipIndex", 0 },
         { "generation", before["generation"] }, { "name", "x" } }) == "STALE");
   }
}

// ---------------------------------------------------------------------------
//! history.list: the long description of the current state
std::string CurrentDescription()
{
   auto r = Call("history.list");
   CHECK(Ok(r));
   if (!Ok(r))
      return {};
   const auto current = r["result"].value("current", -1);
   for (const auto &state : r["result"]["states"])
      if (state.value("index", -2) == current)
         return state.value("description", "");
   return {};
}

void TestSplitAt()
{
   std::fprintf(stderr, "== edit.splitAt\n");
   NewProject();
   const auto a = Tone(3.0);
   const auto b = Tone(3.0);

   // Default: the selected wave tracks; the cursor moves to t
   SelectOnly({ a });
   SetSel(0.25, 0.75);
   {
      const auto before = Snap();
      const auto h = HistoryCount();
      auto r = REQ("edit.splitAt", { { "t", 1.5 } });
      CHECK(r.value("splits", -1) == 1);
      CHECK(r["trackIds"] == json::array({ a }));
      const auto after = Snap();
      CHECK(ClipCount(after, a) == 2);
      CHECK(ClipCount(after, b) == 1);
      CHECK(ClipIs(after, a, 0, 0.0, 1.5));
      CHECK(ClipIs(after, a, 1, 1.5, 3.0));
      CHECK(SelectionIs(after, 1.5, 1.5));
      CHECK(Selected(after, a) && !Selected(after, b));
      CHECK(HistoryCount() == h + 1);
      CHECK(Gen() > before["generation"].get<uint64_t>());
      CHECK(UndoName() == "Split");
      CHECK(CurrentDescription() == "Split");
      CheckUndoRedo("edit.splitAt", before, after);
      // Undo restores one clip
      REQ("history.undo");
      CHECK(ClipCount(Snap(), a) == 1);
      REQ("history.redo");
      CHECK(ClipCount(Snap(), a) == 2);
   }
   // Again at (almost) the same point: nothing to split -- no empty clip,
   // no history entry, the selection is kept
   {
      SetSel(0.5, 0.5);
      const auto h = HistoryCount();
      const auto g = Gen();
      auto r = REQ("edit.splitAt", { { "t", 1.5 + 1e-7 } });
      CHECK(r.value("splits", -1) == 0);
      CHECK(r["trackIds"] == json::array());
      const auto snap = Snap();
      CHECK(ClipCount(snap, a) == 2);
      CHECK(SelectionIs(snap, 0.5, 0.5));
      CHECK(HistoryCount() == h);
      // Beyond the end and in front of the audio
      CHECK(REQ("edit.splitAt", { { "t", 10.0 } }).value("splits", -1) == 0);
      CHECK(REQ("edit.splitAt", { { "t", -1.0 } }).value("splits", -1) == 0);
      CHECK(HistoryCount() == h);
      // ... and the clip references stay valid
      CHECK(Gen() == g);
   }
   // Explicit tracks (one entry for both), whatever is selected
   {
      const auto before = Snap();
      const auto h = HistoryCount();
      auto r = REQ("edit.splitAt", { { "t", 2.0 }, { "trackIds", { b, a, b } } });
      CHECK(r.value("splits", -1) == 2);
      CHECK(r["trackIds"] == json::array({ b, a }));
      const auto after = Snap();
      CHECK(ClipCount(after, a) == 3);
      CHECK(ClipCount(after, b) == 2);
      CHECK(ClipIs(after, a, 2, 2.0, 3.0));
      CHECK(ClipIs(after, b, 0, 0.0, 2.0));
      CHECK(SelectionIs(after, 2.0, 2.0));
      CHECK(Selected(after, a) && !Selected(after, b));
      CHECK(HistoryCount() == h + 1);
      CheckUndoRedo("edit.splitAt trackIds", before, after);
   }
   // No track selected: the tracks whose clips contain t
   {
      const auto c = Tone(1.0);
      REQ("select.none");
      const auto snap0 = Snap();
      CHECK(!Selected(snap0, a) && !Selected(snap0, b) && !Selected(snap0, c));
      auto r = REQ("edit.splitAt", { { "t", 2.5 } });
      CHECK(r.value("splits", -1) == 2);
      CHECK(r["trackIds"] == json::array({ a, b }));
      const auto after = Snap();
      CHECK(ClipCount(after, a) == 4);
      CHECK(ClipCount(after, b) == 3);
      CHECK(ClipCount(after, c) == 1);
      CHECK(SelectionIs(after, 2.5, 2.5));
      // ... still no track selected
      CHECK(!Selected(after, a) && !Selected(after, b));
      REQ("history.undo");
      // c only
      r = REQ("edit.splitAt", { { "t", 0.5 }, { "trackIds", { c } } });
      CHECK(r["trackIds"] == json::array({ c }));
      CHECK(ClipCount(Snap(), c) == 2);
      // Stereo tracks split both channels
      const auto st = Tone(2.0, 2);
      r = REQ("edit.splitAt", { { "t", 1.0 }, { "trackIds", { st } } });
      CHECK(r.value("splits", -1) == 1);
      CHECK(ClipCount(Snap(), st) == 2);
      CHECK(ClipIs(Snap(), st, 1, 1.0, 2.0));
   }
   // Errors
   const auto lt = REQ("tracks.add", { { "kind", "label" } }).value("id", int64_t(-1));
   CHECK(Err("edit.splitAt") == "INVALID_ARGS");
   CHECK(Err("edit.splitAt", { { "t", "x" } }) == "INVALID_ARGS");
   CHECK(Err("edit.splitAt", { { "t", 1.0 }, { "trackIds", json::array() } })
      == "INVALID_ARGS");
   CHECK(Err("edit.splitAt", { { "t", 1.0 }, { "trackIds", { 987654 } } })
      == "NOT_FOUND");
   CHECK(Err("edit.splitAt", { { "t", 1.0 }, { "trackIds", { lt } } })
      == "NOT_FOUND");
}

double TrimOf(const json &snap, int64_t id, size_t index, const char *key)
{
   auto c = Clip(snap, id, index);
   return c.contains(key) ? c[key].get<double>() : -1.0;
}

json Trim(int64_t track, int clipIndex, json extra)
{
   extra["trackId"] = track;
   extra["clipIndex"] = clipIndex;
   extra["generation"] = Gen();
   return extra;
}

void TestClipTrim()
{
   std::fprintf(stderr, "== clips.trim\n");
   NewProject();
   const auto a = Tone(3.0);
   const auto before = Snap();
   const int rate = Clip(before, a, 0).value("rate", 44100);
   const double sample = 1.0 / rate;
   const auto g0 = Gen();
   const auto h0 = HistoryCount();

   // Live drag (final:false): model change only
   {
      auto r = REQ("clips.trim", Trim(a, 0, { { "trimLeft", 0.5 },
         { "final", false } }));
      CHECK(Near(r["trimLeft"].get<double>(), 0.5));
      CHECK(Near(r["start"].get<double>(), 0.5, 1e-4));
      CHECK(Near(r["end"].get<double>(), 3.0, 1e-4));
      CHECK(Gen() == g0);
      CHECK(HistoryCount() == h0);
      const auto snap = Snap();
      CHECK(ClipIs(snap, a, 0, 0.5, 3.0));
      CHECK(Near(TrimOf(snap, a, 0, "trimLeft"), 0.5));
      CHECK(TrackOf(snap, a)["waveVersion"] != TrackOf(before, a)["waveVersion"]);
   }
   {
      const auto from = gSink->Count();
      REQ("clips.trim", Trim(a, 0, { { "trimLeft", 0.6 }, { "final", false } }));
      REQ("clips.trim", Trim(a, 0, { { "trimLeft", 0.75 }, { "final", false } }));
      // the last value arrives in a snapshot event (trailing, <= 10 Hz)
      auto ev = gSink->WaitFor("snapshot", 3s, from, [&](const json &snap) {
         return Near(TrimOf(snap, a, 0, "trimLeft"), 0.75);
      });
      CHECK_MSG(ev.has_value(), "no snapshot with the last live trim");
      CHECK(Gen() == g0);
   }
   // final:true: ONE entry for the whole drag, measured from its start
   {
      auto r = REQ("clips.trim", Trim(a, 0, { { "trimLeft", 1.0 },
         { "final", true } }));
      CHECK(Near(r["trimLeft"].get<double>(), 1.0));
      CHECK(Gen() > g0);
      CHECK(HistoryCount() == h0 + 1);
      CHECK(UndoName() == "Trim by 1.00s");
      CHECK(CurrentDescription() == "Adjust left trim by 1.00 seconds");
      const auto after = Snap();
      CHECK(ClipIs(after, a, 0, 1.0, 3.0));
      CheckUndoRedo("clips.trim left", before, after);
      // STALE: the generation of the drag
      CHECK(Err("clips.trim", { { "trackId", a }, { "clipIndex", 0 },
         { "generation", g0 }, { "trimLeft", 0.0 } }) == "STALE");
   }
   // Right border; final defaults to true
   {
      const auto b4 = Snap();
      REQ("clips.trim", Trim(a, 0, { { "trimRight", 0.5 } }));
      CHECK(UndoName() == "Trim by 0.50s");
      CHECK(CurrentDescription() == "Adjust right trim by 0.50 seconds");
      const auto after = Snap();
      CHECK(ClipIs(after, a, 0, 1.0, 2.5));
      CHECK(Near(TrimOf(after, a, 0, "trimLeft"), 1.0));
      CHECK(Near(TrimOf(after, a, 0, "trimRight"), 0.5));
      CheckUndoRedo("clips.trim right", b4, after);
   }
   // Clamped to the clip's audio
   {
      auto r = REQ("clips.trim", Trim(a, 0, { { "trimLeft", -5.0 },
         { "trimRight", -1.0 } }));
      CHECK(Near(r["trimLeft"].get<double>(), 0.0));
      CHECK(Near(r["trimRight"].get<double>(), 0.0));
      CHECK(ClipIs(Snap(), a, 0, 0.0, 3.0));
      // At least one sample stays
      r = REQ("clips.trim", Trim(a, 0, { { "trimLeft", 10.0 } }));
      CHECK(Near(r["start"].get<double>(), 3.0 - sample, 1e-9));
      CHECK(Near(r["end"].get<double>(), 3.0, 1e-9));
      r = REQ("clips.trim", Trim(a, 0, { { "trimLeft", 0.0 },
         { "trimRight", 10.0 } }));
      CHECK(Near(r["start"].get<double>(), 0.0, 1e-9));
      CHECK(Near(r["end"].get<double>(), sample, 1e-9));
      // Whole samples
      r = REQ("clips.trim", Trim(a, 0, { { "trimLeft", 0.1 + 0.3 * sample },
         { "trimRight", 0.0 } }));
      CHECK(Near(r["trimLeft"].get<double>() * rate,
         std::rint(r["trimLeft"].get<double>() * rate), 1e-6));
      REQ("clips.trim", Trim(a, 0, { { "trimLeft", 0.0 } }));
      CHECK(ClipIs(Snap(), a, 0, 0.0, 3.0));
   }
   // Neighbours: a split hides audio that trimming brings back, up to the
   // neighbouring clip
   {
      REQ("edit.splitAt", { { "t", 1.5 }, { "trackIds", { a } } });
      CHECK(ClipCount(Snap(), a) == 2);
      const auto h = HistoryCount();
      // Clip 1 cannot grow over clip 0, clip 0 not over clip 1: no change,
      // no entry
      auto r = REQ("clips.trim", Trim(a, 1, { { "trimLeft", 0.0 } }));
      CHECK(Near(r["start"].get<double>(), 1.5, 1e-9));
      r = REQ("clips.trim", Trim(a, 0, { { "trimRight", 0.0 } }));
      CHECK(Near(r["end"].get<double>(), 1.5, 1e-9));
      CHECK(HistoryCount() == h);
      // Shorten clip 0 to [0, 1), then clip 1 can grow to 1.0
      REQ("clips.trim", Trim(a, 0, { { "trimRight", 2.0 } }));
      r = REQ("clips.trim", Trim(a, 1, { { "trimLeft", 0.0 } }));
      CHECK(Near(r["start"].get<double>(), 1.0, 1e-9));
      CHECK(Near(r["trimLeft"].get<double>(), 1.0, 1e-9));
      const auto snap = Snap();
      CHECK(ClipIs(snap, a, 0, 0.0, 1.0));
      CHECK(ClipIs(snap, a, 1, 1.0, 3.0));
      CHECK(HistoryCount() == h + 2);
   }
   // A drag that returns to its start: no entry, the snapshot shows the
   // original geometry
   {
      const auto h = HistoryCount();
      REQ("clips.trim", Trim(a, 1, { { "trimLeft", 1.4 }, { "final", false } }));
      CHECK(ClipIs(Snap(), a, 1, 1.4, 3.0));
      const auto from = gSink->Count();
      auto env = Call("clips.trim", Trim(a, 1, { { "trimLeft", 1.0 } }));
      CHECK(Ok(env));
      auto ev = SnapshotEventSince(from, env);
      CHECK(ClipIs(ev, a, 1, 1.0, 3.0));
      CHECK(HistoryCount() == h);
   }
   // An unfinished live drag of another clip is cancelled by the next
   // drag: only the finished one is in the entry
   {
      const auto h = HistoryCount();
      const auto b4 = Snap();
      REQ("clips.trim", Trim(a, 0, { { "trimRight", 2.5 }, { "final", false } }));
      CHECK(ClipIs(Snap(), a, 0, 0.0, 0.5));
      REQ("clips.trim", Trim(a, 1, { { "trimRight", 0.25 }, { "final", false } }));
      CHECK(ClipIs(Snap(), a, 0, 0.0, 1.0));
      REQ("clips.trim", Trim(a, 1, { { "trimRight", 0.5 }, { "final", true } }));
      const auto after = Snap();
      CHECK(ClipIs(after, a, 0, 0.0, 1.0));
      CHECK(ClipIs(after, a, 1, 1.0, 2.5));
      CHECK(HistoryCount() == h + 1);
      CHECK(CurrentDescription() == "Adjust right trim by 0.50 seconds");
      CheckUndoRedo("clips.trim cancels another drag", b4, after);
   }
   // A live drag whose generation is gone (undo in between) starts over
   {
      REQ("clips.trim", Trim(a, 1, { { "trimLeft", 1.2 }, { "final", false } }));
      REQ("history.undo");
      const auto snap = Snap();
      CHECK(ClipIs(snap, a, 1, 1.0, 3.0));
      REQ("clips.trim", Trim(a, 1, { { "trimLeft", 1.25 } }));
      CHECK(CurrentDescription() == "Adjust left trim by 0.25 seconds");
   }
   // Errors
   CHECK(Err("clips.trim", Trim(a, 0, json::object())) == "INVALID_ARGS");
   CHECK(Err("clips.trim", Trim(a, 0, { { "trimLeft", "x" } })) == "INVALID_ARGS");
   CHECK(Err("clips.trim", Trim(a, 9, { { "trimLeft", 0.0 } })) == "NOT_FOUND");
   CHECK(Err("clips.trim", { { "trackId", a }, { "clipIndex", 0 },
      { "trimLeft", 0.0 } }) == "INVALID_ARGS");
}

// ---------------------------------------------------------------------------
json LabelsOf(const json &snap, int64_t id)
{
   auto t = TrackOf(snap, id);
   return t.is_object() && t.contains("labels") ? t["labels"] : json::array();
}

bool SameLabels(const json &x, const json &y, double eps)
{
   if (x.size() != y.size())
      return false;
   for (size_t i = 0; i < x.size(); ++i)
      if (!Near(x[i]["t0"].get<double>(), y[i]["t0"].get<double>(), eps) ||
          !Near(x[i]["t1"].get<double>(), y[i]["t1"].get<double>(), eps) ||
          x[i]["title"] != y[i]["title"])
         return false;
   return true;
}

void TestLabels()
{
   std::fprintf(stderr, "== labels\n");
   NewProject();
   const std::string dir = gDirs->root;
   CHECK(Err("labels.export", { { "path", dir + "/none.txt" },
      { "format", "text" } }) == "FAILED");

   const auto a = Tone(3.0);
   SelectOnly({ a });
   SetSel(1.0, 2.0);
   int64_t lt;
   {
      const auto before = Snap();
      auto r = REQ("labels.add", { { "title", "Intro" } });
      lt = r.value("trackId", int64_t(-1));
      CHECK(r.value("index", -1) == 0);
      const auto after = Snap();
      auto t = TrackOf(after, lt);
      CHECK(t.value("kind", "") == "label");
      CHECK(t.value("selected", false) && t.value("focused", false));
      CHECK(LabelsOf(after, lt).size() == 1);
      CHECK(LabelsOf(after, lt)[0].value("title", "") == "Intro");
      CHECK(Near(LabelsOf(after, lt)[0]["t0"].get<double>(), 1.0));
      CHECK(Near(LabelsOf(after, lt)[0]["t1"].get<double>(), 2.0));
      CHECK(UndoName() == "Label");
      CheckUndoRedo("labels.add", before, after);
   }
   // The focused label track receives the next label; the index is sorted
   SetSel(0.5, 0.5);
   {
      auto r = REQ("labels.add", { { "title", "A" } });
      CHECK(r.value("trackId", int64_t(-1)) == lt);
      CHECK(r.value("index", -1) == 0);
      CHECK(LabelsOf(Snap(), lt)[1].value("title", "") == "Intro");
   }
   // Edit the title
   {
      const auto before = Snap();
      auto r = REQ("labels.edit", { { "trackId", lt }, { "index", 0 },
         { "generation", before["generation"] }, { "title", "Start" } });
      CHECK(r.value("index", -1) == 0);
      const auto after = Snap();
      CHECK(LabelsOf(after, lt)[0].value("title", "") == "Start");
      CHECK(UndoName() == "Label Edit");
      CheckUndoRedo("labels.edit title", before, after);
   }
   // Edit times: re-sorted, the new index is returned
   {
      const auto before = Snap();
      auto r = REQ("labels.edit", { { "trackId", lt }, { "index", 0 },
         { "generation", before["generation"] }, { "t0", 2.5 }, { "t1", 2.75 } });
      CHECK(r.value("index", -1) == 1);
      const auto after = Snap();
      const auto labels = LabelsOf(after, lt);
      CHECK(labels.size() == 2);
      CHECK(labels[0].value("title", "") == "Intro");
      CHECK(labels[1].value("title", "") == "Start");
      CHECK(Near(labels[1]["t0"].get<double>(), 2.5));
      CHECK(Near(labels[1]["t1"].get<double>(), 2.75));
      CheckUndoRedo("labels.edit times", before, after);
      CHECK(Err("labels.edit", { { "trackId", lt }, { "index", 0 },
         { "generation", before["generation"] }, { "title", "x" } }) == "STALE");
   }
   // Moving a label backwards
   {
      auto r = REQ("labels.edit", { { "trackId", lt }, { "index", 1 },
         { "t0", 0.25 }, { "t1", 0.5 } });
      CHECK(r.value("index", -1) == 0);
      CHECK(LabelsOf(Snap(), lt)[0].value("title", "") == "Start");
   }
   // No change: no history entry
   {
      const auto h = HistoryCount();
      auto r = REQ("labels.edit", { { "trackId", lt }, { "index", 0 },
         { "title", "Start" } });
      CHECK(r.value("index", -1) == 0);
      CHECK(HistoryCount() == h);
   }
   // At an explicit position (e.g. the play head): the selection is not
   // used and not changed
   {
      SetSel(0.1, 0.2);
      const auto before = Snap();
      auto r = REQ("labels.add", { { "title", "Here" }, { "t0", 2.25 } });
      CHECK(r.value("trackId", int64_t(-1)) == lt);
      auto after = Snap();
      CHECK(SelectionIs(after, 0.1, 0.2));
      auto labels = LabelsOf(after, lt);
      const auto index = r.value("index", -1);
      CHECK(index >= 0 && index < int(labels.size()));
      if (index >= 0 && index < int(labels.size())) {
         CHECK(labels[index].value("title", "") == "Here");
         CHECK(Near(labels[index]["t0"].get<double>(), 2.25));
         CHECK(Near(labels[index]["t1"].get<double>(), 2.25));
      }
      CHECK(UndoName() == "Label");
      CheckUndoRedo("labels.add at t0", before, after);
      r = REQ("labels.add", { { "title", "Range" }, { "t0", 0.5 }, { "t1", 0.8 } });
      after = Snap();
      CHECK(SelectionIs(after, 0.1, 0.2));
      labels = LabelsOf(after, lt);
      const auto i2 = r.value("index", -1);
      CHECK(i2 >= 0 && i2 < int(labels.size()));
      if (i2 >= 0 && i2 < int(labels.size())) {
         CHECK(labels[i2].value("title", "") == "Range");
         CHECK(Near(labels[i2]["t0"].get<double>(), 0.5));
         CHECK(Near(labels[i2]["t1"].get<double>(), 0.8));
      }
      const auto h = HistoryCount();
      CHECK(Err("labels.add", { { "t1", 1.0 } }) == "INVALID_ARGS");
      CHECK(Err("labels.add", { { "t0", 1.0 }, { "t1", 0.5 } }) == "INVALID_ARGS");
      CHECK(Err("labels.add", { { "t0", -1.0 } }) == "INVALID_ARGS");
      CHECK(Err("labels.add", { { "t0", "x" } }) == "INVALID_ARGS");
      CHECK(HistoryCount() == h);
      // The checks below expect the labels from before
      REQ("history.undo");
      REQ("history.undo");
      CHECK(SameLabels(LabelsOf(Snap(), lt), LabelsOf(before, lt), 1e-9));
   }
   CHECK(Err("labels.edit", { { "trackId", lt }, { "index", 9 }, { "title", "x" } })
      == "NOT_FOUND");
   CHECK(Err("labels.edit", { { "trackId", lt }, { "index", 0 }, { "t0", 3.0 },
      { "t1", 2.0 } }) == "INVALID_ARGS");
   CHECK(Err("labels.edit", { { "trackId", a }, { "index", 0 }, { "title", "x" } })
      == "NOT_FOUND");
   // Unicode title
   SetSel(2.9, 2.95);
   REQ("labels.add", { { "title", "인트로 🎵 \"q\"" } });
   CHECK(LabelsOf(Snap(), lt).size() == 3);
   // Remove
   {
      const auto before = Snap();
      REQ("labels.remove", { { "trackId", lt }, { "index", 0 },
         { "generation", before["generation"] } });
      const auto after = Snap();
      CHECK(LabelsOf(after, lt).size() == 2);
      CHECK(LabelsOf(after, lt)[0].value("title", "") == "Intro");
      CHECK(UndoName() == "Label Edit");
      CheckUndoRedo("labels.remove", before, after);
      CHECK(Err("labels.remove", { { "trackId", lt }, { "index", 0 },
         { "generation", before["generation"] } }) == "STALE");
   }
   CHECK(Err("labels.remove", { { "trackId", lt }, { "index", 5 } }) == "NOT_FOUND");
   const auto original = LabelsOf(Snap(), lt);

   // Text round trip
   const std::string txt = dir + "/labels.txt";
   {
      auto r = REQ("labels.export", { { "path", txt }, { "format", "text" } });
      CHECK(r.value("labels", 0) == 2);
      const auto text = ReadFile(txt);
      CHECK_MSG(text.find("1.000000\t2.000000\tIntro") != std::string::npos, text);
      CHECK(text.find("인트로 🎵") != std::string::npos);
      // Exporting again replaces the file
      REQ("labels.export", { { "path", txt }, { "format", "text" } });
      CHECK(ReadFile(txt) == text);
   }
   {
      const auto before = Snap();
      auto r = REQ("labels.import", { { "path", txt } });
      const auto imported = r.value("trackId", int64_t(-1));
      const auto after = Snap();
      auto t = TrackOf(after, imported);
      CHECK(t.value("kind", "") == "label");
      CHECK(t.value("name", "") == "labels");
      CHECK(t.value("selected", false) && !Selected(after, a) && !Selected(after, lt));
      CHECK_MSG(SameLabels(LabelsOf(after, imported), original, 1e-5),
         LabelsOf(after, imported).dump());
      CHECK(UndoName() == "Import Labels");
      CheckUndoRedo("labels.import", before, after);
      REQ("tracks.remove", { { "ids", { imported } } });
   }
   // SubRip round trip
   const std::string srt = dir + "/labels.srt";
   {
      REQ("labels.export", { { "path", srt }, { "format", "subrip" } });
      const auto text = ReadFile(srt);
      CHECK_MSG(text.find("00:00:01,000 --> 00:00:02,000") != std::string::npos, text);
      auto r = REQ("labels.import", { { "path", srt } });
      const auto imported = r.value("trackId", int64_t(-1));
      CHECK_MSG(SameLabels(LabelsOf(Snap(), imported), original, 1e-3),
         LabelsOf(Snap(), imported).dump());
      REQ("tracks.remove", { { "ids", { imported } } });
   }
   // WebVTT / podcast chapters: export only
   const std::string vtt = dir + "/labels.vtt";
   REQ("labels.export", { { "path", vtt }, { "format", "webvtt" } });
   CHECK(ReadFile(vtt).rfind("WEBVTT", 0) == 0);
   CHECK(ReadFile(vtt).find("00:00:01.000 --> 00:00:02.000") != std::string::npos);
   CHECK(Err("labels.import", { { "path", vtt } }) == "UNSUPPORTED");
   const std::string chapters = dir + "/chapters.json";
   REQ("labels.export", { { "path", chapters }, { "format", "podcastChapters" } });
   CHECK(ReadFile(chapters).find("\"chapters\"") != std::string::npos);
   CHECK(Err("labels.import", { { "path", chapters } }) == "UNSUPPORTED");
   CHECK(Err("labels.export", { { "path", txt }, { "format", "midi" } }) ==
      "INVALID_ARGS");
   CHECK(Err("labels.import", { { "path", dir + "/missing.txt" } }) == "NOT_FOUND");
   // Labels go through the clipboard and region edits like audio
   {
      NewProject();
      const auto l2 = REQ("tracks.add", { { "kind", "label" } }).value("id", int64_t(-1));
      SetSel(1.0, 2.0);
      REQ("labels.add", { { "title", "X" } });
      SelectOnly({ l2 });
      SetSel(0.5, 2.5);
      REQ("edit.copy");
      SetSel(3.0, 3.0);
      REQ("edit.paste");
      auto labels = LabelsOf(Snap(), l2);
      CHECK(labels.size() == 2);
      if (labels.size() == 2) {
         CHECK(Near(labels[1]["t0"].get<double>(), 3.5));
         CHECK(Near(labels[1]["t1"].get<double>(), 4.5));
         CHECK(labels[1].value("title", "") == "X");
      }
      SetSel(0.5, 2.5);
      REQ("edit.delete");
      labels = LabelsOf(Snap(), l2);
      CHECK(labels.size() == 1);
      if (labels.size() == 1) {
         CHECK(Near(labels[0]["t0"].get<double>(), 1.5));
         CHECK(Near(labels[0]["t1"].get<double>(), 2.5));
      }
   }
   // A hand-written file: one-sided label, extra whitespace in titles
   const std::string hand = dir + "/hand.txt";
   WriteFile(hand, "0.5\tPoint\n1.25\t1.5\tTwo words  \n");
   {
      auto r = REQ("labels.import", { { "path", hand } });
      auto labels = LabelsOf(Snap(), r.value("trackId", int64_t(-1)));
      CHECK(labels.size() == 2);
      if (labels.size() == 2) {
         CHECK(Near(labels[0]["t0"].get<double>(), 0.5) &&
            Near(labels[0]["t1"].get<double>(), 0.5));
         CHECK(labels[0].value("title", "") == "Point");
         CHECK(labels[1].value("title", "") == "Two words  ");
      }
   }
}

} // namespace

int main()
{
   TempDirs dirs;
   gDirs = &dirs;
   std::fprintf(stderr, "test root: %s\n", dirs.root.c_str());
   gSink = std::make_shared<Sink>();
   if (!aubridge::Start(dirs.ConfigJson(), gSink)) {
      std::fprintf(stderr, "Start failed\n");
      return 1;
   }
   auto ready = gSink->WaitFor("engine.ready", 180s);
   if (!ready) {
      auto failed = gSink->Last("engine.failed");
      std::fprintf(stderr, "engine failed: %s\n",
         failed ? failed->dump().c_str() : "(timeout)");
      aubridge::Stop();
      return 1;
   }

   TestSelection();
   TestClipNavigation();
   TestZeroCrossingAndSwap();
   TestClipboard();
   TestRegionEdits();
   TestTrackManagement();
   TestMixer();
   TestStereo();
   TestRateFormatMix();
   TestAlign();
   TestClips();
   TestSplitAt();
   TestClipTrim();
   TestLabels();

   aubridge::Stop();
   gSink.reset();
   if (Failures() == 0)
      std::fprintf(stderr, "bridge-test-edit: all checks passed\n");
   else
      std::fprintf(stderr, "bridge-test-edit: %d check(s) FAILED\n", Failures());
   return Failures() == 0 ? 0 : 1;
}
