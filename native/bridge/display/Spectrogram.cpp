/*  SPDX-License-Identifier: GPL-2.0-or-later */
/**********************************************************************

  Audacity: A Digital Audio Editor

  Spectrogram.cpp

  Spectrogram columns for the Android bridge (API.md §7.5).

  The STFT of one column is a port of SpecCache::CalculateOneSpectrum and
  ComputeSpectrumUsingRealFFTf / ComputeSpectrogramGainFactors of 3.7.9's
  src/tracks/playabletrack/wavetrack/ui/SpectrumCache.cpp (Paul Licameli,
  split from WaveClip.cpp), the column positions are
  WaveClipUIUtilities::fillWhere (with the half-sample bias), and the
  bin -> row mapping and findValue come from SpectrumView.cpp (DrawClipSpectrum,
  maximum method, Bug971).  v1 uses the STFT algorithm with a LINEAR
  frequency scale 0 ... rate/2 (API.md §7.5); window size, window type,
  zero padding, range, gain and frequency gain are Audacity's spectrogram
  preferences (defaults: 2048 Hann, padding 2, 80 dB, 20 dB, 0 dB/dec).

  Android port: instead of WaveClipSpectrumCache's single contiguous window
  per clip channel, columns are computed in 256-column elements on the
  sequence-local column grid of the waveform (independent of scrolling and
  of the other tiles) and kept as 8-bit rows in the clip's
  ClipDisplayCache.  Samples are read a whole sample block at a time.

**********************************************************************/
#include "DisplayInternal.h"

#include <algorithm>
#include <cmath>
#include <cstring>
#include <memory>

#include "aubridge/Bridge.h"   // PpsForLevel
#include "ClipDisplayCache.h"
#include "DisplayTracks.h"
#include "Hooks.h"
#include "RealFFTf.h"
#include "SampleBlock.h"
#include "Sequence.h"
#include "Session.h"
#include "SpectrogramSettings.h"
#include "WaveClip.h"
#include "WaveTrack.h"

