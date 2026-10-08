/**********************************************************************
 
 Audacity: A Digital Audio Editor
 
 @file DefaultPlaybackPolicy.h
 
 Paul Licameli split from PlaybackSchedule.h

 Audacity Android port: copied from Audacity 3.7.9
 src/DefaultPlaybackPolicy.{h,cpp} (the src/ layer is not built), wrapped
 in namespace aubridge.  Addition: SleepInterval counts the passes of
 AudioIO's buffer thread (AudioThreadPasses(), used to stop safely after a
 seek), and CountingPlaybackPolicy (the library's default behaviour + the
 same counting) for streams without this policy.
 
 **********************************************************************/
#ifndef __AUBRIDGE_DEFAULT_PLAYBACK_POLICY__
#define __AUBRIDGE_DEFAULT_PLAYBACK_POLICY__

#include <atomic>
#include <chrono>
#include <cstdint>

#include "PlaybackSchedule.h"

class AudacityProject;

namespace aubridge {

//! The PlaybackPolicy used by Audacity for most playback.
/*! It subscribes to messages from ViewInfo and PlayRegion for loop bounds
 adjustment.  Therefore it is not a low-level class that can be defined with
 the playback engine.
 */
class DefaultPlaybackPolicy final
   : public PlaybackPolicy
   , public NonInterferingBase
{
public:
   DefaultPlaybackPolicy( AudacityProject &project,
      double trackEndTime, double loopEndTime, std::optional<double> pStartTime,
      bool loopEnabled, bool variableSpeed);
   ~DefaultPlaybackPolicy() override;

   void Initialize( PlaybackSchedule &schedule, double rate ) override;

   Mixer::WarpOptions MixerWarpOptions(PlaybackSchedule &schedule) override;

   BufferTimes SuggestedBufferTimes(PlaybackSchedule &schedule) override;

   bool Done( PlaybackSchedule &schedule, unsigned long ) override;

   double OffsetSequenceTime(PlaybackSchedule& schedule, double offset) override;

   PlaybackSlice GetPlaybackSlice(
      PlaybackSchedule &schedule, size_t available ) override;

   std::pair<double, double>
      AdvancedTrackTime( PlaybackSchedule &schedule,
         double trackTime, size_t nSamples ) override;

   bool RepositionPlayback(
      PlaybackSchedule &schedule, const Mixers &playbackMixers,
      size_t frames, size_t available ) override;

   bool Looping( const PlaybackSchedule & ) const override;

   //! Android port: counts AudioThreadPasses()
   std::chrono::milliseconds
      SleepInterval( PlaybackSchedule &schedule ) override;

private:
   bool RevertToOldDefault( const PlaybackSchedule &schedule ) const;
   void WriteMessage();
   double GetPlaySpeed();

   AudacityProject &mProject;

   // The main thread writes changes in response to user events, and
   // the audio thread later reads, and changes the playback.
   struct SlotData {
      double mPlaySpeed;
      double mT0;
      double mT1;
      bool mLoopEnabled;
   };
   MessageBuffer<SlotData> mMessageChannel;

   Observer::Subscription mRegionSubscription,
      mSpeedSubscription;

   double mLastPlaySpeed{ 1.0 };
   const double mTrackEndTime;
   double mLoopEndTime;
   std::optional<double> mpStartTime;
   size_t mRemaining{ 0 };
   bool mProgress{ true };
   bool mLoopEnabled{ true };
   bool mVariableSpeed{ false };
};

//! Android port: passes of AudioIO's buffer thread, counted by the
//! policies of this file (PlaybackPolicy::SleepInterval runs once at the
//! top of every pass of AudioIO::AudioThread)
std::atomic<uint64_t> &AudioThreadPasses();

//! Android port: the library's default ("old") playback behaviour, plus the
//! pass counting
class CountingPlaybackPolicy final : public PlaybackPolicy
{
public:
   ~CountingPlaybackPolicy() override;
   std::chrono::milliseconds
      SleepInterval( PlaybackSchedule &schedule ) override;
};

} // namespace aubridge

#endif
