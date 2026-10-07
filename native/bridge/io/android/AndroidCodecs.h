/*  SPDX-License-Identifier: GPL-2.0-or-later */
/**********************************************************************

  Audacity Android port

  AndroidCodecs.h

  Public surface of the Android NDK media plug-ins (native/bridge/io/
  android) for the rest of the bridge.  The plug-ins register themselves
  with static registrars (Importer::RegisteredImportPlugin,
  ExportPluginRegistry::RegisteredPlugin) when libaudacity-bridge.so is
  loaded; nothing has to be called for them to work.  This header only
  offers identifiers for self-checks and an optional pre-warm of the AAC
  encoder probe.

  Android only.

**********************************************************************/
#pragma once

#include <vector>

namespace aubridge::android_media {

//! Registry identifier of the import plug-in (element of pref "/Importers")
inline constexpr const char *kImporterRegistryId = "android-media";
//! ImportPlugin::GetPluginStringID()
inline constexpr const char *kImporterStringId = "android-mediacodec";
//! ImportPlugin::GetPluginFormatDescription() msgid (an entry of
//! Importer::GetFileTypes(); it must be the last plug-in entry)
inline constexpr const char *kImporterDescription =
   "AAC, M4A, AMR and other formats (Android)";

//! Registry identifier of the export plug-in (element of pref "/Exporters")
inline constexpr const char *kExporterRegistryId = "AndroidAAC";
//! FormatInfo::description msgid = API.md export format key
inline constexpr const char *kExportFormatKey = "M4A (AAC) Files";

//! MediaCodecInfo.CodecProfileLevel AAC object types (option "Profile")
inline constexpr int kAacProfileLC = 2;
inline constexpr int kAacProfileHE = 5;
inline constexpr int kAacProfileHEv2 = 29;

struct AacEncoderCaps
{
   //! An "audio/mp4a-latm" encoder exists
   bool available{ false };
   //! At least one probe encode succeeded (otherwise sampleRates is a
   //! conservative default list and only AAC-LC is offered)
   bool verified{ false };
   //! Ascending; stereo AAC-LC encodes at these rates
   std::vector<int> sampleRates;
   //! Subset of { LC, HE, HEv2 }, LC first
   std::vector<int> profiles;
};

//! Probes the device's AAC encoder on first use (creates and runs a few
//! short encoder sessions: typically 0.1 - 0.5 s, bounded by a time
//! budget) and caches the result for the process.  Thread-safe.  The io
//! module may call it once from a worker thread after bootstrap so that the
//! first export dialog does not wait for the probe.
const AacEncoderCaps &AacEncoderCapabilities();

} // namespace aubridge::android_media
