/*  SPDX-License-Identifier: GPL-2.0-or-later */
/**********************************************************************

  Audacity Android port

  EffectSchema.cpp

  effects.describe / setParams / loadPreset / savePreset / deletePreset
  (API.md §3.3, §5.5).

  * The generic schema comes from VisitSettings(ConstSettingsVisitor&)
    (replaces src/ShuttleGetDefinition.cpp, which reports no ranges).
  * Nyquist plug-ins are described from NyquistBase::mControls (the const
    visitor loses the defaults and the text rows).
  * Noise Reduction has no visitable parameters: its basic settings are the
    /Effects/NoiseReduction/ preferences (src/effects/NoiseReduction.cpp).
  * setParams validates every value first (own checks with messages, then
    the library's validating ShuttleSetAutomation pass) and writes only
    when everything is valid; partial maps are merged into the current
    automation string because missing keys would reset to defaults.

**********************************************************************/
#include "EffectsInternal.h"

#include <algorithm>
#include <charconv>
#include <cmath>
#include <limits>
#include <map>

#include "Edit.h"
#include "Modules.h"
#include "Session.h"

#include "ConfigInterface.h"
#include "DtmfBase.h"
#include "Effect.h"
#include "EffectAutomationParameters.h"
#include "EffectManager.h"
#include "NyquistBase.h"
#include "PluginManager.h"
#include "Project.h"
#include "SettingsVisitor.h"
#include "ShuttleAutomation.h"
#include "ViewInfo.h"
#include "WaveTrack.h"

namespace aubridge {
namespace effects {

// ---------------------------------------------------------------------------
// Shared helpers
// ---------------------------------------------------------------------------
std::string FormatNumber(double value)
{
   if (!std::isfinite(value))
      return "0";
   char buf[64];
   auto [end, ec] = std::to_chars(buf, buf + sizeof buf, value);
   if (ec != std::errc{})
      return "0";
   return std::string(buf, end);
}

double ValidateDuration(double d)
{
   // 24 hours is far beyond anything a phone can hold; it guards against
   // absurd inputs that would only fail deep inside the generator
   if (!std::isfinite(d) || d <= 0.0 || d > 24 * 3600.0)
      Fail(ErrorCode::INVALID_ARGS,
         "duration must be > 0 and at most 86400 seconds");
   return d;
}

namespace {

// ---------------------------------------------------------------------------
// Schema
// ---------------------------------------------------------------------------
struct ParamDef {
   enum class Kind { Bool, Int, Size, Float, DoubleF, Double, String, Enum };
   wxString key;
   Kind kind = Kind::Double;
   double def = 0, min = 0, max = 0, scale = 1, value = 0;
   float fmin = 0, fmax = 0;     //!< Float/DoubleF: bounds as the library sees them
   bool bounded = true;
   wxString strValue, strDef;
   std::vector<EnumValueSymbol> choices;
   //! Nyquist control type name, for EffectDescription.nyquist
   std::string nyquistType;
   std::string label, unit, display;
   std::vector<std::string> intChoices;   //!< int parameters shown as a choice
   bool semitones = false;   //!< display "ratio" of a pitch
};

std::string KindName(ParamDef::Kind kind, bool intChoice)
{
   using K = ParamDef::Kind;
   if (intChoice)
      return "enum";
   switch (kind) {
   case K::Bool: return "bool";
   case K::Int: case K::Size: return "int";
   case K::String: return "string";
   case K::Enum: return "enum";
   default: return "double";
   }
}

//! Collects the parameters of a ConstSettingsVisitor visit; skips the
//! unnamed (not persisted) ones, e.g. Amplify `Amp`, EQ display prefs
class SchemaVisitor final : public ConstSettingsVisitor {
public:
   std::vector<ParamDef> params;

