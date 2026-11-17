package com.ikemenselect.core

import java.io.Closeable

/** Leitura posicional de um arquivo, sem carregá-lo inteiro na memória. */
interface RandomAccessSource : Closeable {
    val length: Long

    /** Lê até [len] bytes a partir de [position]; perto do fim devolve menos bytes (ou nenhum). */
    fun read(position: Long, len: Int): ByteArray

    override fun close() {}
}

class ByteArraySource(private val data: ByteArray) : RandomAccessSource {
    override val length: Long get() = data.size.toLong()

    override fun read(position: Long, len: Int): ByteArray {
        if (position < 0 || position >= data.size || len <= 0) return ByteArray(0)
        val end = minOf(data.size.toLong(), position + len).toInt()
        return data.copyOfRange(position.toInt(), end)
    }
}

/** Imagem decodificada: pixels em ARGB (não pré-multiplicado). */
class Portrait(val width: Int, val height: Int, val argb: IntArray, var info: String = "")

/** Decodificador de PNG fornecido pela plataforma (usado pelos formatos 10, 11 e 12 do SFF v2). */
fun interface PngDecoder {
    fun decode(png: ByteArray): Portrait?
}

private const val OPAQUE = -0x1000000 // 0xFF000000
private const val MAX_PIXELS = 16_000_000L
private const val MAX_BLOCK = 64L * 1024 * 1024

private fun ByteArray.u8(i: Int): Int = this[i].toInt() and 0xFF
private fun ByteArray.u16(i: Int): Int = u8(i) or (u8(i + 1) shl 8)
private fun ByteArray.s16(i: Int): Int = u16(i).toShort().toInt()
private fun ByteArray.u32(i: Int): Long =
    u8(i).toLong() or (u8(i + 1).toLong() shl 8) or (u8(i + 2).toLong() shl 16) or (u8(i + 3).toLong() shl 24)

private fun rgb(r: Int, g: Int, b: Int): Int = OPAQUE or (r shl 16) or (g shl 8) or b

private fun applyPalette(indices: ByteArray, w: Int, h: Int, pal: IntArray): Portrait {
    val out = IntArray(w * h)
    for (i in out.indices) {
        val idx = if (i < indices.size) indices[i].toInt() and 0xFF else 0
        out[i] = pal[idx]
    }
    return Portrait(w, h, out)
}

/**
 * Lê um sprite de um arquivo .sff (formatos v1 e v2 do M.U.G.E.N / Ikemen GO).
 * A lógica segue o código de leitura do próprio Ikemen GO (src/image.go).
 */
object SffReader {

    /** Retrato de seleção de personagem: sprite 9000,0. */
    fun readPortrait(src: RandomAccessSource, png: PngDecoder? = null): Portrait? = readSprite(src, 9000, 0, png)

    fun readSprite(src: RandomAccessSource, group: Int, number: Int, png: PngDecoder? = null): Portrait? =
        try {
            val h = src.read(0, 80)
            val signature = "ElecbyteSpr"
            if (h.size < 32 || String(h, 0, 11, Charsets.ISO_8859_1) != signature) {
                null
            } else {
                when (h.u8(15)) {
                    1 -> readV1(src, h, group, number)?.also { it.info = "SFF v1 · PCX" }
                    2 -> if (h.size >= 68) readV2(src, h, group, number, png) else null
                    else -> null
                }
            }
        } catch (e: Exception) {
            null
        } catch (e: OutOfMemoryError) {
            null
        }


    /** Abre o .sff e monta o índice dos sprites. Devolve null se o arquivo não for um SFF válido. */
    fun open(src: RandomAccessSource, png: PngDecoder? = null): SffFile? =
        try {
            val h = src.read(0, 80)
            if (h.size < 32 || String(h, 0, 11, Charsets.ISO_8859_1) != "ElecbyteSpr") {
                null
            } else {
                when (h.u8(15)) {
                    1 -> openV1(src, h)
                    2 -> if (h.size >= 68) openV2(src, h, png) else null
                    else -> null
                }
            }
        } catch (e: Exception) {
            null
        } catch (e: OutOfMemoryError) {
            null
        }

