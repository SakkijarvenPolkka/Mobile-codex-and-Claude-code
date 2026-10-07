/*  SPDX-License-Identifier: GPL-2.0-or-later */
/**********************************************************************

  Audacity Android port

  FileSettings.cpp

  Copied from Audacity 3.7.9 lib-wx-init/SettingsWX.cpp (class renamed);
  MakeFileSettings() replaces the AudacityFileConfig factory of
  src/AudacityApp.cpp.

**********************************************************************/
#include "FileSettings.h"

#include <cassert>

#include <wx/confbase.h>
#include <wx/fileconf.h>

namespace aubridge {

void FileSettings::DoBeginGroup(const wxString& prefix)
{
   if(prefix.StartsWith("/"))
      mGroupStack.push_back(prefix);
   else
   {
      if(mGroupStack.size() > 1)
         mGroupStack.push_back(mGroupStack.Last() + "/" + prefix);
      else
         mGroupStack.push_back("/" + prefix);
   }
   //This creates group if it didn't exist
   mConfig->SetPath(mGroupStack.Last());
}

void FileSettings::DoEndGroup() noexcept
{
   assert(mGroupStack.size() > 1);// "No matching DoBeginGroup"

   if(mGroupStack.size() > 1)
      mGroupStack.pop_back();

   mConfig->SetPath(mGroupStack.Last());
}

FileSettings::FileSettings(std::shared_ptr<wxConfigBase> config)
   : mConfig{std::move(config)}
{
   mGroupStack.push_back("/");
}

FileSettings::~FileSettings()
{
   mConfig->Flush();
}

wxString FileSettings::GetGroup() const
{
   assert(!mGroupStack.empty());
   if(mGroupStack.size() > 1)
   {
      const auto& path = mGroupStack.Last();
      return path.Right(path.Length() - 1);
   }
   return {};
}

wxArrayString FileSettings::GetChildGroups() const
{
   long index;
   wxString group;

   if(mConfig->GetFirstGroup(group, index))
   {
      wxArrayString groups;
      groups.push_back(group);
      while(mConfig->GetNextGroup(group, index))
         groups.push_back(group);
      return groups;
   }
   return {};
}

wxArrayString FileSettings::GetChildKeys() const
{
   long index;
   wxString key;
   if(mConfig->GetFirstEntry(key, index))
   {
      wxArrayString keys;
      keys.push_back(key);
      while(mConfig->GetNextEntry(key, index))
         keys.push_back(key);
      return keys;
   }
   return {};
}

bool FileSettings::HasEntry(const wxString& key) const
{
   return mConfig->HasEntry(MakePath(key));
}

bool FileSettings::HasGroup(const wxString& key) const
{
   return mConfig->HasGroup(MakePath(key));
}

bool FileSettings::Remove(const wxString& key)
{
   if(key.empty())
   {
      for(auto& group : GetChildGroups())
         mConfig->DeleteGroup(group);
      for(auto& entry : GetChildKeys())
         mConfig->DeleteEntry(entry, false);
      return true;
   }
   const auto path = MakePath(key);
   if(mConfig->HasEntry(path))
      return mConfig->DeleteEntry(path, false);
   if(mConfig->HasGroup(path))
      return mConfig->DeleteGroup(path);
   return false;
}

void FileSettings::Clear()
{
   mConfig->DeleteAll();
}

bool FileSettings::Read(const wxString& key, bool* value) const
{
   return mConfig->Read(MakePath(key), value);
}

bool FileSettings::Read(const wxString& key, int* value) const
{
   return mConfig->Read(MakePath(key), value);
}

bool FileSettings::Read(const wxString& key, long* value) const
{
   return mConfig->Read(MakePath(key), value);
}

bool FileSettings::Read(const wxString& key, long long* value) const
{
   wxString str;
   if(mConfig->Read(MakePath(key), &str))
   {
      if(str.ToLongLong(value))
         return true;
   }
   return false;
}

bool FileSettings::Read(const wxString& key, double* value) const
{
   return mConfig->Read(MakePath(key), value);
}

bool FileSettings::Read(const wxString& key, wxString* value) const
{
   return mConfig->Read(MakePath(key), value);
}

bool FileSettings::Write(const wxString& key, bool value)
{
   return mConfig->Write(MakePath(key), value);
}

bool FileSettings::Write(const wxString& key, int value)
{
   return mConfig->Write(MakePath(key), value);
}

bool FileSettings::Write(const wxString& key, long value)
{
   return mConfig->Write(MakePath(key), value);
}

bool FileSettings::Write(const wxString& key, long long value)
{
   return mConfig->Write(MakePath(key), wxString::Format("%lld", value));
}

bool FileSettings::Write(const wxString& key, double value)
{
   return mConfig->Write(MakePath(key), value);
}

bool FileSettings::Write(const wxString& key, const wxString& value)
{
   return mConfig->Write(MakePath(key), value);
}

bool FileSettings::Flush() noexcept
{
   try
   {
      return mConfig->Flush();
   }
   catch(...)
   {
      //TODO: log error
   }
   return false;
}

wxString FileSettings::MakePath(const wxString& key) const
{
   if(key.StartsWith("/"))
      return key;
   if(mGroupStack.size() > 1)
      return mGroupStack.Last() + "/" + key;
   return "/" + key;
}

std::unique_ptr<audacity::BasicSettings> MakeFileSettings(const wxString &path)
{
   auto config = std::make_shared<wxFileConfig>(wxEmptyString, wxEmptyString,
      path, wxEmptyString, wxCONFIG_USE_LOCAL_FILE, wxConvUTF8);
   config->SetExpandEnvVars(false);
   return std::make_unique<FileSettings>(std::move(config));
}

} // namespace aubridge
