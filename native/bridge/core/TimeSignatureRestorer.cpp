/*  SPDX-License-Identifier: GPL-2.0-or-later */
/**********************************************************************

  Audacity Android port

  TimeSignatureRestorer.cpp

  Undo/redo of tempo and time signature.  Copied from Audacity 3.7.9
  src/toolbars/TimeSignatureToolBar.cpp (TimeSignatureRestorer and its
  UndoRedoExtensionRegistry entry); the toolbar itself is not ported.

**********************************************************************/
#include "Project.h"
#include "ProjectTimeSignature.h"
#include "UndoManager.h"

namespace {

// Undo/redo handling of time signature changes
// DV: where should this really go?

struct TimeSignatureRestorer final : UndoStateExtension
{
   explicit TimeSignatureRestorer(AudacityProject& project)
       : mTempo { ProjectTimeSignature::Get(project).GetTempo() }
       , mUpper { ProjectTimeSignature::Get(project).GetUpperTimeSignature() }
       , mLower { ProjectTimeSignature::Get(project).GetLowerTimeSignature() }
   {
   }
   void RestoreUndoRedoState(AudacityProject& project) override
   {
      auto& timeSignature = ProjectTimeSignature::Get(project);

      timeSignature.SetTempo(mTempo);
      timeSignature.SetUpperTimeSignature(mUpper);
      timeSignature.SetLowerTimeSignature(mLower);
   }

   double mTempo;
   int mUpper;
   int mLower;
};

UndoRedoExtensionRegistry::Entry sEntry {
   [](AudacityProject& project) -> std::shared_ptr<UndoStateExtension>
   { return std::make_shared<TimeSignatureRestorer>(project); }
};
}
