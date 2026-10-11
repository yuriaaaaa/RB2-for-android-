package com.deivid22srk.restuff.mods

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Where each game file sits inside the .afs packs.
 *
 * The packs carry no file names. The game finds an entry by hashing its
 * logical path (`battle/character/model/chara_000_2p_model_i.pak`) and looking
 * the hash up in a per-pack table compiled into default.xex; the position in
 * that table is the entry's index in the .afs. The game writes those tables to
 * `pack-hashes.bin` when it starts (see rb2_app.h), so the game has to have
 * been started once before character mods can be installed.
 */
class PackIndex private constructor(private val packs: Map<String, IntArray>) {

    data class Slot(val pack: String, val index: Int)

    /** Pack file name (lower case) → number of entries the game expects. */
    fun entryCount(pack: String): Int? = packs[pack.lowercase()]?.size

    /** The slot the game reads [logicalPath] from, or null if no pack has it. */
    fun locate(logicalPath: String): Slot? {
        val h = hash(logicalPath)
        for ((pack, hashes) in packs) {
            val i = hashes.indexOf(h)
            if (i >= 0) return Slot(pack, i)
        }
        return null
    }

    companion object {
        const val FILE_NAME = "pack-hashes.bin"

        /** The game's path hash: h = h * 137 + byte, with 0 turned into 1. */
        fun hash(path: String): Int {
            var h = 0
            for (b in path.toByteArray(Charsets.ISO_8859_1)) h = h * 137 + (b.toInt() and 0xFF)
            return if (h == 0) 1 else h
        }

        fun read(file: File): PackIndex? = runCatching {
            val buf = ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
            val magic = ByteArray(4).also { buf.get(it) }
            if (String(magic, Charsets.ISO_8859_1) != "RB2H") return null
            val packCount = buf.int
            val packs = LinkedHashMap<String, IntArray>()
            repeat(packCount) {
                val name = ByteArray(buf.get().toInt() and 0xFF).also { buf.get(it) }
                val count = buf.int
                packs[String(name, Charsets.ISO_8859_1).lowercase()] = IntArray(count) { buf.int }
            }
            PackIndex(packs)
        }.getOrNull()

        /** For tests: [packs] maps a pack file name to the logical paths of its entries, in order. */
        fun of(packs: Map<String, List<String>>): PackIndex =
            PackIndex(packs.entries.associate { (k, v) -> k.lowercase() to v.map(::hash).toIntArray() })

        /** Writes [packs] the way the game does, for tests. */
        fun write(file: File, packs: Map<String, List<String>>) {
            val size = 8 + packs.entries.sumOf { (k, v) -> 1 + k.length + 4 + 4 * v.size }
            val buf = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
            buf.put("RB2H".toByteArray()).putInt(packs.size)
            for ((name, paths) in packs) {
                buf.put(name.length.toByte()).put(name.toByteArray()).putInt(paths.size)
                paths.forEach { buf.putInt(hash(it)) }
            }
            file.writeBytes(buf.array())
        }

        private val CHARACTER_MODEL = Regex("""^(\d{3})_.*?_(D_)?(\d+)p_([isvm])\.zpak$""", RegexOption.IGNORE_CASE)

        /**
         * The logical paths a mod file may stand for, best guess first.
         *
         * Character mods use the PS3/PC pack names (`067_brolly_0_2p_i.zpak`,
         * with i = ioram, s = spr, v = vram); the game asks for
         * `battle/character/model/chara_067_2p_model_{i,m,v}.pak`. A mod that
         * already uses logical paths (`.../battle/character/...pak`) is taken
         * at its word.
         */
        fun candidates(zipPath: String): List<String> {
            val out = mutableListOf<String>()
            val parts = zipPath.split('/')
            // Only .pak names are logical paths; anything else would just be a long shot.
            if (parts.last().endsWith(".pak", ignoreCase = true)) for (start in parts.indices) {
                val tail = parts.subList(start, parts.size).joinToString("/")
                out += tail
                if (tail != tail.lowercase()) out += tail.lowercase()
            }
            val name = parts.last()
            CHARACTER_MODEL.matchEntire(name)?.let { m ->
                val (id, damaged, players, part) = m.destructured
                val kind = when (part.lowercase()) { "s" -> "m"; else -> part.lowercase() }
                val d = if (damaged.isNotEmpty()) "D_" else ""
                out.add(0, "battle/character/model/chara_${id}_$d${players}p_model_$kind.pak")
            }
            return out
        }
    }
}
