<!-- SPDX-License-Identifier: GPL-2.0-or-later -->
# Android codec plug-ins (`native/bridge/io/android`)

Two Audacity 3.7.9 plug-ins that use the Android platform codecs through the
NDK (`libmediandk`), so that the port can open and write the formats that
desktop Audacity handles with FFmpeg (which the port does not build):

| File | What |
|---|---|
| `AndroidMediaImport.cpp` | `ImportPlugin` "AAC, M4A, AMR and other formats (Android)" — `AMediaExtractor` + `AMediaCodec` decoders |
| `AndroidAacExport.cpp` | `ExportPlugin` "M4A (AAC) Files" — `AMediaCodec` AAC encoder + `AMediaMuxer` (MPEG-4) |
| `AndroidCodecs.h` | identifiers for self-checks, `AacEncoderCapabilities()` (optional pre-warm) |
| `AndroidMediaCommon.h` | RAII owners, logging, format keys |
| `sources.cmake` | `AUBRIDGE_IO_ANDROID_SOURCES` (+ `mediandk`, `log`) |
| `hosttest/` | host harness: the real plug-in code against a fake NDK media layer |

Both plug-ins register themselves with the static registrars of
lib-import-export (`Importer::RegisteredImportPlugin`,
`ExportPluginRegistry::RegisteredPlugin`). `aubridge-io` is linked as a whole
archive into `libaudacity-bridge.so`, so the registrars run when the library is
loaded, before the spine calls `Importer::Initialize()` /
`ExportPluginRegistry::Initialize()`. Nothing has to call into these files.

## Build integration

* `../sources.cmake` includes `android/sources.cmake`; `native/bridge/CMakeLists.txt`
  compiles `AUBRIDGE_IO_ANDROID_SOURCES` into `aubridge-io` **on Android only**
  and links `mediandk` when that list is not empty (`log` comes with
  `aubridge-headers`; `sources.cmake` also appends both to
  `AUBRIDGE_IO_ANDROID_LIBRARIES`).
* Only NDK symbols of API ≤ 28 are used (minSdk 28):
  `AMediaExtractor_getFileFormat/getSampleSize`, `AMediaCodec_getName` are API 28,
  everything else API 21. Format keys newer than API 21 are spelled as string
  literals (`"pcm-encoding"`, `"csd-0"`, `"title"` ...).

## Importer (`AndroidMediaImport.cpp`)

**Registration order.** `Importer::Initialize()` merges the registry with the
preference `/Importers` (default `AUP,PCM,OGG,FLAC,MP3,LOF,WavPack,portsmf,FFmpeg`).
Plug-ins named there come first, in that order; all others are placed in a final
pass in *ascending identifier order*. The design note proposed an
`OrderingHint::End` placement — that does **not** work: the End item is placed
in an earlier pass, and Opus (hint *Unspecified*) is appended after it (verified
on the host: `AUP,PCM,OGG,FLAC,MP3,LOF,WavPack,AndroidMedia,Opus`). The plug-in
therefore registers with the identifier **`android-media`** and no hint: a
lower-case identifier sorts after every capitalised one, so it is placed after
Opus, and the merged order is remembered in `/Importers`
(`...,WavPack,Opus,android-media`), also on devices that already have an older
`/Importers` value. Files whose extension the plug-in lists still try it first
(`Import.cpp` puts extension matches first).

**Extensions** (first dibs): `m4a m4b m4r mp4 m4v mov aac adts 3gp 3gpp 3g2 3ga amr
awb mkv mka webm weba ts`. Formats with native importers (wav, aiff, mp3, ogg,
opus, flac, wv) are not listed; for those and for everything else this plug-in
is the last resort ("anything MediaExtractor decodes").

**Probe (`Open`).** `open()` + `fstat` (regular, non-empty file), a fresh
`AMediaExtractor` on the fd (the NDK dup()s it), rejection of DRM
(`getPsshInfo`), one stream per **audio** track for which
`AMediaCodec_createDecoderByType` succeeds (AC-3/DTS/... are device dependent).
Stream descriptions use the FFmpeg importer's msgid
(`Index[%02x] Codec[%s], Language[%s], Bitrate[%s], Channels[%d], Duration[%d]`)
so existing translations apply. All streams default to *used*
(`SetStreamUsage` may deselect; a `nullptr` listener imports all).

**Decoding** (per used stream, sequentially, on the calling = engine thread):

* fresh extractor, `selectTrack`, decoder configured with the track format plus
  `"pcm-encoding" = float` (retried without when configure fails; not set for
  `audio/raw`, whose pcm-encoding describes the input), `max-input-size`
  defaulted to 128 KiB when the container has none;