   void Define(bool v, const wxChar *key, bool d, bool, bool, bool) override
   { Add(key, ParamDef::Kind::Bool, v, d, 0, 1, 1); }
   void Define(size_t v, const wxChar *key, int d, int mn, int mx, int s) override
   { Add(key, ParamDef::Kind::Size, double(v), d, mn, mx, s); }
   void Define(int v, const wxChar *key, int d, int mn, int mx, int s) override
   { Add(key, ParamDef::Kind::Int, v, d, mn, mx, s); }
   void Define(float v, const wxChar *key, float d, float mn, float mx,
      float s) override
   {
      if (auto p = Add(key, ParamDef::Kind::Float, v, d, mn, mx, s))
         p->fmin = mn, p->fmax = mx;
   }
   void Define(double v, const wxChar *key, float d, float mn, float mx,
      float s) override
   {
      if (auto p = Add(key, ParamDef::Kind::DoubleF, v, d, mn, mx, s))
         p->fmin = mn, p->fmax = mx;
   }
   void Define(double v, const wxChar *key, double d, double mn, double mx,
      double s) override
   { Add(key, ParamDef::Kind::Double, v, d, mn, mx, s); }
   void Define(const wxString &v, const wxChar *key, wxString d, wxString,
      wxString, wxString) override
   {
      if (auto p = Add(key, ParamDef::Kind::String, 0, 0, 0, 0, 0)) {
         p->strValue = v;
         p->strDef = d;
         p->bounded = false;
      }
   }
   void DefineEnum(int v, const wxChar *key, int d,
      const EnumValueSymbol strings[], size_t n) override
   {
      if (auto p = Add(key, ParamDef::Kind::Enum, v, d, 0, double(n) - 1, 1))
         p->choices.assign(strings, strings + n);
   }

private:
   ParamDef *Add(const wxChar *key, ParamDef::Kind kind, double v, double d,
      double mn, double mx, double s)
   {
      if (!key || !*key)
         return nullptr;
      ParamDef p;
      p.key = key;
      p.kind = kind;
      p.value = v;
      p.def = d;
      p.min = mn;
      p.max = mx;
      p.scale = s;
      params.push_back(std::move(p));
      return &params.back();
   }
};

//! Shortest decimal that is the same float (bounds declared as float)
double ShortFloat(float f)
{
   char buf[64];
   auto [end, ec] = std::to_chars(buf, buf + sizeof buf, f);
   if (ec != std::errc{})
      return f;
   // (the NDK's libc++ has no floating-point from_chars; wx parses with the
   // C locale whatever the process locale is)
   double d = f;
   if (!wxString::FromAscii(buf, end - buf).ToCDouble(&d))
      return f;
   return d;
}

//! Doubles that are exactly a float (bounds declared with float literals,
//! e.g. Amplify's Ratio) as the float's shortest text: 0.003162, not
//! 0.0031620000954717398
double Tidy(double v)
{
   if (!std::isfinite(v) || std::fabs(v) > 3.0e38)
      return v;
   const float f = static_cast<float>(v);
   return static_cast<double>(f) == v ? ShortFloat(f) : v;
}

bool IsNyquistPrompt(const Loaded &fx)
{
   return fx.descriptor && fx.descriptor->GetPath() == NYQUIST_PROMPT_ID;
}

NyquistBase *AsNyquist(const Loaded &fx)
{
   return fx.special == Special::Nyquist && !IsNyquistPrompt(fx)
      ? dynamic_cast<NyquistBase *>(fx.plugin) : nullptr;
}

std::string NyquistTypeName(int type)
{
   switch (type) {
   case NYQ_CTRL_INT: return "int";
   case NYQ_CTRL_FLOAT: return "float";
   case NYQ_CTRL_STRING: return "string";
   case NYQ_CTRL_CHOICE: return "choice";
   case NYQ_CTRL_INT_TEXT: return "int-text";
   case NYQ_CTRL_FLOAT_TEXT: return "float-text";
   case NYQ_CTRL_TEXT: return "text";
   case NYQ_CTRL_TIME: return "time";
   case NYQ_CTRL_FILE: return "file";
   default: return "unknown";
   }
}

//! Values of the FactoryDefaults preset (written by EffectManager when the
//! effect was first loaded)
std::map<wxString, wxString> FactoryDefaultValues(const Loaded &fx)
{
   std::map<wxString, wxString> result;
   wxString parms;
   if (!GetConfig(fx.plugin->GetDefinition(), PluginSettings::Private,
          FactoryDefaultsGroup(), wxT("Parameters"), parms))
      return result;
   CommandParameters eap{ parms };
   wxString key;
   long index = 0;
   eap.SetPath(wxT("/"));
   for (bool more = eap.GetFirstEntry(key, index); more;
        more = eap.GetNextEntry(key, index)) {
      wxString value;
      if (eap.Read(key, &value))
         result[key] = value;
   }
   return result;
}

double ParseDouble(const wxString &text, double fallback = 0.0)
{
   double d = fallback;
   if (!text.ToCDouble(&d))
      return fallback;
   return d;
}

std::vector<ParamDef> NyquistSchema(const Loaded &fx, NyquistBase &nyq,
   std::vector<ParamDef> *textRows)
{
   const auto defaults = FactoryDefaultValues(fx);
   std::vector<ParamDef> params;
   for (const auto &ctrl : nyq.mControls) {
      ParamDef p;
      p.key = ctrl.var;
      p.nyquistType = NyquistTypeName(ctrl.type);
      p.label = ToUtf8(ctrl.name);
      p.unit = ToUtf8(ctrl.label);
      const auto defIt = defaults.find(CommandParameters::NormalizeName(ctrl.var));
      const bool haveDef = defIt != defaults.end();
      double current = ctrl.val;
      if (current == UNINITIALIZED_CONTROL && ctrl.type != NYQ_CTRL_STRING)
         current = ParseDouble(ctrl.valStr);
      switch (ctrl.type) {
      case NYQ_CTRL_TEXT:
         if (textRows) {
            p.kind = ParamDef::Kind::String;
            textRows->push_back(std::move(p));
         }
         continue;
      case NYQ_CTRL_INT:
      case NYQ_CTRL_INT_TEXT:
         p.kind = ParamDef::Kind::Int;
         p.value = std::trunc(current);
         p.min = ctrl.low;
         p.max = ctrl.high;
         p.def = haveDef ? ParseDouble(defIt->second, p.value)
                         : std::trunc(ParseDouble(ctrl.valStr));
         break;
      case NYQ_CTRL_FLOAT:
      case NYQ_CTRL_FLOAT_TEXT:
      case NYQ_CTRL_TIME:
         p.kind = ParamDef::Kind::Double;
         p.value = current;
         p.min = ctrl.low;
         p.max = ctrl.high;
         p.def = haveDef ? ParseDouble(defIt->second, p.value)
                         : ParseDouble(ctrl.valStr);
         break;
      case NYQ_CTRL_CHOICE: {
         p.kind = ParamDef::Kind::Enum;
         p.choices = ctrl.choices;
         // low/high are not initialized for choices (effects.md §8.3)
         p.min = 0;
         p.max = double(ctrl.choices.size()) - 1;
         p.value = std::trunc(current);
         p.def = std::trunc(ParseDouble(ctrl.valStr));
         if (haveDef)
            for (size_t i = 0; i < ctrl.choices.size(); ++i)
               if (ctrl.choices[i].Internal() == defIt->second)
                  p.def = double(i);
         break;
      }
      case NYQ_CTRL_STRING:
      case NYQ_CTRL_FILE:
      default:
         p.kind = ParamDef::Kind::String;
         p.bounded = false;
         p.strValue = ctrl.valStr;
         p.strDef = haveDef ? defIt->second : wxString{};
         break;
      }
      params.push_back(std::move(p));
   }
   return params;
}

std::vector<ParamDef> NoiseReductionSchema(const Loaded &fx)
{
   const auto v = NoiseReductionGet(*fx.plugin);
   std::vector<ParamDef> params;
   auto add = [&](const wxChar *key, ParamDef::Kind kind, double value,
      double def, double mn, double mx) -> ParamDef & {
      ParamDef p;
      p.key = key;
      p.kind = kind;
      p.value = value;
      p.def = def;
      p.min = mn;
      p.max = mx;
      params.push_back(std::move(p));
      return params.back();
   };
   // Ranges of src/effects/NoiseReduction.cpp controlInfo()
   add(wxT("Gain"), ParamDef::Kind::Double, v.gain, 6.0, 0.0, 48.0);
   add(wxT("Sensitivity"), ParamDef::Kind::Double, v.sensitivity, 6.0, 0.01, 24.0);
   add(wxT("FreqSmoothing"), ParamDef::Kind::Int, v.freqSmoothing, 6, 0, 12);
   auto &choice = add(wxT("ReductionChoice"), ParamDef::Kind::Enum,
      v.reductionChoice == 2 ? 1 : 0, 0, 0, 1);
   // RESIDUE_CHOICE defined, ISOLATE_CHOICE not (NoiseReductionBase.h)
   choice.choices = { EnumValueSymbol{ wxT("Reduce"), XXO("Re&duce") },
      EnumValueSymbol{ wxT("Residue"), XXO("Resid&ue") } };
   return params;
}

//! The Nyquist Prompt: its code and the nested parameter string of the
//! code's own controls (NyquistBase.cpp KEY_Command / KEY_Parameters; the
//! const visitor reports nothing once settings were loaded, mExternal)
std::vector<ParamDef> PromptSchema(const Loaded &fx)
{
   CommandParameters eap{ GetParameterString(fx) };
   std::vector<ParamDef> params;
   for (auto key : { wxT("Command"), wxT("Parameters") }) {
      ParamDef p;
      p.key = key;
      p.kind = ParamDef::Kind::String;
      p.bounded = false;
      eap.Read(key, &p.strValue, wxString{});
      params.push_back(std::move(p));
   }
   // src/effects/nyquist/Nyquist.cpp prompt dialog
   params[0].label = StripMnemonics(XO("Enter Nyquist Command: ").Translation());
   return params;
}

std::vector<ParamDef> Schema(const Loaded &fx)
{
   std::vector<ParamDef> params;
   if (fx.special == Special::NoiseReduction)
      params = NoiseReductionSchema(fx);
   else if (IsNyquistPrompt(fx))
      params = PromptSchema(fx);
   else if (auto nyq = AsNyquist(fx))
      return NyquistSchema(fx, *nyq, nullptr);
   else {
      SchemaVisitor visitor;
      fx.plugin->GetDefinition().VisitSettings(visitor, *fx.settings);
      params = std::move(visitor.params);
   }
   // Labels of the built-ins
   const auto symbol = fx.descriptor
      ? fx.descriptor->GetSymbol().Internal() : wxString{};
   for (auto &p : params) {
      if (auto label = LookupParamLabel(symbol, p.key)) {
         p.label = label->label;
         p.unit = label->unit;
         p.display = label->display;
         p.intChoices = label->intChoices;
         p.semitones = label->semitones;
      }
      if (p.label.empty())
         p.label = ToUtf8(p.key);
   }
   return params;
}

json NumberOrInt(const ParamDef &p, double v)
{
   using K = ParamDef::Kind;
   if (p.kind == K::Int || p.kind == K::Size || p.kind == K::Enum ||
       !p.intChoices.empty())
      return json(static_cast<int64_t>(std::llround(Finite(v))));
   return json(Finite(v));
}

json ParamJson(const ParamDef &p)
{
   using K = ParamDef::Kind;
   const bool intChoice = !p.intChoices.empty() &&
      (p.kind == K::Int || p.kind == K::Size);
   json j{ { "key", ToUtf8(p.key) }, { "label", p.label },
      { "kind", KindName(p.kind, intChoice) } };
   switch (p.kind) {
   case K::Bool:
      j["default"] = p.def != 0;
      j["value"] = p.value != 0;
      break;
   case K::String:
      j["default"] = ToUtf8(p.strDef);
      j["value"] = ToUtf8(p.strValue);
      break;
   case K::Enum: {
      json choices = json::array(), labels = json::array();
      for (auto &c : p.choices) {
         choices.push_back(ToUtf8(c.Internal()));
         labels.push_back(StripMnemonics(
            c.Msgid().empty() ? c.Internal() : c.Msgid().Translation()));
      }
      j["choices"] = std::move(choices);
      j["choiceLabels"] = std::move(labels);
      j["default"] = NumberOrInt(p, p.def);
      j["value"] = NumberOrInt(p, p.value);
      break;
   }
   default:
      if (intChoice) {
         json choices = json::array(), labels = json::array();
         for (size_t i = 0; i < p.intChoices.size(); ++i) {
            choices.push_back(std::to_string(i + static_cast<size_t>(p.min)));
            labels.push_back(p.intChoices[i]);
         }
         j["choices"] = std::move(choices);
         j["choiceLabels"] = std::move(labels);
         j["default"] = NumberOrInt(p, p.def - p.min);
         j["value"] = NumberOrInt(p, p.value - p.min);
         break;
      }
      {
         const bool isFloat = p.kind == K::Float || p.kind == K::DoubleF;
         const double mn = isFloat ? ShortFloat(p.fmin) : Tidy(p.min);
         const double mx = isFloat ? ShortFloat(p.fmax) : Tidy(p.max);
         // FLT_MAX / INT_MAX bounds mean "unbounded" (Echo delay, Repeat
         // count, Nyquist "nil" bounds): left out
         auto bounded = [&](double v) {
            if (!std::isfinite(v) || std::fabs(v) >= 3.0e38)
               return false;
            if ((p.kind == K::Int || p.kind == K::Size) &&
                (v >= double(std::numeric_limits<int>::max()) ||
                 v <= double(std::numeric_limits<int>::min())))
               return false;
            return true;
         };
         if (bounded(mn))
            j["min"] = mn;
         if (bounded(mx))
            j["max"] = mx;
         if (p.scale != 0 && std::isfinite(p.scale))
            j["scale"] = Tidy(p.scale);
         j["default"] = NumberOrInt(p, Tidy(p.def));
         j["value"] = NumberOrInt(p, Tidy(p.value));
      }
      break;
   }
   j["unit"] = p.unit;
   j["display"] = p.display;
   if (p.semitones)
      j["semitones"] = true;
   return j;
}

// ---------------------------------------------------------------------------
// Validation of setParams values
// ---------------------------------------------------------------------------
[[noreturn]] void Bad(const ParamDef &p, const std::string &why)
{
   Fail(ErrorCode::INVALID_ARGS, "parameter '" + ToUtf8(p.key) + "': " + why);
}

//! Converts and checks one JSON value; returns the automation string value
//! and stores the numeric value (for post-write fixups) in `number`
wxString ValidateValue(const ParamDef &p, const json &v, double &number)
{
   using K = ParamDef::Kind;
   const bool intChoice = !p.intChoices.empty() &&
      (p.kind == K::Int || p.kind == K::Size);
   auto checkRange = [&](double d) {
      if (!p.bounded)
         return;
      if (d < p.min)
         Bad(p, FormatNumber(d) + " is below the minimum " + FormatNumber(p.min));
      if (d > p.max)
         Bad(p, FormatNumber(d) + " is above the maximum " + FormatNumber(p.max));
   };
   if (intChoice) {
      int64_t index = -1;
      if (v.is_number_integer())
         index = v.get<int64_t>();
      else if (v.is_string()) {
         const auto s = v.get<std::string>();
         for (size_t i = 0; i < p.intChoices.size(); ++i)
            if (s == std::to_string(i + static_cast<size_t>(p.min)))
               index = int64_t(i);
      }
      if (index < 0 || index >= int64_t(p.intChoices.size()))
         Bad(p, "expected a choice index 0.." +
            std::to_string(p.intChoices.size() - 1));
      number = double(index) + p.min;
      return wxString::Format(wxT("%lld"), static_cast<long long>(number));
   }
   switch (p.kind) {
   case K::Bool:
      if (!v.is_boolean())
         Bad(p, "expected a JSON boolean");
      number = v.get<bool>() ? 1 : 0;
      return number != 0 ? wxT("1") : wxT("0");
   case K::Int:
   case K::Size: {
      if (!v.is_number())
         Bad(p, "expected an integer");
      const double d = v.get<double>();
      if (!std::isfinite(d) || d != std::floor(d))
         Bad(p, "expected an integer");
      checkRange(d);
      if (d < double(std::numeric_limits<int>::min()) ||
          d > double(std::numeric_limits<int>::max()))
         Bad(p, "out of range");
      number = d;
      return wxString::Format(wxT("%d"), static_cast<int>(d));
   }
   case K::Float:
   case K::DoubleF: {
      if (!v.is_number())
         Bad(p, "expected a number");
      double d = v.get<double>();
      if (!std::isfinite(d))
         Bad(p, "expected a finite number");
      // The library compares in float precision (ReadAndVerify(float) /
      // double against float bounds): accept what rounds into the bounds,
      // then move onto the exact bound so the library check passes too
      const float f = static_cast<float>(d);
      if (f < p.fmin)
         Bad(p, FormatNumber(d) + " is below the minimum " +
            FormatNumber(ShortFloat(p.fmin)));
      if (f > p.fmax)
         Bad(p, FormatNumber(d) + " is above the maximum " +
            FormatNumber(ShortFloat(p.fmax)));
      d = std::clamp(d, double(p.fmin), double(p.fmax));
      number = d;
      return FromUtf8(FormatNumber(d));
   }
   case K::Double: {
      if (!v.is_number())
         Bad(p, "expected a number");
      double d = v.get<double>();
      if (!std::isfinite(d))
         Bad(p, "expected a finite number");
      // Bounds declared as float literals are reported in their short form
      // (Tidy): accept a value that equals the bound in float precision
      if (p.bounded) {
         if (d < p.min && static_cast<float>(d) == static_cast<float>(p.min))
            d = p.min;
         if (d > p.max && static_cast<float>(d) == static_cast<float>(p.max))
            d = p.max;
      }
      checkRange(d);
      number = d;
      return FromUtf8(FormatNumber(d));
   }
   case K::Enum: {
      if (v.is_number_integer()) {
         const auto i = v.get<int64_t>();
         if (i < 0 || i >= int64_t(p.choices.size()))
            Bad(p, "choice index " + std::to_string(i) + " out of range 0.." +
               std::to_string(p.choices.size() - 1));
         number = double(i);
         return p.choices[i].Internal();
      }
      if (v.is_string()) {
         const auto s = FromUtf8(v.get<std::string>());
         for (size_t i = 0; i < p.choices.size(); ++i)
            if (p.choices[i].Internal() == s) {
               number = double(i);
               return s;
            }
         std::string names;
         for (auto &c : p.choices)
            names += (names.empty() ? "" : ", ") + ToUtf8(c.Internal());
         Bad(p, "unknown choice '" + v.get<std::string>() + "' (choices: " +
            names + ")");
      }
      Bad(p, "expected a choice index or name");
   }
   case K::String:
   default:
      if (!v.is_string())
         Bad(p, "expected a string");
      {
         const auto s = v.get<std::string>();
         auto w = FromUtf8(s);
         if (w.empty() && !s.empty())
            Bad(p, "invalid UTF-8");
         return w;
      }
   }
}

const ParamDef *FindParam(const std::vector<ParamDef> &params,
   const std::string &key)
{
   const auto wanted = CommandParameters::NormalizeName(FromUtf8(key));
   for (auto &p : params)
      if (p.key == FromUtf8(key) ||
          CommandParameters::NormalizeName(p.key) == wanted)
         return &p;
   return nullptr;
}

struct CurvePoint { double f, dB; };
struct CurveEdit {
   std::vector<CurvePoint> points;
   std::optional<bool> linearFreq;
};

CurveEdit ParseCurve(const json &curve)
{
   if (!curve.is_object())
      Fail(ErrorCode::INVALID_ARGS, "curve must be an object");
   CurveEdit edit;
   if (auto it = curve.find("linearFreq"); it != curve.end() && !it->is_null()) {
      if (!it->is_boolean())
         Fail(ErrorCode::INVALID_ARGS, "curve.linearFreq must be a boolean");
      edit.linearFreq = it->get<bool>();
   }
   auto it = curve.find("points");
   if (it == curve.end() || !it->is_array())
      Fail(ErrorCode::INVALID_ARGS, "curve.points must be an array");
   // EqualizationBase::VisitSettings reads at most 200 points
   if (it->size() > 200)
      Fail(ErrorCode::INVALID_ARGS, "curve.points: at most 200 points");
   for (auto &point : *it) {
      if (!point.is_object() || !point.contains("f") || !point.contains("dB") ||
          !point["f"].is_number() || !point["dB"].is_number())
         Fail(ErrorCode::INVALID_ARGS,
            "curve.points: each point is {f: number, dB: number}");
      CurvePoint cp{ point["f"].get<double>(), point["dB"].get<double>() };
      // Ranges of EqualizationBase::VisitSettings; f <= 0 ends the list
      if (!std::isfinite(cp.f) || cp.f <= 0.0 || cp.f > 1000000.0)
         Fail(ErrorCode::INVALID_ARGS,
            "curve.points: f must be in (0, 1000000] Hz");
      if (!std::isfinite(cp.dB) || cp.dB < -10000.0 || cp.dB > 10000.0)
         Fail(ErrorCode::INVALID_ARGS,
            "curve.points: dB must be in [-10000, 10000]");
      edit.points.push_back(cp);
   }
   std::stable_sort(edit.points.begin(), edit.points.end(),
      [](const CurvePoint &a, const CurvePoint &b) { return a.f < b.f; });
   return edit;
}

bool IsEq(const Loaded &fx)
{
   return fx.special == Special::FilterCurveEq || fx.special == Special::GraphicEq;
}

//! Writes `params` into the effect through the library's visitor
//! (validating pass first); restores `original` on failure
bool WriteParameterString(const Loaded &fx, const wxString &params,
   const wxString &original)
{
   auto &effect = *fx.effect;
   CommandParameters eap{ params };
   ShuttleSetAutomation S;
   S.SetForValidating(&eap);
   bool ok = effect.VisitSettings(S, *fx.settings) && S.bOK;
   if (ok) {
      S.SetForWriting(&eap);
      ok = effect.VisitSettings(S, *fx.settings) && S.bOK;
   }
   if (!ok) {
      // EqualizationBase's visitor clears the curve even when validating
      CommandParameters restore{ original };
      ShuttleSetAutomation R;
      R.SetForWriting(&restore);
      effect.VisitSettings(R, *fx.settings);
   }
   return ok;
}

json CurveJson(const Loaded &fx)
{
   CommandParameters eap{ GetParameterString(fx) };
   json points = json::array();
   for (int i = 0; i < 200; ++i) {
      double f = -1, dB = 0;
      if (!eap.Read(wxString::Format(wxT("f%i"), i), &f) || f <= 0)
         break;
      eap.Read(wxString::Format(wxT("v%i"), i), &dB);
      points.push_back(json{ { "f", Finite(f) }, { "dB", Finite(dB) } });
   }
   bool lin = false;
   eap.Read(wxT("InterpolateLin"), &lin, false);
   return json{ { "points", std::move(points) }, { "linearFreq", lin } };
}

} // namespace

// ---------------------------------------------------------------------------
// Loading
// ---------------------------------------------------------------------------
Loaded LoadEffect(const std::string &id)
{
   Loaded fx;
   fx.id = FromUtf8(id);
   auto &pm = PluginManager::Get();
   fx.descriptor = pm.GetPlugin(fx.id);
   if (!fx.descriptor || fx.descriptor->GetPluginType() != PluginTypeEffect)
      Fail(ErrorCode::NOT_FOUND, "unknown effect '" + id + "'");
   if (!fx.descriptor->IsEnabled() || !fx.descriptor->IsValid() ||
       !PluginManager::IsPluginAvailable(*fx.descriptor))
      Fail(ErrorCode::NOT_FOUND, Translated(
         XO("This plugin could not be loaded.\nIt may have been deleted.")));
   auto [plugin, settings] = EffectManager::Get().GetEffectAndDefaultSettings(fx.id);
   fx.plugin = plugin;
   fx.settings = settings;
   fx.effect = dynamic_cast<Effect *>(plugin);
   if (!fx.plugin || !fx.settings || !fx.effect)
      Fail(ErrorCode::NOT_FOUND, Translated(
         XO("This plugin could not be loaded.\nIt may have been deleted.")));
   fx.type = fx.descriptor->GetEffectType();
   fx.special = SpecialOf(*fx.plugin);
   return fx;
}

wxString GetParameterString(const Loaded &fx)
{
   wxString s;
   fx.plugin->SaveSettingsAsString(*fx.settings, s);
   return s;
}

double LastUsedDuration(const Loaded &fx)
{
   double d = 30.0;
   GetConfig(fx.plugin->GetDefinition(), PluginSettings::Private,
      CurrentSettingsGroup(), EffectSettingsExtra::DurationKey(), d, 30.0);
   if (!std::isfinite(d) || d <= 0)
      d = 30.0;
   return d;
}

// ---------------------------------------------------------------------------
// Describe
// ---------------------------------------------------------------------------
json Describe(const Loaded &fx)
{
   auto &def = fx.plugin->GetDefinition();
   json result{ { "id", ToUtf8(fx.id) },
      { "name", Translated(PluginManager::Get().GetName(fx.id)) },
      { "type", TypeName(fx.type) } };

   json params = json::array();
   json nyquist = nullptr;
   if (auto nyq = AsNyquist(fx)) {
      std::vector<ParamDef> textRows;
      auto schema = NyquistSchema(fx, *nyq, &textRows);
      for (auto &p : schema)
         params.push_back(ParamJson(p));
      // Richer metadata: every control in .ny order, incl. text rows
      json controls = json::array();
      size_t next = 0, nextText = 0;
      for (const auto &ctrl : nyq->mControls) {
         if (ctrl.type == NYQ_CTRL_TEXT) {
            if (nextText < textRows.size()) {
               controls.push_back(json{ { "key", ToUtf8(ctrl.var) },
                  { "label", ToUtf8(ctrl.name) }, { "kind", "text" },
                  { "type", "text" } });
               ++nextText;
            }
            continue;
         }
         if (next >= schema.size())
            break;
         auto c = ParamJson(schema[next]);
         c["type"] = schema[next].nyquistType;
         if (ctrl.type == NYQ_CTRL_FILE) {
            json types = json::array();
            for (auto &ft : ctrl.fileTypes) {
               json exts = json::array();
               for (auto &e : ft.extensions)
                  exts.push_back(ToUtf8(e));
               types.push_back(json{ { "description",
                  Translated(ft.description) }, { "extensions", exts } });
            }
            c["fileTypes"] = std::move(types);
         }
         if (ctrl.type == NYQ_CTRL_INT || ctrl.type == NYQ_CTRL_FLOAT ||
             ctrl.type == NYQ_CTRL_TIME)
            c["ticks"] = ctrl.ticks;
         controls.push_back(std::move(c));
         ++next;
      }
      nyquist = json{ { "controls", std::move(controls) } };
   }
   else {
      for (auto &p : Schema(fx))
         params.push_back(ParamJson(p));
      if (fx.special == Special::Nyquist)   // the Prompt
         nyquist = json{ { "controls", json::array() }, { "prompt", true } };
   }
   result["params"] = std::move(params);

   json factory = json::array(), user = json::array();
   if (fx.special != Special::NoiseReduction) {
      for (auto &name : def.GetFactoryPresets())
         factory.push_back(ToUtf8(name));
      for (auto &name : GetUserPresets(*fx.plugin))
         user.push_back(ToUtf8(name));
   }
   result["presets"] = json{ { "factory", factory }, { "user", user } };

   // Generators take a duration; Nyquist generators decide their length
   const bool supportsDuration = fx.type == EffectTypeGenerate &&
      fx.special != Special::Nyquist;
   double duration = LastUsedDuration(fx);
   if (auto project = Session::Get().Project()) {
      const auto &region = ViewInfo::Get(*project).selectedRegion;
      if (supportsDuration && !region.isPoint())
         duration = region.duration();
   }
   result["supportsDuration"] = supportsDuration;
   result["duration"] = Finite(duration, 30.0);

   const auto special = SpecialName(fx.special);
   result["special"] = special.empty() ? json(nullptr) : json(special);
   if (fx.special == Special::NoiseReduction)
      result["profileCaptured"] = NoiseReductionHasProfile(*fx.plugin);
   if (IsEq(fx)) {
      result["curve"] = CurveJson(fx);
      result["curves"] = factory;
   }
   if (fx.special == Special::Amplify) {
      // The desktop dialog shows the new peak and refuses to clip
      const auto peak = SelectionPeak(fx);
      result["peak"] = peak ? json(Finite(*peak)) : json(nullptr);
   }
   result["nyquist"] = std::move(nyquist);
   result["help"] = ToUtf8(def.ManualPage().GET());
   return result;
}

// ---------------------------------------------------------------------------
// setParams
// ---------------------------------------------------------------------------
void ApplyParamArgs(const Loaded &fx, const json &args)
{
   const json *params = nullptr;
   if (auto it = args.find("params"); it != args.end() && !it->is_null()) {
      if (!it->is_object())
         Fail(ErrorCode::INVALID_ARGS, "params must be an object");
      params = &*it;
   }
   std::optional<CurveEdit> curve;
   if (auto it = args.find("curve"); it != args.end() && !it->is_null()) {
      if (!IsEq(fx))
         Fail(ErrorCode::INVALID_ARGS,
            "curve is only accepted by the equalization effects");
      curve = ParseCurve(*it);
   }
   std::optional<double> duration;
   if (auto d = OptDouble(args, "duration")) {
      if (fx.type != EffectTypeGenerate)
         Fail(ErrorCode::INVALID_ARGS, "duration is only accepted by generators");
      duration = ValidateDuration(*d);
   }

   // 1. Validate everything
   const auto schema = Schema(fx);
   std::vector<std::pair<const ParamDef *, wxString>> edits;
   std::vector<double> numbers;
   if (params)
      for (auto it = params->begin(); it != params->end(); ++it) {
         auto p = FindParam(schema, it.key());
         if (!p)
            Fail(ErrorCode::INVALID_ARGS, "unknown parameter '" + it.key() + "'");
         double number = 0;
         auto text = ValidateValue(*p, it.value(), number);
         if (fx.special == Special::Dtmf && p->key == wxT("Sequence")) {
            // PostSet of DtmfBase (DtmfBase.cpp): only the keypad symbols
            if (text.empty())
               Bad(*p, "the sequence must not be empty");
            for (auto ch : text)
               if (std::find(DtmfBase::kSymbols.begin(),
                      DtmfBase::kSymbols.end(), ch) == DtmfBase::kSymbols.end())
                  Bad(*p, "invalid character (allowed: 0-9 * # A-D a-z)");
         }
         edits.emplace_back(p, std::move(text));
         numbers.push_back(number);
      }

   // 2. Write
   if (fx.special == Special::NoiseReduction) {
      auto values = NoiseReductionGet(*fx.plugin);
      for (size_t i = 0; i < edits.size(); ++i) {
         const auto &key = edits[i].first->key;
         const double v = numbers[i];
         if (key == wxT("Gain"))
            values.gain = v;
         else if (key == wxT("Sensitivity"))
            values.sensitivity = v;
         else if (key == wxT("FreqSmoothing"))
            values.freqSmoothing = static_cast<int>(v);
         else if (key == wxT("ReductionChoice"))
            values.reductionChoice = v != 0 ? 2 /* NRC_LEAVE_RESIDUE */ : 0;
      }
      if (!edits.empty())
         NoiseReductionSet(*fx.plugin, values);
   }
   else if (!edits.empty() || curve) {
      const auto original = GetParameterString(fx);
      CommandParameters eap{ original };
      for (auto &[p, text] : edits)
         eap.Write(p->key, text);
      if (curve) {
         for (int i = 0; i < 200; ++i) {
            eap.DeleteEntry(wxString::Format(wxT("f%i"), i));
            eap.DeleteEntry(wxString::Format(wxT("v%i"), i));
         }
         for (size_t i = 0; i < curve->points.size(); ++i) {
            eap.Write(wxString::Format(wxT("f%i"), int(i)),
               FromUtf8(FormatNumber(curve->points[i].f)));
            eap.Write(wxString::Format(wxT("v%i"), int(i)),
               FromUtf8(FormatNumber(curve->points[i].dB)));
         }
         if (curve->linearFreq)
            eap.Write(wxT("InterpolateLin"),
               *curve->linearFreq ? wxT("1") : wxT("0"));
      }
      wxString merged;
      eap.GetParameters(merged);
      if (!WriteParameterString(fx, merged, original))
         Fail(ErrorCode::INVALID_ARGS,
            "the effect rejected the parameters (nothing changed)");
      // PostSet of the LoadSettings path, skipped by the visitor (Phaser:
      // even stages; DTMF: tone/silence lengths)
      if (fx.special == Special::Phaser || fx.special == Special::Dtmf)
         PostInitFixups(*fx.plugin, *fx.settings);
   }

   if (duration)
      SetConfig(fx.plugin->GetDefinition(), PluginSettings::Private,
         CurrentSettingsGroup(), EffectSettingsExtra::DurationKey(), *duration);
}

// ---------------------------------------------------------------------------
// Commands
// ---------------------------------------------------------------------------
namespace {

json DescribeCmd(const json &args)
{
   return Describe(LoadEffect(ArgString(args, "id")));
}

json SetParamsCmd(const json &args)
{
   auto fx = LoadEffect(ArgString(args, "id"));
   ApplyParamArgs(fx, args);
   return Describe(fx);
}

void ValidatePresetName(const std::string &name)
{
   auto w = FromUtf8(name);
   if (w.Strip(wxString::both).empty())
      Fail(ErrorCode::INVALID_ARGS, "the preset name must not be empty");
   for (auto ch : w)
      if (ch == wxT('/') || ch == wxT('\\') || ch < 0x20 || ch == wxT('='))
         Fail(ErrorCode::INVALID_ARGS,
            "the preset name must not contain '/', '\\', '=' or control characters");
}

bool HasUserPreset(const Loaded &fx, const wxString &name)
{
   const auto presets = GetUserPresets(*fx.plugin);
   return std::find(presets.begin(), presets.end(), name) != presets.end();
}

json LoadPresetCmd(const json &args)
{
   auto fx = LoadEffect(ArgString(args, "id"));
   const auto kind = ArgString(args, "kind");
   auto &def = fx.plugin->GetDefinition();
   const auto name = OptString(args, "name");
   const auto index = OptInt(args, "index");
   CaptureScope capture;
   if (kind == "factory") {
      if (fx.special == Special::NoiseReduction)
         Fail(ErrorCode::NOT_FOUND, "Noise Reduction has no factory presets");
      const auto presets = def.GetFactoryPresets();
      int found = -1;
      if (name) {
         const auto wanted = FromUtf8(*name);
         for (size_t i = 0; i < presets.size(); ++i)
            if (presets[i] == wanted)
               found = int(i);
      }
      else if (index && *index >= 0 && *index < int64_t(presets.size()))
         found = int(*index);
      else if (!index)
         Fail(ErrorCode::INVALID_ARGS, "name or index is required");
      if (found < 0)
         Fail(ErrorCode::NOT_FOUND, "no such factory preset");
      if (!def.LoadFactoryPreset(found, *fx.settings))
         Fail(ErrorCode::FAILED, capture.HasMessage() ? capture.Message()
            : "the preset could not be loaded");
   }
   else if (kind == "user") {
      if (!name)
         Fail(ErrorCode::INVALID_ARGS, "name is required");
      const auto wanted = FromUtf8(*name).Strip(wxString::both);
      if (fx.special == Special::NoiseReduction || !HasUserPreset(fx, wanted))
         Fail(ErrorCode::NOT_FOUND, "no such user preset");
      if (!def.LoadUserPreset(UserPresetsGroup(wanted), *fx.settings) ||
          capture.HasMessage())
         // LoadSettingsFromString loads the defaults and shows a message
         // when the stored string is invalid
         Fail(ErrorCode::FAILED, capture.HasMessage() ? capture.Message()
            : "the preset could not be loaded");
   }
   else if (kind == "defaults") {
      if (fx.special == Special::NoiseReduction)
         NoiseReductionSet(*fx.plugin, NoiseReductionValues{});
      else if (fx.special == Special::Amplify) {
         // AmplifyBase::LoadFactoryDefaults calls Init(), which needs the
         // effect context of DoEffect (SIGSEGV otherwise, effects.md §4.5):
         // with an audio selection it gives ratio = 1/peak like the desktop
         // dialog, else the stored factory defaults
         if (!LoadAmplifyDefaults(fx) &&
             !def.LoadUserPreset(FactoryDefaultsGroup(), *fx.settings))
            Fail(ErrorCode::FAILED, "the defaults could not be loaded");
      }
      else if (!def.LoadFactoryDefaults(*fx.settings))
         Fail(ErrorCode::FAILED, capture.HasMessage() ? capture.Message()
            : "the defaults could not be loaded");
   }
   else
      Fail(ErrorCode::INVALID_ARGS,
         "kind must be \"factory\", \"user\" or \"defaults\"");
   return Describe(fx);
}

json SavePresetCmd(const json &args)
{
   auto fx = LoadEffect(ArgString(args, "id"));
   const auto name = ArgString(args, "name");
   ValidatePresetName(name);
   if (fx.special == Special::NoiseReduction)
      Fail(ErrorCode::UNSUPPORTED, "Noise Reduction has no presets");
   const auto wxName = FromUtf8(name).Strip(wxString::both);
   if (!fx.plugin->GetDefinition().SaveUserPreset(UserPresetsGroup(wxName),
          *fx.settings))
      Fail(ErrorCode::FAILED, "the preset could not be saved");
   return json::object();
}

json DeletePresetCmd(const json &args)
{
   auto fx = LoadEffect(ArgString(args, "id"));
   const auto name = FromUtf8(ArgString(args, "name")).Strip(wxString::both);
   if (fx.special == Special::NoiseReduction || !HasUserPreset(fx, name))
      Fail(ErrorCode::NOT_FOUND, "no such user preset");
   if (!RemoveConfigSubgroup(fx.plugin->GetDefinition(),
          PluginSettings::Private, UserPresetsGroup(name)))
      Fail(ErrorCode::FAILED, "the preset could not be deleted");
   return json::object();
}

} // namespace

void RegisterSchemaCommands(ModuleRegistry &registry)
{
   registry.AddCommand("effects.describe", DescribeCmd);
   registry.AddCommand("effects.setParams", SetParamsCmd);
   registry.AddCommand("effects.loadPreset", LoadPresetCmd);
   registry.AddCommand("effects.savePreset", SavePresetCmd);
   registry.AddCommand("effects.deletePreset", DeletePresetCmd);
}

} // namespace effects
} // namespace aubridge
