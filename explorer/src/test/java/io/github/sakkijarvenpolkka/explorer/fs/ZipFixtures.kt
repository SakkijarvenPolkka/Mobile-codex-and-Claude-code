// SPDX-License-Identifier: MIT
package io.github.sakkijarvenpolkka.explorer.fs

import java.io.ByteArrayOutputStream
import java.nio.charset.Charset
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** General purpose flag bit 0: the entry is encrypted. */
const val FLAG_ENCRYPTED = 0x0001

/** General purpose flag bit 11 (EFS): the entry's name is UTF-8. */
const val FLAG_UTF8 = 0x0800

/**
 * An archive written by [ZipOutputStream] with [charset], of [entries] in order: a name to its content,
 * or to null for a folder (a name ending in `/`).
 */
fun zipOf(vararg entries: Pair<String, String?>, charset: Charset = Charsets.UTF_8): ByteArray {
    val bytes = ByteArrayOutputStream()
    ZipOutputStream(bytes, charset).use { zip ->
        for ((name, content) in entries) {
            zip.putNextEntry(ZipEntry(name))
            if (content != null) zip.write(content.toByteArray())
            zip.closeEntry()
        }
    }
    return bytes.toByteArray()
}

/** Writes the archive [bytes] to [name] in [dir]. */
fun putZip(dir: Path, name: String, bytes: ByteArray): Path = Files.write(dir.resolve(name), bytes)

/** The names of the entries of the archive at [zip], in order, as UTF-8. */
fun entryNames(zip: Path): List<String> = ZipFile(zip.toFile()).use { file -> file.entries().toList().map { it.name } }

/**
 * Where the headers of the archive [bytes] are, to patch: the offset of each entry's central header, in
 * order, from the end of central directory record (no ZIP64, no archive comment).
 */
fun centralHeaders(bytes: ByteArray): List<Int> {
    val end = bytes.size - 22
    check(u32(bytes, end) == 0x06054b50L) { "no end of central directory record" }
    val count = u16(bytes, end + 10)
    var at = u32(bytes, end + 16).toInt()
    return List(count) {
        val header = at
        check(u32(bytes, header) == 0x02014b50L) { "no central header at $header" }
        at += 46 + u16(bytes, header + 28) + u16(bytes, header + 30) + u16(bytes, header + 32)
        header
    }
}

/** The offset of the local header of the entry whose central header is at [central]. */
fun localHeaderOf(bytes: ByteArray, central: Int): Int = u32(bytes, central + 42).toInt()

/** Where the data of entry [index] starts: after its local header, its name and its extra field. */
fun ByteArray.dataOf(index: Int): Int {
    val local = localHeaderOf(this, centralHeaders(this)[index])
    return local + 30 + u16(this, local + 26) + u16(this, local + 28)
}

/** The general purpose flags of entry [index], from its central header. */
fun ByteArray.flags(index: Int): Int = u16(this, centralHeaders(this)[index] + 8)

/** Sets [bits] of the general purpose flags of entry [index], in its local and its central header. */
fun ByteArray.setFlags(index: Int, bits: Int) = changeFlags(index) { it or bits }

/** Clears [bits] of the general purpose flags of entry [index], in its local and its central header. */
fun ByteArray.clearFlags(index: Int, bits: Int) = changeFlags(index) { it and bits.inv() }

private fun ByteArray.changeFlags(index: Int, change: (Int) -> Int) {
    val central = centralHeaders(this)[index]
    val local = localHeaderOf(this, central)
    put16(local + 6, change(u16(this, local + 6)))
    put16(central + 8, change(u16(this, central + 8)))
}

/** Gives entry [index] [name], which must be as long in bytes as the one it has, in both its headers. */
fun ByteArray.rename(index: Int, name: String) {
    val central = centralHeaders(this)[index]
    val local = localHeaderOf(this, central)
    val bytes = name.toByteArray()
    check(bytes.size == u16(this, central + 28)) { "$name is not as long as the name it replaces" }
    bytes.copyInto(this, local + 30)
    bytes.copyInto(this, central + 46)
}

/** Writes [value] as the little-endian 16-bit number at [at]. */
fun ByteArray.put16(at: Int, value: Int) {
    this[at] = value.toByte()
    this[at + 1] = (value shr 8).toByte()
}

/** Writes [value] as the little-endian 32-bit number at [at]. */
fun ByteArray.put32(at: Int, value: Long) {
    for (i in 0 until 4) this[at + i] = (value shr (8 * i)).toByte()
}

private fun u16(bytes: ByteArray, at: Int): Int =
    (bytes[at].toInt() and 0xFF) or ((bytes[at + 1].toInt() and 0xFF) shl 8)

private fun u32(bytes: ByteArray, at: Int): Long =
    (u16(bytes, at).toLong()) or (u16(bytes, at + 2).toLong() shl 16)