* synchronous loop: feed until `TRY_AGAIN_LATER` (`dequeueInputBuffer(0)`), then
  drain outputs (10 ms wait only if nothing could be fed); `getSampleSize`
  checked against the input buffer; encrypted samples rejected; read errors at
  the end of a truncated file = end of stream (+ warning);
* output encodings: 16 bit (also when the key is absent), float, and for
  `audio/raw` 8-bit unsigned / 24-bit packed / 32-bit int (converted to float,
  effective format int16/int24/float); tracks are created with
  `ImportUtils::ChooseFormat(effective)` — float for decoders that honour the
  float request, like the desktop FFmpeg importer;
* output format = `OUTPUT_FORMAT_CHANGED` (or `getOutputFormat()` at the first
  buffer for codecs that never signal it); `info.offset` honoured;
* channels: 2 → one stereo track, 1 → mono, *n* > 2 → *n* mono tracks in Android
  channel order (FL, FR, FC, LFE, ...), as `ImportPCM` does;
* format changes: a first segment shorter than 0.1 s decoded before the output
  format settled (implicit HE-AAC SBR/PS signalling) is discarded and the
  track starts at the elapsed time; a later sample-rate change starts a **new
  track** at the elapsed time (a WaveTrack has one rate); a later channel-count
  change keeps the tracks and maps channels (drop extra / repeat last);
* leading output frames with **negative time stamps** (encoder priming hidden
  by an MP4 edit list, applied by MPEG4Extractor since Android 11) are dropped
  (at most 0.25 s, only before the first appended frame) → gapless;
* progress: `OnImportProgress` at most every 40 ms and at the end of each
  stream (stream *k* of *n* covers [k/n, (k+1)/n], by output PTS / track
  duration); `IsCancelled()`/`IsStopped()` are checked right after each call
  (the bridge listener calls `Cancel()`/`Stop()` from inside it);
* Cancel → `Cancelled`, all tracks discarded; Stop → the audio decoded so far is
  kept (`Stopped`; a Stop before any audio is reported as `Cancelled`, because a
  `Stopped` result without tracks makes `Importer` try other plug-ins);
* codec errors (`dequeue*`/`queue*` errors, unsupported encoding, stall > 10 s):
  audio decoded before the error is kept and a warning listing the stream is
  shown (`ImportUtils::ShowMessageBox`, one box per file); a stream that yields
  nothing is an error (`GetErrorMessage()` set); a decoder that never returns
  the EOS buffer is finished 3 s after input EOS;
* all output buffers are released on every path (scope guard), codecs and
  extractors are RAII-owned.

**Tags.** `AMediaExtractor_getFileFormat` reports container metadata since API 29
(`title`, `artist`, `albumartist`, `author`, `album`, `genre`, `year`, `date`,
`cdtracknum`). Applied like `FFmpegImportFileHandle::WriteMetadata`: a `Tags`
with the user's defaults plus the file's TITLE/ARTIST/ALBUM/TRACKNUMBER/GENRE/
YEAR replaces the project tags when it has a title, artist or album; `"3/12"` →
`3`, numeric iTunes genre → ID3v1 name, year from `date`. On API 28 there are no
tags (a `MediaMetadataRetriever` hint path from Kotlin would be the fallback —
not implemented).

## Exporter (`AndroidAacExport.cpp`)

`FormatInfo { "M4A", "M4A (AAC) Files", {"m4a"}, maxChannels 2, canMetaData false }`,
MIME `audio/mp4`, registry id `AndroidAAC` placed **after MP3**
(`OrderingHint::After "MP3"`). Format key (API.md) = `M4A (AAC) Files`.

**Encoder capabilities** (`AacEncoderCapabilities()`, `AndroidCodecs.h`): probed
once per process on first use (options editor or export), thread-safe
(`std::call_once`). Each probe configures an encoder, encodes 8192 frames of
silence and waits for an encoded access unit (Codec2's software AAC encoder
validates some settings only when encoding starts). Rates probed (stereo LC,
64 kbps): 8000 … 96000; profiles: HE-AAC / HE-AAC v2 at 44.1 (or 48) kHz,
accepted only if the AudioSpecificConfig (`csd-0`) shows SBR/PS (explicit
signalling) or a half-rate core (implicit) — an encoder that silently falls
back to LC is not offered HE. Time budget 5 s, abort after 2 timed-out probes;
typical cost 0.1–0.5 s. If nothing could be verified, a conservative rate list
and LC only are offered (`verified = false`). The io module may call
`AacEncoderCapabilities()` once on a worker thread after bootstrap to pre-warm.

