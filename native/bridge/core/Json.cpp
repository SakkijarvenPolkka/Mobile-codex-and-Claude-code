/*  SPDX-License-Identifier: GPL-2.0-or-later */
/**********************************************************************

  Audacity Android port

  Json.cpp

**********************************************************************/
#include "Json.h"

#include <cmath>

#include "TranslatableString.h"

namespace aubridge {

std::string ToUtf8(const wxString &s)
{
   const auto buf = s.utf8_str();
   return std::string(buf.data(), buf.length());
}

wxString FromUtf8(const std::string &s)
{
   return wxString::FromUTF8(s.data(), s.size());
}

std::string Translated(const TranslatableString &s)
{
   return ToUtf8(s.Translation());
}

std::string Dump(const json &j)
{
   return j.dump(-1, ' ', false, json::error_handler_t::replace);
}

double Finite(double value, double fallback)
{
   return std::isfinite(value) ? value : fallback;
}

namespace {
[[noreturn]] void Bad(const char *key, const char *what)
{
   Fail(ErrorCode::INVALID_ARGS,
      std::string("argument '") + key + "' " + what);
}

const json *Find(const json &args, const char *key)
{
   if (!args.is_object())
      return nullptr;
   auto it = args.find(key);
   if (it == args.end() || it->is_null())
      return nullptr;
   return &*it;
}

const json &Require(const json &args, const char *key)
{
   if (auto p = Find(args, key))
      return *p;
   Bad(key, "is missing");
}

double ToDouble(const json &v, const char *key)
{
   if (!v.is_number())
      Bad(key, "must be a number");
   const double d = v.get<double>();
   if (!std::isfinite(d))
      Bad(key, "must be finite");
   return d;
}

int64_t ToInt(const json &v, const char *key)
{
   if (v.is_number_integer())
      return v.get<int64_t>();
   if (v.is_number_float()) {
      const double d = v.get<double>();
      if (std::isfinite(d) && d == std::floor(d) && std::fabs(d) < 9.2e18)
         return static_cast<int64_t>(d);
   }
   Bad(key, "must be an integer");
}

bool ToBool(const json &v, const char *key)
{
   if (!v.is_boolean())
      Bad(key, "must be a boolean");
   return v.get<bool>();
}

std::string ToString(const json &v, const char *key)
{
   if (!v.is_string())
      Bad(key, "must be a string");
   return v.get<std::string>();
}
} // namespace

double ArgDouble(const json &args, const char *key)
{ return ToDouble(Require(args, key), key); }

std::optional<double> OptDouble(const json &args, const char *key)
{
   if (auto p = Find(args, key))
      return ToDouble(*p, key);
   return std::nullopt;
}

int64_t ArgInt(const json &args, const char *key)
{ return ToInt(Require(args, key), key); }

std::optional<int64_t> OptInt(const json &args, const char *key)
{
   if (auto p = Find(args, key))
      return ToInt(*p, key);
   return std::nullopt;
}

bool ArgBool(const json &args, const char *key)
{ return ToBool(Require(args, key), key); }

std::optional<bool> OptBool(const json &args, const char *key)
{
   if (auto p = Find(args, key))
      return ToBool(*p, key);
   return std::nullopt;
}

std::string ArgString(const json &args, const char *key)
{ return ToString(Require(args, key), key); }

std::optional<std::string> OptString(const json &args, const char *key)
{
   if (auto p = Find(args, key))
      return ToString(*p, key);
   return std::nullopt;
}

std::vector<std::string> ArgStringArray(const json &args, const char *key)
{
   const auto &v = Require(args, key);
   if (!v.is_array())
      Bad(key, "must be an array of strings");
   std::vector<std::string> result;
   for (const auto &e : v)
      result.push_back(ToString(e, key));
   return result;
}

std::vector<int64_t> ArgIntArray(const json &args, const char *key)
{
   const auto &v = Require(args, key);
   if (!v.is_array())
      Bad(key, "must be an array of integers");
   std::vector<int64_t> result;
   for (const auto &e : v)
      result.push_back(ToInt(e, key));
   return result;
}

void RequireRange(const char *key, double value, double lo, double hi)
{
   if (!(value >= lo && value <= hi))
      Fail(ErrorCode::INVALID_ARGS, std::string("argument '") + key +
         "' out of range [" + std::to_string(lo) + ", " +
         std::to_string(hi) + "]");
}

} // namespace aubridge
