#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-2.0-or-later
"""Compile a gettext PO catalog into a binary MO file -- a pure-Python
replacement of `msgfmt` for the Audacity Android port (no gettext needed).

Semantics follow GNU msgfmt without options:
  * fuzzy entries are left out (but the fuzziness of the header is ignored),
    untranslated entries (empty msgstr / msgstr[0]) and obsolete ones (#~)
    too;
  * msgctxt entries are keyed "<context>\\x04<msgid>";
  * plural entries are keyed "<msgid>\\x00<msgid_plural>" and carry the
    msgstr[0..n-1] forms joined by "\\x00";
  * C escapes of PO strings are decoded; the catalog is written in UTF-8
    (a header charset other than UTF-8 is converted and rewritten);
  * originals are sorted by their bytes (binary search works); no hash
    table is written (allowed by the format; wxWidgets and GNU gettext do
    not need one).

Usage:
  po2mo.py input.po output.mo            compile (output is reproducible)
  po2mo.py --check input.po output.mo    exit 1 unless output.mo is what
                                         compiling input.po would produce
  po2mo.py --verify input.po output.mo   read output.mo back with Python's
                                         gettext module and compare every
                                         message, context and plural form
"""

import argparse
import gettext
import io
import re
import struct
import sys

MO_MAGIC = 0x950412DE
CTXT_SEPARATOR = "\x04"

_ESCAPES = {
    "n": "\n", "t": "\t", "r": "\r", "a": "\a", "b": "\b", "f": "\f",
    "v": "\v", "\\": "\\", '"': '"', "'": "'", "?": "?",
}


class PoError(Exception):
    pass


def unescape(text, where):
    """Decodes the C escapes of the contents of one PO string literal."""
    out = []
    i = 0
    n = len(text)
    while i < n:
        c = text[i]
        if c != "\\":
            out.append(c)
            i += 1
            continue
        i += 1
        if i >= n:
            raise PoError("%s: dangling backslash" % where)
        c = text[i]
        if c in _ESCAPES:
            out.append(_ESCAPES[c])
            i += 1
        elif c in "01234567":
            j = i
            while j < n and j < i + 3 and text[j] in "01234567":
                j += 1
            out.append(chr(int(text[i:j], 8)))
            i = j
        elif c == "x":
            j = i + 1
            while j < n and text[j] in "0123456789abcdefABCDEF":
                j += 1
            if j == i + 1:
                raise PoError("%s: \\x without digits" % where)
            out.append(chr(int(text[i + 1:j], 16)))
            i = j
        else:
            raise PoError("%s: unknown escape \\%s" % (where, c))
    return "".join(out)


class Entry:
    __slots__ = ("msgctxt", "msgid", "msgid_plural", "msgstr", "fuzzy",
                 "obsolete", "line")

    def __init__(self, line):
        self.msgctxt = None
        self.msgid = None
        self.msgid_plural = None
        self.msgstr = {}          # index -> text (index 0 for singular)
        self.fuzzy = False
        self.obsolete = False
        self.line = line

    def is_header(self):
        return self.msgctxt is None and self.msgid == ""

    def forms(self):
        if not self.msgstr:
            return []
        count = max(self.msgstr) + 1
        return [self.msgstr.get(i, "") for i in range(count)]


_STRING = re.compile(r'^"(.*)"\s*$')
_KEYWORD = re.compile(r'^(msgctxt|msgid_plural|msgid|msgstr(?:\[(\d+)\])?)\s+(".*)$')