**Options** (custom `ExportOptionsEditor`, prefs `/FileFormats/AndroidAAC/*`):

| index | id | title | used for | values (enum int) | default |
|---|---|---|---|---|---|
| 0 | 1 | Profile | – | 2 = AAC-LC, 5 = HE-AAC, 29 = HE-AAC v2 — only the probed ones; ReadOnly if only LC | 2 |
| 1 | 0 | Bit Rate | AAC-LC | 32, 48, 64, 96, 128, 160, 192, 256, 320 kbps (bps values, names "N kbps") | 192000 |
| 2 | 2 | Bit Rate | HE-AAC; Hidden unless profile 5 | 24, 32, 48, 64 kbps | 48000 |
| 3 | 3 | Bit Rate | HE-AAC v2; Hidden unless profile 29 | 16, 24, 32, 48 kbps | 32000 |

All values are `int`. `SetValue` rejects wrong types *and* values that are not
in the enum (stricter than `PlainExportOptionsEditor`). Changing the profile
toggles the Hidden flags and notifies the listener (`OnExportOptionChange` ×3
between Begin/End, then `OnSampleRateListChange`). Sample rates: the probed
list for LC, restricted to 16–48 kHz for HE/HEv2. Options 2/3 only exist when
the device supports the profile.

**Initialize** (engine thread): validates channels (1–2), profile (unsupported →
LC), rate (must be in the profile's list, else `ExportException`), takes the bit
rate of the chosen profile's option, maps HE-AAC v2 + mono → HE-AAC (PS needs
stereo), clamps LC to 6 bits/sample/channel (e.g. 8 kHz mono ≤ 48 kbps).
Creates and starts the encoder **before** creating the file (rejected settings
leave no file), then `open(O_RDWR|O_CREAT|O_TRUNC)`, `AMediaMuxer_new(MPEG_4)`,
and the Mixer (interleaved **int16**, the encoder's native input; the mixer's
format conversion dithers).

**Process** (export worker thread; synchronous `AMediaCodec` is not thread-affine
as long as it is never used concurrently): mixer → input buffers (whole frames,
PTS = frames/rate), `UpdateProgress` after each mixer block, EOS queued when the
mixer is done (or after Stop, with the last mixed block); drain: the muxer
track is added at `OUTPUT_FORMAT_CHANGED` when the format carries `csd-0`, else
from the `CODEC_CONFIG` buffer at the first access unit; config buffers are not
written; samples are written with the buffer **base** pointer (the NDK adds
`info.offset`), EOS flag stripped, PTS forced non-decreasing; `AMediaMuxer_stop`
writes `moov`; `close()` checked. Errors: codec errors →
`ExportErrorException` (with a hint to try another profile/bit rate/rate),
write/stop/close failures → `ExportDiskFullError`, no audio → `ExportException`;
stall watchdog 10 s.

**Cleanup.** Cancel → `Cancelled` and the file is deleted; any exception →
resources released and the file deleted before rethrowing; a processor
destroyed without a finished file (Initialize threw after creating it, or the
task never ran) deletes it in its destructor; Stopped → a valid, shorter file.

## Verification done (no device available)

* **Android compile, both ABIs**: each `.cpp` compiled with the exact flags of a
  lib-import-export translation unit from `ninja -C native/build-android-<abi> -t compdb`
  (library include dirs as `-isystem`), plus `-Wall -Wextra -Werror
  -Werror=unguarded-availability` — clean for arm64 and x86_64.
* **Link check**: throw-away shared library from the two objects against
  `native/build-android-<abi>/lib/*.so` + `-lmediandk -llog` with
  `-Wl,--no-undefined -Wl,--fatal-warnings` — links for both ABIs.
* **Host harness** (`hosttest/run_hosttest.py`): the real plug-in sources
  compiled for Linux (`-Wall -Wextra -Werror` with GCC) against a fake NDK media
  layer and the host-built 3.7.9 libraries (all modules linked, real
  `Importer`/`ExportTaskBuilder`/project database). 90 checks in four modes: registry order
  (importer last after Opus; M4A after MP3), float/int16/raw-8/24/32 output,
  float rejected at configure, no FORMAT_CHANGED event, non-zero buffer
  offsets, settling format, mid-stream rate change, negative-PTS priming,
  multi-stream + `SetStreamUsage`, 6 channels, cancel, stop, decoder errors
  (start / mid-stream), missing EOS, truncated file, DRM, non-media file, tags,
  missing `max-input-size`; encoder probe (all profiles / LC-only encoder /
  implicit signalling / no encoder), options editor semantics and
  Store/Load, export success (all frames, payload read at base+offset,
  monotonic PTS, csd), cancel/stop/encoder error/write error/unsupported
  rate/task never run (file deleted), HEv2+mono fallback, LC bit-rate clamp,
  csd only in a CODEC_CONFIG buffer; no leaked codecs/extractors/muxers/
  formats/output buffers after every case. It found and fixed a real bug: a
  dangling `utf8_str()` buffer of a temporary `wxString` (the file could not be
  opened).

  ```
  native/bridge/io/android/hosttest/run_hosttest.py [--out DIR]   # needs native/build-host built
  ```

