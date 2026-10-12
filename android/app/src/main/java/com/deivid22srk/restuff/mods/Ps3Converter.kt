package com.deivid22srk.restuff.mods

import java.io.ByteArrayOutputStream
import java.io.IOException

/**
 * Turns a PS3/PC character (its `_i`, `_s` and `_v` .zpak files) into the
 * Xbox 360 layout the game reads.
 *
 * The model data (materials, shapes, vertex buffers, bones) is the same on
 * both; what differs is the texture memory and a few header values:
 * - DXT textures are stored with 16-bit words swapped and in the Xenos tiled
 *   order, one mip level each.
 * - The 1-pixel-high shading ramps become 32 KB blobs holding the ramp twice.
 * - The .spr is tagged SPR3 and carries the 360 version of each section type.
 * - The ioram/vram packs hold one blob at 0x1000, padded to 4 KB.
 * - Every name's `_PS3.` becomes `X360.` (same length, so the string table
 *   keeps its layout).
 */
object Ps3Converter {

    class ConvertException(message: String) : IOException(message)

    data class Character(val ioram: ByteArray, val model: ByteArray, val vram: ByteArray)

    /** Converts one character. Inputs and outputs are STPZ-compressed .zpak files. */
    fun convert(i: ByteArray, s: ByteArray, v: ByteArray): Character {
        val di = Stpz.decompress(i)
        val ds = Stpz.decompress(s)
        val dv = Stpz.decompress(v)
        val (iname, ioff, isz) = entries(di).singleOrNull() ?: throw ConvertException("the _i pack should hold one file")
        val (vname, voff, vsz) = entries(dv).singleOrNull() ?: throw ConvertException("the _v pack should hold one file")
        val ioram = di.copyOfRange(ioff, ioff + isz)
        val vram = dv.copyOfRange(voff, voff + vsz)

        val sent = entries(ds)
        val (_, soff, _) = sent.firstOrNull { it.first.endsWith(".spr") }
            ?: throw ConvertException("the _s pack has no .spr")
        if (String(ds, soff, 4, Charsets.ISO_8859_1) != "SPRP") throw ConvertException("the .spr isn't a PS3 one")
        for ((k, e) in sent.withIndex()) putName(ds, 16 + k * 48 + 16, x360Name(e.first))

        fun be(o: Int) = be32(ds, soff + o)
        val sb = soff + 64 + be(20)
        val dib = sb + be(24)
        val dbb = dib + be(28)
        val count = be(8)
        val newVram = ByteArrayOutputStream()
        var off = 0
        for (t in 0 until count) {
            val typeAt = soff + 64 + t * 12
            val type = String(ds, typeAt, 4, Charsets.ISO_8859_1)
            val c = be32(ds, typeAt + 8)
            if (type == "TX2D") {
                for (j in 0 until c) {
                    val e = dib + off + j * 32
                    val tx = dbb + be32(ds, e + 12)
                    var rel = be32(ds, tx)
                    val toff = be32(ds, tx + 4)
                    val w = be16(ds, tx + 16)
                    val h = be16(ds, tx + 18)
                    val enc = ds[tx + 0x20].toInt() and 0xFF
                    val blob: ByteArray
                    if (enc == 0 && h == 1) {
                        blob = ByteArray(32768)
                        System.arraycopy(vram, toff, blob, 0, w * 4)
                        System.arraycopy(vram, toff, blob, 16384, w * 4)
                        rel = when (rel) { 2 -> 0x82; 0x22 -> 0xa2; else -> rel or 0x80 }
                    } else if (enc == 8 || enc == 24 || enc == 32) {
                        val bpp = if (enc == 8) 8 else 16
                        val bw = maxOf(1, w / 4)
                        val bh = maxOf(1, h / 4)
                        val top = vram.copyOfRange(toff, toff + bw * bh * bpp)
                        for (q in 0 until top.size - 1 step 2) {
                            val x = top[q]; top[q] = top[q + 1]; top[q + 1] = x
                        }
                        blob = XenosTiling.tile(top, bw, bh, bpp)
                    } else {
                        throw ConvertException("unsupported texture format $enc (${w}x$h)")
                    }
                    val start = alignUp(newVram.size(), 4096)
                    newVram.write(ByteArray(start - newVram.size()))
                    newVram.write(blob)
                    putBe32(ds, tx, rel)
                    putBe32(ds, tx + 4, start)
                    putBe32(ds, tx + 8, 0)
                    putBe32(ds, tx + 12, blob.size)
                    putBe16(ds, tx + 22, 1)
                }
            }
            off += c * 32
        }
        newVram.write(ByteArray(alignUp(newVram.size(), 4096) - newVram.size()))
        replaceAll(ds, sb, dib, "_PS3.", "X360.")
        "SPR3".toByteArray(Charsets.ISO_8859_1).copyInto(ds, soff)
        for (t in 0 until count) {
            val typeAt = soff + 64 + t * 12
            X360_TYPE_VERSION[String(ds, typeAt, 4, Charsets.ISO_8859_1)]?.let { putBe32(ds, typeAt + 4, it) }
        }
        putBe32(ds, soff + 0x28, ioram.size)
        putBe32(ds, soff + 0x30, newVram.size())
        return Character(
            ioram = Stpz.compress(singlePak(iname, ioram)),
            model = Stpz.compress(ds),
            vram = Stpz.compress(singlePak(vname, newVram.toByteArray())),
        )
    }

