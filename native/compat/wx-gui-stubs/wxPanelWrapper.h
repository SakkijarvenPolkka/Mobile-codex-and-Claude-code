// Audacity Android port: headless stand-in for lib-wx-wrappers'
// wxPanelWrapper.h (see README.md).  Dialogs never show; ShowModal()
// reports that the user cancelled.
#ifndef AUDACITY_PORT_STUB_WXPANELWRAPPER_H
#define AUDACITY_PORT_STUB_WXPANELWRAPPER_H

#include <wx/defs.h>
#include <wx/event.h>
#include <wx/string.h>

#include "TranslatableString.h"

class wxWindow;

//! Minimal headless replacements for the wx controls used by the stubs
class wxChoice
{
public:
   wxString GetStringSelection() const { return {}; }
   int GetSelection() const { return -1; }
};

class wxTextCtrl
{
public:
   void SetValue(const wxString &) {}
   wxString GetValue() const { return {}; }
};

class wxDialogWrapper : public wxEvtHandler
{
public:
   struct Size {};

   wxDialogWrapper() = default;
   template<typename... Args>
   wxDialogWrapper(wxWindow *, wxWindowID, const TranslatableString &, Args&&...) {}

   void SetName() {}
   void SetName(const TranslatableString &) {}
   void SetTitle(const TranslatableString &) {}
   void Layout() {}
   void Fit() {}
   Size GetSize() const { return {}; }
   void SetMinSize(Size) {}
   void Center() {}
   void Centre() {}
   int ShowModal() { return wxID_CANCEL; }
   bool Show(bool = true) { return false; }
   void EndModal(int) {}
};

#endif