def parse_po(text, name="<po>"):
    """Parses PO text (already decoded) into a list of Entry objects."""
    entries = []
    current = None
    flags_pending = set()
    field = None            # (kind, index) the continuation lines append to

    def finish():
        nonlocal current
        if current is not None:
            if current.msgid is None:
                raise PoError("%s:%d: entry without msgid" % (name, current.line))
            entries.append(current)
        current = None

    # Only "\n" ends a line (str.splitlines() would also split at U+0085,
    # U+2028, ... inside strings)
    for lineno, raw in enumerate(text.split("\n"), 1):
        line = raw.strip(" \t\r\f\v")
        where = "%s:%d" % (name, lineno)
        if not line:
            continue
        obsolete = False
        if line.startswith("#~"):
            obsolete = True
            line = line[2:].strip()
            if not line or line.startswith("|"):
                continue          # "#~|" previous-msgid of an obsolete entry
        elif line.startswith("#"):
            if line.startswith("#,"):
                flags = {f.strip() for f in line[2:].split(",")}
                # Flags precede the entry they belong to
                if current is not None and current.msgstr:
                    finish()
                flags_pending |= flags
            continue
        m = _KEYWORD.match(line)
        if m:
            keyword, index, rest = m.group(1), m.group(2), m.group(3)
            s = _STRING.match(rest)
            if not s:
                raise PoError("%s: malformed string" % where)
            value = unescape(s.group(1), where)
            if keyword in ("msgctxt", "msgid"):
                # A new entry starts with msgctxt, or with msgid unless the
                # entry so far holds just a msgctxt
                if not (keyword == "msgid" and current is not None and
                        current.msgid is None and not current.msgstr):
                    finish()
                    current = Entry(lineno)
                    current.fuzzy = "fuzzy" in flags_pending
                    flags_pending = set()
                current.obsolete = obsolete
                if keyword == "msgctxt":
                    current.msgctxt = value
                else:
                    current.msgid = value
                field = (keyword, None)
            else:
                if current is None or current.msgid is None:
                    raise PoError("%s: %s before msgid" % (where, keyword))
                if keyword == "msgid_plural":
                    current.msgid_plural = value
                    field = (keyword, None)
                else:
                    idx = int(index) if index is not None else 0
                    if idx in current.msgstr:
                        raise PoError("%s: duplicate %s" % (where, keyword))
                    current.msgstr[idx] = value
                    field = ("msgstr", idx)
            continue
        s = _STRING.match(line)
        if s:
            if current is None or field is None:
                raise PoError("%s: string continuation outside an entry" % where)
            value = unescape(s.group(1), where)
            kind, idx = field
            if kind == "msgctxt":
                current.msgctxt += value
            elif kind == "msgid":
                current.msgid += value
            elif kind == "msgid_plural":
                current.msgid_plural += value
            else:
                current.msgstr[idx] += value
            continue
        raise PoError("%s: cannot parse: %s" % (where, raw))
    finish()
    return entries


def header_charset(entries):
    for e in entries:
        if e.is_header() and not e.obsolete:
            m = re.search(r"charset=([^\s;]+)", e.msgstr.get(0, ""), re.I)
            if m:
                return m.group(1)
    return "UTF-8"


def decode_po(data, name="<po>"):
    """Decodes PO bytes using the charset declared in the header."""
    # The header is ASCII in practice; parse it from a latin-1 view first
    charset = header_charset(parse_po(data.decode("latin-1"), name))
    if charset.upper() in ("CHARSET", ""):
        charset = "UTF-8"
    return data.decode(charset), charset


def messages(entries, charset):
    """The (key, value) pairs msgfmt would write, as text."""
    result = {}
    for e in entries:
        if e.obsolete:
            continue
        forms = e.forms()
        if not forms or forms[0] == "":
            continue                    # untranslated
        if e.fuzzy and not e.is_header():
            continue
        if e.msgid_plural is not None:
            key = e.msgid + "\x00" + e.msgid_plural
            value = "\x00".join(forms)
        else:
            if len(forms) != 1:
                raise PoError("line %d: msgstr[n] without msgid_plural" % e.line)
            key = e.msgid
            value = forms[0]
        if e.msgctxt is not None:
            key = e.msgctxt + CTXT_SEPARATOR + key
        if e.is_header() and charset.upper().replace("-", "") != "UTF8":
            value = re.sub(r"charset=[^\s;\\]+", "charset=UTF-8", value, flags=re.I)
        if key in result:
            raise PoError("line %d: duplicate message %r" % (e.line, key[:60]))
        result[key] = value
    return result


def build_mo(msgs):
    """Serializes {key: value} into MO bytes (little endian, revision 0)."""
    items = sorted((k.encode("utf-8"), v.encode("utf-8")) for k, v in msgs.items())
    n = len(items)
    header_size = 7 * 4
    orig_table = header_size
    trans_table = orig_table + 8 * n
    hash_size = 0
    hash_offset = trans_table + 8 * n
    data_offset = hash_offset + 4 * hash_size
    originals = []
    translations = []
    blob = io.BytesIO()
    for key, _ in items:
        originals.append((len(key), data_offset + blob.tell()))
        blob.write(key + b"\x00")
    for _, value in items:
        translations.append((len(value), data_offset + blob.tell()))
        blob.write(value + b"\x00")
    out = io.BytesIO()
    out.write(struct.pack("<7I", MO_MAGIC, 0, n, orig_table, trans_table,
                          hash_size, hash_offset))
    for length, offset in originals:
        out.write(struct.pack("<2I", length, offset))
    for length, offset in translations:
        out.write(struct.pack("<2I", length, offset))
    out.write(blob.getvalue())
    return out.getvalue()


