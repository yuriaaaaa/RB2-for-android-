package com.deivid22srk.restuff.mods

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
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
