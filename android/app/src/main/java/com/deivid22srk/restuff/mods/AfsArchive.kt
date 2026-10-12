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
 * The game finds an entry by adding up the sizes before it, so entries are
 * never appended: see [replaceAll].
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

    /** Writes [data] as entry [index]. */
    fun replace(index: Int, data: File) = replaceAll(mapOf(index to data))

    /**
     * Writes each file in [changes] (entry index → new data) into the archive.
     *
     * The game doesn't read an entry's offset from the table of contents: it
     * adds up the earlier entries' sizes, each rounded up to [alignment]. So
     * every entry has to stay exactly where that sum puts it. Data that is
     * smaller than the original is zero-padded to the original size (the game
     * reads the STPZ/STPK headers, not the padding); data that needs more room
     * moves everything after it further down the file, in one pass from the
     * end, and the offsets in the table follow. Nothing is ever appended.
     */
    fun replaceAll(changes: Map<Int, File>) {
        if (changes.isEmpty()) return
        val align = alignment.toLong()
        val n = entries.size
        val newSize = LongArray(n) { entries[it].size }
        for ((index, data) in changes) {
            require(index in 0 until n) { "entry $index is not in ${file.name}" }
            val e = entries[index]
            if (e.offset == 0L && e.size == 0L) throw IOException("${file.name}: entry $index is empty and has no place")
            newSize[index] = maxOf(data.length(), e.size)
        }
        // Physical order; empty entries take no room.
        val order = entries.filter { it.offset > 0 }.sortedBy { it.offset }
        val newOffset = LongArray(n) { entries[it].offset }
        val shiftAt = LongArray(order.size)
        var delta = 0L
        for ((k, e) in order.withIndex()) {
            newOffset[e.index] = e.offset + delta
            shiftAt[k] = delta
            delta += alignUp(newSize[e.index], align) - alignUp(e.size, align)
        }
        val lastEnd = order.lastOrNull()?.let { it.offset + alignUp(it.size, align) } ?: 0L

        RandomAccessFile(file, "rw").use { raf ->
            val oldLength = raf.length()
            if (delta > 0) {
                raf.setLength(oldLength + delta)
                // Whatever follows the last entry (a name table) moves by the full amount.
                if (oldLength > lastEnd) move(raf, lastEnd, lastEnd + delta, oldLength - lastEnd)
                for (k in order.indices.reversed()) {
                    val e = order[k]
                    if (shiftAt[k] == 0L) break
                    if (e.index !in changes) move(raf, e.offset, e.offset + shiftAt[k], alignUp(e.size, align))
                }
            }
            val buffer = ByteArray(1 shl 16)
            for ((index, data) in changes) {
                val at = newOffset[index]
                raf.seek(at)
                var written = 0L
                data.inputStream().use { input ->
                    while (true) {
                        val r = input.read(buffer)
                        if (r < 0) break
                        raf.write(buffer, 0, r)
                        written += r
                    }
                }
                // Zero the rest of the slot: padding up to the size the table gives, then alignment.
                java.util.Arrays.fill(buffer, 0)
                var left = alignUp(newSize[index], align) - written
                while (left > 0) {
                    val w = minOf(left, buffer.size.toLong()).toInt()
                    raf.write(buffer, 0, w)
                    left -= w
                }
            }
            for (i in 0 until n) {
                if (newOffset[i] != entries[i].offset) writeU32(raf, 8L + i * 8L, newOffset[i])
                if (newSize[i] != entries[i].size) writeU32(raf, 8L + i * 8L + 4, newSize[i])
            }
            if (delta > 0) {
                movePointer(raf, 8L + n * 8L, lastEnd, oldLength, delta)
                val firstData = order.firstOrNull()?.offset ?: 0L
                if (firstData - 8 >= 8L + n * 8L + 8) movePointer(raf, firstData - 8, lastEnd, oldLength, delta)
            }
            val newNameTable = if (nameTableOffset >= lastEnd) nameTableOffset + delta else nameTableOffset
            if (nameTableOffset > 0) {
                for (i in changes.keys) {
                    if (nameTableSize >= (i + 1) * NAME_RECORD.toLong()) {
                        writeU32(raf, newNameTable + i * NAME_RECORD + 44, newSize[i])
                    }
                }
            }
        }
    }

    /** Copies [length] bytes from [from] to [to] (to > from), back to front so the ranges may overlap. */
    private fun move(raf: RandomAccessFile, from: Long, to: Long, length: Long) {
        val buffer = ByteArray(1 shl 20)
        var left = length
        while (left > 0) {
            val n = minOf(left, buffer.size.toLong()).toInt()
            left -= n
            raf.seek(from + left)
            raf.readFully(buffer, 0, n)
            raf.seek(to + left)
            raf.write(buffer, 0, n)
        }
    }

    /** The name-table pointer sits after the table of contents; move it with the table. */
    private fun movePointer(raf: RandomAccessFile, at: Long, lastEnd: Long, oldLength: Long, delta: Long) {
        val b = ByteArray(4)
        raf.seek(at)
        raf.readFully(b)
        val value = ByteBuffer.wrap(b).order(order).int.toLong() and 0xFFFFFFFFL
        if (value in lastEnd until oldLength) writeU32(raf, at, value + delta)
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
