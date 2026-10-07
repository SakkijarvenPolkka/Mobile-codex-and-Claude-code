/*  SPDX-License-Identifier: GPL-2.0-or-later */
/**********************************************************************

  Audacity Android port

  BridgeError.h

  Error codes of the command envelope (API.md §3.1) and the exception a
  command handler throws to answer with an error envelope.

**********************************************************************/
#pragma once

#include <stdexcept>
#include <string>

namespace aubridge {

//! Error codes of API.md §3.1 (string constants, the wire values)
namespace ErrorCode {
inline constexpr const char *NOT_READY = "NOT_READY";
inline constexpr const char *NO_PROJECT = "NO_PROJECT";
inline constexpr const char *AUDIO_BUSY = "AUDIO_BUSY";
inline constexpr const char *NO_SELECTION = "NO_SELECTION";
inline constexpr const char *INVALID_ARGS = "INVALID_ARGS";
inline constexpr const char *NOT_FOUND = "NOT_FOUND";
inline constexpr const char *STALE = "STALE";
inline constexpr const char *NEEDS_PATH = "NEEDS_PATH";
inline constexpr const char *CANCELLED = "CANCELLED";
inline constexpr const char *UNSUPPORTED = "UNSUPPORTED";
inline constexpr const char *FAILED = "FAILED";
inline constexpr const char *INTERNAL = "INTERNAL";
inline constexpr const char *UNKNOWN_COMMAND = "UNKNOWN_COMMAND";
}

//! Throw from a command handler to return `{"ok":false,"error":{code,message}}`
/*! The dispatcher does not roll anything back for a BridgeError: throw it
 before mutating, or mutate inside RunEdit() (which rolls back on any
 exception). */
struct BridgeError : std::runtime_error {
   BridgeError(std::string code, const std::string &message)
      : std::runtime_error{ message }, code{ std::move(code) } {}
   std::string code;
};

//! Convenience: `throw BridgeError{code, message}`
[[noreturn]] inline void Fail(const char *code, const std::string &message)
{
   throw BridgeError{ code, message };
}

} // namespace aubridge
