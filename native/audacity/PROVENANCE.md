# Vendored Audacity sources

This directory contains a subset of the Audacity source tree, imported from the
official repository at tag **Audacity-3.7.9**
(commit `86d74c770974b25188ca2f23bcce47c1181bd08a`, https://github.com/audacity/audacity).

Imported paths (relative to the Audacity repository root):

| Path here | Upstream path | Notes |
|-----------|---------------|-------|
| `libraries/lib-*` | `libraries/lib-*` | Toolkit-neutral core libraries. GUI-only and desktop-only libraries (wx GUI wrappers, themes, VST/VST3/AU/LV2/LADSPA hosts, cloud, crash reporting, URL schemes) were not imported. |
| `modules/import-export/mod-*` | `modules/import-export/mod-*` | Importer/exporter modules (FFmpeg and command-line export not imported). |
| `lib-src/*` | `lib-src/*` | Bundled third-party code: libsoxr, SoundTouch, libsbsms, pffft, portsmf, SQLite, TwoLAME, Nyquist. |
| `cmake-proxies/*` | `cmake-proxies/*` | Build descriptions of the bundled third-party code (reference). |
| `nyquist/`, `plug-ins/` | same | Nyquist runtime and bundled Nyquist plug-ins. |
| `include/`, `src/audacity_config.h.in`, `LICENSE.txt` | same | |

The first commit that adds this directory contains the files exactly as they are
upstream; every later change made for the Android port is a separate, reviewable
commit. Audacity is licensed under the GNU GPL (see `LICENSE.txt`); this port is
distributed under the same terms.
