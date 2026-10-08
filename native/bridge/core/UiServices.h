/*  SPDX-License-Identifier: GPL-2.0-or-later */
/**********************************************************************

  Audacity Android port

  UiServices.h

  The bridge's BasicUI::Services (replacement of lib-wx-init's
  wxWidgetsBasicUI): everything maps to `progress` and `dialog` events
  (API.md §4.3, §4.4).

   * CallAfter     -> EngineThread internal queue (never inline)
   * Yield         -> drain the internal queue (nested, bounded)
   * error dialogs and OK-only message boxes -> non-blocking `dialog` events
   * questions (Yes/No, Cancel button, multi-choice) -> blocking `dialog`
     events answered with ReplyDialog() (multiChoice: ReplyDialogChoices());
     the engine thread waits in a nested loop that drains internal work only
   * progress dialogs -> `progress` events; cancel/stop arrive through
     atomics set by CancelProgress(); never null

  Modules use ProgressScope for their own long operations (e.g. an import
  with its own progress listener) and Dialogs:: for messages/questions.

**********************************************************************/
#pragma once

#include <memory>
#include <optional>
#include <string>
#include <vector>

#include "BasicUI.h"

namespace aubridge {

struct ProgressState;

//! A `progress` operation: emits `begin` on construction and `end` on
//! destruction; Update() emits `update` events (at most 20 per second).
class ProgressScope final {
public:
   ProgressScope(const std::string &title, const std::string &message,
      bool cancellable = true, bool stoppable = false);
   ~ProgressScope();
   ProgressScope(const ProgressScope &) = delete;
   ProgressScope &operator=(const ProgressScope &) = delete;

   int Id() const;

   //! @param fraction in [0, 1]; negative = indeterminate
   //! @param message replaces the message when non-null
   //! @return Success, or Cancelled / Stopped once the user asked for it
   BasicUI::ProgressResult Update(double fraction,
      const std::string *message = nullptr);
   //! Current result without emitting anything
   BasicUI::ProgressResult Result() const;
   bool IsCancelled() const
   { return Result() == BasicUI::ProgressResult::Cancelled; }
   bool IsStopped() const
   { return Result() == BasicUI::ProgressResult::Stopped; }

   void SetTitle(const std::string &title);
   void SetMessage(const std::string &message);
   //! Forget a previous cancel/stop request
   void Reset();

private:
   std::shared_ptr<ProgressState> mState;
   std::string mMessage;
   int64_t mLastUpdateMs = 0;
   bool mUpdated = false;
};

namespace Dialogs {

enum class Style { Info, Warning, Error, Question };

//! Non-blocking message (`blocking:false`); any thread
void Show(Style style, const std::string &title, const std::string &message,
   const std::string &helpPage = {});

//! Blocking question; returns the index of the chosen button, or -1 when
//! dismissed, when no UI is attached, or when the engine is stopping.
//! On the engine thread it waits in a nested loop (internal work only).
int Ask(Style style, const std::string &title, const std::string &message,
   const std::vector<std::string> &buttons, int defaultButton = 0,
   const std::string &helpPage = {});

//! Blocking choice (`kind:"choice"`); returns the chosen index or -1
int Choose(const std::string &title, const std::string &message,
   const std::vector<std::string> &choices, int defaultChoice = 0,
   const std::string &helpPage = {});

//! Blocking multiple choice (`kind:"multiChoice"`, API.md §4.4), e.g. the
//! streams of a multi-stream file on import.  `defaultChecked[i]` pre-checks
//! choice i (missing entries are unchecked).
//! @return the checked indices (ascending, without duplicates; may be
//!   empty), or std::nullopt when cancelled (replyDialog(id, -1)), when no UI
//!   is attached, or when the engine is stopping.  replyDialog(id, b >= 0)
//!   accepts the default checks.
std::optional<std::vector<int>> ChooseMany(const std::string &title,
   const std::string &message, const std::vector<std::string> &choices,
   const std::vector<bool> &defaultChecked = {},
   const std::string &helpPage = {});

//! Manual page id -> URL (port of HelpSystem::ShowHelp's name mangling)
std::string HelpUrl(const std::string &pageId);

// ---- spine internals --------------------------------------------------
void Reply(int dialogId, int button);
//! ReplyDialogChoices(): answers a multiChoice dialog (for other blocking
//! dialogs a single index is taken as the button/choice, else -1)
void ReplyChoices(int dialogId, const std::vector<int> &indices);
//! Answers every pending blocking dialog with -1 (Stop())
void CancelAll();

} // namespace Dialogs

namespace UiServices {
//! Any thread, never blocks
void CancelProgress(int progressId, bool stop);
//! Requests cancel of every running progress (Stop())
void CancelAllProgress();
BasicUI::Services &Instance();
}

} // namespace aubridge
