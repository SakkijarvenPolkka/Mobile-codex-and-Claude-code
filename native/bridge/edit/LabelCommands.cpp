/*  SPDX-License-Identifier: GPL-2.0-or-later */
/**********************************************************************

  Audacity Android port

  LabelCommands.cpp

  labels.* (API.md §3.3 "clips / labels").  Ports of Audacity 3.7.9:
   * src/menus/LabelMenus.cpp: DoAddLabel, OnAddLabel, OnAddLabelPlaying
     (labels.add uses an explicit t0/t1, else the play position while this
     project's stream is active, else the selection)
   * src/tracks/labeltrack/ui/LabelTrackView.cpp, LabelGlyphHandle.cpp:
     the "Modified Label" / "Deleted Label" history entries of label text
     and glyph edits (labels.edit, labels.remove)
   * src/menus/FileMenus.cpp: OnImportLabels, OnExportLabels (the file
     dialogs are replaced by the `path` argument)

  Label references carry an optional `generation` (STALE when old): label
  indices shift on every add/sort.

**********************************************************************/
#include "EditUtil.h"

#include <memory>

#include <wx/filename.h>
#include <wx/textfile.h>

#include "AudioIO.h"
#include "Edit.h"
#include "LabelTrack.h"
#include "ModuleRegistry.h"
#include "ProjectAudioIO.h"
#include "Session.h"
#include "Track.h"
#include "TrackFocus.h"
#include "ViewInfo.h"