def read_mo(data):
    """Parses MO bytes back into {key: value} (for --verify)."""
    magic = struct.unpack("<I", data[:4])[0]
    endian = "<" if magic == MO_MAGIC else ">"
    if struct.unpack(endian + "I", data[:4])[0] != MO_MAGIC:
        raise PoError("not an MO file")
    _, _, n, orig, trans, _, _ = struct.unpack(endian + "7I", data[:28])
    result = {}
    for i in range(n):
        klen, koff = struct.unpack(endian + "2I", data[orig + 8 * i: orig + 8 * i + 8])
        vlen, voff = struct.unpack(endian + "2I", data[trans + 8 * i: trans + 8 * i + 8])
        result[data[koff:koff + klen].decode("utf-8")] = \
            data[voff:voff + vlen].decode("utf-8")
    return result


def compile_po(path):
    with open(path, "rb") as f:
        data = f.read()
    text, charset = decode_po(data, path)
    entries = parse_po(text, path)
    msgs = messages(entries, charset)
    return entries, msgs, build_mo(msgs)


def verify(entries, msgs, mo_bytes):
    """Reads the MO back (raw and through Python's gettext) and compares."""
    errors = []
    raw = read_mo(mo_bytes)
    if raw != msgs:
        errors.append("raw MO contents differ from the PO messages")
    trans = gettext.GNUTranslations(io.BytesIO(mo_bytes))
    checked = {"plain": 0, "context": 0, "plural": 0}
    for e in entries:
        if e.obsolete or e.is_header():
            continue
        forms = e.forms()
        included = bool(forms) and forms[0] != "" and not e.fuzzy
        if e.msgid_plural is not None:
            for n in (1, 2, 5):
                if e.msgctxt is not None:
                    got = trans.npgettext(e.msgctxt, e.msgid, e.msgid_plural, n)
                else:
                    got = trans.ngettext(e.msgid, e.msgid_plural, n)
                if included:
                    idx = trans.plural(n)
                    want = forms[idx] if idx < len(forms) else None
                else:
                    want = e.msgid if n == 1 else e.msgid_plural
                if got != want:
                    errors.append("line %d: ngettext(%r, %d) = %r, expected %r"
                                  % (e.line, e.msgid[:40], n, got, want))
            checked["plural"] += 1
        else:
            if e.msgctxt is not None:
                got = trans.pgettext(e.msgctxt, e.msgid)
                checked["context"] += 1
            else:
                got = trans.gettext(e.msgid)
                checked["plain"] += 1
            want = forms[0] if included else e.msgid
            if got != want:
                errors.append("line %d: gettext(%r) = %r, expected %r"
                              % (e.line, e.msgid[:40], got, want))
    info = trans.info()
    if info.get("content-type", "").lower().find("charset=utf-8") < 0:
        errors.append("header does not declare charset=UTF-8")
    return errors, checked


def main(argv):
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--check", action="store_true",
                      help="compare output.mo with the compiled input.po")
    mode.add_argument("--verify", action="store_true",
                      help="read output.mo back and compare every message")
    parser.add_argument("po")
    parser.add_argument("mo")
    args = parser.parse_args(argv)
    try:
        entries, msgs, mo_bytes = compile_po(args.po)
    except (PoError, UnicodeDecodeError) as e:
        print("po2mo: %s" % e, file=sys.stderr)
        return 2
    if args.check or args.verify:
        try:
            with open(args.mo, "rb") as f:
                existing = f.read()
        except OSError as e:
            print("po2mo: %s" % e, file=sys.stderr)
            return 1
        if args.check:
            if existing != mo_bytes:
                print("po2mo: %s is out of date: run %s %s %s" %
                      (args.mo, sys.argv[0], args.po, args.mo), file=sys.stderr)
                return 1
            print("po2mo: %s is up to date (%d messages)" % (args.mo, len(msgs)))
            return 0
        errors, checked = verify(entries, msgs, existing)
        for error in errors[:50]:
            print("po2mo: " + error, file=sys.stderr)
        if errors:
            print("po2mo: %d mismatches" % len(errors), file=sys.stderr)
            return 1
        print("po2mo: %s verified: %d messages, %d plain, %d with context, "
              "%d plural (%d fuzzy/untranslated left out)" %
              (args.mo, len(msgs), checked["plain"], checked["context"],
               checked["plural"],
               sum(1 for e in entries if not e.obsolete and not e.is_header())
               - (len(msgs) - 1)))
        return 0
    with open(args.mo, "wb") as f:
        f.write(mo_bytes)
    print("po2mo: wrote %s (%d messages)" % (args.mo, len(msgs)))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