    /** Every 360 .spr carries these per-type values where the PS3 ones have 0. */
    private val X360_TYPE_VERSION = mapOf(
        "TX2D" to 0x10000, "MTRL" to 8, "SHAP" to 5, "VBUF" to 0xa,
        "SCNE" to 7, "BONE" to 3, "DRVN" to 1, "TXAN" to 1,
    )

    private fun x360Name(name: String) = name.replace("_PS3.", "X360.")

    /** STPK with one GPU blob at 0x1000, the way the 360 packs ioram/vram. */
    private fun singlePak(name: String, data: ByteArray): ByteArray {
        val out = ByteArray(alignUp(0x1000 + data.size, 0x1000))
        "STPK".toByteArray(Charsets.ISO_8859_1).copyInto(out, 0)
        putBe32(out, 4, 1)
        putBe32(out, 8, 1)
        putBe32(out, 12, 0x1000)
        putBe32(out, 16, 0x1000)
        putBe32(out, 20, data.size)
        putName(out, 32, x360Name(name))
        data.copyInto(out, 0x1000)
        return out
    }

    /** (name, offset, size) of each file in an STPK. */
    private fun entries(d: ByteArray): List<Triple<String, Int, Int>> {
        if (d.size < 16 || String(d, 0, 4, Charsets.ISO_8859_1) != "STPK") throw ConvertException("not an STPK pack")
        return (0 until be32(d, 8)).map { i ->
            val at = 16 + i * 48
            var end = at + 16
            while (end < at + 48 && d[end] != 0.toByte()) end++
            Triple(String(d, at + 16, end - at - 16, Charsets.ISO_8859_1), be32(d, at), be32(d, at + 4))
        }
    }

    private fun putName(d: ByteArray, at: Int, name: String) {
        d.fill(0, at, at + 32)
        name.toByteArray(Charsets.ISO_8859_1).let { it.copyInto(d, at, 0, minOf(it.size, 32)) }
    }

    private fun replaceAll(d: ByteArray, from: Int, to: Int, old: String, new: String) {
        val o = old.toByteArray(Charsets.ISO_8859_1)
        val n = new.toByteArray(Charsets.ISO_8859_1)
        var i = from
        while (i <= to - o.size) {
            var hit = true
            for (k in o.indices) if (d[i + k] != o[k]) { hit = false; break }
            if (hit) { n.copyInto(d, i); i += o.size } else i++
        }
    }

    private fun alignUp(v: Int, a: Int) = (v + a - 1) / a * a
    private fun be16(d: ByteArray, o: Int) = ((d[o].toInt() and 0xFF) shl 8) or (d[o + 1].toInt() and 0xFF)
    private fun be32(d: ByteArray, o: Int) = ((d[o].toInt() and 0xFF) shl 24) or ((d[o + 1].toInt() and 0xFF) shl 16) or
        ((d[o + 2].toInt() and 0xFF) shl 8) or (d[o + 3].toInt() and 0xFF)
    private fun putBe16(d: ByteArray, o: Int, v: Int) { d[o] = (v shr 8).toByte(); d[o + 1] = v.toByte() }
    private fun putBe32(d: ByteArray, o: Int, v: Int) {
        d[o] = (v shr 24).toByte(); d[o + 1] = (v shr 16).toByte(); d[o + 2] = (v shr 8).toByte(); d[o + 3] = v.toByte()
    }
}

/** Xbox 360 (Xenos) tiled texture layout, for block-compressed textures. */
object XenosTiling {
    private fun log2(bpp: Int) = when (bpp) { 1 -> 0; 2 -> 1; 4 -> 2; 8 -> 3; 16 -> 4; else -> error("bpp $bpp") }

    private fun outer(y: Int, width: Int, lb: Int): Int {
        val macro = ((y shr 5) * (width shr 5)) shl (lb + 7)
        val micro = ((y and 6) shl 2) shl lb
        return macro + ((micro and 15.inv()) shl 1) + (micro and 15) + ((y and 8) shl (3 + lb)) + ((y and 1) shl 4)
    }

    private fun inner(x: Int, y: Int, lb: Int, base: Int): Int {
        val macro = (x shr 5) shl (lb + 7)
        val micro = (x and 7) shl lb
        val off = base + (macro + ((micro and 15.inv()) shl 1) + (micro and 15))
        return ((off and 511.inv()) shl 3) + ((off and 448) shl 2) + (off and 63) + ((y and 16) shl 7) +
            (((((y and 8) shr 2) + (x shr 3)) and 3) shl 6)
    }

