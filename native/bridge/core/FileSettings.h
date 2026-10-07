/*  SPDX-License-Identifier: GPL-2.0-or-later */
/**********************************************************************

  Audacity Android port

  FileSettings.h

  audacity::BasicSettings over wxFileConfig: a copy of lib-wx-init's
  SettingsWX (Audacity 3.7.9, lib-wx-init/SettingsWX.{h,cpp}), which
  cannot be linked because lib-wx-init is a wx GUI library.  Groups and
  child enumeration work (ActiveProjects, PluginManager need them).

**********************************************************************/
#pragma once

#include <memory>

#include <wx/arrstr.h>
#include <wx/string.h>

#include "BasicSettings.h"

class wxConfigBase;

namespace aubridge {

class FileSettings final : public audacity::BasicSettings
{
   wxArrayString mGroupStack;
   std::shared_ptr<wxConfigBase> mConfig;
protected:
   void DoBeginGroup(const wxString& prefix) override;
   void DoEndGroup() noexcept override;

public:
   explicit FileSettings(std::shared_ptr<wxConfigBase> config);
   ~FileSettings() override;

   wxString GetGroup() const override;
   wxArrayString GetChildGroups() const override;
   wxArrayString GetChildKeys() const override;

   bool HasEntry(const wxString& key) const override;
   bool HasGroup(const wxString& key) const override;
   bool Remove(const wxString& key) override;
   void Clear() override;

   bool Read(const wxString& key, bool* value) const override;
   bool Read(const wxString& key, int* value) const override;
   bool Read(const wxString& key, long* value) const override;
   bool Read(const wxString& key, long long* value) const override;
   bool Read(const wxString& key, double* value) const override;
   bool Read(const wxString& key, wxString* value) const override;

   bool Write(const wxString& key, bool value) override;
   bool Write(const wxString& key, int value) override;
   bool Write(const wxString& key, long value) override;
   bool Write(const wxString& key, long long value) override;
   bool Write(const wxString& key, double value) override;
   bool Write(const wxString& key, const wxString& value) override;

   bool Flush() noexcept override;

private:
   wxString MakePath(const wxString& key) const;
};

//! A local wxFileConfig at `path` (UTF-8 file, no environment variable
//! expansion -- AudacityFileConfig does the same, issue #6448)
std::unique_ptr<audacity::BasicSettings> MakeFileSettings(const wxString &path);

} // namespace aubridge