    private fun openV1(src: RandomAccessSource, h: ByteArray): SffFile {
        val count = h.u32(20).coerceIn(0L, 200_000L).toInt()
        var ofs = h.u32(24)
        val list = ArrayList<V1Entry>()
        for (i in 0 until count) {
            if (ofs <= 0 || ofs + 32 > src.length) break
            val sh = src.read(ofs, 32)
            if (sh.size < 32) break
            val e = V1Entry(
                ofs, sh.u32(0), sh.u32(4), sh.u16(12), sh.u16(14), sh.u16(16), sh.u8(18) != 0,
                sh.s16(8), sh.s16(10),
            )
            list.add(e)
            if (e.next <= ofs) break
            ofs = e.next
        }
        return object : SffFile() {
            override val version = 1
            override val sprites: List<SpriteInfo> =
                list.map { SpriteInfo(it.group, it.number, 0, 0, it.axisX, it.axisY) }
            private val index = HashMap<Long, Int>().also { m ->
                list.forEachIndexed { i, e -> m.putIfAbsent(key(e.group, e.number), i) }
            }

            override fun decode(group: Int, number: Int): Portrait? =
                try {
                    val k = index[key(group, number)]
                    if (k == null) null else decodeV1Linked(src, list, k)?.also { it.info = "SFF v1 · PCX" }
                } catch (e: Exception) {
                    null
                } catch (e: OutOfMemoryError) {
                    null
                }

            override fun close() = src.close()
        }
    }

    private fun openV2(src: RandomAccessSource, h: ByteArray, png: PngDecoder?): SffFile {
        val hdr = V2Header(h)
        val table = src.read(hdr.spriteTable, hdr.count * 28)
        val n = table.size / 28
        val infos = (0 until n).map {
            val s = v2Sprite(table, it)
            SpriteInfo(s.group, s.number, s.width, s.height, table.s16(it * 28 + 8), table.s16(it * 28 + 10))
        }
        val index = HashMap<Long, Int>()
        infos.forEachIndexed { i, s -> index.putIfAbsent(SffFile.key(s.group, s.number), i) }
        val palCache = HashMap<Int, IntArray?>()
        return object : SffFile() {
            override val version = 2
            override val sprites: List<SpriteInfo> = infos

            override fun decode(group: Int, number: Int): Portrait? =
                try {
                    val k = index[key(group, number)]
                    if (k == null) {
                        null
                    } else {
                        decodeV2(src, table, k, hdr, png) { pi ->
                            if (palCache.containsKey(pi)) palCache[pi]
                            else v2Palette(src, hdr.paletteTable, hdr.paletteCount, hdr.lofs, pi).also { palCache[pi] = it }
                        }
                    }
                } catch (e: Exception) {
                    null
                } catch (e: OutOfMemoryError) {
                    null
                }

            override fun close() = src.close()
        }
    }

    // ------------------------------------------------------------------ SFF v1

    private class V1Entry(
        val header: Long,
        val next: Long,
        val size: Long,
        val group: Int,
        val number: Int,
        val link: Int,
        val paletteSame: Boolean,
        val axisX: Int = 0,
        val axisY: Int = 0,
    )

    private fun readV1(src: RandomAccessSource, h: ByteArray, group: Int, number: Int): Portrait? {
        val count = h.u32(20).coerceIn(0L, 200_000L).toInt()
        var ofs = h.u32(24)
        val list = ArrayList<V1Entry>()
        var found = -1
        for (i in 0 until count) {
            if (ofs <= 0 || ofs + 32 > src.length) break
            val sh = src.read(ofs, 32)
            if (sh.size < 32) break
            val e = V1Entry(
                ofs, sh.u32(0), sh.u32(4), sh.u16(12), sh.u16(14), sh.u16(16), sh.u8(18) != 0,
                sh.s16(8), sh.s16(10),
            )
            list.add(e)
            if (e.group == group && e.number == number) {
                found = i
                break
            }
            if (e.next <= ofs) break
            ofs = e.next
        }
        if (found < 0) return null
        return decodeV1Linked(src, list, found)
    }

