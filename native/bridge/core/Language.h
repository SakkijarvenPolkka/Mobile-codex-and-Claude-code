/*  SPDX-License-Identifier: GPL-2.0-or-later */
/**********************************************************************

  Audacity Android port

  Language.h

  The language of the engine's strings (effect names, history labels,
  messages): Audacity's gettext catalogs (`<lang>/LC_MESSAGES/audacity.mo`
  in a directory of the path list, i.e. filesDir/audacity/locale) loaded by
  lib-strings' Languages::SetLang (the desktop does this in
  AudacityApp::InitPart2 and on the Interface preferences page).

  The `language` setting (API.md §5.1, pref /Android/Language) is "system"
  (the start configuration's locale, e.g. "ko_KR" -> "ko"), "en", or the
  code of an installed catalog ("ko").  A language without a catalog
  resolves to "en".

  Engine thread only.

**********************************************************************/
#pragma once

#include <string>
#include <vector>

namespace aubridge {
namespace Language {

//! The `language` setting: "system" (default), "en" or a catalog code
std::string GetSetting();
//! Validates and stores the setting (does not apply it).
//! @return false for an unknown language
bool SetSetting(const std::string &value);
bool IsValidSetting(const std::string &value);

//! Codes of the catalogs found in the path list (e.g. {"ko"}), sorted
std::vector<std::string> Installed();

//! Java Locale.toString() ("ko_KR", "zh_CN_#Hans", "iw_IL") -> a gettext
//! style code ("ko_KR", "zh_CN", "he_IL"); "en" when empty
std::string NormalizeLocale(const std::string &locale);
//! The language the setting stands for: a code with an installed catalog
//! (full code like "pt_BR" first, then the two-letter code), else "en"
std::string Resolve(const std::string &setting, const std::string &locale);

//! The language currently applied ("en", "ko", ...)
std::string Current();

//! Resolves the setting against the start configuration's locale and
//! installs it: /Locale/Language, Languages::SetLang, then the C locale
//! fixups (UTF-8 LC_CTYPE, "C" LC_NUMERIC) and Internat::Init().  Needs the
//! preferences and the path list.  Cheap when nothing changed.
//! @return true when the language changed
bool Apply(bool force = false);

} // namespace Language
} // namespace aubridge