namespace aubridge {
namespace edit {

namespace {

AudacityProject &Project()
{
   return Session::Get().RequireProject();
}

LabelTrack &RequireLabelTrack(AudacityProject &project, int64_t id)
{
   if (auto lt = dynamic_cast<LabelTrack *>(TrackById(project, id)))
      return *lt;
   Fail(ErrorCode::NOT_FOUND, "no label track with id " + std::to_string(id));
}

int RequireLabelIndex(const LabelTrack &lt, const json &args)
{
   const auto index = ArgInt(args, "index");
   if (index < 0 || index >= lt.GetNumLabels())
      Fail(ErrorCode::NOT_FOUND, "no label " + std::to_string(index));
   return int(index);
}

json LabelsAdd(const json &args)
{
   auto &project = Project();
   auto &tracks = TrackList::Get(project);
   const wxString title = FromUtf8(OptString(args, "title").value_or(""));
   const auto t0 = OptTime(args, "t0");
   const auto t1 = OptTime(args, "t1");
   if (t1 && !t0)
      Fail(ErrorCode::INVALID_ARGS, "argument 't1' needs 't0'");

   SelectedRegion region = ViewInfo::Get(project).selectedRegion;
   if (t0) {
      // An explicit position (e.g. the play head the UI shows): the
      // selection is not used and not changed
      const double end = t1.value_or(*t0);
      if (*t0 < 0 || end < *t0)
         Fail(ErrorCode::INVALID_ARGS,
            "label times must satisfy 0 <= t0 <= t1");
      region = SelectedRegion(*t0, end);
   }
   else {
      // OnAddLabel: at the selection; OnAddLabelPlaying: at the play
      // position while the stream of this project is active
      const auto token = ProjectAudioIO::Get(project).GetAudioIOToken();
      if (auto gAudioIO = AudioIO::Get();
          token > 0 && gAudioIO && gAudioIO->IsStreamActive(token)) {
         const double indicator = gAudioIO->GetStreamTime();
         region = SelectedRegion(indicator, indicator);
      }
   }

   LabelTrack *lt = nullptr;
   int index = -1;
   RunEdit(project, XO("Added label"), XO("Label"), [&] {
      // The focused label track (DoAddLabel), else the first selected label
      // track, else a NEW label track
      lt = dynamic_cast<LabelTrack *>(
         TrackFocus::Get(project).PeekFocus().get());
      if (!lt)
         lt = *tracks.Selected<LabelTrack>().begin();
      if (!lt)
         lt = LabelTrack::Create(tracks);
      lt->SetSelected(true);
      index = lt->AddLabel(region, title);
   });
   TrackFocus::Get(project).Set(lt);
   return json{ { "trackId", TrackIdValue(*lt) }, { "index", index } };
}

json LabelsEdit(const json &args)
{
   auto &project = Project();
   CheckOptionalGeneration(args);
   auto &lt = RequireLabelTrack(project, ArgInt(args, "trackId"));
   const int index = RequireLabelIndex(lt, args);
   const auto title = OptString(args, "title");
   const auto t0 = OptTime(args, "t0");
   const auto t1 = OptTime(args, "t1");

   LabelStruct label = *lt.GetLabel(index);
   const double n0 = t0.value_or(label.getT0());
   const double n1 = t1.value_or(label.getT1());
   if (n1 < n0)
      Fail(ErrorCode::INVALID_ARGS, "label times must satisfy t0 <= t1");
   const wxString newTitle = title ? FromUtf8(*title) : label.title;
   const bool timesChanged = n0 != label.getT0() || n1 != label.getT1();
   if (!timesChanged && newTitle == label.title)
      return json{ { "index", index } };

   int newIndex = index;
   RunEdit(project, XO("Modified Label"), XO("Label Edit"), [&] {
      label.selectedRegion.setTimes(n0, n1);
      label.title = newTitle;
      lt.SetLabel(size_t(index), label);
      if (timesChanged) {
         // SetLabel does not re-sort.  Follow the label through the
         // permutations SortLabels publishes: rotate(j, i, i + 1) moves
         // label i to j and shifts j..i-1 up by one
         auto subscription = lt.Subscribe([&](const LabelTrackEvent &e) {
            if (e.type != LabelTrackEvent::Permutation)
               return;
            const int from = e.mFormerPosition, to = e.mPresentPosition;
            if (newIndex == from)
               newIndex = to;
            else if (to <= newIndex && newIndex < from)
               ++newIndex;
         });
         lt.SortLabels();
      }
   });
   return json{ { "index", newIndex } };
}

json LabelsRemove(const json &args)
{
   auto &project = Project();
   CheckOptionalGeneration(args);
   auto &lt = RequireLabelTrack(project, ArgInt(args, "trackId"));
   const int index = RequireLabelIndex(lt, args);
   RunEdit(project, XO("Deleted Label"), XO("Label Edit"),
      [&] { lt.DeleteLabel(index); });
   return json::object();
}

json LabelsImport(const json &args)
{
   auto &project = Project();
   auto &tracks = TrackList::Get(project);
   const wxString fileName = FromUtf8(ArgString(args, "path"));
   if (!wxFileName::FileExists(fileName))
      Fail(ErrorCode::NOT_FOUND, "no such file: " + ToUtf8(fileName));
   const LabelFormat format = LabelTrack::FormatForFileName(fileName);
   // LabelTrack::Import only reads text and SubRip files
   if (format == LabelFormat::WEBVTT)
      Fail(ErrorCode::UNSUPPORTED, Translated(
         XO("Importing WebVTT files is not currently supported.")));
   if (format != LabelFormat::TEXT && format != LabelFormat::SUBRIP)
      Fail(ErrorCode::UNSUPPORTED, "labels can be imported from text and "
         "SubRip (.srt) files only");

   wxTextFile f;
   f.Open(fileName);
   if (!f.IsOpened())
      Fail(ErrorCode::FAILED, Translated(
         XO("Could not open file: %s").Format(fileName)));

   auto newTrack = std::make_shared<LabelTrack>();
   wxString sTrackName;
   wxFileName::SplitPath(fileName, nullptr, nullptr, &sTrackName, nullptr);
   newTrack->SetName(sTrackName);
   // Unreadable lines are skipped ("One or more saved labels could not be
   // read." is a non-blocking dialog)
   newTrack->Import(f, format);

   RunEdit(project, XO("Imported labels from '%s'").Format(fileName),
      XO("Import Labels"), [&] {
         SelectNoTracks(project);
         newTrack->SetSelected(true);
         tracks.Add(newTrack);
      });
   return json{ { "trackId", TrackIdValue(*newTrack) } };
}

json LabelsExport(const json &args)
{
   auto &project = Project();
   const wxString fName = FromUtf8(ArgString(args, "path"));
   const auto formatName = ArgString(args, "format");
   LabelFormat format;
   if (formatName == "text")
      format = LabelFormat::TEXT;
   else if (formatName == "subrip")
      format = LabelFormat::SUBRIP;
   else if (formatName == "webvtt")
      format = LabelFormat::WEBVTT;
   else if (formatName == "podcastChapters")
      format = LabelFormat::PODCAST_CHAPTERS_JSON;
   else
      Fail(ErrorCode::INVALID_ARGS,
         "argument 'format' must be text, subrip, webvtt or podcastChapters");

   auto trackRange = TrackList::Get(project).Any<const LabelTrack>();
   if (trackRange.empty())
      Fail(ErrorCode::FAILED,
         Translated(XO("There are no label tracks to export.")));

   // wxTextFile would append to an existing file: replace it
   if (wxFileExists(fName) && !wxRemoveFile(fName))
      Fail(ErrorCode::FAILED,
         Translated(XO("Couldn't write to file: %s").Format(fName)));

   wxTextFile f(fName);
   f.Create();
   f.Open();
   if (!f.IsOpened())
      Fail(ErrorCode::FAILED,
         Translated(XO("Couldn't write to file: %s").Format(fName)));

   int count = 0;
   for (auto lt : trackRange) {
      lt->Export(f, format);
      count += lt->GetNumLabels();
   }
   const bool written = f.Write();
   f.Close();
   if (!written)
      Fail(ErrorCode::FAILED,
         Translated(XO("Couldn't write to file: %s").Format(fName)));
   return json{ { "path", ToUtf8(fName) }, { "labels", count } };
}

} // namespace

void RegisterLabelCommands(ModuleRegistry &registry)
{
   // M, I: labels can be added while playing or recording
   registry.AddCommand("labels.add", LabelsAdd, NeedsProject | Mutates);
   const unsigned m = NeedsProject | NeedsIdleAudio | Mutates;
   registry.AddCommand("labels.edit", LabelsEdit, m);
   registry.AddCommand("labels.remove", LabelsRemove, m);
   registry.AddCommand("labels.import", LabelsImport, m);
   // "-": no model change (AudioIONotBusy | LabelTracksExist in 3.7.9)
   registry.AddCommand("labels.export", LabelsExport,
      NeedsProject | NeedsIdleAudio);
}

} // namespace edit
} // namespace aubridge
