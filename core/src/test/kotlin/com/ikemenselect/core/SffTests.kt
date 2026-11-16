package com.ikemenselect.core

import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SffTests {

    // ------------------------------------------------------------ helpers

    private class Out {
        private val b = ByteArrayOutputStream()
        val size: Int get() = b.size()
        fun u8(v: Int) = b.write(v and 0xFF)
        fun u16(v: Int) { u8(v); u8(v shr 8) }
        fun u32(v: Long) { u16((v and 0xFFFF).toInt()); u16(((v shr 16) and 0xFFFF).toInt()) }
        fun bytes(a: ByteArray) = b.write(a)
        fun pad(to: Int) { while (b.size() < to) u8(0) }
        fun toByteArray(): ByteArray = b.toByteArray()
    }

    private fun Portrait?.rows(): List<String> {
        val p = this!!
        return (0 until p.height).map { y ->
            (0 until p.width).joinToString("") { x -> if ((p.argb[y * p.width + x] ushr 24) == 0) "." else "#" }
        }
    }

    private fun rgb(r: Int, g: Int, b: Int): Int = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    // ------------------------------------------------------------ SFF v2 builder

    private class V2S(
        val group: Int, val number: Int, val w: Int, val h: Int,
        val fmt: Int, val depth: Int, val data: ByteArray,
        val pal: Int = 0, val link: Int = 0,
    )

    /** palettes: cada uma é uma lista de cores RGB (índice 0 incluído). */
    private fun buildV2(palettes: List<IntArray>, sprites: List<V2S>): ByteArray {
        val tableOfs = 128
        val palTableOfs = tableOfs + sprites.size * 28
        val ldataOfs = palTableOfs + palettes.size * 16

        val ld = Out()
        val palOfs = palettes.map { p ->
            val o = ld.size
            for (c in p) { ld.u8(c shr 16); ld.u8(c shr 8); ld.u8(c); ld.u8(255) }
            o
        }
        val sprOfs = sprites.map { s -> val o = ld.size; ld.bytes(s.data); o }
        val ldata = ld.toByteArray()

        val out = Out()
        out.bytes("ElecbyteSpr".toByteArray(Charsets.ISO_8859_1)); out.u8(0)
        out.u8(0); out.u8(1); out.u8(0); out.u8(2)
        out.pad(36)
        out.u32(tableOfs.toLong()); out.u32(sprites.size.toLong())
        out.u32(palTableOfs.toLong()); out.u32(palettes.size.toLong())
        out.u32(ldataOfs.toLong()); out.u32(ldata.size.toLong())
        out.u32((ldataOfs + ldata.size).toLong()); out.u32(0)
        out.pad(tableOfs)
        for ((i, s) in sprites.withIndex()) {
            out.u16(s.group); out.u16(s.number); out.u16(s.w); out.u16(s.h)
            out.u16(0); out.u16(0)
            out.u16(s.link); out.u8(s.fmt); out.u8(s.depth)
            out.u32(sprOfs[i].toLong()); out.u32(s.data.size.toLong())
            out.u16(s.pal); out.u16(0)
        }
        for ((i, p) in palettes.withIndex()) {
            out.u16(1); out.u16(i); out.u16(p.size); out.u16(0)
            out.u32(palOfs[i].toLong()); out.u32((p.size * 4).toLong())
        }
        out.bytes(ldata)
        return out.toByteArray()
    }

    private val pal4 = intArrayOf(0, rgb(255, 0, 0), rgb(0, 255, 0), rgb(0, 0, 255))
    private val pal4b = intArrayOf(0, rgb(10, 20, 30), rgb(40, 50, 60), rgb(70, 80, 90))

    private fun withSize(payload: ByteArray): ByteArray =
        Out().also { it.u32(payload.size.toLong()); it.bytes(payload) }.toByteArray()

    private fun rle8Encode(px: IntArray): ByteArray {
        val o = Out()
        var i = 0
        while (i < px.size) {
            var run = 1
            while (i + run < px.size && px[i + run] == px[i] && run < 63) run++
            if (run == 1 && px[i] < 0x40) o.u8(px[i]) else { o.u8(0x40 or run); o.u8(px[i]) }
            i += run
        }
        return o.toByteArray()
    }

    // ------------------------------------------------------------ SFF v1 builder

    private class V1S(val group: Int, val number: Int, val pcx: ByteArray, val link: Int = 0, val palSame: Boolean = false)

    private fun buildV1(sprites: List<V1S>): ByteArray {
        val out = Out()
        out.bytes("ElecbyteSpr".toByteArray(Charsets.ISO_8859_1)); out.u8(0)
        out.u8(0); out.u8(0); out.u8(0); out.u8(1)
        out.u32(1); out.u32(sprites.size.toLong()); out.u32(512); out.u32(32)
        out.pad(512)
        var ofs = 512L
        for ((i, s) in sprites.withIndex()) {
            val next = if (i == sprites.size - 1) 0L else ofs + 32 + s.pcx.size
            out.u32(next); out.u32(s.pcx.size.toLong())
            out.u16(0); out.u16(0)
            out.u16(s.group); out.u16(s.number); out.u16(s.link)
            out.u8(if (s.palSame) 1 else 0)
            out.pad((ofs + 32).toInt())
            out.bytes(s.pcx)
            ofs = next
        }
        return out.toByteArray()
    }

    /** PCX de 8 bits com RLE; [pal] = 256 cores (ou null para não gravar palette). */
    private fun pcx(w: Int, h: Int, idx: IntArray, pal: IntArray?): ByteArray {
        val bpl = if (w % 2 == 0) w else w + 1
        val o = Out()
        o.u8(0x0A); o.u8(5); o.u8(1); o.u8(8)
        o.u16(0); o.u16(0); o.u16(w - 1); o.u16(h - 1)
        o.pad(65); o.u8(1); o.u16(bpl)
        o.pad(128)
        for (y in 0 until h) {
            val row = IntArray(bpl) { x -> if (x < w) idx[y * w + x] else 0 }
            var i = 0
            while (i < bpl) {
                var run = 1
                while (i + run < bpl && row[i + run] == row[i] && run < 63) run++
                if (run == 1 && row[i] < 0xC0) o.u8(row[i]) else { o.u8(0xC0 or run); o.u8(row[i]) }
                i += run
            }
        }
        if (pal != null) {
            o.u8(0x0C)
            for (c in 0 until 256) { val v = pal[c]; o.u8(v shr 16); o.u8(v shr 8); o.u8(v) }
        }
        return o.toByteArray()
    }

    private fun palette256(base: Int): IntArray = IntArray(256) { rgb((base + it) and 0xFF, 100, (200 + it) and 0xFF) }

    // ------------------------------------------------------------ v1 tests

    @Test
    fun v1ReadsPortraitWithOwnPalette() {
        val img = intArrayOf(0, 1, 2, 3, 3, 2, 1, 0, 1, 1, 2, 2) // 4x3 (largura 4 -> sem padding)
        val p0 = palette256(0)
        val p1 = palette256(50)
        val file = buildV1(
            listOf(
                V1S(0, 0, pcx(2, 2, intArrayOf(1, 1, 1, 1), p0)),
                V1S(9000, 0, pcx(4, 3, img, p1)),
            ),
        )
        val p = SffReader.readPortrait(ByteArraySource(file))!!
        assertEquals(4, p.width)
        assertEquals(3, p.height)
        assertEquals(0, p.argb[0]) // índice 0 = transparente
        assertEquals(p1[1], p.argb[1])
        assertEquals(p1[3], p.argb[3])
        assertEquals(p1[2], p.argb[11])
    }

    @Test
    fun v1OddWidthUsesLinePadding() {
        val img = intArrayOf(1, 2, 3, 3, 2, 1, 1, 1, 2) // 3x3, bpl = 4
        val p1 = palette256(7)
        val file = buildV1(listOf(V1S(0, 0, pcx(1, 1, intArrayOf(1), palette256(0))), V1S(9000, 0, pcx(3, 3, img, p1))))
        val p = SffReader.readSprite(ByteArraySource(file), 9000, 0)!!
        assertEquals(listOf(p1[1], p1[2], p1[3], p1[3], p1[2], p1[1], p1[1], p1[1], p1[2]), p.argb.toList())
    }

    @Test
    fun v1PaletteSameUsesPreviousSpritePalette() {
        val p0 = palette256(0)
        val file = buildV1(
            listOf(
                V1S(0, 0, pcx(2, 2, intArrayOf(1, 1, 1, 1), p0)),
                V1S(9000, 0, pcx(2, 2, intArrayOf(1, 2, 3, 0), null), palSame = true),
            ),
        )
        val p = SffReader.readPortrait(ByteArraySource(file))!!
        assertEquals(listOf(p0[1], p0[2], p0[3], 0), p.argb.toList())
    }

    @Test
    fun v1LinkedSpriteReusesTheOtherSprite() {
        val p0 = palette256(0)
        val p1 = palette256(90)
        val file = buildV1(
            listOf(
                V1S(0, 0, pcx(2, 2, intArrayOf(1, 1, 1, 1), p0)),
                V1S(1, 0, pcx(2, 1, intArrayOf(2, 3), p1)),
                V1S(9000, 0, ByteArray(0), link = 1),
            ),
        )
        val p = SffReader.readPortrait(ByteArraySource(file))!!
        assertEquals(listOf(p1[2], p1[3]), p.argb.toList())
    }

    @Test
    fun v1MissingSpriteAndBrokenFilesGiveNull() {
        val file = buildV1(listOf(V1S(0, 0, pcx(1, 1, intArrayOf(1), palette256(0)))))
        assertNull(SffReader.readPortrait(ByteArraySource(file)))
        assertNull(SffReader.readPortrait(ByteArraySource(ByteArray(0))))
        assertNull(SffReader.readPortrait(ByteArraySource("not an sff file at all, just text".toByteArray())))
        // arquivo cortado no meio do sprite
        val withPortrait = buildV1(listOf(V1S(0, 0, pcx(1, 1, intArrayOf(1), palette256(0))), V1S(9000, 0, pcx(4, 4, IntArray(16) { 1 }, palette256(0)))))
        SffReader.readPortrait(ByteArraySource(withPortrait.copyOf(withPortrait.size - 400))) // não pode lançar exceção
    }

    // ------------------------------------------------------------ v2 tests

    @Test
    fun v2RawIndexed() {
        val file = buildV2(listOf(pal4), listOf(V2S(9000, 0, 2, 2, 0, 8, byteArrayOf(1, 2, 3, 0))))
        val p = SffReader.readPortrait(ByteArraySource(file))!!
        assertEquals(listOf(pal4[1], pal4[2], pal4[3], 0), p.argb.toList())
    }

    @Test
    fun v2Rle8() {
        val px = IntArray(40) { if (it < 10) 0 else if (it < 30) 2 else 1 } // 8x5
        val file = buildV2(listOf(pal4), listOf(V2S(9000, 0, 8, 5, 2, 8, withSize(rle8Encode(px)))))
        val p = SffReader.readPortrait(ByteArraySource(file))!!
        assertEquals(px.map { pal4[it] }, p.argb.toList())
    }

    @Test
    fun v2Rle5HandMadeStream() {
        // 4x cor 7 (rl=3, cor explícita), depois: cor 5 repetida 2x, cor 3 uma vez
        val stream = byteArrayOf(3, 0x82.toByte(), 7, 0x25, 0x03)
        assertEquals(listOf<Byte>(7, 7, 7, 7, 5, 5, 3), rle5Decode(stream, 7)!!.toList())
    }

    @Test
    fun lz5RealFontStreams() {
        // Trechos reais do arquivo font/default-3x5.sff do Ikemen GO (glifos 'A' e '@').
        val a = intArrayOf(0x18, 0x84, 0x20, 0xA4, 0x02, 0x05, 0x01, 0x02).map { it.toByte() }.toByteArray()
        val glyphA = lz5Decode(a, 15)!!.toList().chunked(3).map { r -> r.joinToString("") { if (it.toInt() == 0) "." else "#" } }
        assertEquals(listOf("###", "#.#", "###", "#.#", "#.#"), glyphA)

        val at = intArrayOf(
            0x84, 0x20, 0x84, 0x01, 0x04, 0x20, 0x43, 0x64, 0x23, 0x01, 0x06, 0x03, 0x42, 0x0F, 0x45, 0x20, 0x63, 0x40, 0xA4,
        ).map { it.toByte() }.toByteArray()
        val glyphAt = lz5Decode(at, 36)!!.toList().chunked(6).map { r -> r.joinToString("") { if (it.toInt() == 0) "." else "#" } }
        assertEquals(listOf(".####.", "#.####", "##.#.#", "##.#.#", "#.###.", ".#####"), glyphAt)
    }

    @Test
    fun v2Lz5ThroughTheReader() {
        val a = intArrayOf(0x18, 0x84, 0x20, 0xA4, 0x02, 0x05, 0x01, 0x02).map { it.toByte() }.toByteArray()
        // os glifos da fonte usam índices de cor até 31, então a palette tem 32 cores
        val pal32 = IntArray(32) { if (it == 0) 0 else rgb(it * 8, 0, 0) }
        val file = buildV2(listOf(pal32), listOf(V2S(9000, 0, 3, 5, 4, 5, withSize(a))))
        val p = SffReader.readPortrait(ByteArraySource(file))
        assertEquals(listOf("###", "#.#", "###", "#.#", "#.#"), p.rows())
    }

    @Test
    fun v2LinkedSpriteUsesOwnPalette() {
        val file = buildV2(
            listOf(pal4, pal4b),
            listOf(
                V2S(1, 0, 2, 1, 0, 8, byteArrayOf(1, 2)),
                V2S(9000, 0, 0, 0, 1, 0, ByteArray(0), pal = 1, link = 0),
            ),
        )
        val p = SffReader.readPortrait(ByteArraySource(file))!!
        assertEquals(2, p.width)
        assertEquals(listOf(pal4b[1], pal4b[2]), p.argb.toList())
    }

    @Test
    fun v2PngFormatsUseTheProvidedDecoder() {
        val img = BufferedImage(2, 1, BufferedImage.TYPE_INT_ARGB)
        img.setRGB(0, 0, 0xFF123456.toInt())
        img.setRGB(1, 0, 0x80ABCDEF.toInt())
        val png = ByteArrayOutputStream().also { ImageIO.write(img, "png", it) }.toByteArray()
        val data = Out().also { it.u32(0); it.bytes(png) }.toByteArray() // 4 bytes de cabeçalho + PNG
        val file = buildV2(listOf(pal4), listOf(V2S(9000, 0, 2, 1, 12, 32, data)))

        val decoder = PngDecoder { bytes ->
            val bi = ImageIO.read(ByteArrayInputStream(bytes)) ?: return@PngDecoder null
            Portrait(bi.width, bi.height, IntArray(bi.width * bi.height) { bi.getRGB(it % bi.width, it / bi.width) })
        }
        val p = SffReader.readPortrait(ByteArraySource(file), decoder)!!
        assertEquals(listOf(0xFF123456.toInt(), 0x80ABCDEF.toInt()), p.argb.toList())
        assertNull(SffReader.readPortrait(ByteArraySource(file), null)) // sem decodificador de PNG
    }

    @Test
    fun v2MissingSpriteGivesNull() {
        val file = buildV2(listOf(pal4), listOf(V2S(0, 0, 1, 1, 0, 8, byteArrayOf(1))))
        assertNull(SffReader.readPortrait(ByteArraySource(file)))
        assertNull(SffReader.readSprite(ByteArraySource(file.copyOf(40)), 0, 0)) // cabeçalho cortado
    }

    // ------------------------------------------------------------ CharFiles

    @Test
    fun charFilesDefPath() {
        assertEquals("kfm/kfm.def", CharFiles.defPath("kfm"))
        assertEquals("kfm/kfm.def", CharFiles.defPath("kfm, stages/a.def"))
        assertEquals("ryu/ryu_alt.def", CharFiles.defPath("ryu/ryu_alt.def"))
        assertEquals("kfm/kfm.def", CharFiles.defPath("chars\\KFM\\kfm.def".replace("KFM", "kfm")))
        assertEquals("a/b/b.def", CharFiles.defPath("a/b"))
        assertNull(CharFiles.defPath("  "))
    }

    @Test
    fun charFilesSpriteName() {
        val def = "[Info]\nname = \"X\"\n[Files]\ncmd = x.cmd\nSprite = sprites\\x.sff ; comment\n[Palette Keymap]\nsprite = no.sff\n"
        assertEquals("sprites/x.sff", CharFiles.spriteFile(def))
        assertNull(CharFiles.spriteFile("[Info]\nsprite = a.sff"))
        assertTrue(CharFiles.spriteFile("[Files]\nsprite =") == null)
    }

    @Test
    fun selectDefCellKeepsEntry() {
        val def = SelectDef.parse("[Characters]\nkfm, stages/a.def\nslot = {\n  ryu/ryu_alt.def, previous=d\n  b\n}\n")
        assertEquals("kfm", def.cells[0]!!.entry)
        assertEquals("ryu/ryu_alt.def", def.cells[1]!!.entry)
        assertEquals("Zed/zed_x.def", Cell.forEntry(" Zed/zed_x.def ").entry)
    }
}