    /** Sprite "linked": tamanho 0, reaproveita os dados de um sprite anterior. */
    private fun decodeV1Linked(src: RandomAccessSource, list: List<V1Entry>, found: Int): Portrait? {
        var k = found
        var hops = 0
        while (list[k].size == 0L) {
            val l = list[k].link
            if (l >= k || ++hops > 16) return null
            k = l
        }
        return decodeV1(src, list, k)
    }

    private fun previousWithData(list: List<V1Entry>, k: Int): Int? {
        for (j in k - 1 downTo 0) if (list[j].size != 0L) return j
        return null
    }

    private fun v1Block(src: RandomAccessSource, e: V1Entry): ByteArray? {
        val dataStart = e.header + 32
        val blockEnd = if (e.next > dataStart) e.next else dataStart + e.size
        val len = blockEnd - dataStart
        if (len < 128 || len > MAX_BLOCK) return null
        val block = src.read(dataStart, len.toInt())
        return if (block.size < 128) null else block
    }

    /** Palette própria do sprite (a que vem no fim do PCX). */
    private fun v1OwnPalette(block: ByteArray, isFirst: Boolean): IntArray? {
        val start: Int = if (isFirst) {
            block.size - 768
        } else {
            var pos = block.size - 769
            var marker = -1
            while (pos >= 128) {
                if (block.u8(pos) == 0x0C) {
                    marker = pos
                    break
                }
                pos--
            }
            (if (marker >= 0) marker else block.size - 769) + 1
        }
        if (start < 0 || start + 768 > block.size) return null
        val pal = IntArray(256)
        for (c in 1 until 256) {
            val o = start + c * 3
            pal[c] = rgb(block.u8(o), block.u8(o + 1), block.u8(o + 2))
        }
        return pal // índice 0 fica 0 = transparente
    }

    private fun isFirstSprite(list: List<V1Entry>, k: Int): Boolean =
        previousWithData(list, k) == null || (list[k].group == 0 && list[k].number == 0)

    private fun v1Palette(src: RandomAccessSource, list: List<V1Entry>, k: Int, own: ByteArray): IntArray? {
        var cur = k
        var block = own
        var hops = 0
        while (true) {
            val prev = previousWithData(list, cur)
            if (list[cur].paletteSame && prev != null) {
                if (++hops > 100_000) return null
                cur = prev
                block = v1Block(src, list[cur]) ?: return null
                continue
            }
            return v1OwnPalette(block, isFirstSprite(list, cur))
        }
    }

    private fun decodeV1(src: RandomAccessSource, list: List<V1Entry>, k: Int): Portrait? {
        val e = list[k]
        val block = v1Block(src, e) ?: return null
        if (block.u8(3) != 8) return null // só PCX de 8 bits
        val w = block.u16(8) - block.u16(4) + 1
        val h = block.u16(10) - block.u16(6) + 1
        if (w <= 0 || h <= 0 || w.toLong() * h > MAX_PIXELS) return null
        val bytesPerLine = block.u16(66)
        val encoding = block.u8(2)

        val prev = previousWithData(list, k)
        val same = e.paletteSame && prev != null
        val pixelEnd: Int = if (isFirstSprite(list, k) || same) {
            block.size
        } else {
            var pos = block.size - 769
            var marker = -1
            while (pos >= 128) {
                if (block.u8(pos) == 0x0C) {
                    marker = pos
                    break
                }
                pos--
            }
            if (marker >= 0) marker else block.size - 769
        }
        val raw = block.copyOfRange(128, maxOf(128, pixelEnd))
        val indices = if (encoding == 1) pcxRleDecode(raw, w, h, bytesPerLine) else raw
        if (indices == null) return null
        val pal = v1Palette(src, list, k, block) ?: return null
        return applyPalette(indices, w, h, pal)
    }