## Device test checklist

Run on at least one Android 9 (API 28, OMX era) and one Android 13+ (Codec2)
device, arm64; x86_64 emulator for smoke. Watch `adb logcat -s AudacityMedia`.

Import
- [ ] `.m4a` (AAC-LC 44.1k stereo, iTunes-encoded with `iTunSMPB`/edit list): length
      equals the source (no ~2112-sample priming at the start on Android 11+);
      `track N: dropped ... leading frames` in the log.
- [ ] HE-AAC and HE-AAC v2 `.m4a` / `.aac` (ADTS): output 44.1/48 kHz stereo, no
      leading glitch, no extra short track (watch for "discarding ... frames").
- [ ] `.3gp`/`.amr` (AMR-NB 8 kHz) and `.awb` (AMR-WB 16 kHz) voice recordings.
- [ ] `.mp4` / `.mkv` / `.webm` video with one audio track (video ignored);
      `.mkv` with two audio tracks (stream dialog shows both, deselecting works).
- [ ] WebM Opus and Vorbis, MKV with FLAC (large frames), MPEG-TS radio capture.
- [ ] 5.1 AAC/AC-3 file: 6 mono tracks in FL, FR, FC, LFE, BL, BR order (AC-3 only
      where the device has a decoder; otherwise "no decoder" in the log).
- [ ] Float output on API ≥ 31 (log shows `encoding 4`); 16-bit on API 28.
- [ ] Tags (API 29+): title/artist/album/track/year/genre of an `.m4a` appear in
      the metadata editor; API 28: project tags unchanged.
- [ ] Cancel and Stop during a long (≥ 30 min) import: cancel leaves nothing,
      stop keeps the beginning; progress reaches 100 %.
- [ ] Truncated `.m4a` (cut with `truncate`): partial import + warning dialog.
- [ ] DRM file (`.m4p` or Widevine `.mp4`): refused, no crash.
- [ ] A `.mp3`, `.wav`, `.flac`, `.ogg`, `.opus` still go to the native importers
      (logcat shows no AudacityMedia activity); a renamed `.m4a` → `.bin`
      imports through this plug-in as the last resort.
- [ ] `/Importers` in `audacity.cfg` ends with `,Opus,android-media`.

Export
- [ ] First export dialog: probe line `AAC encoder probe (... ms): verified rates [...]
      profiles: LC [HE] [HEv2]` — note the duration (budget 5 s).
- [ ] LC 44.1k stereo 192 kbps and 48k mono 64 kbps: plays in the system player,
      VLC, and re-imports with the same length (+ encoder priming ≈ 1024–2048
      samples of leading silence: known).
- [ ] HE-AAC 48 kbps and HE-AAC v2 32 kbps stereo at 44.1k; HE-AAC v2 with a mono
      export (falls back to HE-AAC, log line); each HE bit rate at 22.05/24/32 kHz
      (device encoders reject some combinations: the error message must say so).
- [ ] 8 kHz mono LC 320 kbps → clamped to 48 kbps (log line).
- [ ] Cancel during export: staging file gone; Stop: shorter playable file.
- [ ] Disk full (fill the cache partition): "disk full" error, no partial file.
- [ ] 2 h stereo export: memory stable, file plays, duration correct (moov at end).
- [ ] Export while the probe has not run yet (first action after app start).

## Known gaps / TODO

* Encoder priming is not trimmed on export (no edit list / iTunSMPB:
  `AMediaMuxer` has no API for it) and encoder delay/padding keys
  (`encoder-delay`, `encoder-padding`) are not applied on import unless the
  extractor exposes them as negative time stamps.
* No metadata in exported `.m4a` (`canMetaData = false`); an `ilst` post-pass
  would be a v2 feature.
* A positive start time of the first audio packet is ignored (audio starts at
  0); desktop FFmpeg import inserts silence for it.
* HE-AAC bit-rate limits are device/encoder specific; settings the encoder
  rejects surface as an export error with a hint, not as a smaller option list.
* Import tags need API 29+ (no `MediaMetadataRetriever` fallback on API 28).
* `moov` is written at the end of the file (no "fast start").
