package com.deivid22srk.restuff.mods

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Reads and patches the game's AFS archives (data/X360/st_pack_*_pt_x360.afs).
 *
 * Layout: "AFS\0", entry count, then (offset, size) per entry, then the
 * (offset, size) of the name table. The name table has one 48-byte record per
 * entry: a 32-byte name, six 16-bit date fields and the entry size. Some
 * archives keep the name-table pointer in the 8 bytes just before the first
 * entry instead of right after the TOC, so both places are checked.
 *
 * Replacing an entry never moves another one: new data that fits the entry's
 * slot is written in place, anything bigger is appended at the end of the file,
 * and the TOC (plus the name record's size) is updated to point at it.
 */
class AfsArchive private constructor(
    private val file: File,
    val order: ByteOrder,
    val entries: List<Entry>,
    val nameTableOffset: Long,
    val nameTableSize: Long,
    val alignment: Int,
) {

    data class Entry(val index: Int, val name: String, val offset: Long, val size: Long)

    class FormatException(message: String) : IOException(message)

    /** Entry names are matched without case, as mods made on Windows don't keep it. */
    fun find(name: String): Entry? = entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
        ?: entries.firstOrNull { baseName(it.name).equals(baseName(name), ignoreCase = true) }

    /** Raw bytes of the name table's first [records] records, for the pack report. */
    fun nameTableHex(records: Int = 3): String = runCatching {
        if (nameTableOffset <= 0) return@runCatching "none"
        RandomAccessFile(file, "r").use { raf ->
            val b = ByteArray(minOf(records * NAME_RECORD.toLong(), raf.length() - nameTableOffset).toInt())
            raf.seek(nameTableOffset)
            raf.readFully(b)
            b.joinToString(" ") { "%02x".format(it) }
        }
    }.getOrDefault("unreadable")

    /** Writes [data] as entry [index]; returns where it ended up. */
    fun replace(index: Int, data: File): Entry {
        val entry = entries[index]
        val length = data.length()
        RandomAccessFile(file, "rw").use { raf ->
            val slotEnd = slotEnd(entry, raf.length())
            // An empty entry (offset 0) has no slot; writing there would hit the header.
            val offset = if (entry.offset > 0 && entry.offset + length <= slotEnd) {
                entry.offset
            } else {
                alignUp(raf.length(), alignment.toLong())
            }
            raf.seek(offset)
            data.inputStream().use { input ->
                val buffer = ByteArray(1 shl 16)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    raf.write(buffer, 0, n)
                }
            }
            if (offset != entry.offset) {
                // Keep the archive's alignment so the next append starts on a boundary.
                val end = offset + length
                val padded = alignUp(end, alignment.toLong())
                if (padded > raf.length()) raf.setLength(padded)
            }
            writeU32(raf, 8L + index * 8L, offset)
            writeU32(raf, 8L + index * 8L + 4, length)
            if (nameTableOffset > 0 && nameTableSize >= (index + 1) * NAME_RECORD.toLong()) {
                writeU32(raf, nameTableOffset + index * NAME_RECORD + 44, length)
            }
            return entry.copy(offset = offset, size = length)
        }
    }

    /** End of the space [entry] may use: the next thing in the file after it. */
    private fun slotEnd(entry: Entry, fileLength: Long): Long {
        var end = fileLength
        for (other in entries) {
            if (other.offset > entry.offset && other.offset < end) end = other.offset
        }
        if (nameTableOffset > entry.offset && nameTableOffset < end) end = nameTableOffset
        // The last entry before the end of the file may only grow by appending.
        return if (end == fileLength) alignUp(entry.offset + entry.size, alignment.toLong()).coerceAtMost(fileLength) else end
    }

    private fun writeU32(raf: RandomAccessFile, at: Long, value: Long) {
        val b = ByteBuffer.allocate(4).order(order).putInt(value.toInt()).array()
        raf.seek(at)
        raf.write(b)
    }

    companion object {
        private const val NAME_RECORD = 48
        private const val MAX_ENTRIES = 1_000_000

        /** Name without any folder part, as packs may store paths. */
        fun baseName(name: String): String = name.substringAfterLast('/').substringAfterLast('\\')

        fun isAfs(file: File): Boolean = runCatching {
            RandomAccessFile(file, "r").use { raf ->
                val magic = ByteArray(4)
                raf.readFully(magic)
                magic[0] == 'A'.code.toByte() && magic[1] == 'F'.code.toByte() &&
                    magic[2] == 'S'.code.toByte() && magic[3] == 0.toByte()
            }
        }.getOrDefault(false)

        /**
         * Parses [file]. The Xbox 360 packs may be big-endian, and a big-endian
         * count read as little-endian can still look plausible, so each byte
         * order gets a full parse and the first that holds together wins
         * (preferring one whose entries have names).
         */
        fun open(file: File): AfsArchive {
            RandomAccessFile(file, "r").use { raf ->
                val length = raf.length()
                if (length < 16) throw FormatException("${file.name} is too small to be an AFS archive")
                val head = ByteArray(4)
                raf.readFully(head)
                if (head[0] != 'A'.code.toByte() || head[1] != 'F'.code.toByte() ||
                    head[2] != 'S'.code.toByte() || head[3] != 0.toByte()
                ) {
                    throw FormatException("${file.name} is not an AFS archive")
                }
                val parsed = listOf(ByteOrder.BIG_ENDIAN, ByteOrder.LITTLE_ENDIAN)
                    .mapNotNull { runCatching { parse(file, raf, it) }.getOrNull() }
                return parsed.firstOrNull { a -> a.entries.any { it.name.isNotEmpty() } }
                    ?: parsed.firstOrNull()
                    ?: throw FormatException("${file.name}: unreadable AFS table of contents")
            }
        }

        private fun parse(file: File, raf: RandomAccessFile, order: ByteOrder): AfsArchive {
            val length = raf.length()
            val countBytes = ByteArray(4)
            raf.seek(4)
            raf.readFully(countBytes)
            val count = ByteBuffer.wrap(countBytes).order(order).int.toLong() and 0xFFFFFFFFL
            if (count !in 1..MAX_ENTRIES || 8 + count * 8 + 8 > length) {
                throw FormatException("${file.name}: implausible entry count $count")
            }
            val n = count.toInt()
            val toc = ByteArray(n * 8 + 8)
            raf.seek(8)
            raf.readFully(toc)
            val tb = ByteBuffer.wrap(toc).order(order)
            val offsets = LongArray(n)
            val sizes = LongArray(n)
            val tocEnd = 8L + n * 8L
            for (i in 0 until n) {
                offsets[i] = tb.int.toLong() and 0xFFFFFFFFL
                sizes[i] = tb.int.toLong() and 0xFFFFFFFFL
                val empty = offsets[i] == 0L && sizes[i] == 0L
                if (!empty && (offsets[i] < tocEnd || offsets[i] + sizes[i] > length)) {
                    throw FormatException("${file.name}: entry $i is outside the file")
                }
            }

            var ntOffset = tb.int.toLong() and 0xFFFFFFFFL
            var ntSize = tb.int.toLong() and 0xFFFFFFFFL
            if (!plausibleNameTable(ntOffset, ntSize, n, length)) {
                val firstData = offsets.filter { it > 0 }.minOrNull() ?: 0L
                if (firstData >= tocEnd + 8) {
                    val alt = ByteArray(8)
                    raf.seek(firstData - 8)
                    raf.readFully(alt)
                    val ab = ByteBuffer.wrap(alt).order(order)
                    ntOffset = ab.int.toLong() and 0xFFFFFFFFL
                    ntSize = ab.int.toLong() and 0xFFFFFFFFL
                }
            }
            val names = Array(n) { "" }
            if (plausibleNameTable(ntOffset, ntSize, n, length)) {
                val table = ByteArray(n * NAME_RECORD)
                raf.seek(ntOffset)
                raf.readFully(table)
                for (i in 0 until n) {
                    var end = i * NAME_RECORD
                    val limit = end + 32
                    while (end < limit && table[end] != 0.toByte()) end++
                    names[i] = String(table, i * NAME_RECORD, end - i * NAME_RECORD, Charsets.ISO_8859_1)
                }
                // A table of control characters is not a name table.
                if (names.any { name -> name.any { it.code < 0x20 } }) names.fill("")
            } else {
                ntOffset = 0
                ntSize = 0
            }
            if (names.all { it.isEmpty() }) {
                ntOffset = 0
                ntSize = 0
            }

            val nonEmpty = offsets.filter { it > 0 }
            val alignment = when {
                nonEmpty.isEmpty() -> 2048
                nonEmpty.all { it % 2048 == 0L } -> 2048
                nonEmpty.all { it % 32 == 0L } -> 32
                else -> 4
            }
            val entries = (0 until n).map { Entry(it, names[it], offsets[it], sizes[it]) }
            return AfsArchive(file, order, entries, ntOffset, ntSize, alignment)
        }

        /** First bytes of [file] in hex, for a report when a pack can't be read. */
        fun headerHex(file: File, bytes: Int = 64): String = runCatching {
            RandomAccessFile(file, "r").use { raf ->
                val b = ByteArray(minOf(bytes.toLong(), raf.length()).toInt())
                raf.readFully(b)
                b.joinToString(" ") { "%02x".format(it) }
            }
        }.getOrDefault("unreadable")

        private fun plausibleNameTable(offset: Long, size: Long, count: Int, length: Long): Boolean =
            offset > 0 && size >= count.toLong() * NAME_RECORD && offset + count.toLong() * NAME_RECORD <= length

        private fun alignUp(value: Long, alignment: Long): Long =
            (value + alignment - 1) / alignment * alignment
    }
}
