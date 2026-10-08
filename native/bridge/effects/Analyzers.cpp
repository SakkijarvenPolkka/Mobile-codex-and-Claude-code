/*  SPDX-License-Identifier: GPL-2.0-or-later */
/**********************************************************************

  Audacity Android port

  Analyzers.cpp

  analyze.spectrum and analyze.contrast (API.md §3.3): the computations
  behind Analyze ▸ Plot Spectrum... and Analyze ▸ Contrast..., which in
  3.7.9 are menu commands of src/ rather than effects.

  * Spectrum: the audio is fetched like PlotSpectrumBase::GetAudio
    (lib-builtin-effects/PlotSpectrumBase.cpp, Dominic Mazzoni, Matthieu
    Hodgkinson) -- the sum of every channel of the selected wave tracks --
    but read in chunks and capped at 2^23 samples (32 MiB), where the
    desktop allocates three buffers of up to 2^27 samples (1.5 GB, an OOM
    kill on phones; critic.md §4.9).  SpectrumAnalyst (lib-fft) computes,
    and the y range is clamped like FrequencyPlotDialog::Recalc
    (src/FreqWindow.cpp).
  * Contrast: ContrastBase::GetDB (lib-builtin-effects) over two ranges of
    the one selected wave track; WCAG 2 verdict of src/effects/Contrast.cpp
    (Audacity Team).

**********************************************************************/
#include "EffectsInternal.h"

#include <cmath>
#include <limits>

#include "Edit.h"
#include "Modules.h"
#include "Session.h"
#include "UiServices.h"

#include "ContrastBase.h"
#include "Decibels.h"
#include "FFT.h"
#include "Project.h"
#include "ProjectRate.h"
#include "SpectrumAnalyst.h"
#include "ViewInfo.h"
#include "WaveTrack.h"

