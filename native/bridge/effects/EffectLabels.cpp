/*  SPDX-License-Identifier: GPL-2.0-or-later */
/**********************************************************************

  Audacity Android port

  EffectLabels.cpp

  Labels, units and display hints of the built-in effects' parameters
  (API.md §5.5).  The libraries carry only automation keys; the visible
  texts are those of the 3.7.9 wxWidgets dialogs in src/effects/*.cpp
  (Audacity Team, GPL-2.0-or-later), with the same msgids so the
  translation catalogs (e.g. ko) apply.  Mnemonics ("&") and the trailing
  colon of dialog captions are stripped after translation.

**********************************************************************/
#include "EffectsInternal.h"

#include <map>
#include <utility>

namespace aubridge {
namespace effects {

namespace {

struct Entry {
   const wxChar *key;
   TranslatableString label;   //!< XXO/XO msgid of the 3.7.9 dialog
   TranslatableString unit;
   const char *display = "";
   std::vector<TranslatableString> intChoices{};
   //! display "ratio" of a pitch: the UI may also show 12*log2(ratio)
   bool semitones = false;
};

using Table = std::map<wxString, std::vector<Entry>>;

const Table &TheTable()
{
   static const TranslatableString none{};
   static const auto dB = XO("dB");
   static const auto seconds = XO("seconds");
   static const auto hz = XO("Hz");
   static const auto percent = XO("%");
   static const Table table{
      // src/effects/Amplify.cpp
      { wxT("Amplify"), {
         { wxT("Ratio"), XXO("&Amplification (dB):"), none, "dB" },
         { wxT("AllowClipping"), XXO("Allo&w clipping"), none },
      } },
      // src/effects/AutoDuck.cpp
      { wxT("Auto Duck"), {
         { wxT("DuckAmountDb"), XXO("Duck &amount:"), dB },
         { wxT("MaximumPause"), XXO("Ma&ximum pause:"), seconds },
         { wxT("OuterFadeDownLen"), XXO("Outer fade &down length:"), seconds },
         { wxT("OuterFadeUpLen"), XXO("Outer fade &up length:"), seconds },
         { wxT("InnerFadeDownLen"), XXO("Inner fade d&own length:"), seconds },
         { wxT("InnerFadeUpLen"), XXO("Inner &fade up length:"), seconds },
         { wxT("ThresholdDb"), XXO("&Threshold:"), dB },
      } },
      // src/effects/BassTreble.cpp
      { wxT("Bass and Treble"), {
         { wxT("Bass"), XXO("Ba&ss (dB):"), none },
         { wxT("Treble"), XXO("&Treble (dB):"), none },
         { wxT("Gain"), XXO("&Volume (dB):"), none },
         { wxT("Link Sliders"), XXO("&Link Volume control to Tone controls"), none },
      } },
      // src/effects/ChangePitch.cpp.  "ratio": the mobile UI shows and
      // edits the factor 1 + percent/100 (1.25 = +25 %), API.md §5.5
      { wxT("Change Pitch"), {
         { wxT("Percentage"), XXO("Percent C&hange:"), percent, "ratio", {},
            true },
         { wxT("SBSMS"), XXO("&Use high quality stretching (slow)"), none },
      } },
      // src/effects/ChangeSpeed.cpp
      { wxT("Change Speed and Pitch"), {
         { wxT("Percentage"), XXO("Percent C&hange:"), percent, "ratio" },
      } },
      // src/effects/ChangeTempo.cpp
      { wxT("Change Tempo"), {
         { wxT("Percentage"), XXO("Percent C&hange:"), percent, "ratio" },
         { wxT("SBSMS"), XXO("&Use high quality stretching (slow)"), none },
      } },
      // src/effects/ScienFilter.cpp
      { wxT("Classic Filters"), {
         { wxT("FilterType"), XXO("&Filter Type:"), none },
         { wxT("FilterSubtype"), XXO("&Subtype:"), none },
         { wxT("Order"), XXO("O&rder:"), none },
         { wxT("Cutoff"), XXO("C&utoff:"), hz },
         { wxT("PassbandRipple"), XO("&Passband Ripple:"), dB },
         { wxT("StopbandRipple"), XO("Minimum S&topband Attenuation:"), dB },
      } },
      // src/effects/ClickRemoval.cpp
      { wxT("Click Removal"), {
         { wxT("Threshold"), XXO("&Threshold (lower is more sensitive):"), none },
         { wxT("Width"), XXO("Max &Spike Width (higher is more sensitive):"), none },
      } },
      // src/effects/CompressorEditor.cpp, DynamicRangeProcessorEditor.{h,cpp}
      { wxT("Compressor"), {
         { wxT("thresholdDb"), XXO("&Threshold (dB)"), none },
         { wxT("makeupGainDb"), XXO("&Make-up gain (dB)"), none },
         { wxT("kneeWidthDb"), XXO("Knee &width (dB)"), none },
         { wxT("compressionRatio"), XXO("Rati&o:"), none },
         { wxT("lookaheadMs"), XXO("&Lookahead (ms)"), none },
         { wxT("attackMs"), XXO("Attac&k (ms)"), none },
         { wxT("releaseMs"), XXO("&Release (ms)"), none },
         { wxT("showInput"), XO("I&nput"), none },
         { wxT("showOutput"), XO("O&utput"), none },
         { wxT("showActual"), XO("A&ctual compression"), none },
         { wxT("showTarget"), XO("Tar&get compression"), none },
      } },
      // src/effects/Distortion.cpp
      { wxT("Distortion"), {
         { wxT("Type"), XXO("Distortion type:"), none },
         { wxT("DC Block"), XXO("DC blocking filter"), none },
         { wxT("Threshold dB"), XO("Upper Threshold"), dB },
         { wxT("Noise Floor"), XO("Noise Floor"), dB },
         { wxT("Parameter 1"), XO("Parameter 1"), none },
         { wxT("Parameter 2"), XO("Parameter 2"), none },
         { wxT("Repeats"), XO("Number of repeats"), none },
      } },
      // src/effects/DtmfGen.cpp
      { wxT("DTMF Tones"), {
         { wxT("Sequence"), XXO("DTMF &sequence:"), none },
         { wxT("Duty Cycle"), XO("&Tone/silence ratio:"), percent },
         { wxT("Amplitude"), XXO("&Amplitude (0-1):"), none },
      } },
      // src/effects/Echo.cpp
      { wxT("Echo"), {
         { wxT("Delay"), XXO("&Delay time (seconds):"), none },
         { wxT("Decay"), XXO("D&ecay factor:"), none },
      } },
      // src/effects/EqualizationUI.cpp
      { wxT("Filter Curve"), {
         { wxT("FilterLength"), XXO("Length of &Filter:"), none },
         { wxT("InterpolateLin"), XXO("Li&near Frequency Scale"), none },
         { wxT("InterpolationMethod"), XO("Interpolation type"), none },
      } },
      { wxT("Graphic EQ"), {
         { wxT("FilterLength"), XXO("Length of &Filter:"), none },
         { wxT("InterpolateLin"), XXO("Li&near Frequency Scale"), none },
         { wxT("InterpolationMethod"), XO("Interpolation type"), none },
      } },
      // src/effects/LegacyCompressor.cpp
      { wxT("Legacy Compressor"), {
         { wxT("Threshold"), XO("&Threshold:"), dB },
         { wxT("NoiseFloor"), XO("&Noise Floor:"), dB },
         { wxT("Ratio"), XO("&Ratio:"), none },
         { wxT("AttackTime"), XO("&Attack Time:"), seconds },
         { wxT("ReleaseTime"), XO("R&elease Time:"), seconds },
         { wxT("Normalize"), XXO("Ma&ke-up gain for 0 dB after compressing"), none },
         { wxT("UsePeak"), XXO("C&ompress based on Peaks"), none },
      } },
      // src/effects/LimiterEditor.cpp, DynamicRangeProcessorEditor.{h,cpp}
      { wxT("Limiter"), {
         { wxT("thresholdDb"), XXO("&Threshold (dB)"), none },
         { wxT("makeupTargetDb"), XXO("&Make-up target (dB)"), none },
         { wxT("kneeWidthDb"), XXO("Knee &width (dB)"), none },
         { wxT("lookaheadMs"), XXO("&Lookahead (ms)"), none },
         { wxT("releaseMs"), XXO("&Release (ms)"), none },
         { wxT("showInput"), XO("I&nput"), none },
         { wxT("showOutput"), XO("O&utput"), none },
         { wxT("showActual"), XO("A&ctual compression"), none },
         { wxT("showTarget"), XO("Tar&get compression"), none },
      } },
      // src/effects/Loudness.cpp
      { wxT("Loudness Normalization"), {
         { wxT("StereoIndependent"), XXO("Normalize &stereo channels independently"), none },
         { wxT("LUFSLevel"), XO("Loudness LUFS"), XO("LUFS") },
         { wxT("RMSLevel"), XO("RMS dB"), dB },
         { wxT("DualMono"), XXO("&Treat mono as dual-mono (recommended)"), none },
         { wxT("NormalizeTo"), XO("&Normalize"), none, "",
            { XO("perceived loudness"), XO("RMS") } },
      } },
      // src/effects/Noise.cpp
      { wxT("Noise"), {
         { wxT("Type"), XXO("&Noise type:"), none },
         { wxT("Amplitude"), XXO("&Amplitude (0-1):"), none },
      } },
      // src/effects/NoiseReduction.cpp (preference-backed, API.md §5.5)
      { wxT("Noise Reduction"), {
         { wxT("Gain"), XXO("&Noise reduction (dB):"), none },
         { wxT("Sensitivity"), XXO("&Sensitivity:"), none },
         { wxT("FreqSmoothing"), XXO("&Frequency smoothing (bands):"), none },
         { wxT("ReductionChoice"), XXO("Noise:"), none },
      } },
      // src/effects/Normalize.cpp
      { wxT("Normalize"), {
         { wxT("PeakLevel"), XO("Peak amplitude dB"), none },
         { wxT("RemoveDcOffset"), XXO("&Remove DC offset (center on 0.0 vertically)"), none },
         { wxT("ApplyVolume"), XXO("&Normalize peak amplitude to   "), none },
         { wxT("StereoIndependent"), XXO("N&ormalize stereo channels independently"), none },
      } },
      // src/effects/Paulstretch.cpp
      { wxT("Paulstretch"), {
         { wxT("Stretch Factor"), XXO("&Stretch Factor:"), none },
         { wxT("Time Resolution"), XXO("&Time Resolution (seconds):"), none },
      } },
      // src/effects/Phaser.cpp
      { wxT("Phaser"), {
         { wxT("Stages"), XXO("&Stages:"), none },
         { wxT("DryWet"), XXO("&Dry/Wet:"), none },
         { wxT("Freq"), XXO("LFO Freq&uency (Hz):"), none },
         { wxT("Phase"), XXO("LFO Sta&rt Phase (deg.):"), none },
         { wxT("Depth"), XXO("Dept&h:"), none },
         { wxT("Feedback"), XXO("Feedbac&k (%):"), none },
         { wxT("Gain"), XXO("&Output gain (dB):"), none },
      } },
      // src/effects/Repeat.cpp
      { wxT("Repeat"), {
         { wxT("Count"), XXO("&Number of repeats to add:"), none },
      } },
      // src/effects/Reverb.cpp
      { wxT("Reverb"), {
         { wxT("RoomSize"), XXO("&Room Size (%):"), none },
         { wxT("Delay"), XXO("&Pre-delay (ms):"), none },
         { wxT("Reverberance"), XXO("Rever&berance (%):"), none },
         { wxT("HfDamping"), XXO("Da&mping (%):"), none },
         { wxT("ToneLow"), XXO("Tone &Low (%):"), none },
         { wxT("ToneHigh"), XXO("Tone &High (%):"), none },
         { wxT("WetGain"), XXO("Wet &Gain (dB):"), none },
         { wxT("DryGain"), XXO("Dr&y Gain (dB):"), none },
         { wxT("StereoWidth"), XXO("Stereo Wid&th (%):"), none },
         { wxT("WetOnly"), XXO("Wet O&nly"), none },
      } },
      // src/effects/TimeScale.cpp
      { wxT("Sliding Stretch"), {
         { wxT("RatePercentChangeStart"), XO("Initial Tempo Change (%)"), none,
            "ratio" },
         { wxT("RatePercentChangeEnd"), XO("Final Tempo Change (%)"), none,
            "ratio" },
         { wxT("PitchHalfStepsStart"), XO("Initial Pitch Shift"),
            XXO("(&semitones) [-12 to 12]:") },
         { wxT("PitchHalfStepsEnd"), XO("Final Pitch Shift"),
            XXO("(s&emitones) [-12 to 12]:") },
         { wxT("PitchPercentChangeStart"), XO("Initial Pitch Shift"),
            XXO("(%) [-50 to 100]:"), "ratio", {}, true },
         { wxT("PitchPercentChangeEnd"), XO("Final Pitch Shift"),
            XXO("(%) [-50 to 100]:"), "ratio", {}, true },
      } },
      // src/effects/ToneGen.cpp
      { wxT("Tone"), {
         { wxT("Frequency"), XXO("&Frequency (Hz):"), none },
         { wxT("Amplitude"), XXO("&Amplitude (0-1):"), none },
         { wxT("Waveform"), XXO("&Waveform:"), none },
         { wxT("Interpolation"), XXO("I&nterpolation:"), none },
      } },
      { wxT("Chirp"), {
         { wxT("StartFreq"), XO("Frequency Hertz Start"), hz },
         { wxT("EndFreq"), XO("Frequency Hertz End"), hz },
         { wxT("StartAmp"), XO("Amplitude Start"), none },
         { wxT("EndAmp"), XO("Amplitude End"), none },
         { wxT("Waveform"), XXO("&Waveform:"), none },
         { wxT("Interpolation"), XXO("I&nterpolation:"), none },
      } },
      // src/effects/TruncSilence.cpp
      { wxT("Truncate Silence"), {
         { wxT("Threshold"), XXO("&Threshold:"), dB },
         { wxT("Action"), XO("Action"), none },
         { wxT("Minimum"), XXO("&Duration:"), seconds },
         { wxT("Truncate"), XXO("Tr&uncate to:"), seconds },
         { wxT("Compress"), XXO("C&ompress to:"), percent },
         { wxT("Independent"), XXO("Trunc&ate tracks independently"), none },
         { wxT("TruncateStart"), XXO("Remove silence from &beginning"), none },
         { wxT("TruncateMiddle"), XXO("Remove silence from &middle"), none },
         { wxT("TruncateEnd"), XXO("Remove silence from &end"), none },
      } },
      // src/effects/Wahwah.cpp
      { wxT("Wahwah"), {
         { wxT("Freq"), XXO("LFO Freq&uency (Hz):"), none },
         { wxT("Phase"), XXO("LFO Sta&rt Phase (deg.):"), none },
         { wxT("Depth"), XXO("Dept&h (%):"), none },
         { wxT("Resonance"), XXO("Reso&nance:"), none },
         { wxT("Offset"), XXO("Wah Frequency Offse&t (%):"), none },
         { wxT("Gain"), XXO("&Output gain (dB):"), none },
      } },
      // src/effects/FindClipping.cpp
      { wxT("Find Clipping"), {
         { wxT("Duty Cycle Start"), XXO("&Start threshold (samples):"), none },
         { wxT("Duty Cycle End"), XXO("St&op threshold (samples):"), none },
      } },
   };
   return table;
}

} // namespace

std::string StripMnemonics(const wxString &text)
{
   wxString s = text;
   // CJK style "(&X)" (e.g. ko: "증폭 (dB)(&A):")
   for (size_t pos = 0; (pos = s.find(wxT("(&"), pos)) != wxString::npos;) {
      if (pos + 3 < s.length() && s[pos + 3] == wxT(')')) {
         // also drop one space before it
         size_t start = pos;
         if (start > 0 && s[start - 1] == wxT(' '))
            --start;
         s.erase(start, pos + 4 - start);
         pos = start;
      }
      else
         pos += 2;
   }
   // "&&" -> "&", "&x" -> "x"
   wxString out;
   for (size_t i = 0; i < s.length(); ++i) {
      if (s[i] == wxT('&')) {
         if (i + 1 < s.length() && s[i + 1] == wxT('&')) {
            out += wxT('&');
            ++i;
         }
         continue;
      }
      out += s[i];
   }
   out.Trim(true).Trim(false);
   while (!out.empty() &&
          (out.Last() == wxT(':') || out.Last() == wxChar(0xFF1A)))
      out.RemoveLast().Trim(true);
   return ToUtf8(out);
}

std::optional<ParamLabel> LookupParamLabel(const wxString &symbolInternal,
   const wxString &key)
{
   const auto &table = TheTable();
   auto it = table.find(symbolInternal);
   if (it == table.end())
      return std::nullopt;
   for (auto &entry : it->second)
      if (key == entry.key) {
         ParamLabel result;
         result.label = StripMnemonics(entry.label.Translation());
         if (!entry.unit.empty())
            result.unit = StripMnemonics(entry.unit.Translation());
         result.display = entry.display;
         result.semitones = entry.semitones;
         for (auto &choice : entry.intChoices)
            result.intChoices.push_back(StripMnemonics(choice.Translation()));
         return result;
      }
   return std::nullopt;
}

} // namespace effects
} // namespace aubridge
