/*  SPDX-License-Identifier: GPL-2.0-or-later */
/**********************************************************************

  Audacity Android port

  EffectMenus.cpp

  effects.list (API.md §3.3, §5.4): every enabled effect plus the menu
  sections of the Generate / Effect / Analyze / Tools menus.

  The grouping is a port of MenuHelper::PopulateEffectsMenu
  (src/menus/MenuHelper.cpp, Audacity 3.7.9, the Audacity Team) for every
  /Effects/GroupBy choice, and the "default" Effect menu groups are those of
  resources/EffectsMenuDefaults.xml (compiled in below; titles are sent as
  their English msgids, which Kotlin translates).  A desktop submenu becomes
  a titled MenuSection; items directly in a menu become untitled sections.

**********************************************************************/
#include "EffectsInternal.h"

#include <algorithm>
#include <map>
#include <tuple>

#include "Modules.h"
#include "Session.h"

#include "EffectManager.h"
#include "EffectPlugin.h"
#include "PluginManager.h"
#include "Prefs.h"

#include <wx/filename.h>

namespace aubridge {
namespace effects {

std::string TypeName(EffectType type)
{
   switch (type) {
   case EffectTypeGenerate: return "generate";
   case EffectTypeAnalyze: return "analyze";
   case EffectTypeTool: return "tool";
   case EffectTypeProcess: return "process";
   default: return "hidden";
   }
}

bool IsBundled(const PluginDescriptor &plug)
{
   // MenuHelper.cpp IsBundledPlugin: default effects, and plug-ins below the
   // application's resources; on Android the bundled .ny files are extracted
   // to the start configuration's pluginsDir (and Nyquist's own nyquistDir)
   if (plug.IsEffectDefault())
      return true;
   const auto &paths = Session::Get().GetPaths();
   wxFileName pluginPath{ plug.GetPath() };
   pluginPath.MakeAbsolute();
   const auto dir = pluginPath.GetPath();
   for (const auto &base : { paths.pluginsDir, paths.nyquistDir }) {
      if (base.empty())
         continue;
      const auto baseDir = wxFileName::DirName(FromUtf8(base)).GetPath();
      if (dir == baseDir || dir.StartsWith(baseDir + wxFileName::GetPathSeparator()))
         return true;
   }
   return false;
}

namespace {

// resources/EffectsMenuDefaults.xml of 3.7.9 (group name, effect msgids)
struct MenuGroup {
   const wxChar *name;
   std::vector<const wxChar *> effects;
};
const std::vector<MenuGroup> &EffectsMenuDefaults()
{
   static const std::vector<MenuGroup> groups{
      { wxT("Volume and Compression"), { wxT("Amplify"), wxT("Compressor"),
         wxT("Limiter"), wxT("Normalize"), wxT("Loudness Normalization"),
         wxT("Auto Duck") } },
      { wxT("Fading"), { wxT("Fade In"), wxT("Fade Out"),
         wxT("Studio Fade Out"), wxT("Adjustable Fade"),
         wxT("Crossfade Clips"), wxT("Crossfade Tracks") } },
      { wxT("Pitch and Tempo"), { wxT("Change Pitch"),
         wxT("Change Speed and Pitch"), wxT("Change Tempo"),
         wxT("Paulstretch"), wxT("Sliding Stretch") } },
      { wxT("EQ and Filters"), { wxT("Bass and Treble"), wxT("Graphic EQ"),
         wxT("Filter Curve EQ"), wxT("High-Pass Filter"),
         wxT("Low-Pass Filter"), wxT("Shelf Filter"), wxT("Notch Filter") } },
      { wxT("Noise Removal and Repair"), { wxT("Click Removal"),
         wxT("Noise Reduction"), wxT("Noise Gate"), wxT("Repair"),
         wxT("Clip Fix") } },
      { wxT("Delay and Reverb"), { wxT("Echo"), wxT("Reverb"),
         wxT("Delay") } },
      { wxT("Distortion and Modulation"), { wxT("Tremolo"),
         wxT("Distortion"), wxT("Wahwah"), wxT("Phaser"), wxT("Vocoder") } },
      { wxT("Special"), { wxT("Repeat"), wxT("Reverse"), wxT("Invert"),
         wxT("Truncate Silence"), wxT("Vocal Reduction and Isolation"),
         wxT("Vocal Remover") } },
      { wxT("Spectral Tools"), { wxT("Spectral Delete"),
         wxT("Spectral Edit Multi Tool"), wxT("Spectral Edit Parametric EQ"),
         wxT("Spectral Edit Shelves") } },
      { wxT("Legacy"), { wxT("Legacy Compressor"), wxT("Legacy Limiter"),
         wxT("Classic Filters") } },
   };
   return groups;
}

using Plugs = std::vector<const PluginDescriptor *>;

// MenuHelper.cpp IsEnabledPlugin
bool IsEnabledPlugin(const PluginDescriptor *plug)
{
   if (PluginManager::Get().IsPluginLoaded(plug->GetID()) &&
       EffectManager::Get().IsHidden(plug->GetID()))
      return false;
   return plug->IsEnabled();
}

bool InDefaultGroups(const PluginDescriptor *plug)
{
   const auto msgid = plug->GetSymbol().Msgid().MSGID().GET();
   for (auto &group : EffectsMenuDefaults())
      for (auto name : group.effects)
         if (msgid == name)
            return true;
   return false;
}

wxString NameKey(const PluginDescriptor *p)
{
   return p->GetSymbol().Translation();
}

TranslatableString Vendor(const PluginDescriptor *p, const TranslatableString &fallback)
{
   auto v = EffectManager::Get().GetVendorName(p->GetID());
   return v.empty() ? fallback : v;
}

TranslatableString Family(const PluginDescriptor *p, const TranslatableString &fallback)
{
   auto v = EffectManager::Get().GetEffectFamilyName(p->GetID());
   return v.empty() ? fallback : v;
}

// The comparators of MenuHelper.cpp
bool CompareByName(const PluginDescriptor *a, const PluginDescriptor *b)
{
   return std::make_pair(NameKey(a), a->GetPath()) <
      std::make_pair(NameKey(b), b->GetPath());
}

bool CompareByPublisher(const PluginDescriptor *a, const PluginDescriptor *b)
{
   return std::make_tuple(Vendor(a, XO("Uncategorized")).Translation(),
             NameKey(a), a->GetPath()) <
      std::make_tuple(Vendor(b, XO("Uncategorized")).Translation(),
         NameKey(b), b->GetPath());
}

bool CompareByPublisherAndName(const PluginDescriptor *a,
   const PluginDescriptor *b)
{
   auto ak = a->IsEffectDefault() ? wxString{} : Vendor(a, {}).Translation();
   auto bk = b->IsEffectDefault() ? wxString{} : Vendor(b, {}).Translation();
   return std::make_tuple(ak, NameKey(a), a->GetPath()) <
      std::make_tuple(bk, NameKey(b), b->GetPath());
}

bool CompareByTypeAndName(const PluginDescriptor *a, const PluginDescriptor *b)
{
   auto ak = a->IsEffectDefault() ? wxString{}
      : Family(a, XO("Uncategorized")).Translation();
   auto bk = b->IsEffectDefault() ? wxString{}
      : Family(b, XO("Uncategorized")).Translation();
   return std::make_tuple(ak, NameKey(a), a->GetPath()) <
      std::make_tuple(bk, NameKey(b), b->GetPath());
}

bool CompareByType(const PluginDescriptor *a, const PluginDescriptor *b)
{
   return std::make_tuple(Family(a, XO("Uncategorized")).Translation(),
             NameKey(a), a->GetPath()) <
      std::make_tuple(Family(b, XO("Uncategorized")).Translation(),
         NameKey(b), b->GetPath());
}

bool CompareByTypeAndPublisher(const PluginDescriptor *a,
   const PluginDescriptor *b)
{
   return std::make_tuple(Family(a, XO("Uncategorized")).Translation(),
             Vendor(a, XO("Unknown")).Translation(), NameKey(a), a->GetPath()) <
      std::make_tuple(Family(b, XO("Uncategorized")).Translation(),
         Vendor(b, XO("Unknown")).Translation(), NameKey(b), b->GetPath());
}

//! Builds the MenuSection list of one menu
class SectionWriter {
public:
   json sections = json::array();