namespace aubridge {
namespace effects {
namespace {

// ---------------------------------------------------------------------------
// Plot Spectrum
// ---------------------------------------------------------------------------
constexpr size_t kMaxSpectrumSamples = size_t(1) << 23;

struct SpectrumAudio {
   std::vector<float> data;
   double rate = 0;
   bool truncated = false;
};

SpectrumAudio FetchAudio(AudacityProject &project)
{
   SpectrumAudio audio;
   const auto &region = ViewInfo::Get(project).selectedRegion;
   size_t dataLen = 0;
   int selcount = 0;
   constexpr size_t chunk = 1 << 16;
   std::vector<float> buffer1(chunk), buffer2(chunk);
   float *const buffers[] = { buffer1.data(), buffer2.data() };
   for (auto track : TrackList::Get(project).Selected<const WaveTrack>()) {
      const auto start = track->TimeToLongSamples(region.t0());
      if (selcount == 0) {
         audio.rate = track->GetRate();
         const auto end = track->TimeToLongSamples(region.t1());
         const auto len = end - start;
         if (len > sampleCount{ kMaxSpectrumSamples }) {
            audio.truncated = true;
            dataLen = kMaxSpectrumSamples;
         }
         else
            dataLen = len > 0 ? len.as_size_t() : 0;
         audio.data.assign(dataLen, 0.0f);
      }
      if (track->GetRate() != audio.rate)
         Fail(ErrorCode::FAILED, Translated(XO(
"To plot the spectrum, all selected tracks must have the same sample rate.")));
      const auto nChannels = std::min<size_t>(track->NChannels(), 2);
      for (size_t pos = 0; pos < dataLen; pos += chunk) {
         const size_t n = std::min(chunk, dataLen - pos);
         // Don't allow throw for bad reads
         if (!track->GetFloats(0, nChannels, buffers, start + pos, n, false,
                FillFormat::fillZero, false))
            Fail(ErrorCode::FAILED, Translated(XO(
"Audio could not be analyzed. This may be due to a stretched or pitch-shifted clip.\nTry resetting any stretched clips, or mixing and rendering the tracks before analyzing")));
         for (size_t c = 0; c < nChannels; ++c)
            for (size_t i = 0; i < n; ++i)
               audio.data[pos + i] += buffers[c][i];
      }
      ++selcount;
   }
   return audio;
}

SpectrumAnalyst::Algorithm ParseAlgorithm(const std::string &name)
{
   static const std::pair<const char *, SpectrumAnalyst::Algorithm> names[] = {
      { "spectrum", SpectrumAnalyst::Spectrum },
      { "autocorrelation", SpectrumAnalyst::Autocorrelation },
      { "cubeRootAutocorrelation", SpectrumAnalyst::CubeRootAutocorrelation },
      { "enhancedAutocorrelation", SpectrumAnalyst::EnhancedAutocorrelation },
      { "cepstrum", SpectrumAnalyst::Cepstrum },
   };
   for (auto &[n, alg] : names)
      if (name == n)
         return alg;
   Fail(ErrorCode::INVALID_ARGS, "unknown algorithm '" + name + "'");
}

int ParseWindow(const std::string &name)
{
   // eWindowFunctions order (lib-fft/FFT.h)
   static const char *const names[] = { "rectangular", "bartlett", "hamming",
      "hann", "blackman", "blackmanHarris", "welch", "gaussian25",
      "gaussian35", "gaussian45" };
   static_assert(sizeof names / sizeof *names == eWinFuncCount);
   for (int i = 0; i < eWinFuncCount; ++i)
      if (name == names[i])
         return i;
   Fail(ErrorCode::INVALID_ARGS, "unknown window '" + name + "'");
}

json SpectrumCmd(const json &args)
{
   auto &project = Session::Get().RequireProject();
   const auto alg = ParseAlgorithm(OptString(args, "algorithm").value_or("spectrum"));
   const int window = ParseWindow(OptString(args, "window").value_or("hann"));
   const auto size = OptInt(args, "size").value_or(1024);
   // FreqWindow.cpp size choices: 128 ... 131072
   if (size < 128 || size > 131072 || (size & (size - 1)) != 0)
      Fail(ErrorCode::INVALID_ARGS,
         "size must be a power of two from 128 to 131072");
   RequireAudioSelection(project, XO("Plot Spectrum"));

   ProgressScope progress{ Translated(XO("Frequency Analysis")),
      Translated(XO("Plot Spectrum")), false, false };
   SpectrumAudio audio;
   try {
      audio = FetchAudio(project);
   }
   catch (const std::bad_alloc &) {
      Fail(ErrorCode::FAILED, "not enough memory to analyze the selection");
   }
   const size_t windowSize = size_t(size);
   if (audio.data.size() < windowSize || audio.rate <= 0)
      Fail(ErrorCode::FAILED, Translated(XO("Not enough data selected.")));

   SpectrumAnalyst analyst;
   float yMin = 0, yMax = 0;
   const bool ok = analyst.Calculate(alg, window, windowSize, audio.rate,
      audio.data.data(), audio.data.size(), &yMin, &yMax,
      [&](long long num, long long den) {
         progress.Update(den > 0 ? double(num) / double(den) : -1.0);
      });
   if (!ok)
      Fail(ErrorCode::FAILED, Translated(XO("Not enough data selected.")));

   const auto n = std::max(0, analyst.GetProcessedSize());
   const float *processed = analyst.GetProcessed();
   json values = json::array();
   double minValue = yMin, maxValue = yMax;
   if (alg == SpectrumAnalyst::Spectrum) {
      // FrequencyPlotDialog::Recalc: the dB range of the plot
      double dBRange = DecibelScaleCutoff.Read();
      if (dBRange < 90.)
         dBRange = 90.;
      if (minValue < -dBRange)
         minValue = -dBRange;
      if (maxValue <= -dBRange)
         maxValue = -dBRange + 10.;
      else
         maxValue += .5;
      // JSON has no -inf (log10 of 0): below the range is the range floor
      for (int i = 0; i < n; ++i) {
         const double v = processed[i];
         values.push_back(std::isfinite(v) ? std::max(v, -dBRange) : -dBRange);
      }
   }
   else
      for (int i = 0; i < n; ++i)
         values.push_back(Finite(processed[i]));

   json result{ { "rate", audio.rate }, { "values", std::move(values) },
      { "minValue", Finite(minValue) }, { "maxValue", Finite(maxValue) },
      { "algorithm", OptString(args, "algorithm").value_or("spectrum") },
      { "size", size } };
   if (alg == SpectrumAnalyst::Spectrum)
      result["binHz"] = audio.rate / double(windowSize);
   else
      // lag (autocorrelation) or quefrency (cepstrum) of value i = i / rate
      result["binSeconds"] = 1.0 / audio.rate;
   if (audio.truncated)
      result["warning"] = Translated(XO(
"Too much audio was selected. Only the first %.1f seconds of audio will be analyzed.")
         .Format(double(kMaxSpectrumSamples) / audio.rate));
   return result;
}

// ---------------------------------------------------------------------------
// Contrast
// ---------------------------------------------------------------------------
class ContrastAnalysis final : public ContrastBase {
public:
   explicit ContrastAnalysis(AudacityProject &project) : mProject{ project } {}
   //! RMS level of [t0, t1] of the selected track, dB (-inf for silence)
   std::optional<float> MeasureDb(double t0, double t1)
   {
      mT0 = t0;
      mT1 = t1;
      float dB = 0;
      if (!GetDB(dB))
         return std::nullopt;
      return dB;
   }
private:
   AudacityProject &GetProject() override { return mProject; }
   AudacityProject &mProject;
};

std::pair<double, double> ArgRange(const json &args, const char *key)
{
   auto it = args.find(key);
   if (it == args.end() || !it->is_object())
      Fail(ErrorCode::INVALID_ARGS,
         std::string("argument '") + key + "' must be an object {t0, t1}");
   const double t0 = ArgDouble(*it, "t0"), t1 = ArgDouble(*it, "t1");
   if (!std::isfinite(t0) || !std::isfinite(t1) || t1 < t0)
      Fail(ErrorCode::INVALID_ARGS,
         std::string("argument '") + key + "': need finite t0 <= t1");
   return { t0, t1 };
}

json DbJson(float dB)
{
   // JSON has no -inf: digital silence is reported as -1000 dB together
   // with the "...Silent" flag (the desktop shows "zero")
   return std::isfinite(dB) ? json(double(dB)) : json(dB < 0 ? -1000.0 : 1000.0);
}

json ContrastCmd(const json &args)
{
   auto &project = Session::Get().RequireProject();
   const auto fg = ArgRange(args, "foreground");
   const auto bg = ArgRange(args, "background");
   const auto selected =
      TrackList::Get(project).Selected<const WaveTrack>().size();
   if (selected == 0)
      Fail(ErrorCode::NO_SELECTION, Translated(XO("Please select an audio track.")));
   if (selected > 1)
      Fail(ErrorCode::NO_SELECTION,
         Translated(XO("You can only measure one track at a time.")));

   CaptureScope capture;
   ContrastAnalysis analysis{ project };
   analysis.mProjectRate = ProjectRate::Get(project).GetRate();
   const auto fgDb = analysis.MeasureDb(fg.first, fg.second);
   if (!fgDb)
      Fail(ErrorCode::FAILED, capture.Message());
   const auto bgDb = analysis.MeasureDb(bg.first, bg.second);
   if (!bgDb)
      Fail(ErrorCode::FAILED, capture.Message());

   // src/effects/Contrast.cpp ContrastDialog::results()
   constexpr float WCAG2_PASS = 20.0f;   // dB difference required to pass
   constexpr float DB_MAX_LIMIT = 0.0f;  // audio is massively distorted
   const float diff = *fgDb - *bgDb;
   const float absDiff = std::fabs(diff);
   TranslatableString verdict;
   bool passes = false;
   if (*fgDb > DB_MAX_LIMIT)
      verdict = XO("Foreground level too high");
   else if (*bgDb > DB_MAX_LIMIT)
      verdict = XO("Background level too high");
   else if (*bgDb > *fgDb)
      verdict = XO("Background higher than foreground");
   else if (absDiff > WCAG2_PASS) {
      verdict = XO("WCAG2 Pass");
      passes = true;
   }
   else
      verdict = XO("WCAG2 Fail");

   json result{ { "foregroundDb", DbJson(*fgDb) },
      { "backgroundDb", DbJson(*bgDb) },
      { "foregroundSilent", std::isinf(*fgDb) },
      { "backgroundSilent", std::isinf(*bgDb) },
      { "passes", passes }, { "verdict", Translated(verdict) } };
   // fg - bg: +inf when only the background is silent; NaN when both are
   if (std::isnan(diff))
      result["differenceDb"] = 0.0;
   else
      result["differenceDb"] = DbJson(diff);
   return result;
}

} // namespace

void RegisterAnalyzerCommands(ModuleRegistry &registry)
{
   const unsigned flags = NeedsProject | NeedsIdleAudio;
   registry.AddCommand("analyze.spectrum", SpectrumCmd, flags | LongRunning);
   registry.AddCommand("analyze.contrast", ContrastCmd, flags);
}

} // namespace effects
} // namespace aubridge
