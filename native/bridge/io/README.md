<!-- SPDX-License-Identifier: GPL-2.0-or-later -->
# Bridge module "io" (`native/bridge/io`)

`import.*` and `export.*` of `../API.md` §3.3 / §5.6, on top of the 3.7.9
`lib-import-export` registries and the `mod-*` plug-ins. The Android codec
plug-ins (AAC/M4A/AMR/... import, M4A export) live in `android/` (see its
README); they register themselves and need nothing from this directory.

| File | What |
|---|---|
| `RegisterIoModule.cpp` | entry point: commands, lib-app-services import handler (after bootstrap), registry diagnostics (`log`), Android: AAC encoder probe pre-warm on a detached worker |
| `ImportCommands.cpp` | `import.formats`, `import.files`; port of `ProjectFileManager::Import/DoImport/AddImportedTracks` and the `ImportProgress` listener |
| `ExportCommands.cpp` | `export.formats/defaults/options/setOption/run`; options sessions; port of the `ExportAudioDialog`/`ExportFilePanel` rules and of `ExportProgressUI::Show` |
| `IoModule.h` | internal interface between these files |

## Import

* One `RunEditSelf` per file (a failure rolls back that file only); the
  undo entry is `Imported '<file name>'`. Files are sorted by name like
  `FileMenus::DoImport`; failures go to `messages`, the command fails only
  when nothing was imported.
* `BridgeImportListener`: `SetStreamUsage` for every file (the OGG importer
  defaults its links to "unused"), a `multiChoice` dialog
  (`Dialogs::ChooseMany`) for multi-stream files, `ProgressScope`
  (cancel → `Cancel()`, stop → `Stop()`), the importers' error messages,
  a free-space check against `GetFileUncompressedBytes()`.
* **Upstream bug worked around:** 3.7.9 `OggImportFileHandle::Import`
  dereferences the null `TrackListHolder` of an unused link
  (`FinalizeImport(outTracks, std::move(*stream))`, `ImportOGG.cpp:338-341`),
  i.e. deselecting a link of a chained Ogg file crashes (desktop too). The
  listener decodes all links and drops the tracks of the deselected ones
  (`FilterStreams`, link → track count parsed from the stream description).
  A two-line guard in `ImportOGG.cpp` (`if (stream)`) would be the real fix.
* Tags: the project's `Tags` are duplicated and handed to the importer
  (3.7.9 `DoImport`), so PCM/MP3 merge and FLAC/Ogg/WavPack replace; the
  undo entry restores them.
* The first import into an empty project sets the project rate to the first
  imported track's rate (not in 3.7.9's `AddImportedTracks`; Audacity ≤ 3.3
  did it) and names a temporary project after the file.
* `newProject`: imports into a fresh `ProjectSession`, which replaces the
  current project only when something was imported.
* lib-app-services `ProjectFileManager::SetImportHandler`: mod-aup's
  `<import>` elements (and mod-lof on the host) import nested into the
  running file's undo entry (`NestedImport`).

## Export

* Options session per format key: the editor is (re)loaded from the
  preferences by `export.options`; `export.setOption` checks the tag, enum
  membership, range and read-only flags, sets and stores at once.
  WavPack's correction file is forced off and reported hidden/read-only;
  "Other uncompressed files" stores once on creation so that
  `ExportPCM::GetFormatInfo(1)` (reads the prefs) agrees with the editor.
* `export.run`: staging path must not exist (3.7.9 `ExportTaskBuilder::Build`
  would overwrite in place); `Build()` (→ `Initialize`) on the engine thread
  with `mBatchMode` raised (MP3 never asks to resample); `Process` on a
  joined worker thread while the engine thread drains only internal work and
  publishes progress; exceptions mapped like `ExceptionWrappedCall`; the
  output (and a WavPack `.wvc` the call created) is deleted on failure and
  cancel; a `Cancelled` result nobody asked for (an `Initialize` that
  returned false) is `FAILED`.
* Channels 1/2 only (no custom mapping); MP3 reports `canMetaData:false`
  (the port has no libid3tag).

## Tests

`native/tests/bridge/io` → `bridge-test-io` (ctest `bridge-io`, label `io`).
