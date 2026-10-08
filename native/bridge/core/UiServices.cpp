/*  SPDX-License-Identifier: GPL-2.0-or-later */
/**********************************************************************

  Audacity Android port

  UiServices.cpp

  BasicUI::Services for the bridge.  Replaces lib-wx-init's
  wxWidgetsBasicUI (Audacity 3.7.9, Paul Licameli) -- same contract,
  toolkit replaced by events to Kotlin.  HelpUrl() ports the page-name
  mangling of lib-wx-init/HelpSystem.cpp (HelpSystem::ShowHelp).

**********************************************************************/
#include "UiServices.h"

#include <algorithm>
#include <atomic>
#include <cctype>
#include <chrono>
#include <future>
#include <mutex>
#include <unordered_map>

#include <wx/intl.h>

#include "Engine.h"
#include "EngineThread.h"
#include "Events.h"
#include "Json.h"

namespace aubridge {

using BasicUI::ProgressResult;

namespace {
int64_t NowMs()
{
   using namespace std::chrono;
   return duration_cast<milliseconds>(
      steady_clock::now().time_since_epoch()).count();
}

std::string Tr(const wxChar *msgid)
{
   return ToUtf8(wxGetTranslation(msgid));
}

//! The warning of every ProjectFileIO constructor (lib-project-file-io
//! ProjectFileIO.cpp) when TempDir has less than 100 MB free.  It fires for
//! each project object, the bootstrap self-check probe included, and points
//! to a desktop preference (Directories) the port does not have
bool IsLowTempSpaceWarning(const TranslatableString &message,
   const ManualPageID &helpPage)
{
   return helpPage.GET() == wxT("Error:_Disk_full_or_not_writable") &&
      message.MSGID().GET().StartsWith(
         wxT("There is very little free disk space left on"));
}

std::atomic<bool> sLowTempSpaceWarned{ false };
} // namespace

// ---------------------------------------------------------------------------
// Progress
// ---------------------------------------------------------------------------
struct ProgressState {
   int id = 0;
   std::atomic<unsigned> result{ unsigned(ProgressResult::Success) };
   bool cancellable = true;
   bool stoppable = false;
};

namespace {
struct ProgressRegistry {
   std::mutex mutex;
   std::unordered_map<int, std::weak_ptr<ProgressState>> map;
   std::atomic<int> nextId{ 1 };
};
ProgressRegistry &Progresses()
{
   static ProgressRegistry registry;
   return registry;
}
} // namespace

ProgressScope::ProgressScope(const std::string &title,
   const std::string &message, bool cancellable, bool stoppable)
   : mState{ std::make_shared<ProgressState>() }
   , mMessage{ message }
{
   auto &reg = Progresses();
   mState->id = reg.nextId++;
   mState->cancellable = cancellable;
   mState->stoppable = stoppable;
   {
      std::lock_guard lock{ reg.mutex };
      reg.map[mState->id] = mState;
   }
   Events::Emit("progress", json{
      { "id", mState->id }, { "phase", "begin" }, { "title", title },
      { "message", message }, { "fraction", 0.0 },
      { "cancellable", cancellable }, { "stoppable", stoppable } });
}

ProgressScope::~ProgressScope()
{
   auto &reg = Progresses();
   {
      std::lock_guard lock{ reg.mutex };
      reg.map.erase(mState->id);
   }
   Events::Emit("progress", json{ { "id", mState->id }, { "phase", "end" } });
}

int ProgressScope::Id() const
{
   return mState->id;
}

ProgressResult ProgressScope::Result() const
{
   return ProgressResult(mState->result.load());
}

ProgressResult ProgressScope::Update(double fraction, const std::string *message)
{
   const auto result = Result();
   if (result != ProgressResult::Success)
      return result;
   bool changed = false;
   if (message && *message != mMessage) {
      mMessage = *message;
      changed = true;
   }
   const auto now = NowMs();
   // At most 20 updates per second, but never drop a message change or the
   // first update
   if (!mUpdated || changed || now - mLastUpdateMs >= 50) {
      mUpdated = true;
      mLastUpdateMs = now;
      json payload{ { "id", mState->id }, { "phase", "update" },
         { "fraction", Finite(fraction < 0 ? -1.0 : std::min(fraction, 1.0), -1.0) },
         { "message", mMessage } };
      Events::Emit("progress", payload);
   }
   return Result();
}

void ProgressScope::SetTitle(const std::string &title)
{
   Events::Emit("progress", json{ { "id", mState->id }, { "phase", "update" },
      { "title", title }, { "message", mMessage }, { "fraction", -1.0 } });
}

void ProgressScope::SetMessage(const std::string &message)
{
   mMessage = message;
}

void ProgressScope::Reset()
{
   mState->result = unsigned(ProgressResult::Success);
}

namespace UiServices {
void CancelProgress(int progressId, bool stop)
{
   auto &reg = Progresses();
   std::shared_ptr<ProgressState> state;
   {
      std::lock_guard lock{ reg.mutex };
      auto it = reg.map.find(progressId);
      if (it != reg.map.end())
         state = it->second.lock();
   }
   if (!state)
      return;
   if (stop && state->stoppable)
      state->result = unsigned(ProgressResult::Stopped);
   else
      state->result = unsigned(ProgressResult::Cancelled);
}

void CancelAllProgress()
{
   auto &reg = Progresses();
   std::lock_guard lock{ reg.mutex };
   for (auto &[id, weak] : reg.map)
      if (auto state = weak.lock())
         state->result = unsigned(ProgressResult::Cancelled);
}
} // namespace UiServices

// ---------------------------------------------------------------------------
// Dialogs
// ---------------------------------------------------------------------------
namespace Dialogs {
namespace {
//! What a blocking dialog was answered with
struct Answer {
   int button = -1;           //!< button / choice index, -1 = cancelled
   std::vector<int> indices;  //!< multiChoice: the checked choices
};
struct Pending {
   std::shared_ptr<std::promise<Answer>> promise;
   int count = 0;                    //!< buttons (message) / choices
   bool multi = false;               //!< kind "multiChoice"
   std::vector<int> defaultIndices;  //!< multiChoice: replyDialog(id, >= 0)
};
struct DialogRegistry {
   std::mutex mutex;
   std::unordered_map<int, Pending> pending;
   std::atomic<int> nextId{ 1 };
};
DialogRegistry &Registry()
{
   static DialogRegistry registry;
   return registry;
}

const char *StyleName(Style style)
{
   switch (style) {
   case Style::Warning: return "warning";
   case Style::Error: return "error";
   case Style::Question: return "question";
   default: return "info";
   }
}

Answer Blocking(json payload, Pending pending)
{
   if (!Events::GetSink())
      return {};
   auto &engine = EngineThread::Get();
   auto &reg = Registry();
   const int id = reg.nextId++;
   pending.promise = std::make_shared<std::promise<Answer>>();
   auto future = pending.promise->get_future();
   {
      std::lock_guard lock{ reg.mutex };
      reg.pending[id] = std::move(pending);
   }
   if (engine.IsStopping()) {
      std::lock_guard lock{ reg.mutex };
      reg.pending.erase(id);
      return {};
   }
   payload["id"] = id;
   payload["blocking"] = true;
   Events::Emit("dialog", payload);
   try {
      return engine.WaitModal(future);
   }
   catch (const std::future_error &) {
      return {};
   }
}

int BlockingButton(json payload, int count)
{
   Pending pending;
   pending.count = count;
   return Blocking(std::move(payload), std::move(pending)).button;
}

//! Removes the pending dialog `dialogId`; false if there is none
bool Take(int dialogId, Pending &pending)
{
   auto &reg = Registry();
   std::lock_guard lock{ reg.mutex };
   auto it = reg.pending.find(dialogId);
   if (it == reg.pending.end())
      return false;
   pending = std::move(it->second);
   reg.pending.erase(it);
   return true;
}
} // namespace

std::string HelpUrl(const std::string &pageId)
{
   if (pageId.empty())
      return {};
   std::string name = pageId, anchor;
   if (auto pos = name.rfind('#'); pos != std::string::npos) {
      anchor = name.substr(pos);
      name = name.substr(0, pos);
   }
   static const std::string home = "https://manual.audacityteam.org/";
   if (name == "Main_Page")
      return home + "index.html" + anchor;
   if (name == "Audacity_Support")
      return "https://support.audacityteam.org";
   if (name == "Quick_Help")
      return home + "quick_help.html" + anchor;
   if (name.rfind("http", 0) == 0)
      return name + anchor;
   // lower case; "%xx" -> '_'; other unsafe characters -> '_'; ' ' -> '+';
   // "__+" -> '_'; "_." -> '.'
   std::string s;
   for (size_t i = 0; i < name.size(); ++i) {
      const unsigned char c = name[i];
      if (c == '%' && i + 2 < name.size()) {
         s += '_';
         i += 2;
      }
      else if (std::isalnum(c) || c == '.' || c == ' ')
         s += char(std::tolower(c));
      else
         s += '_';
   }
   std::string t;
   for (char c : s) {
      if (c == ' ')
         c = '+';
      if (c == '_' && !t.empty() && t.back() == '_')
         continue;
      t += c;
   }
   std::string u;
   for (size_t i = 0; i < t.size(); ++i) {
      if (t[i] == '_' && i + 1 < t.size() && t[i + 1] == '.')
         continue;
      u += t[i];
   }
   return home + "man/" + u + ".html" + anchor;
}

void Show(Style style, const std::string &title, const std::string &message,
   const std::string &helpPage)
{
   const int id = Registry().nextId++;
   Events::Emit("dialog", json{ { "id", id }, { "kind", "message" },
      { "style", StyleName(style) }, { "title", title }, { "message", message },
      { "buttons", json::array({ Tr(wxT("OK")) }) }, { "defaultButton", 0 },
      { "blocking", false }, { "helpPage", HelpUrl(helpPage) } });
}

int Ask(Style style, const std::string &title, const std::string &message,
   const std::vector<std::string> &buttons, int defaultButton,
   const std::string &helpPage)
{
   json payload{ { "kind", "message" }, { "style", StyleName(style) },
      { "title", title }, { "message", message }, { "buttons", buttons },
      { "defaultButton", defaultButton }, { "helpPage", HelpUrl(helpPage) } };
   return BlockingButton(std::move(payload), int(buttons.size()));
}

int Choose(const std::string &title, const std::string &message,
   const std::vector<std::string> &choices, int defaultChoice,
   const std::string &helpPage)
{
   json payload{ { "kind", "choice" }, { "style", "question" },
      { "title", title }, { "message", message },
      { "buttons", json::array({ Tr(wxT("OK")) }) }, { "choices", choices },
      { "defaultButton", defaultChoice }, { "helpPage", HelpUrl(helpPage) } };
   return BlockingButton(std::move(payload), int(choices.size()));
}

std::optional<std::vector<int>> ChooseMany(const std::string &title,
   const std::string &message, const std::vector<std::string> &choices,
   const std::vector<bool> &defaultChecked, const std::string &helpPage)
{
   json checked = json::array();
   Pending pending;
   pending.count = int(choices.size());
   pending.multi = true;
   for (size_t i = 0; i < choices.size(); ++i) {
      const bool on = i < defaultChecked.size() && defaultChecked[i];
      checked.push_back(on);
      if (on)
         pending.defaultIndices.push_back(int(i));
   }
   json payload{ { "kind", "multiChoice" }, { "style", "question" },
      { "title", title }, { "message", message },
      { "buttons", json::array({ Tr(wxT("OK")), Tr(wxT("Cancel")) }) },
      { "defaultButton", 0 }, { "choices", choices },
      { "defaultChecked", std::move(checked) },
      { "helpPage", HelpUrl(helpPage) } };
   auto answer = Blocking(std::move(payload), std::move(pending));
   if (answer.button < 0)
      return std::nullopt;
   return std::move(answer.indices);
}

void Reply(int dialogId, int button)
{
   Pending pending;
   if (!Take(dialogId, pending))
      return;
   Answer answer;
   if (pending.multi) {
      // Cancel, or OK with the default checks
      if (button >= 0) {
         answer.button = 0;
         answer.indices = std::move(pending.defaultIndices);
      }
   }
   else if (button >= 0 && button < pending.count)
      answer.button = button;
   pending.promise->set_value(std::move(answer));
}

void ReplyChoices(int dialogId, const std::vector<int> &indices)
{
   Pending pending;
   if (!Take(dialogId, pending))
      return;
   Answer answer;
   if (pending.multi) {
      answer.button = 0;
      for (int index : indices)
         if (index >= 0 && index < pending.count)
            answer.indices.push_back(index);
      std::sort(answer.indices.begin(), answer.indices.end());
      answer.indices.erase(
         std::unique(answer.indices.begin(), answer.indices.end()),
         answer.indices.end());
   }
   else if (indices.size() == 1 && indices[0] >= 0 &&
            indices[0] < pending.count)
      // A message box / single choice answered with one index
      answer.button = indices[0];
   pending.promise->set_value(std::move(answer));
}

void CancelAll()
{
   auto &reg = Registry();
   std::unordered_map<int, Pending> all;
   {
      std::lock_guard lock{ reg.mutex };
      all.swap(reg.pending);
   }
   for (auto &[id, pending] : all)
      pending.promise->set_value(Answer{});
}

} // namespace Dialogs

// ---------------------------------------------------------------------------
// BasicUI::Services
// ---------------------------------------------------------------------------
namespace {

Dialogs::Style StyleOf(BasicUI::Icon icon, Dialogs::Style fallback)
{
   switch (icon) {
   case BasicUI::Icon::Warning: return Dialogs::Style::Warning;
   case BasicUI::Icon::Error: return Dialogs::Style::Error;
   case BasicUI::Icon::Question: return Dialogs::Style::Question;
   case BasicUI::Icon::Information: return Dialogs::Style::Info;
   default: return fallback;
   }
}

class BridgeProgress final : public BasicUI::ProgressDialog {
public:
   BridgeProgress(const TranslatableString &title,
      const TranslatableString &message, unsigned flags)
      : mScope{ Translated(title), Translated(message),
         (flags & BasicUI::ProgressShowCancel) != 0,
         (flags & BasicUI::ProgressShowStop) != 0 }
   {}
   ProgressResult Poll(unsigned long long numerator,
      unsigned long long denominator,
      const TranslatableString &message) override
   {
      const double fraction = denominator
         ? double(numerator) / double(denominator) : -1.0;
      if (!message.empty()) {
         const auto text = Translated(message);
         return mScope.Update(fraction, &text);
      }
      // Do NOT drain any queue here (re-entrancy, editing-commands.md §1.1)
      return mScope.Update(fraction);
   }
   void SetMessage(const TranslatableString &message) override
   { mScope.SetMessage(Translated(message)); }
   void SetDialogTitle(const TranslatableString &title) override
   { mScope.SetTitle(Translated(title)); }
   void Reinit() override { mScope.Reset(); }
private:
   ProgressScope mScope;
};

class BridgeGenericProgress final : public BasicUI::GenericProgressDialog {
public:
   BridgeGenericProgress(const TranslatableString &title,
      const TranslatableString &message, int style)
      : mScope{ Translated(title), Translated(message),
         (style & BasicUI::ProgressCanAbort) != 0, false }
      , mCanAbort{ (style & BasicUI::ProgressCanAbort) != 0 }
   {}
   ProgressResult Pulse() override
   {
      const auto result = mScope.Update(-1.0);
      if (mCanAbort && result != ProgressResult::Success)
         return result;
      return ProgressResult::Success;
   }
private:
   ProgressScope mScope;
   const bool mCanAbort;
};

class BridgeServices final : public BasicUI::Services {
public:
   void DoCallAfter(const BasicUI::Action &action) override
   {
      // Never inline, even on the engine thread: callers rely on "later"
      if (!EngineThread::Get().PostInternal(action))
         Events::Log(Events::LogLevel::Warning,
            "CallAfter dropped: engine thread not running");
   }

