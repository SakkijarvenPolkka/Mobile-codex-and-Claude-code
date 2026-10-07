/*  SPDX-License-Identifier: GPL-2.0-or-later */
/**********************************************************************

  Audacity Android port

  ProjectCommands.cpp

  project.* commands (API.md §3.3 "project").  Pre-checks of
  project.open follow Audacity 3.7.9 ProjectFileManager::OpenFile; the
  metadata edit follows TagsEditorDialog::EditProjectMetadata.

**********************************************************************/
#include "SpineCommands.h"

#include <wx/dir.h>
#include <wx/ffile.h>
#include <wx/filename.h>
#include <wx/log.h>

#include "ActiveProjects.h"
#include "Edit.h"
#include "Project.h"
#include "ProjectFileIO.h"
#include "ProjectHistory.h"
#include "ProjectRate.h"
#include "ProjectSession.h"
#include "Session.h"
#include "Snapshot.h"
#include "Tags.h"
#include "TempDirectory.h"

namespace aubridge {

namespace {

bool EndsWithNoCase(const wxString &s, const wxString &suffix)
{
   return s.Lower().EndsWith(suffix.Lower());
}

FilePath RequireAbsolutePath(const json &args, const char *key = "path")
{
   const auto path = FromUtf8(ArgString(args, key));
   if (path.empty() || !wxFileName(path).IsAbsolute())
      Fail(ErrorCode::INVALID_ARGS,
         std::string("argument '") + key + "' must be an absolute path");
   return wxFileName(path).GetFullPath();
}

FilePath RequireNewProjectPath(const json &args)
{
   const auto path = RequireAbsolutePath(args);
   if (!EndsWithNoCase(path, wxT(".aup3")))
      Fail(ErrorCode::INVALID_ARGS, "the path must end with .aup3");
   if (!wxDirExists(wxPathOnly(path)))
      Fail(ErrorCode::INVALID_ARGS, "the directory of the path does not exist");
   return path;
}

bool IsOpen(const FilePath &path)
{
   auto *project = Session::Get().Project();
   if (!project)
      return false;
   auto &fileIO = ProjectFileIO::Get(*project);
   return wxFileName(path).SameAs(wxFileName(fileIO.GetFileName()));
}

json FileEntry(const FilePath &path)
{
   wxFileName fn{ path };
   long long modified = 0;
   {
      wxLogNull noLog;
      if (fn.FileExists())
         modified = fn.GetModificationTime().GetValue().GetValue();
   }
   unsigned long long size = 0;
   for (const auto &suffix : { wxString{}, wxString{ wxT("-wal") } }) {
      const auto s = wxFileName::GetSize(path + suffix);
      if (s != wxInvalidSize)
         size += s.GetValue();
   }
   return json{ { "path", ToUtf8(fn.GetFullPath()) },
      { "name", ToUtf8(fn.GetName()) },
      { "modifiedMs", modified }, { "sizeBytes", size } };
}

// Pre-checks of ProjectFileManager::OpenFile for a project file
void CheckProjectFile(const FilePath &fileName)
{
   if (fileName.Lower().EndsWith(wxT(".aup3.bak")) ||
       fileName.Lower().EndsWith(wxT("~.aup3")))
      Fail(ErrorCode::INVALID_ARGS, Translated(XO(
"You are trying to open an automatically created backup file.\nDoing this may result in severe data loss.\n\nPlease open the actual Audacity project file instead.")));
   if (!wxFileExists(fileName))
      Fail(ErrorCode::NOT_FOUND,
         Translated(XO("Could not open file: %s").Format(fileName)));
   {
      wxLogNull noLog;
      wxFFile ff(fileName, wxT("rb"));
      if (!ff.IsOpened())
         Fail(ErrorCode::FAILED,
            Translated(XO("Could not open file: %s").Format(fileName)));
      char buf[7];
      const auto numRead = ff.Read(buf, 6);
      if (numRead != 6)
         Fail(ErrorCode::FAILED, Translated(
            XO("File may be invalid or corrupted: \n%s").Format(fileName)));
      if (wxStrncmp(buf, "SQLite", 6) != 0)
         Fail(ErrorCode::INVALID_ARGS,
            "not an Audacity project (use import.files)");
   }
   if (IsOpen(fileName))
      Fail(ErrorCode::FAILED, Translated(
         XO("%s is already open in another window.")
            .Format(wxFileName(fileName).GetName())));
   // Disallow opening of .aup3 project files from FAT drives (Bug 2800)
   if (TempDirectory::FATFilesystemDenied(fileName,
      XO("Project resides on FAT formatted drive.\n"
        "Copy it to another drive to open it.")))
      Fail(ErrorCode::FAILED, Translated(XO(
         "Project resides on FAT formatted drive.\n"
         "Copy it to another drive to open it.")));
}

//! Opens `fileName` into a fresh session; the current project is closed
//! only when that succeeded
void OpenInto(const FilePath &fileName)
{
   CheckProjectFile(fileName);
   auto fresh = ProjectSession::CreateNew();
   std::string error;
   if (!fresh->OpenProjectFile(fileName, error)) {
      fresh->Close();
      Fail(ErrorCode::FAILED, error);
   }
   Session::Get().SetCurrent(std::move(fresh));
}

json ProjectNew(const json &args)
{
   auto &session = Session::Get();
   const bool closeCurrent = OptBool(args, "closeCurrent").value_or(true);
   if (!closeCurrent && session.Project())
      Fail(ErrorCode::UNSUPPORTED, "only one project can be open at a time");
   session.CloseCurrent();
   session.SetCurrent(ProjectSession::CreateNew());
   return json::object();
}

json ProjectOpen(const json &args)
{
   OpenInto(RequireAbsolutePath(args));
   return json::object();
}

json ProjectSave(const json &)
{
   auto &session = Session::Get();
   auto &project = session.RequireProject();
   auto &fileIO = ProjectFileIO::Get(project);
   if (fileIO.IsTemporary())
      Fail(ErrorCode::NEEDS_PATH,
         "the project was never saved: use project.saveAs");
   std::string error;
   if (!session.Current()->Save(error))
      Fail(ErrorCode::FAILED, error.empty() ? "Saving failed" : error);
   session.ScheduleSnapshot();
   return json{ { "path", ToUtf8(fileIO.GetFileName()) } };
}

json ProjectSaveAs(const json &args)
{
   auto &session = Session::Get();
   auto &project = session.RequireProject();
   const auto path = RequireNewProjectPath(args);
   std::string error;
   if (!session.Current()->SaveAs(path, error))
      Fail(ErrorCode::FAILED, error.empty() ? "Saving failed" : error);
   session.ScheduleSnapshot();
   return json{ { "path", ToUtf8(ProjectFileIO::Get(project).GetFileName()) } };
}

json ProjectSaveCopy(const json &args)
{
   auto &session = Session::Get();
   session.RequireProject();
   const auto path = RequireNewProjectPath(args);
   std::string error;
   if (!session.Current()->SaveCopy(path, error))
      Fail(ErrorCode::FAILED, error.empty() ? "Saving a copy failed" : error);
   return json{ { "path", ToUtf8(path) } };
}

json ProjectClose(const json &)
{
   Session::Get().CloseCurrent();
   return json::object();
}

json ProjectInfo(const json &)
{
   return BuildProjectInfo();
}

json ProjectSnapshot(const json &)
{
   return Session::Get().EmitSnapshot();
}

json ProjectSetRate(const json &args)
{
   auto &project = Session::Get().RequireProject();
   const auto rate = ArgDouble(args, "rate");
   RequireRange("rate", rate, 1000, 768000);
   ProjectRate::Get(project).SetRate(rate);
   // Not undoable in 3.7.9, but persisted with the next autosave
   ModifyState(project, true);
   return json::object();
}

json ProjectRecoverable(const json &)
{
   FilePath exclude;
   if (auto project = Session::Get().Project())
      exclude = ProjectFileIO::Get(*project).GetFileName();
   json projects = json::array();
   for (const auto &path : ProjectSession::ScanRecoverable(exclude))
      projects.push_back(FileEntry(path));
   return json{ { "projects", std::move(projects) } };
}

json ProjectRecover(const json &args)
{
   OpenInto(RequireAbsolutePath(args));
   return json::object();
}

json ProjectDiscardRecoverable(const json &args)
{
   std::vector<FilePath> paths;
   for (const auto &p : ArgStringArray(args, "paths")) {
      const auto path = wxFileName(FromUtf8(p)).GetFullPath();
      if (IsOpen(path))
         Fail(ErrorCode::INVALID_ARGS,
            "cannot discard the open project " + p);
      paths.push_back(path);
   }
   for (const auto &path : paths)
      ProjectSession::DiscardRecoverable(path);
   return json::object();
}

json TagsGet(const json &)
{
   auto &project = Session::Get().RequireProject();
   json tags = json::array();
   for (const auto &[name, value] : Tags::Get(project).GetRange())
      tags.push_back(json{ { "name", ToUtf8(name) }, { "value", ToUtf8(value) } });
   return json{ { "tags", std::move(tags) } };
}

json TagsSet(const json &args)
{
   auto &project = Session::Get().RequireProject();
   auto it = args.find("tags");
   if (it == args.end() || !it->is_array())
      Fail(ErrorCode::INVALID_ARGS, "argument 'tags' must be an array");
   std::vector<std::pair<wxString, wxString>> entries;
   for (const auto &tag : *it) {
      if (!tag.is_object())
         Fail(ErrorCode::INVALID_ARGS, "tags must be {name, value} objects");
      const auto name = FromUtf8(ArgString(tag, "name"));
      const auto value = FromUtf8(OptString(tag, "value").value_or(""));
      if (name.empty() || !name.IsAscii())
         Fail(ErrorCode::INVALID_ARGS, "tag names must be non-empty ASCII");
      entries.emplace_back(name, value);
   }
   // TagsEditorDialog::EditProjectMetadata: the Tags object may be shared
   // with undo states, so replace it with an edited duplicate
   RunEdit(project, XO("Edit Metadata Tags"), XO("Metadata Tags"), [&] {
      auto &tags = Tags::Get(project);
      auto newTags = tags.Duplicate();
      newTags->Clear();
      for (const auto &[name, value] : entries)
         newTags->SetTag(name, value);
      if (tags == *newTags)
         return false;
      Tags::Set(project, newTags);
      return true;
   });
   return json::object();
}

json ProjectList(const json &)
{
   json projects = json::array();
   const auto dir = FromUtf8(Session::Get().GetPaths().projectsDir);
   if (wxDirExists(dir)) {
      wxLogNull noLog;
      wxArrayString files;
      wxDir::GetAllFiles(dir, &files, wxT("*.aup3"), wxDIR_FILES);
      files.Sort();
      for (const auto &file : files) {
         // Hide the safety backups of Save As (with __WXGTK__ named
         // "<name>~.aup3", ProjectFileIO::SafetyFileName) and compaction
         // leftovers
         if (file.EndsWith(wxT("~.aup3")) || file.Contains(wxT("_compact_temp")))
            continue;
         projects.push_back(FileEntry(file));
      }
   }
   return json{ { "projects", std::move(projects) } };
}

json ProjectDelete(const json &args)
{
   const auto path = RequireAbsolutePath(args);
   if (!EndsWithNoCase(path, wxT(".aup3")) &&
       !EndsWithNoCase(path, wxT(".aup3unsaved")))
      Fail(ErrorCode::INVALID_ARGS, "not a project file");
   if (IsOpen(path))
      Fail(ErrorCode::INVALID_ARGS, "cannot delete the open project");
   if (!wxFileExists(path))
      Fail(ErrorCode::NOT_FOUND, "no such file");
   if (!ProjectFileIO::RemoveProject(path))
      Fail(ErrorCode::FAILED, "could not delete " + ToUtf8(path));
   ActiveProjects::Remove(path);
   return json::object();
}

} // namespace

void RegisterProjectCommands(ModuleRegistry &registry)
{
   registry.AddCommand("project.new", ProjectNew, NeedsIdleAudio);
   registry.AddCommand("project.open", ProjectOpen, NeedsIdleAudio | LongRunning);
   registry.AddCommand("project.save", ProjectSave,
      NeedsProject | NeedsIdleAudio | LongRunning);
   registry.AddCommand("project.saveAs", ProjectSaveAs,
      NeedsProject | NeedsIdleAudio | LongRunning);
   registry.AddCommand("project.saveCopy", ProjectSaveCopy,
      NeedsProject | NeedsIdleAudio | LongRunning);
   registry.AddCommand("project.close", ProjectClose, NeedsIdleAudio);
   registry.AddCommand("project.info", ProjectInfo);
   registry.AddCommand("project.snapshot", ProjectSnapshot);
   registry.AddCommand("project.setRate", ProjectSetRate,
      NeedsProject | NeedsIdleAudio | SelectionOnly);
   registry.AddCommand("project.recoverable", ProjectRecoverable);
   registry.AddCommand("project.recover", ProjectRecover,
      NeedsIdleAudio | LongRunning);
   registry.AddCommand("project.discardRecoverable", ProjectDiscardRecoverable,
      NeedsIdleAudio);
   registry.AddCommand("project.tags.get", TagsGet, NeedsProject);
   registry.AddCommand("project.tags.set", TagsSet,
      NeedsProject | NeedsIdleAudio | Mutates);
   registry.AddCommand("project.list", ProjectList);
   registry.AddCommand("project.delete", ProjectDelete, NeedsIdleAudio);
}

} // namespace aubridge
