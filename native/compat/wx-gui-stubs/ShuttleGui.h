// Audacity Android port: headless stand-in for lib-shuttlegui's ShuttleGui.h
// (see README.md).  Every layout call is a no-op; Add* calls return dummy
// controls.
#ifndef AUDACITY_PORT_STUB_SHUTTLEGUI_H
#define AUDACITY_PORT_STUB_SHUTTLEGUI_H

#include "wxPanelWrapper.h"

enum teShuttleMode
{
   eIsCreating,
   eIsGettingFromDialog,
   eIsSettingToDialog,
   eIsGettingMetadata,
   eIsCreatingFromPrefs,
   eIsSavingToPrefs,
};

class ShuttleGui
{
   wxChoice mChoice;
   wxTextCtrl mText;
public:
   template<typename Window>
   ShuttleGui(Window *, teShuttleMode, bool = true) {}

   template<typename... A> void SetBorder(A&&...) {}
   template<typename... A> void StartVerticalLay(A&&...) {}
   template<typename... A> void EndVerticalLay(A&&...) {}
   template<typename... A> void StartHorizontalLay(A&&...) {}
   template<typename... A> void EndHorizontalLay(A&&...) {}
   template<typename... A> void StartMultiColumn(A&&...) {}
   template<typename... A> void EndMultiColumn(A&&...) {}
   template<typename... A> void StartStatic(A&&...) {}
   template<typename... A> void EndStatic(A&&...) {}
   template<typename... A> void SetStretchyCol(A&&...) {}
   template<typename... A> void AddTitle(A&&...) {}
   template<typename... A> void AddVariableText(A&&...) {}
   template<typename... A> void AddStandardButtons(A&&...) {}
   template<typename... A> wxChoice *AddChoice(A&&...) { return &mChoice; }
   template<typename... A> wxTextCtrl *AddTextBox(A&&...) { return &mText; }
   template<typename... A> ShuttleGui &Id(A&&...) { return *this; }
   template<typename... A> void AddButton(A&&...) {}
};

#endif