   void DoYield() override
   {
      if (EngineThread::IsCurrent())
         EngineThread::Get().DrainInternal();
   }

   void DoProcessIdle() override
   {
      if (EngineThread::IsCurrent()) {
         EngineThread::Get().DrainInternal();
         Engine::HandleIdle();
      }
   }

   void DoShowErrorDialog(const BasicUI::WindowPlacement &,
      const TranslatableString &title, const TranslatableString &message,
      const ManualPageID &helpPage,
      const BasicUI::ErrorDialogOptions &options) override
   {
      auto text = Translated(message);
      if (IsLowTempSpaceWarning(message, helpPage)) {
         // Once per process, with advice that applies on Android
         if (sLowTempSpaceWarned.exchange(true)) {
            Events::Log(Events::LogLevel::Warning,
               "Very little free storage space left for project data");
            return;
         }
         text = Translated(XO(
"There is very little free storage space left on this device (less than 100 MB).\n"
"Recording, editing and saving may fail. Please free up some space."));
      }
      if (!options.log.empty())
         Events::Log(Events::LogLevel::Error,
            text + "\n" + ToUtf8(wxString(options.log)));
      Dialogs::Show(Dialogs::Style::Error, Translated(title), text,
         ToUtf8(helpPage.GET()));
   }

   BasicUI::MessageBoxResult DoMessageBox(const TranslatableString &message,
      BasicUI::MessageBoxOptions options) override
   {
      using namespace BasicUI;
      const auto title = Translated(options.caption);
      const auto text = Translated(message);
      if (options.buttonStyle != Button::YesNo && !options.cancelButton) {
         // Informational: never block the engine thread
         Dialogs::Show(StyleOf(options.iconStyle, Dialogs::Style::Info),
            title, text);
         return MessageBoxResult::Ok;
      }
      std::vector<std::string> buttons;
      int defaultButton = 0;
      if (options.buttonStyle == Button::YesNo) {
         buttons = { Tr(wxT("Yes")), Tr(wxT("No")) };
         defaultButton = options.yesOrOkDefaultButton ? 0 : 1;
      }
      else
         buttons = { Tr(wxT("OK")) };
      if (options.cancelButton)
         buttons.push_back(Tr(wxT("Cancel")));
      const int reply = Dialogs::Ask(
         StyleOf(options.iconStyle, Dialogs::Style::Question), title, text,
         buttons, defaultButton);
      if (options.buttonStyle == Button::YesNo) {
         if (reply == 0)
            return MessageBoxResult::Yes;
         if (reply == 1)
            return MessageBoxResult::No;
         return options.cancelButton
            ? MessageBoxResult::Cancel : MessageBoxResult::No;
      }
      return reply == 0 ? MessageBoxResult::Ok : MessageBoxResult::Cancel;
   }

