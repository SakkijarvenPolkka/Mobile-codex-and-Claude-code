/*  SPDX-License-Identifier: GPL-2.0-or-later */
/**********************************************************************

  Audacity Android port

  AudioDevices.cpp

  audio.devices / audio.setDevices / audio.permission / audio.latency /
  audio.setInputOptions (API.md §3.3, §5.3, §6.6), the PortAudio
  re-initialisation for a new device list (instead of DeviceManager::Rescan,
  which is not used: audio-io.md §1.8), the AAudio defaults from the start
  configuration, the input presets, and the bridge-owned latency correction
  (audio-io.md §2.7.4): the duplex offset measured by the host API is stored
  per route after every recording with playback and used, with the user's
  trim, for the next one on that route (TransportManager also re-aligns
  each take to its own measurement).

**********************************************************************/
#include <algorithm>
#include <cctype>
#include <cmath>
#include <cstdint>
#include <iterator>
#include <optional>
#include <string>
#include <vector>

#include "AudioIO.h"
#include "AudioIOBase.h"
#include "Prefs.h"
#include "Project.h"

#include "portaudio.h"
#include "pa_android_aaudio.h"

#include "AudioModule.h"
#include "TransportManager.h"

#include "BridgeError.h"
#include "BridgePrefs.h"
#include "Events.h"
#include "Json.h"
#include "ModuleRegistry.h"
#include "Session.h"