namespace aubridge::display {

namespace {

std::unique_ptr<SpectrogramSettings> sSettings;
uint64_t sSpectroUseCounter = 0;

//! The analysis settings: a private copy (its FFT and window caches are
//! mutable state), read from the preferences once
SpectrogramSettings &Settings()
{
   if (!sSettings) {
      auto settings = std::make_unique<SpectrogramSettings>(); // LoadPrefs + Validate
      settings->algorithm = SpectrogramSettings::algSTFT;
      settings->scaleType = SpectrogramSettings::stLinear;
      settings->CacheWindows();
      sSettings = std::move(settings);
   }
   return *sSettings;
}

// --- port of SpectrumCache.cpp ---------------------------------------------

void ComputeSpectrumUsingRealFFTf(float *__restrict buffer,
   const FFTParam *hFFT, const float *__restrict window, size_t len,
   float *__restrict out)
{
   size_t i;
   if (len > hFFT->Points * 2)
      len = hFFT->Points * 2;
   for (i = 0; i < len; i++)
      buffer[i] *= window[i];
   for (; i < (hFFT->Points * 2); i++)
      buffer[i] = 0; // zero pad as needed
   RealFFTf(buffer, hFFT);
   // Handle the (real-only) DC
   float power = buffer[0] * buffer[0];
   if (power <= 0)
      out[0] = -160.0;
   else
      out[0] = 10.0 * log10f(power);
   for (i = 1; i < hFFT->Points; i++) {
      const int index = hFFT->BitReversed[i];
      const float re = buffer[index], im = buffer[index + 1];
      power = re * re + im * im;
      if (power <= 0)
         out[i] = -160.0;
      else
         out[i] = 10.0 * log10f(power);
   }
}

void ComputeSpectrogramGainFactors(size_t fftLen, double rate,
   int frequencyGain, std::vector<float> &gainFactors)
{
   if (frequencyGain > 0) {
      // Compute a frequency-dependent gain factor
      // scaled such that 1000 Hz gets a gain of 0dB

      // This is the reciprocal of the bin number of 1000 Hz:
      const double factor = ((double)rate / (double)fftLen) / 1000.0;

      auto half = fftLen / 2;
      gainFactors.reserve(half);
      // Don't take logarithm of zero!  Let bin 0 replicate the gain factor for bin 1.
      gainFactors.push_back(frequencyGain * log10(factor));
      for (decltype(half) x = 1; x < half; x++)
         gainFactors.push_back(frequencyGain * log10(factor * x));
   }
}

// --- port of SpectrumView.cpp ----------------------------------------------

inline float findValue(const float *spectrum, float bin0, float bin1,
   unsigned nBins, bool autocorrelation, int gain, int range)
{
   float value;
   // Maximum method, and no apportionment of any single bins over multiple
   // pixel rows.  See Bug971
   int index, limitIndex;
   if (autocorrelation) {
      // bin = 2 * nBins / (nBins - 1 - array_index);
      // Solve for index
      index = std::max(0.0f, std::min(float(nBins - 1),
         (nBins - 1) - (2 * nBins) / (std::max(1.0f, bin0))));
      limitIndex = std::max(0.0f, std::min(float(nBins - 1),
         (nBins - 1) - (2 * nBins) / (std::max(1.0f, bin1))));
   }
   else {
      index = std::min<int>(nBins - 1, (int)(floor(0.5 + bin0)));
      limitIndex = std::min<int>(nBins, (int)(floor(0.5 + bin1)));
   }
   value = spectrum[index];
   while (++index < limitIndex)
      value = std::max(value, spectrum[index]);
   if (!autocorrelation) {
      // Last step converts dB to a 0.0-1.0 range
      value = (value + range + gain) / (double)range;
   }
   value = std::min(1.0f, std::max(0.0f, value));
   return value;
}

// ---------------------------------------------------------------------------

//! Reads committed samples of a Sequence, one decoded sample block at a
//! time (a SqliteSampleBlock read fetches the whole blob anyway)
class BlockReader {
public:
   explicit BlockReader(const Sequence &sequence)
      : mSequence{ sequence }
      , mNumSamples{ sequence.GetNumSamples().as_long_long() }
   {}

   //! [start, start + len) must lie in [0, committed samples)
   void Read(int64_t start, size_t len, float *dst)
   {
      while (len > 0) {
         if (start < mFirst || start >= mFirst + int64_t(mData.size())) {
            if (!Load(start)) {
               std::fill(dst, dst + len, 0.0f);
               return;
            }
         }
         const size_t offset = size_t(start - mFirst);
         const size_t n = std::min(len, mData.size() - offset);
         std::copy(mData.begin() + offset, mData.begin() + offset + n, dst);
         dst += n;
         start += int64_t(n);
         len -= n;
      }
   }

private:
   bool Load(int64_t sample)
   {
      if (sample < 0 || sample >= mNumSamples)
         return false;
      const auto &blocks = mSequence.GetBlockArray();
      const int b = mSequence.FindBlock(sampleCount{ sample });
      if (b < 0 || size_t(b) >= blocks.size())
         return false;
      const auto &block = blocks[size_t(b)];
      if (!block.sb)
         return false;
      const size_t n = block.sb->GetSampleCount();
      if (n == 0)
         return false;
      mData.resize(n);
      // mayThrow = false: zeros on a read error ("Don't throw just for
      // display", SpectrumCache.cpp)
      if (block.sb->GetSamples(reinterpret_cast<samplePtr>(mData.data()),
             floatSample, 0, n, false) != n)
         std::fill(mData.begin(), mData.end(), 0.0f);
      mFirst = block.start.as_long_long();
      return true;
   }