    // ------------------------------------------------------------------ SFF v2

    private class V2Sprite(
        val group: Int,
        val number: Int,
        val width: Int,
        val height: Int,
        val link: Int,
        val format: Int,
        val depth: Int,
        val offset: Long,
        val size: Long,
        val paletteIndex: Int,
        val flags: Int,
    )

    private fun v2Sprite(t: ByteArray, i: Int): V2Sprite {
        val o = i * 28
        return V2Sprite(
            group = t.u16(o), number = t.u16(o + 2),
            width = t.u16(o + 4), height = t.u16(o + 6),
            link = t.u16(o + 12), format = t.u8(o + 14), depth = t.u8(o + 15),
            offset = t.u32(o + 16), size = t.u32(o + 20),
            paletteIndex = t.u16(o + 24), flags = t.u16(o + 26),
        )
    }

    private fun readV2(src: RandomAccessSource, h: ByteArray, group: Int, number: Int, png: PngDecoder?): Portrait? {
        val hdr = V2Header(h)
        val table = src.read(hdr.spriteTable, hdr.count * 28)
        val n = table.size / 28
        var found = -1
        for (i in 0 until n) {
            if (table.u16(i * 28) == group && table.u16(i * 28 + 2) == number) {
                found = i
                break
            }
        }
        if (found < 0) return null
        return decodeV2(src, table, found, hdr, png) { v2Palette(src, hdr.paletteTable, hdr.paletteCount, hdr.lofs, it) }
    }

    private class V2Header(h: ByteArray) {
        val spriteTable = h.u32(36)
        val count = h.u32(40).coerceIn(0L, 200_000L).toInt()
        val paletteTable = h.u32(44)
        val paletteCount = h.u32(48).coerceIn(0L, 100_000L).toInt()
        val lofs = h.u32(52)
        val tofs = h.u32(60)
    }

    private fun decodeV2(
        src: RandomAccessSource,
        table: ByteArray,
        found: Int,
        hdr: V2Header,
        png: PngDecoder?,
        palette: (Int) -> IntArray?,
    ): Portrait? {
        val own = v2Sprite(table, found)
        var data = own
        var idx = found
        var hops = 0
        while (data.size == 0L) {
            if (data.link >= idx || ++hops > 16) return null
            idx = data.link
            data = v2Sprite(table, idx)
        }

        val w = data.width
        val hgt = data.height
        val base = if ((data.flags and 1) == 0) hdr.lofs else hdr.tofs
        val pos = base + data.offset

        val result = when (data.format) {
            0 -> {
                val bytes = src.read(pos, minOf(data.size, MAX_BLOCK).toInt())
                decodeRawV2(bytes, data, own.paletteIndex, palette)
            }
            2, 3, 4 -> {
                if (w <= 0 || hgt <= 0 || w.toLong() * hgt > MAX_PIXELS || data.size < 4) return null
                val packed = src.read(pos + 4, minOf(data.size - 4, MAX_BLOCK).toInt())
                val px = when (data.format) {
                    2 -> rle8Decode(packed, w * hgt)
                    3 -> rle5Decode(packed, w * hgt)
                    else -> lz5Decode(packed, w * hgt)
                } ?: return null
                val pal = palette(own.paletteIndex) ?: return null
                applyPalette(px, w, hgt, pal)
            }
            10, 11, 12 -> {
                if (data.size < 12) return null
                val packed = src.read(pos + 4, minOf(data.size - 4, MAX_BLOCK).toInt())
                val dec = decodePng(packed)
                when {
                    dec == null -> png?.decode(packed) // PNG fora do comum: tenta o decodificador da plataforma
                    dec.indices != null -> {
                        // PNG indexado: vale a paleta da tabela do .sff; a do PNG é só reserva.
                        val pal = palette(own.paletteIndex) ?: dec.plte
                        if (pal == null) null else applyPalette(dec.indices, dec.width, dec.height, pal)
                    }
                    else -> Portrait(dec.width, dec.height, dec.argb!!)
                }
            }
            else -> null
        }
        return result?.also { it.info = "SFF v2 · formato ${data.format}" }
    }