    /** Lays out [linear] ([bw]×[bh] blocks of [bpp] bytes) in tiled order, padded to 32×32 blocks. */
    fun tile(linear: ByteArray, bw: Int, bh: Int, bpp: Int): ByteArray {
        val lb = log2(bpp)
        val pitch = (bw + 31) and 31.inv()
        val out = ByteArray(pitch * ((bh + 31) and 31.inv()) * bpp)
        for (y in 0 until bh) {
            val base = outer(y, pitch, lb)
            for (x in 0 until bw) {
                System.arraycopy(linear, (y * bw + x) * bpp, out, inner(x, y, lb, base), bpp)
            }
        }
        return out
    }
}

/**
 * The game's STPZ compression: an STPK split into 15360-byte blocks, each LZ
 * compressed (tokens: size<<1 | match flag, then a 1-byte back offset or the
 * literal bytes), with little-endian "0DCS"/"0LCS" headers.
 */
object Stpz {
    private const val BLOCK = 15360

    fun isStpz(d: ByteArray) = d.size >= 4 && String(d, 0, 4, Charsets.ISO_8859_1) == "STPZ"

    /** Unpacks an STPZ file; an STPK is returned as it is. */
    fun decompress(d: ByteArray): ByteArray {
        if (!isStpz(d)) return d
        if (String(d, 16, 4, Charsets.ISO_8859_1) != "0DCS") throw IOException("unknown STPZ layout")
        val total = le32(d, 20)
        val out = ByteArray(total)
        var outPos = 0
        var pos = 32
        while (outPos < total) {
            if (String(d, pos, 4, Charsets.ISO_8859_1) != "0LCS") throw IOException("broken STPZ block at $pos")
            val csize = le32(d, pos + 8)
            var p = pos + 16
            val end = pos + csize
            while (p < end) {
                val value = d[p++].toInt() and 0xFF
                var size = value shr 1
                if (value and 1 != 0) {
                    var from = outPos - (d[p++].toInt() and 0xFF)
                    while (size-- > 0) out[outPos++] = out[from++]
                } else {
                    System.arraycopy(d, p, out, outPos, size)
                    p += size
                    outPos += size
                }
            }
            pos = end
        }
        return out
    }

    /** Packs an STPK the way the game's tools do (blocks of 15360, window 255, runs of 3–127). */
    fun compress(stpk: ByteArray): ByteArray {
        val body = ByteArrayOutputStream()
        var at = 0
        while (at < stpk.size) {
            val n = minOf(BLOCK, stpk.size - at)
            val comp = compressBlock(stpk, at, n)
            body.write("0LCS".toByteArray(Charsets.ISO_8859_1))
            body.write(le(n)); body.write(le(16 + comp.size)); body.write(le(0))
            body.write(comp)
            at += n
        }
        val out = ByteArrayOutputStream()
        out.write("STPZ".toByteArray(Charsets.ISO_8859_1))
        out.write(stpk, 4, 12)
        out.write("0DCS".toByteArray(Charsets.ISO_8859_1))
        out.write(le(stpk.size)); out.write(le(16 + body.size())); out.write(le(BLOCK))
        body.writeTo(out)
        return out.toByteArray()
    }

    private fun compressBlock(d: ByteArray, base: Int, length: Int): ByteArray {
        val minSize = 3
        val maxSize = 127
        val window = 255
        val out = ByteArrayOutputStream()
        var pos = 0
        var start = 0
        var size = 0
        fun literal() {
            out.write(size shl 1)
            out.write(d, base + start, size)
        }
        while (pos < length) {
            var prev = pos - 1
            var prevMax = prev
            var sizeMax = 0
            while (prev >= 0 && prev >= pos - window) {
                var a = pos
                var b = prev
                var cur = 0
                while (a < length && cur < maxSize && d[base + b] == d[base + a]) { a++; b++; cur++ }
                if (cur > sizeMax) { prevMax = prev; sizeMax = cur }
                if (cur >= maxSize || a >= length) break
                prev--
            }
            if (sizeMax >= minSize) {
                if (size > 0) literal()
                out.write((sizeMax shl 1) or 1)
                out.write(pos - prevMax)
                pos += sizeMax
                start = pos
                size = 0
            } else {
                pos++
                size++
                if (size >= maxSize) { literal(); start = pos; size = 0 }
            }
        }
        if (size > 0) literal()
        return out.toByteArray()
    }

    private fun le(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())
    private fun le32(d: ByteArray, o: Int) = (d[o].toInt() and 0xFF) or ((d[o + 1].toInt() and 0xFF) shl 8) or
        ((d[o + 2].toInt() and 0xFF) shl 16) or ((d[o + 3].toInt() and 0xFF) shl 24)
}
