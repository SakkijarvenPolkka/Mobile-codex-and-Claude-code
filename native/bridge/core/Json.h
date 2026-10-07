/*  SPDX-License-Identifier: GPL-2.0-or-later */
/**********************************************************************

  Audacity Android port

  Json.h

  nlohmann::json alias, UTF-8 <-> wxString conversion and argument
  helpers for command handlers.  The argument helpers throw
  BridgeError{INVALID_ARGS} with a readable message.

  Note: the bridge is compiled with JSON_USE_IMPLICIT_CONVERSIONS=0, so
  use `j.get<T>()` (or the helpers below) instead of `T x = j;`.

**********************************************************************/
#pragma once

#include <cstdint>
#include <optional>
#include <string>
#include <vector>

#include <nlohmann/json.hpp>
#include <wx/string.h>

#include "BridgeError.h"

class TranslatableString;

namespace aubridge {

using json = nlohmann::json;

//! wxString -> UTF-8 (never the locale encoding)
std::string ToUtf8(const wxString &s);
//! UTF-8 -> wxString
wxString FromUtf8(const std::string &s);
//! Translation() of a TranslatableString as UTF-8
std::string Translated(const TranslatableString &s);

//! Serialize; invalid UTF-8 is replaced instead of throwing
std::string Dump(const json &j);

//! A finite double, or `fallback` (JSON cannot carry NaN/inf)
double Finite(double value, double fallback = 0.0);

// ---------------------------------------------------------------------------
// Argument helpers.  `args` is the command's argument object.
// Required variants throw INVALID_ARGS when the key is missing or ill-typed;
// optional variants return std::nullopt when the key is missing or null and
// throw when it has the wrong type.
// ---------------------------------------------------------------------------
double ArgDouble(const json &args, const char *key);
std::optional<double> OptDouble(const json &args, const char *key);
int64_t ArgInt(const json &args, const char *key);
std::optional<int64_t> OptInt(const json &args, const char *key);
bool ArgBool(const json &args, const char *key);
std::optional<bool> OptBool(const json &args, const char *key);
std::string ArgString(const json &args, const char *key);
std::optional<std::string> OptString(const json &args, const char *key);
std::vector<std::string> ArgStringArray(const json &args, const char *key);
std::vector<int64_t> ArgIntArray(const json &args, const char *key);

//! Throws INVALID_ARGS unless lo <= value <= hi
void RequireRange(const char *key, double value, double lo, double hi);

} // namespace aubridge