   const Sequence &mSequence;
   const int64_t mNumSamples;
   std::vector<float> mData;
   int64_t mFirst = 0;
};

//! Computes the 256 columns of an element: sequence-local columns
//! [firstColumn, firstColumn + 256) of channel `ch`
void ComputeElement(const WaveClip &clip, size_t ch, double pps,
   int64_t firstColumn, int rows, int64_t visibleStart, bool recording,
   SpectroElement &element)
{
   auto &settings = Settings();
   const Sequence &sequence = *clip.GetSequence(ch);
   const int64_t committed = sequence.GetNumSamples().as_long_long();
   const double rate = clip.GetRate();
   const double samplesPerPixel = rate / pps / clip.GetStretchRatio();

   const size_t windowSize = settings.WindowSize();
   const size_t fftLen = windowSize * settings.ZeroPaddingFactor();
   const size_t padding = (windowSize * (settings.ZeroPaddingFactor() - 1)) / 2;
   const size_t nBins = settings.NBins();

   // Rows: linear scale 0 ... rate/2 (NumberScale nstLinear iterator,
   // settings.findBin(f, binUnit) with binUnit = rate / (2 * half))
   const size_t half = settings.GetFFTLength() / 2;
   const double binUnit = rate / (2 * half);
   std::vector<float> bins(size_t(rows) + 1);
   for (int yy = 0; yy <= rows; ++yy) {
      const float frequency = float(rate / 2 * double(yy) / rows);
      bins[size_t(yy)] = std::max(0.0f, std::min(float(nBins - 1),
         settings.findBin(frequency, float(binUnit))));
   }

   std::vector<float> gainFactors;
   ComputeSpectrogramGainFactors(fftLen, rate, settings.frequencyGain,
      gainFactors);

   element.data.assign(size_t(kElementColumns) * size_t(rows), 0);
   element.completeColumns = size_t(kElementColumns);
   const auto incomplete = [&element](int64_t k) {
      element.completeColumns = std::min(element.completeColumns, size_t(k));
   };
   std::vector<float> scratch(fftLen);
   std::vector<float> spectrum(nBins);
   BlockReader reader{ sequence };
   for (int64_t k = 0; k < kElementColumns; ++k) {
      const int64_t lc = firstColumn + k;
      // fillWhere(addBias = true): w0 = 0.5 + 0.5 + t0 * rate / stretch
      const int64_t centre =
         int64_t(std::floor(1.0 + double(lc) * samplesPerPixel));
      if (centre < visibleStart || centre >= committed) {
         // No audio at this column (yet)
         if (recording && centre >= committed)
            incomplete(k);
         continue;
      }
      // A window of the track centred at this sample, zero padded outside
      // the audio (CalculateOneSpectrum)
      const int64_t from = centre - int64_t(windowSize >> 1);
      std::fill(scratch.begin(), scratch.end(), 0.0f);
      const int64_t s0 = std::max(from, visibleStart);
      const int64_t s1 = std::min(from + int64_t(windowSize), committed);
      if (s1 > s0)
         reader.Read(s0, size_t(s1 - s0),
            scratch.data() + padding + size_t(s0 - from));
      if (recording && from + int64_t(windowSize) > committed)
         incomplete(k);
      ComputeSpectrumUsingRealFFTf(scratch.data(), settings.hFFT.get(),
         settings.window.get(), fftLen, spectrum.data());
      if (!gainFactors.empty())
         for (size_t ii = 0; ii < nBins; ++ii)
            spectrum[ii] += gainFactors[ii];
      uint8_t *column = element.data.data() + size_t(k) * size_t(rows);
      for (int yy = 0; yy < rows; ++yy) {
         const float value = findValue(spectrum.data(), bins[size_t(yy)],
            bins[size_t(yy) + 1], unsigned(nBins), false, settings.gain,
            settings.range);
         column[yy] = uint8_t(std::lround(value * 255.0f));
      }
   }
   element.complete = element.completeColumns == size_t(kElementColumns);
}

} // namespace

int64_t SpectrogramColumns(int64_t trackId, int channel, int zoomLevel,
   int64_t firstColumn, int count, int rows, uint8_t *out, size_t outSize)
{
   if (!out || count <= 0 || count > kMaxColumns || rows <= 0 ||
       rows > kMaxSpectrogramRows || firstColumn < -kMaxFirstColumn ||
       firstColumn > kMaxFirstColumn || zoomLevel < kMinZoomLevel ||
       zoomLevel > kMaxZoomLevel || outSize < size_t(count) * size_t(rows))
      return DisplayStatus::NoSuchTrack;
   std::memset(out, 0, size_t(count) * size_t(rows));
   auto *project = Session::Get().Project();
   if (!project)
      return DisplayStatus::NoSuchTrack;
   const auto resolved = ResolveDisplayTrack(*project, trackId);
   if (!resolved.track || channel < 0 ||
       size_t(channel) >= resolved.track->NChannels())
      return DisplayStatus::NoSuchTrack;
   const auto &track = *resolved.track;

   const double pps = PpsForLevel(zoomLevel);
   const int64_t c0 = firstColumn, c1 = firstColumn + count;
   bool filled = false, partial = false;
   const ClipDisplayCache *used = nullptr;
   const auto clips = track.SortedIntervalArray();
   for (size_t i = 0; i < clips.size(); ++i) {
      const WaveClip &clip = *clips[i];
      const auto span = ComputeSpan(clip, pps);
      if (resolved.recording && i + 1 == clips.size() && c1 > span.end &&
          c1 > span.first)
         partial = true;
      if (span.end <= c0 || span.first >= c1)
         continue;
      if (!clip.GetSequence(size_t(channel)))
         continue;
      const int64_t a = std::max(span.first, c0);
      const int64_t b = std::min(span.end, c1);
      const int64_t shift = SequenceShift(clip, pps);
      const int64_t la = std::max<int64_t>(a - shift, 0);
      const int64_t lb = b - shift;
      if (la >= lb)
         continue;
      // First visible sequence sample (WaveClip::TimeToSamples(trimLeft))
      const int64_t visibleStart = int64_t(std::floor(
         clip.GetTrimLeft() * span.scaledRate + 0.5));

      auto &cache = ClipDisplayCache::ForRequest(clips[i], resolved.liveCapture);
      cache.Sync(clip, resolved.recording);
      cache.Touch();
      used = &cache;
      auto &elements = cache.Spectro(clip, size_t(channel), visibleStart);
      for (int64_t col0 = la / kElementColumns * kElementColumns; col0 < lb;
           col0 += kElementColumns) {
         auto &element = elements[SpectroKey{ pps, col0, rows }];
         if (element.data.empty() || !element.complete)
            ComputeElement(clip, size_t(channel), pps, col0, rows,
               visibleStart, resolved.recording, element);
         element.lastUse = ++sSpectroUseCounter;
         const int64_t from = std::max(la, col0);
         const int64_t to = std::min(lb, col0 + kElementColumns);
         if (to > col0 + int64_t(element.completeColumns))
            partial = true;   // recording: copies columns that will change
         for (int64_t lc = from; lc < to; ++lc) {
            const int64_t j = lc + shift - c0;
            if (j < 0 || j >= count)
               continue;
            std::memcpy(out + size_t(j) * size_t(rows),
               element.data.data() + size_t(lc - col0) * size_t(rows),
               size_t(rows));
            filled = true;
         }
      }
      cache.SpectroChanged(size_t(channel));
   }
   if (used)
      TrimDisplayCaches(kStandingBudgetBytes, used);

   const int64_t version = WaveVersion(track);
   if (filled)
      return partial ? (version | DisplayStatus::PartialBit) : version;
   return partial ? DisplayStatus::Partial : version;
}

void ResetSpectrogram()
{
   sSettings.reset();
}

} // namespace aubridge::display