   //! Items directly in the menu (untitled); consecutive ones of the same
   //! desktop section are merged
   void Items(const Plugs &plugs)
   {
      if (plugs.empty())
         return;
      if (mOpenUntitled && !sections.empty())
         for (auto p : plugs)
            sections.back()["ids"].push_back(ToUtf8(p->GetID()));
      else {
         json ids = json::array();
         for (auto p : plugs)
            ids.push_back(ToUtf8(p->GetID()));
         sections.push_back(json{ { "title", nullptr }, { "ids", ids } });
      }
      mOpenUntitled = true;
   }
   //! A desktop submenu
   void Submenu(const std::string &title, const Plugs &plugs)
   {
      if (plugs.empty())
         return;
      json ids = json::array();
      for (auto p : plugs)
         ids.push_back(ToUtf8(p->GetID()));
      sections.push_back(json{ { "title", title }, { "ids", ids } });
      mOpenUntitled = false;
   }
   //! A new desktop Section (separator)
   void EndSection() { mOpenUntitled = false; }

private:
   bool mOpenUntitled = false;
};

enum class GroupBy { Publisher, Type, TypePublisher };

// MenuHelper.cpp AddGroupedEffectMenuItems: a submenu per group with more
// than one item, the single items directly in the menu
void AddGrouped(SectionWriter &out, const Plugs &plugs, GroupBy groupBy)
{
   const auto unknown = XO("Unknown");
   std::vector<TranslatableString> path;
   Plugs group;
   auto flush = [&] {
      if (group.empty())
         return;
      if (!path.empty() && group.size() > 1) {
         auto title = path.back();
         if (groupBy == GroupBy::TypePublisher && path.size() > 1)
            title = XO("%s: %s").Format(path[0], path[1]);
         out.Submenu(Translated(title), group);
      }
      else
         out.Items(group);
      group.clear();
   };
   for (auto plug : plugs) {
      if (groupBy == GroupBy::Publisher) {
         const auto vendor = Vendor(plug, unknown);
         if (path.empty() || path[0].Translation() != vendor.Translation()) {
            flush();
            path = { vendor };
         }
      }
      else if (groupBy == GroupBy::Type) {
         const auto family = Family(plug, unknown);
         if (path.empty() || path[0].Translation() != family.Translation()) {
            flush();
            path = { family };
         }
      }
      else {
         const auto family = Family(plug, unknown);
         const auto vendor = Vendor(plug, unknown);
         if (path.empty() || path[0].Translation() != family.Translation()) {
            flush();
            path = { family, vendor };
         }
         else if (path[1].Translation() != vendor.Translation()) {
            flush();
            path[1] = vendor;
         }
      }
      group.push_back(plug);
   }
   flush();
}

// MenuHelper.cpp MakeAddGroupItems: the EffectsMenuDefaults.xml groups,
// each sorted by translated name
void AddDefaultGroups(SectionWriter &out, const Plugs &plugs)
{
   for (auto &group : EffectsMenuDefaults()) {
      std::vector<TranslatableString> names;
      for (auto name : group.effects)
         names.push_back(TranslatableString{ name, {} });
      std::sort(names.begin(), names.end(),
         [](const TranslatableString &a, const TranslatableString &b) {
            return a.Translation() < b.Translation();
         });
      Plugs items;
      for (auto &name : names) {
         auto it = std::find_if(plugs.begin(), plugs.end(),
            [&](const PluginDescriptor *p) {
               return p->GetSymbol().Msgid() == name;
            });
         if (it != plugs.end())
            items.push_back(*it);
      }
      out.Submenu(ToUtf8(group.name), items);
   }
}

struct Builder {
   std::function<bool(const PluginDescriptor *)> filter;
   std::function<bool(const PluginDescriptor *, const PluginDescriptor *)> compare;
   std::function<void(SectionWriter &, const Plugs &)> add;
   Plugs plugs;
};

json PopulateMenu(EffectType type, const wxString &groupBy)
{
   auto sorted = [](SectionWriter &out, const Plugs &plugs) { out.Items(plugs); };
   auto grouped = [](GroupBy by) {
      return [by](SectionWriter &out, const Plugs &plugs) {
         AddGrouped(out, plugs, by);
      };
   };
   auto defaultFilter = [](const PluginDescriptor *p) {
      return IsEnabledPlugin(p) && p->IsEffectDefault();
   };
   auto bundledFilter = [](const PluginDescriptor *p) {
      return IsEnabledPlugin(p) && IsBundled(*p);
   };

   std::vector<Builder> builders;
   if (groupBy == wxT("default")) {
      if (type == EffectTypeProcess) {
         builders.push_back({ [=](const PluginDescriptor *p) {
               return bundledFilter(p) && InDefaultGroups(p);
            }, nullptr, AddDefaultGroups, {} });
         builders.push_back({ IsEnabledPlugin, CompareByPublisher,
            grouped(GroupBy::Publisher), {} });
      }
      else {
         builders.push_back({ bundledFilter, CompareByName, sorted, {} });
         builders.push_back({ IsEnabledPlugin, CompareByPublisher,
            grouped(GroupBy::Publisher), {} });
      }
   }
   else if (groupBy == wxT("sortby:publisher:name")) {
      builders.push_back({ defaultFilter, CompareByName, sorted, {} });
      builders.push_back({ IsEnabledPlugin, CompareByPublisherAndName, sorted, {} });
   }
   else if (groupBy == wxT("sortby:type:name")) {
      builders.push_back({ defaultFilter, CompareByName, sorted, {} });
      builders.push_back({ IsEnabledPlugin, CompareByTypeAndName, sorted, {} });
   }
   else if (groupBy == wxT("groupby:publisher")) {
      builders.push_back({ defaultFilter, CompareByPublisher,
         grouped(GroupBy::Publisher), {} });
      builders.push_back({ IsEnabledPlugin, CompareByPublisher,
         grouped(GroupBy::Publisher), {} });
   }
   else if (groupBy == wxT("groupby:type")) {
      builders.push_back({ defaultFilter, CompareByType, grouped(GroupBy::Type), {} });
      builders.push_back({ IsEnabledPlugin, CompareByType, grouped(GroupBy::Type), {} });
   }
   else if (groupBy == wxT("groupby:type:publisher")) {
      builders.push_back({ defaultFilter, CompareByType, grouped(GroupBy::Type), {} });
      builders.push_back({ IsEnabledPlugin, CompareByTypeAndPublisher,
         grouped(GroupBy::TypePublisher), {} });
   }
   else {   // "sortby:name"
      builders.push_back({ defaultFilter, CompareByName, sorted, {} });
      builders.push_back({ IsEnabledPlugin, CompareByName, sorted, {} });
   }

   for (auto &plug : PluginManager::Get().EffectsOfType(type))
      for (auto &builder : builders)
         if (builder.filter(&plug)) {
            builder.plugs.push_back(&plug);
            break;
         }

   SectionWriter out;
   for (auto &builder : builders) {
      if (builder.compare)
         std::sort(builder.plugs.begin(), builder.plugs.end(), builder.compare);
      builder.add(out, builder.plugs);
      out.EndSection();
   }
   return std::move(out.sections);
}

json EffectInfoJson(const PluginDescriptor &plug)
{
   auto &pm = PluginManager::Get();
   const auto &id = plug.GetID();
   json info{ { "id", ToUtf8(id) },
      { "name", Translated(pm.GetName(id)) },
      { "type", TypeName(plug.GetEffectType()) },
      { "family", ToUtf8(plug.GetEffectFamily()) },
      { "vendor", Translated(EffectManager::Get().GetVendorName(id)) },
      { "interactive", plug.IsEffectInteractive() },
      { "realtime", plug.IsEffectRealtime() },
      { "isDefault", plug.IsEffectDefault() } };
   std::string description;
   std::string special = SpecialNameForPath(plug.GetPath());
   // Loading parses .ny files and creates the built-in objects once
   if (auto effect = EffectManager::Get().GetEffect(id)) {
      description = Translated(effect->GetDefinition().GetDescription());
      if (special.empty())
         special = SpecialName(SpecialOf(*effect));
   }
   info["description"] = description;
   info["special"] = special.empty() ? json(nullptr) : json(special);
   return info;
}

json ListCmd(const json &)
{
   auto &pm = PluginManager::Get();
   std::vector<const PluginDescriptor *> all;
   for (auto &plug : pm.PluginsOfType(PluginTypeEffect)) {
      const auto type = plug.GetEffectType();
      if (type != EffectTypeGenerate && type != EffectTypeProcess &&
          type != EffectTypeAnalyze && type != EffectTypeTool)
         continue;
      if (!IsEnabledPlugin(&plug))
         continue;
      all.push_back(&plug);
   }
   std::sort(all.begin(), all.end(), CompareByName);
   json effects = json::array();
   for (auto plug : all)
      effects.push_back(EffectInfoJson(*plug));

   // After loading (IsEnabledPlugin consults loaded effects' hidden flags)
   const wxString groupBy = gPrefs
      ? gPrefs->Read(wxT("/Effects/GroupBy"), wxT("default")) : wxT("default");
   json menus{ { "generate", PopulateMenu(EffectTypeGenerate, groupBy) },
      { "effect", PopulateMenu(EffectTypeProcess, groupBy) },
      { "analyze", PopulateMenu(EffectTypeAnalyze, groupBy) },
      { "tools", PopulateMenu(EffectTypeTool, groupBy) } };
   return json{ { "effects", std::move(effects) }, { "menus", std::move(menus) } };
}

} // namespace

void RegisterMenuCommands(ModuleRegistry &registry)
{
   registry.AddCommand("effects.list", ListCmd);
}

} // namespace effects
} // namespace aubridge