   std::unique_ptr<BasicUI::ProgressDialog> DoMakeProgress(
      const TranslatableString &title, const TranslatableString &message,
      unsigned flags, const TranslatableString &) override
   {
      return std::make_unique<BridgeProgress>(title, message, flags);
   }

   std::unique_ptr<BasicUI::GenericProgressDialog> DoMakeGenericProgress(
      const BasicUI::WindowPlacement &, const TranslatableString &title,
      const TranslatableString &message, int style) override
   {
      return std::make_unique<BridgeGenericProgress>(title, message, style);
   }

   int DoMultiDialog(const TranslatableString &message,
      const TranslatableString &title, const TranslatableStrings &buttons,
      const ManualPageID &helpPage, const TranslatableString &boxMsg,
      bool) override
   {
      std::vector<std::string> choices;
      for (const auto &b : buttons)
         choices.push_back(Translated(b));
      auto text = Translated(message);
      if (!boxMsg.empty())
         text += "\n\n" + Translated(boxMsg);
      return Dialogs::Choose(Translated(title), text, choices, 0,
         ToUtf8(helpPage.GET()));
   }

   bool DoOpenInDefaultBrowser(const wxString &url) override
   {
      // No event for this in API.md v1: the UI opens manual links itself
      Events::Log(Events::LogLevel::Info,
         "OpenInDefaultBrowser not supported: " + ToUtf8(url));
      return false;
   }

   std::unique_ptr<BasicUI::WindowPlacement> DoFindFocus() override
   {
      return std::make_unique<BasicUI::WindowPlacement>();
   }

   void DoSetFocus(const BasicUI::WindowPlacement &) override {}

   bool IsUsingRtlLayout() const override { return false; }

   bool IsUiThread() const override { return EngineThread::IsCurrent(); }
};

} // namespace

BasicUI::Services &UiServices::Instance()
{
   static BridgeServices services;
   return services;
}

} // namespace aubridge
