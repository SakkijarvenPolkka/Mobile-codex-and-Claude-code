# Headless stand-ins for wx GUI headers (Audacity Android port)

The port builds only the toolkit-neutral Audacity libraries against a
GUI-less wxBase.  `mod-mp3/ExportMP3.cpp` still contains two pieces of wx GUI
code:

* the "Locate LAME" dialog (`FindDialog`) -- compiled out, because the port
  links LAME and defines `DISABLE_DYNAMIC_LOADING_LAME`; only its class
  declaration remains, and
* `MP3ExportProcessor::AskResample()`, a modal prompt shown when the project
  rate is not a valid MP3 rate and the export is interactive
  (`project.mBatchMode == 0`).

The headers here give those translation units a *headless* implementation:
every dialog behaves as if the user pressed Cancel (`ShowModal()` returns
`wxID_CANCEL`), so an interactive MP3 export at an unsupported rate is
cancelled instead of crashing.  The bridge should export with
`project.mBatchMode` set (then the exporter picks the nearest valid rate by
itself) or resample first.

They are put on the include path (ahead of wxBase) only for the targets named
in `native/cmake/PortFixups.cmake` (currently `mod-mp3`).  `MP3Prefs.cpp`
(a preference-page control) is not compiled at all.