    private fun decodeRawV2(
        bytes: ByteArray,
        s: V2Sprite,
        paletteIndex: Int,
        palette: (Int) -> IntArray?,
    ): Portrait? {
        val w = s.width
        val h = s.height
        if (w <= 0 || h <= 0 || w.toLong() * h > MAX_PIXELS) return null
        return when (s.depth) {
            8 -> {
                val pal = palette(paletteIndex) ?: return null
                applyPalette(bytes, w, h, pal)
            }
            24, 32 -> {
                val bpp = s.depth / 8
                if (bytes.size < w * h * bpp) return null
                val out = IntArray(w * h)
                for (i in out.indices) {
                    val o = i * bpp
                    val a = if (bpp == 4) bytes.u8(o + 3) else 255
                    out[i] = (a shl 24) or (bytes.u8(o) shl 16) or (bytes.u8(o + 1) shl 8) or bytes.u8(o + 2)
                }
                Portrait(w, h, out)
            }
            else -> null
        }
    }

    private fun v2Palette(src: RandomAccessSource, table: Long, count: Int, lofs: Long, first: Int): IntArray? {
        var idx = first
        var hops = 0
        while (true) {
            if (idx < 0 || idx >= count || ++hops > 32) return null
            val e = src.read(table + idx * 16L, 16)
            if (e.size < 16) return null
            val link = e.u16(6)
            val ofs = e.u32(8)
            val size = e.u32(12)
            if (size == 0L) {
                idx = link
                continue
            }
            val data = src.read(lofs + ofs, minOf(size, 1024L).toInt())
            val pal = IntArray(256)
            for (c in 1 until minOf(data.size / 4, 256)) {
                pal[c] = rgb(data.u8(c * 4), data.u8(c * 4 + 1), data.u8(c * 4 + 2))
            }
            return pal // índice 0 = transparente
        }
    }
}

// ---------------------------------------------------------------------------
// Arquivo .sff aberto uma vez, com índice de todos os sprites
// ---------------------------------------------------------------------------

/** Um sprite do índice do .sff. No SFF v1 [width] e [height] ficam 0 (só se sabe ao decodificar). */
class SpriteInfo(val group: Int, val number: Int, val width: Int, val height: Int, val axisX: Int, val axisY: Int)

/** .sff aberto: lista os sprites e decodifica qualquer um. Não é seguro usar de várias threads ao mesmo tempo. */
abstract class SffFile : Closeable {
    abstract val version: Int
    abstract val sprites: List<SpriteInfo>
    abstract fun decode(group: Int, number: Int): Portrait?

    private val byKey: Map<Long, SpriteInfo> by lazy {
        val m = HashMap<Long, SpriteInfo>()
        for (s in sprites) m.putIfAbsent(key(s.group, s.number), s)
        m
    }

    fun info(group: Int, number: Int): SpriteInfo? = byKey[key(group, number)]
    fun has(group: Int, number: Int): Boolean = byKey.containsKey(key(group, number))

    companion object {
        fun key(group: Int, number: Int): Long = (group.toLong() shl 32) or number.toLong()
    }
}

// ---------------------------------------------------------------------------
// Decodificadores (portados de Ikemen GO, src/image.go)
// ---------------------------------------------------------------------------

/** PCX RLE (SFF v1). Cada linha tem [bytesPerLine] bytes, dos quais só os [w] primeiros são pixels. */
internal fun pcxRleDecode(rle: ByteArray, w: Int, h: Int, bytesPerLine: Int): ByteArray? {
    if (rle.isEmpty()) return null
    val p = ByteArray(w * h)
    var i = 0
    var j = 0
    var k = 0
    var steps = 0L
    val maxSteps = rle.size.toLong() + p.size * 2L + 16
    while (j < p.size) {
        if (++steps > maxSteps) break
        var n = 1
        var d = rle.u8(i)
        if (i < rle.size - 1) i++
        if (d >= 0xC0) {
            n = d and 0x3F
            d = rle.u8(i)
            if (i < rle.size - 1) i++
        }
        while (n > 0) {
            if (k < w && j < p.size) {
                p[j] = d.toByte()
                j++
            }
            k++
            if (k == bytesPerLine) {
                k = 0
                n = 1
            }
            n--
        }
    }
    return p
}

