package com.deivid22srk.restuff.mods

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class Ps3ConverterTest {

    @Test
    fun stpzRoundTrips() {
        val stpk = ByteArray(40_000) { i -> if (i < 16) "STPK\u0000\u0000\u0000\u0001\u0000\u0000\u0000\u0001\u0000\u0000\u0000\u0010"[i].code.toByte() else ((i / 7) % 13 + (i % 300) / 100).toByte() }
        val packed = Stpz.compress(stpk)
        assertEquals("STPZ", String(packed, 0, 4, Charsets.ISO_8859_1))
        assertArrayEquals(stpk.copyOfRange(4, 16), packed.copyOfRange(4, 16))
        assertArrayEquals(stpk, Stpz.decompress(packed))
    }

    @Test
    fun tilesLikeTheXenosLayout() {
        // One DXT1 block row of 4 blocks: tiled into a 32x32-block surface.
        val linear = ByteArray(4 * 8) { it.toByte() }
        val tiled = XenosTiling.tile(linear, 4, 1, 8)
        assertEquals(32 * 32 * 8, tiled.size)
        assertArrayEquals(linear.copyOfRange(0, 8), tiled.copyOfRange(0, 8))
    }

    /**
     * Matches the prototype converter's output for real character files. Needs game-derived
     * files, so it only runs when RB2_CONV_DIR points at a folder holding `in/` (PS3 i/s/v
     * .zpak) and `out/` (the prototype's results with the same names).
     */
    @Test
    fun matchesThePrototype() {
        val dir = System.getenv("RB2_CONV_DIR")?.let(::File)
        assumeTrue(dir != null && dir.isDirectory)
        val inputs = File(dir, "in").listFiles()!!.filter { it.name.endsWith("_i.zpak") }
        assumeTrue(inputs.isNotEmpty())
        for (i in inputs) {
            val stem = i.name.removeSuffix("_i.zpak")
            val c = Ps3Converter.convert(i.readBytes(), File(i.parent, "${stem}_s.zpak").readBytes(),
                File(i.parent, "${stem}_v.zpak").readBytes())
            val out = File(dir, "out")
            assertArrayEquals("$stem i", File(out, "${stem}_i.zpak").readBytes(), c.ioram)
            assertArrayEquals("$stem s", File(out, "${stem}_s.zpak").readBytes(), c.model)
            assertArrayEquals("$stem v", File(out, "${stem}_v.zpak").readBytes(), c.vram)
        }
    }
}

/**
 * Converts every character in a folder of unpacked mods (RB2_MODS_DIR) and checks the results
 * hold together; RB2_MODEL_ORIGINAL is an X360 model pack to borrow face files from.
 */
class Ps3ConverterSweepTest {
    @Test
    fun convertsEveryCharacter() {
        val dir = System.getenv("RB2_MODS_DIR")?.let(::File)
        assumeTrue(dir != null && dir.isDirectory)
        val original = System.getenv("RB2_MODEL_ORIGINAL")?.let { File(it).readBytes() }
        val groups = dir!!.walkTopDown().filter { it.isFile }
            .mapNotNull { f -> PackIndex.characterFile(f.name)?.let { f to it } }
            .groupBy { (f, c) -> f.parent + "/" + c.stem }
        val failures = mutableListOf<String>()
        for ((stem, files) in groups) {
            val byPart = files.associate { (f, c) -> c.part to f }
            val i = byPart['i']; val m = byPart['m']; val v = byPart['v']
            if (i == null || m == null || v == null) { failures += "$stem: incomplete ${byPart.keys}"; continue }
            val r = runCatching {
                val c = Ps3Converter.convert(i.readBytes(), m.readBytes(), v.readBytes(), original,
                    Triple(i.name, m.name, v.name))
                val model = Stpz.decompress(c.model)
                val sprAt = ((model[16].toInt() and 0xFF) shl 24) or ((model[17].toInt() and 0xFF) shl 16) or
                    ((model[18].toInt() and 0xFF) shl 8) or (model[19].toInt() and 0xFF)
                assertEquals("SPR3", String(model, sprAt, 4, Charsets.ISO_8859_1))
                Stpz.decompress(c.ioram); Stpz.decompress(c.vram)
                "${model[11]} files in the model pack"
            }
            println("$stem: ${r.getOrElse { "FAILED ${it.message}" }}")
            r.exceptionOrNull()?.let { failures += "$stem: ${it.message}" }
        }
        println("${groups.size} characters, ${failures.size} failures")
        failures.forEach(::println)
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }
}
