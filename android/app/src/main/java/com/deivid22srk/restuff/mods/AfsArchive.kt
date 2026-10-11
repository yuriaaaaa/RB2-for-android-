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
    private val nameTableOffset: Long,
    private val nameTableSize: Long,
    val alignment: Int,
) {

    data class Entry(val index: Int, val name: String, val offset: Long, val size: Long)

    class FormatException(message: String) : IOException(message)

    /** Entry names are matched without case, as mods made on Windows don't keep it. */
    fun find(name: String): Entry? = entries.firstOrNull { it.name.equals(name, ignoreCase = true) }

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

        fun isAfs(file: File): Boolean = runCatching {
            RandomAccessFile(file, "r").use { raf ->
                val magic = ByteArray(4)
                raf.readFully(magic)
                magic[0] == 'A'.code.toByte() && magic[1] == 'F'.code.toByte() &&
                    magic[2] == 'S'.code.toByte() && magic[3] == 0.toByte()
            }
        }.getOrDefault(false)

        fun open(file: File): AfsArchive {
            RandomAccessFile(file, "r").use { raf ->
                val length = raf.length()
                if (length < 16) throw FormatException("${file.name} is too small to be an AFS archive")
                val head = ByteArray(8)
                raf.readFully(head)
                if (head[0] != 'A'.code.toByte() || head[1] != 'F'.code.toByte() ||
                    head[2] != 'S'.code.toByte() || head[3] != 0.toByte()
                ) {
                    throw FormatException("${file.name} is not an AFS archive")
                }
                val order = listOf(ByteOrder.LITTLE_ENDIAN, ByteOrder.BIG_ENDIAN).firstOrNull { o ->
                    val count = ByteBuffer.wrap(head, 4, 4).order(o).int.toLong() and 0xFFFFFFFFL
                    count in 1..MAX_ENTRIES && 8 + count * 8 + 8 <= length
                } ?: throw FormatException("${file.name}: unreadable entry count")
                val count = (ByteBuffer.wrap(head, 4, 4).order(order).int.toLong() and 0xFFFFFFFFL).toInt()

                val toc = ByteArray(count * 8 + 8)
                raf.seek(8)
                raf.readFully(toc)
                val tb = ByteBuffer.wrap(toc).order(order)
                val offsets = LongArray(count)
                val sizes = LongArray(count)
                for (i in 0 until count) {
                    offsets[i] = tb.int.toLong() and 0xFFFFFFFFL
                    sizes[i] = tb.int.toLong() and 0xFFFFFFFFL
                    if (offsets[i] + sizes[i] > length) {
                        throw FormatException("${file.name}: entry $i runs past the end of the file")
                    }
                }

                var ntOffset = tb.int.toLong() and 0xFFFFFFFFL
                var ntSize = tb.int.toLong() and 0xFFFFFFFFL
                if (!plausibleNameTable(ntOffset, ntSize, count, length)) {
                    val firstData = offsets.filter { it > 0 }.minOrNull() ?: 0L
                    if (firstData >= 8) {
                        val alt = ByteArray(8)
                        raf.seek(firstData - 8)
                        raf.readFully(alt)
                        val ab = ByteBuffer.wrap(alt).order(order)
                        ntOffset = ab.int.toLong() and 0xFFFFFFFFL
                        ntSize = ab.int.toLong() and 0xFFFFFFFFL
                    }
                }
                val names = Array(count) { "" }
                if (plausibleNameTable(ntOffset, ntSize, count, length)) {
                    val table = ByteArray(count * NAME_RECORD)
                    raf.seek(ntOffset)
                    raf.readFully(table)
                    for (i in 0 until count) {
                        var end = i * NAME_RECORD
                        val limit = end + 32
                        while (end < limit && table[end] != 0.toByte()) end++
                        names[i] = String(table, i * NAME_RECORD, end - i * NAME_RECORD, Charsets.ISO_8859_1)
                    }
                } else {
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
                val entries = (0 until count).map { Entry(it, names[it], offsets[it], sizes[it]) }
                return AfsArchive(file, order, entries, ntOffset, ntSize, alignment)
            }
        }

        private fun plausibleNameTable(offset: Long, size: Long, count: Int, length: Long): Boolean =
            offset > 0 && size >= count.toLong() * NAME_RECORD && offset + count.toLong() * NAME_RECORD <= length

        private fun alignUp(value: Long, alignment: Long): Long =
            (value + alignment - 1) / alignment * alignment
    }
}