namespace aubridge {

namespace {

//! A device list from Kotlin, kept until AudioIO is idle
struct DeviceDescriptor {
   std::string name;
   //! "<label>: <product>" with its directions, without the " (id N)" that
   //! makes names unique (ids change when a Bluetooth device reconnects)
   std::string routeName;
   int32_t id = 0;
   int maxIn = 0, maxOut = 0;
   double rate = 0;
};

std::optional<std::vector<DeviceDescriptor>> &PendingDevices()
{
   static std::optional<std::vector<DeviceDescriptor>> pending;
   return pending;
}

//! The connected devices (the latest audio.setDevices list, applied or
//! not): where Android routes a "Default" device depends on them
std::string &ConnectedDevicesSignature()
{
   static std::string signature;
   return signature;
}

//! UNPROCESSED input supported (AudioManager
//! PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED), reported by Kotlin
bool &UnprocessedSupported()
{
   static bool supported = false;
   return supported;
}

const wxString kDuplexOffsetGroup = wxT("/Android/AAudio/DuplexOffsetMs/");
const wxString kInputPresetKey = wxT("/Android/AAudio/InputPreset");

// AAUDIO_INPUT_PRESET_* (<aaudio/AAudio.h>)
constexpr int kPresetGeneric = 1;
constexpr int kPresetCamcorder = 5;
constexpr int kPresetVoiceRecognition = 6;
constexpr int kPresetUnprocessed = 9;
constexpr int kPresetVoicePerformance = 10;

struct PresetName {
   const char *name;
   int preset;
};
//! audio.setInputOptions preset names; "auto" = UNPROCESSED where the
//! device supports it, else VOICE_RECOGNITION (mono) / CAMCORDER (stereo:
//! the stereo microphone pair; VOICE_RECOGNITION is one microphone on
//! most phones, duplicated into both channels)
constexpr PresetName kPresetNames[] = {
   { "auto", 0 },
   { "unprocessed", kPresetUnprocessed },
   { "voiceRecognition", kPresetVoiceRecognition },
   { "camcorder", kPresetCamcorder },
   { "generic", kPresetGeneric },
   { "voicePerformance", kPresetVoicePerformance },
};

const char *PresetLabel(int preset)
{
   for (const auto &p : kPresetNames)
      if (p.preset == preset && preset != 0)
         return p.name;
   return "default";
}

//! FNV-1a, as a short stable pref key component
std::string HashHex(const std::string &text)
{
   uint64_t h = 1469598103934665603ULL;
   for (unsigned char c : text) {
      h ^= c;
      h *= 1099511628211ULL;
   }
   static const char digits[] = "0123456789abcdef";
   std::string out(16, '0');
   for (int i = 15; i >= 0; --i, h >>= 4)
      out[size_t(i)] = digits[h & 0xf];
   return out;
}

//! android.media.AudioDeviceInfo TYPE_* labels; nullptr = not offered
const char *TypeLabel(int type, bool &excluded)
{
   excluded = false;
   switch (type) {
   case 2: return "Speaker";
   case 3: return "Wired headset";
   case 4: return "Wired headphones";
   case 5: return "Line analog";
   case 6: return "Line digital";
   case 8: return "Bluetooth";
   case 9: return "HDMI";
   case 11: return "USB device";
   case 12: return "USB accessory";
   case 13: return "Dock";
   case 15: return "Microphone";
   case 19: return "Aux line";
   case 22: return "USB headset";
   case 23: return "Hearing aid";
   case 26: return "BLE headset";
   case 27: return "BLE speaker";
   case 29: return "HDMI eARC";
   case 30: return "BLE broadcast";
   case 31: return "Dock analog";
   // Telephony, call routes, tuners, internal routes: not for a recorder
   // (earpiece, Bluetooth SCO, HDMI ARC, FM, FM/TV tuner, telephony, IP,
   // bus, speaker-safe, remote submix, echo reference)
   case 1: case 7: case 10: case 14: case 16: case 17: case 18: case 20:
   case 21: case 24: case 25: case 28:
      excluded = true;
      return nullptr;
   default: return nullptr;
   }
}

int MaxChannels(const json &d)
{
   int result = 0;
   auto it = d.find("channelCounts");
   if (it != d.end() && it->is_array())
      for (const auto &c : *it)
         if (c.is_number_integer())
            result = std::max(result, c.get<int>());
   // AudioDeviceInfo: an empty array means "any"
   return result > 0 ? std::min(result, 8) : 2;
}

double NativeRate(const json &d)
{
   std::vector<int> rates;
   auto it = d.find("sampleRates");
   if (it != d.end() && it->is_array())
      for (const auto &r : *it)
         if (r.is_number_integer() && r.get<int>() > 0)
            rates.push_back(r.get<int>());
   if (rates.empty())
      return 0;   // the default route's rate (PaAAudio_SetDefaults)
   const int defaultRate = Session::Get().Config().audioOutputSampleRate;
   for (int preferred : { defaultRate, 48000, 44100 })
      if (preferred > 0 &&
          std::find(rates.begin(), rates.end(), preferred) != rates.end())
         return preferred;
   return *std::max_element(rates.begin(), rates.end());
}

std::vector<DeviceDescriptor> ParseDevices(const json &args)
{
   auto it = args.find("devices");
   if (it == args.end() || !it->is_array())
      Fail(ErrorCode::INVALID_ARGS, "devices must be an array");
   std::vector<DeviceDescriptor> result;
   for (const auto &d : *it) {
      if (!d.is_object())
         Fail(ErrorCode::INVALID_ARGS, "every device must be an object");
      DeviceDescriptor desc;
      if (auto id = OptInt(d, "id"))
         desc.id = int32_t(*id);
      const std::string productName = OptString(d, "name").value_or("");
      const bool isSource = OptBool(d, "isSource").value_or(false);
      const bool isSink = OptBool(d, "isSink").value_or(false);
      std::string label;
      auto type = d.find("type");
      if (type != d.end() && type->is_string())
         label = type->get<std::string>();
      else if (type != d.end() && type->is_number_integer()) {
         bool excluded = false;
         const char *text = TypeLabel(type->get<int>(), excluded);
         if (excluded)
            continue;
         label = text ? text : "Device";
      }
      else
         label = "Device";
      desc.name = productName.empty() ? label : label + ": " + productName;
      const int channels = MaxChannels(d);
      desc.maxIn = isSource ? channels : 0;
      desc.maxOut = isSink ? channels : 0;
      if (desc.maxIn == 0 && desc.maxOut == 0)
         continue;
      desc.routeName = std::string(isSink ? ">" : "") + (isSource ? "<" : "") +
         desc.name;
      desc.rate = NativeRate(d);
      result.push_back(std::move(desc));
   }
   // Stable indices: sort by Android id; unique names
   std::stable_sort(result.begin(), result.end(),
      [](const auto &a, const auto &b) { return a.id < b.id; });
   for (size_t i = 0; i < result.size(); ++i) {
      const auto clash = [&](const std::string &name) {
         if (name == PA_AAUDIO_DEFAULT_OUTPUT_NAME ||
             name == PA_AAUDIO_DEFAULT_INPUT_NAME)
            return true;
         for (size_t j = 0; j < i; ++j)
            if (result[j].name == name)
               return true;
         return false;
      };
      if (clash(result[i].name))
         result[i].name += " (id " + std::to_string(result[i].id) + ")";
   }
   return result;
}

bool AudioIdle()
{
   auto gAudioIO = AudioIO::Get();
   return !gAudioIO || (!gAudioIO->IsBusy() && !gAudioIO->IsMonitoring());
}

void ApplyDevices(const std::vector<DeviceDescriptor> &devices)
{
   std::vector<PaAAudioDeviceDesc> descs;
   descs.reserve(devices.size());
   for (const auto &d : devices) {
      PaAAudioDeviceDesc desc{};
      desc.name = d.name.c_str();
      desc.aaudioDeviceId = d.id;
      desc.maxInputChannels = d.maxIn;
      desc.maxOutputChannels = d.maxOut;
      desc.nativeSampleRate = d.rate;
      desc.framesPerBurst = 0;
      descs.push_back(desc);
   }
   PaAAudio_SetDeviceList(descs.empty() ? nullptr : descs.data(),
      int(descs.size()));
   // Only while no stream is open (a Pa_Terminate under AudioIO's stream
   // would free it underneath AudioIO, audio-io.md §1.8)
   auto gAudioIO = AudioIO::Get();
   if (!gAudioIO)
      return;
   Pa_Terminate();
   const auto err = Pa_Initialize();
   if (err != paNoError)
      Events::Log(Events::LogLevel::Error,
         std::string("Pa_Initialize after a device change failed: ") +
         Pa_GetErrorText(err));
   gAudioIO->HandleDeviceChange();
   Events::Log(Events::LogLevel::Info, "audio devices: " +
      std::to_string(Pa_GetDeviceCount()) + " PortAudio devices");
}

json DeviceJson(PaDeviceIndex index, const PaDeviceInfo &info,
   bool isDefault)
{
   const auto *host = Pa_GetHostApiInfo(info.hostApi);
   return json{ { "index", index },
      { "name", info.name ? info.name : "" },
      { "hostApi", host && host->name ? host->name : "" },
      { "maxInputChannels", info.maxInputChannels },
      { "maxOutputChannels", info.maxOutputChannels },
      { "defaultRate", info.defaultSampleRate },
      { "isDefault", isDefault } };
}

//! Device info of the device named in a pref, else the default device
const PaDeviceInfo *CurrentDevice(bool output)
{
   const wxString name = output
      ? AudioIOPlaybackDevice.Read() : AudioIORecordingDevice.Read();
   const int count = Pa_GetDeviceCount();
   for (int i = 0; i < count; ++i) {
      const auto *info = Pa_GetDeviceInfo(i);
      if (!info || (output ? info->maxOutputChannels : info->maxInputChannels) <= 0)
         continue;
      if (info->name && wxString::FromUTF8(info->name) == name)
         return info;
   }
   const auto index = output
      ? Pa_GetDefaultOutputDevice() : Pa_GetDefaultInputDevice();
   return index >= 0 ? Pa_GetDeviceInfo(index) : nullptr;
}

std::string Signature(const std::vector<DeviceDescriptor> &devices)
{
   std::vector<std::string> names;
   for (const auto &d : devices)
      names.push_back(d.routeName);
   std::sort(names.begin(), names.end());
   names.erase(std::unique(names.begin(), names.end()), names.end());
   std::string signature;
   for (const auto &n : names)
      signature += n + "\n";
   return signature;
}

//! The device of a pref follows Android's routing: a "Default" device, or
//! a device that is gone (AudioIO then opens the default)
bool FollowsRouting(bool output)
{
   const wxString name = output
      ? AudioIOPlaybackDevice.Read() : AudioIORecordingDevice.Read();
   if (name.empty() || name == wxString::FromUTF8(output
         ? PA_AAUDIO_DEFAULT_OUTPUT_NAME : PA_AAUDIO_DEFAULT_INPUT_NAME))
      return true;
   const auto *info = CurrentDevice(output);
   return !info || !info->name || wxString::FromUTF8(info->name) != name;
}

//! Pref key of the measured duplex offset of the current route
wxString RouteKey()
{
   std::string route = ToUtf8(AudioIOPlaybackDevice.Read()) + "_" +
      ToUtf8(AudioIORecordingDevice.Read());
   for (auto &c : route)
      if (!std::isalnum(static_cast<unsigned char>(c)))
         c = '_';
   // Speaker, wired, USB and every Bluetooth headset have very different
   // round trips (~30-300 ms), but the default devices keep their names
   const auto &signature = ConnectedDevicesSignature();
   if (!signature.empty() && (FollowsRouting(true) || FollowsRouting(false)))
      route += "_" + HashHex(signature);
   return kDuplexOffsetGroup + FromUtf8(route);
}

//! First guess before anything was measured on any route
double EstimatedDuplexOffsetMs()
{
   double ms = 0;
   if (const auto *out = CurrentDevice(true))
      ms += out->defaultLowOutputLatency * 1000.0;
   if (const auto *in = CurrentDevice(false))
      ms += in->defaultLowInputLatency * 1000.0;
   return ms;
}

// ---------------------------------------------------------------------------
// Commands
// ---------------------------------------------------------------------------

json Devices(const json &)
{
   json outputs = json::array(), inputs = json::array();
   const int count = Pa_GetDeviceCount();
   const auto defOut = Pa_GetDefaultOutputDevice();
   const auto defIn = Pa_GetDefaultInputDevice();
   for (int i = 0; i < count; ++i) {
      const auto *info = Pa_GetDeviceInfo(i);
      if (!info)
         continue;
      if (info->maxOutputChannels > 0)
         outputs.push_back(DeviceJson(i, *info, i == defOut));
      if (info->maxInputChannels > 0)
         inputs.push_back(DeviceJson(i, *info, i == defIn));
   }
   return json{ { "outputs", std::move(outputs) },
      { "inputs", std::move(inputs) },
      { "current", { { "output", ToUtf8(AudioIOPlaybackDevice.Read()) },
                     { "input", ToUtf8(AudioIORecordingDevice.Read()) },
                     { "recordChannels", AudioIORecordChannels.Read() } } },
      { "pending", PendingDevices().has_value() } };
}

json SetDevices(const json &args)
{
   auto devices = ParseDevices(args);
   // What is connected now (also while a stream runs: a take keeps the
   // route key it started with)
   ConnectedDevicesSignature() = Signature(devices);
   if (AudioIdle()) {
      PendingDevices().reset();
      ApplyDevices(devices);
      return json{ { "applied", true } };
   }
   // Applied when the stream stops (Stop / tick)
   PendingDevices() = std::move(devices);
   return json{ { "applied", false } };
}

json Permission(const json &args)
{
   const bool granted = ArgBool(args, "recordPermission");
   auto &session = Session::Get();
   const bool changed = session.RecordPermission() != granted;
   session.SetRecordPermission(granted);
   if (!granted) {
      // The microphone is gone: stop capturing (a recording is committed)
      auto gAudioIO = AudioIO::Get();
      if (auto *project = session.Project()) {
         auto &transport = TransportManager::Get(*project);
         if (transport.Recording() ||
             (gAudioIO && gAudioIO->IsMonitoring()))
            transport.Stop("device",
               "The microphone permission was revoked");
      }
      else if (gAudioIO && gAudioIO->IsMonitoring())
         gAudioIO->StopStream();
   }
   // RECORD_PERMISSION snapshot flag
   if (changed && session.Project())
      session.ScheduleSnapshot();
   return json::object();
}

json Latency(const json &)
{
   PaAAudioStreamStats st{};
   const bool haveStats = PaAAudio_GetActiveStreamStats(&st) != 0;
   double outMs = -1, inMs = -1;
   if (haveStats && st.outputLatencySec > 0)
      outMs = st.outputLatencySec * 1000.0;
   if (haveStats && st.inputLatencySec > 0)
      inMs = st.inputLatencySec * 1000.0;
   if (outMs < 0)
      if (const auto *out = CurrentDevice(true))
         outMs = out->defaultLowOutputLatency * 1000.0;
   if (inMs < 0)
      if (const auto *in = CurrentDevice(false))
         inMs = in->defaultLowInputLatency * 1000.0;
   double measured = 0;
   const bool isMeasured = MeasuredDuplexOffsetMs(measured);
   return json{ { "outputLatencyMs", std::max(0.0, outMs) },
      { "inputLatencyMs", std::max(0.0, inMs) },
      { "correctionMs", OverdubCorrectionMs() },
      { "duplexOffsetMs", isMeasured ? measured : EstimatedDuplexOffsetMs() },
      { "measured", isMeasured },
      { "userTrimMs", AudioUserLatencyTrimMs.Read() } };
}

json InputOptionsJson()
{
   PaAAudioOptions o{};
   PaAAudio_GetOptions(&o);
   wxString preset;
   gPrefs->Read(kInputPresetKey, &preset, wxString(wxT("auto")));
   return json{ { "preset", ToUtf8(preset) },
      { "monoPreset", PresetLabel(o.inputPreset) },
      { "stereoPreset",
        PresetLabel(o.stereoInputPreset > 0 ? o.stereoInputPreset : o.inputPreset) },
      { "unprocessedSupported", UnprocessedSupported() } };
}

//! audio.setInputOptions {unprocessedSupported?, preset?}: the microphone
//! processing of streams opened afterwards (a running stream keeps its own)
json SetInputOptions(const json &args)
{
   if (auto supported = OptBool(args, "unprocessedSupported"))
      UnprocessedSupported() = *supported;
   if (auto preset = OptString(args, "preset")) {
      const bool known = std::any_of(std::begin(kPresetNames),
         std::end(kPresetNames),
         [&](const PresetName &p) { return *preset == p.name; });
      if (!known)
         Fail(ErrorCode::INVALID_ARGS, "unknown input preset: " + *preset);
      gPrefs->Write(kInputPresetKey, FromUtf8(*preset));
      gPrefs->Flush();
   }
   ApplyInputOptions();
   return InputOptionsJson();
}

} // namespace

// ---------------------------------------------------------------------------
// Module functions
// ---------------------------------------------------------------------------

void ApplyStartConfigDefaults()
{
   const auto &config = Session::Get().Config();
   // Without it Pa_Initialize probes the default route by opening an
   // output stream (README of portaudio-android)
   if (config.audioOutputSampleRate > 0)
      PaAAudio_SetDefaults(double(config.audioOutputSampleRate),
         std::max(0, config.audioFramesPerBuffer));
   ApplyInputOptions();
}

void ApplyInputOptions()
{
   wxString name;
   gPrefs->Read(kInputPresetKey, &name, wxString(wxT("auto")));
   int mono = 0, stereo = 0;
   for (const auto &p : kPresetNames)
      if (name == wxString::FromUTF8(p.name))
         mono = stereo = p.preset;
   if (mono == 0) {
      // "auto" (or an unknown stored value).  The host API falls back to
      // VOICE_RECOGNITION if a preset cannot be opened.
      mono = UnprocessedSupported() ? kPresetUnprocessed : kPresetVoiceRecognition;
      stereo = UnprocessedSupported() ? kPresetUnprocessed : kPresetCamcorder;
   }
   PaAAudioOptions o{};
   PaAAudio_GetOptions(&o);
   o.inputPreset = mono;
   o.stereoInputPreset = stereo == mono ? 0 : stereo;
   PaAAudio_SetOptions(&o);
}

std::string CurrentRouteKey()
{
   return ToUtf8(RouteKey());
}

bool LastStreamDuplexOffsetMs(double &ms)
{
   PaAAudioStreamStats st{};
   if (!PaAAudio_GetActiveStreamStats(&st) || !st.hasInput || !st.hasOutput)
      return false;
   if (!(st.duplexOffsetSec > 0) || st.duplexOffsetSec > 2.0)
      return false;
   ms = st.duplexOffsetSec * 1000.0;
   return true;
}

void ApplyPendingDeviceChange()
{
   auto &pending = PendingDevices();
   if (!pending || !AudioIdle())
      return;
   auto devices = std::move(*pending);
   pending.reset();
   ApplyDevices(devices);
}

bool MeasuredDuplexOffsetMs(double &ms)
{
   double value = 0;
   if (gPrefs->Read(RouteKey(), &value) && value > 0 && value < 2000) {
      ms = value;
      return true;
   }
   return false;
}

double OverdubCorrectionMs()
{
   double offset = 0;
   if (!MeasuredDuplexOffsetMs(offset))
      // Not another route's measurement (a Bluetooth headset's 250 ms
      // would make a wired take 220 ms early, and an early take has lost
      // its beginning): the static latencies, a low estimate
      offset = EstimatedDuplexOffsetMs();
   // Audacity's sign: negative shifts the recording earlier
   return -offset + AudioUserLatencyTrimMs.Read();
}

void StoreMeasuredDuplexOffset(const std::string &routeKey)
{
   double ms = 0;
   if (routeKey.empty() || !LastStreamDuplexOffsetMs(ms))
      return;
   gPrefs->Write(FromUtf8(routeKey), ms);
   gPrefs->Flush();
   Events::Log(Events::LogLevel::Info,
      "measured duplex offset " + std::to_string(ms) + " ms");
}

void ResetDeviceState()
{
   PendingDevices().reset();
   ConnectedDevicesSignature().clear();
}

void RegisterDeviceCommands(ModuleRegistry &registry)
{
   registry.AddCommand("audio.devices", Devices);
   registry.AddCommand("audio.setDevices", SetDevices);
   registry.AddCommand("audio.permission", Permission);
   registry.AddCommand("audio.latency", Latency);
   registry.AddCommand("audio.setInputOptions", SetInputOptions);
}

} // namespace aubridge