internal fun rle8Decode(rle: ByteArray, size: Int): ByteArray? {
    if (rle.isEmpty()) return null
    val p = ByteArray(size)
    var i = 0
    var j = 0
    var steps = 0L
    val maxSteps = rle.size.toLong() + size * 2L + 16
    while (j < size) {
        if (++steps > maxSteps) break
        var n = 1
        var d = rle.u8(i)
        if (i < rle.size - 1) i++
        if ((d and 0xC0) == 0x40) {
            n = d and 0x3F
            d = rle.u8(i)
            if (i < rle.size - 1) i++
        }
        while (n > 0) {
            if (j < size) {
                p[j] = d.toByte()
                j++
            }
            n--
        }
    }
    return p
}

internal fun rle5Decode(rle: ByteArray, size: Int): ByteArray? {
    if (rle.isEmpty()) return null
    val p = ByteArray(size)
    var i = 0
    var j = 0
    var steps = 0L
    val maxSteps = rle.size.toLong() + size * 2L + 16
    while (j < size) {
        if (++steps > maxSteps) break
        var rl = rle.u8(i)
        if (i < rle.size - 1) i++
        var dl = rle.u8(i) and 0x7F
        var c = 0
        if ((rle.u8(i) shr 7) != 0) {
            if (i < rle.size - 1) i++
            c = rle.u8(i)
        }
        if (i < rle.size - 1) i++
        while (true) {
            if (j < size) {
                p[j] = c.toByte()
                j++
            }
            rl--
            if (rl < 0) {
                dl--
                if (dl < 0) break
                c = rle.u8(i) and 0x1F
                rl = rle.u8(i) shr 5
                if (i < rle.size - 1) i++
            }
        }
    }
    return p
}

internal fun lz5Decode(rle: ByteArray, size: Int): ByteArray? {
    if (rle.isEmpty()) return null
    val p = ByteArray(size)
    var i = 0
    var j = 0
    var n: Int
    var ct = rle.u8(0)
    var cts = 0
    var rb = 0
    var rbc = 0
    if (i < rle.size - 1) i++
    var steps = 0L
    val maxSteps = rle.size.toLong() + size * 2L + 16
    while (j < size) {
        if (++steps > maxSteps) break
        var d = rle.u8(i)
        if (i < rle.size - 1) i++
        if ((ct and (1 shl cts)) != 0) {
            if ((d and 0x3F) == 0) {
                d = ((d shl 2) or rle.u8(i)) + 1
                if (i < rle.size - 1) i++
                n = rle.u8(i) + 2
                if (i < rle.size - 1) i++
            } else {
                rb = rb or ((d and 0xC0) shr rbc)
                rbc += 2
                n = d and 0x3F
                if (rbc < 8) {
                    d = rle.u8(i) + 1
                    if (i < rle.size - 1) i++
                } else {
                    d = rb + 1
                    rb = 0
                    rbc = 0
                }
            }
            while (true) {
                if (j < size) {
                    p[j] = if (j - d >= 0) p[j - d] else 0
                    j++
                }
                n--
                if (n < 0) break
            }
        } else {
            if ((d and 0xE0) == 0) {
                n = rle.u8(i) + 8
                if (i < rle.size - 1) i++
            } else {
                n = d shr 5
                d = d and 0x1F
            }
            while (n > 0) {
                if (j < size) {
                    p[j] = d.toByte()
                    j++
                }
                n--
            }
        }
        cts++
        if (cts >= 8) {
            ct = rle.u8(i)
            cts = 0
            if (i < rle.size - 1) i++
        }
    }
    return p
}
