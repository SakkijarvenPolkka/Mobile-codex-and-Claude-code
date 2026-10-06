// Audacity Android port: stand-in for <wx/frame.h> (a wx GUI header that does
// not compile against GUI-less wxBase).  mod-lof includes it without using
// wxFrame.  See ../README.md.
#ifndef _WX_FRAME_H_BASE_
#define _WX_FRAME_H_BASE_
#include <wx/defs.h>
class wxFrame;
#endif
