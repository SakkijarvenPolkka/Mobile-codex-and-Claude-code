/*  SPDX-License-Identifier: GPL-2.0-or-later */
/**********************************************************************

  Audacity Android port

  Language.cpp

  See Language.h.  The catalog lookup follows lib-strings/Languages.cpp
  (TranslationExists: <dir>/<code>/audacity.mo and
  <dir>/<code>/LC_MESSAGES/audacity.mo for every directory of the path
  list); the locale handling replaces AudacityApp::InitLang.

**********************************************************************/
#include "Language.h"

#include <algorithm>
#include <clocale>
#include <cstring>
#include <strings.h>
#if !defined(__ANDROID__)
#include <langinfo.h>
#endif

#include <wx/dir.h>
#include <wx/filename.h>
#include <wx/log.h>

#include "Events.h"
#include "FileNames.h"
#include "Internat.h"
#include "Json.h"
#include "Languages.h"
#include "Prefs.h"
#include "Session.h"

namespace aubridge {
namespace Language {

namespace {

const wxChar *const kSettingKey = wxT("/Android/Language");
const char *const kSystem = "system";

//! The language installed by the last Apply() (engine thread)
std::string &CurrentRef()
{
   static std::string current;
   return current;
}

bool IsCode(const std::string &code)
{
   // "ko", "pt_BR", "ca_ES@valencia", "en-simple": no path separators
   if (code.empty() || code.size() > 32 || code[0] == '.')
      return false;
   return std::all_of(code.begin(), code.end(), [](char c) {
      return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') ||
         (c >= '0' && c <= '9') || c == '_' || c == '-' || c == '@';
   });
}

bool CatalogExists(const std::string &code)
{
   if (!IsCode(code))
      return false;
   const auto c = FromUtf8(code);
   for (const auto &dir : FileNames::AudacityPathList()) {
      if (wxFileName::FileExists(dir + wxT("/") + c + wxT("/LC_MESSAGES/audacity.mo")) ||
          wxFileName::FileExists(dir + wxT("/") + c + wxT("/audacity.mo")))
         return true;
   }
   return false;
}

bool IsEnglish(const std::string &code)
{
   return code == "en" || code.rfind("en_", 0) == 0;
}

// wxLocale (in Languages::SetLang) set LC_ALL for the UI language; bionic
// knows only C/C.UTF-8/en_US.UTF-8, and a host may lack the language's
// locale.  The engine formats no UI numbers: keep "." as the decimal
// separator everywhere (config files, Internat), and UTF-8 multibyte
// conversions (file names) on every platform.
void FixCLocale()
{
#if defined(__ANDROID__)
   std::setlocale(LC_CTYPE, "C.UTF-8");
#else
   const char *codeset = nl_langinfo(CODESET);
   if (!codeset || (::strcasecmp(codeset, "UTF-8") != 0 &&
                    ::strcasecmp(codeset, "utf8") != 0)) {
      if (!std::setlocale(LC_CTYPE, "C.UTF-8"))
         std::setlocale(LC_CTYPE, "en_US.UTF-8");
   }
#endif
   std::setlocale(LC_NUMERIC, "C");
}

} // namespace

std::string GetSetting()
{
   if (!gPrefs)
      return kSystem;
   const auto value = ToUtf8(gPrefs->Read(kSettingKey, wxString{ wxT("system") }));
   return value.empty() ? std::string(kSystem) : value;
}

bool IsValidSetting(const std::string &value)
{
   // "ko" is part of the contract even before its catalog is installed (it
   // then resolves to English)
   if (value == kSystem || value == "en" || value == "ko")
      return true;
   return CatalogExists(value);
}

bool SetSetting(const std::string &value)
{
   if (!IsValidSetting(value) || !gPrefs)
      return false;
   gPrefs->Write(kSettingKey, FromUtf8(value));
   return true;
}

std::vector<std::string> Installed()
{
   std::vector<std::string> codes;
   wxLogNull noLog;
   for (const auto &path : FileNames::AudacityPathList()) {
      wxDir dir;
      if (!wxDirExists(path) || !dir.Open(path))
         continue;
      wxString name;
      for (bool more = dir.GetFirst(&name, wxEmptyString, wxDIR_DIRS); more;
           more = dir.GetNext(&name)) {
         const auto code = ToUtf8(name);
         if (CatalogExists(code) &&
             std::find(codes.begin(), codes.end(), code) == codes.end())
            codes.push_back(code);
      }
   }
   std::sort(codes.begin(), codes.end());
   return codes;
}

std::string NormalizeLocale(const std::string &locale)
{
   // Java Locale.toString(): "ko_KR", "zh_CN_#Hans", "pt_BR"; legacy codes
   auto code = locale.substr(0, locale.find('#'));
   code = code.substr(0, code.find('.'));   // "ko_KR.UTF-8" (POSIX style)
   for (auto &c : code)
      if (c == '-')
         c = '_';
   while (!code.empty() && code.back() == '_')
      code.pop_back();
   if (code.rfind("iw", 0) == 0 && (code.size() == 2 || code[2] == '_'))
      code = "he" + code.substr(2);
   else if (code.rfind("in", 0) == 0 && (code.size() == 2 || code[2] == '_'))
      code = "id" + code.substr(2);
   else if (code.rfind("ji", 0) == 0 && (code.size() == 2 || code[2] == '_'))
      code = "yi" + code.substr(2);
   if (code.empty() || !IsCode(code))
      code = "en";
   return code;
}

std::string Resolve(const std::string &setting, const std::string &locale)
{
   std::string wanted = setting;
   if (wanted.empty() || wanted == kSystem)
      wanted = NormalizeLocale(locale);
   if (IsEnglish(wanted))
      return "en";
   // Full code first ("pt_BR", "zh_TW"), then the language ("ko")
   if (CatalogExists(wanted))
      return wanted;
   const auto base = wanted.substr(0, wanted.find('_'));
   if (base != wanted && CatalogExists(base))
      return base;
   return "en";
}

std::string Current()
{
   const auto &current = CurrentRef();
   return current.empty() ? std::string("en") : current;
}

bool Apply(bool force)
{
   const auto lang =
      Resolve(GetSetting(), Session::Get().Config().locale);
   auto &current = CurrentRef();
   if (!force && lang == current)
      return false;
   if (gPrefs) {
      // Desktop key (lib-strings readers, plug-ins)
      gPrefs->Write(wxT("/Locale/Language"), FromUtf8(lang));
      gPrefs->Flush();
   }
   const auto result =
      Languages::SetLang(FileNames::AudacityPathList(), FromUtf8(lang));
   FixCLocale();
   Internat::Init();
   current = lang;
   Events::Log(Events::LogLevel::Info, "Engine language: " + lang +
      " (wx: " + ToUtf8(result) + ", locale " +
      Session::Get().Config().locale + ")");
   return true;
}

} // namespace Language
} // namespace aubridge
