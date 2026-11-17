package com.ikemenselect.core

import java.io.ByteArrayOutputStream
import java.util.zip.Inflater
import kotlin.math.abs

/**
 * PNG lido sem depender do Android.
 * Para PNG indexado, [indices] traz o índice de cada pixel (e [argb] fica nulo): o SFF v2 usa a
 * paleta da tabela de paletas do .sff, não a de dentro do PNG. Nos demais, [argb] traz as cores.
 */
internal class PngData(
    val width: Int,
    val height: Int,
    val indices: ByteArray?,
    val plte: IntArray?,
    val argb: IntArray?,
)

private fun ByteArray.be32(i: Int): Long =
    ((this[i].toLong() and 0xFF) shl 24) or ((this[i + 1].toLong() and 0xFF) shl 16) or
        ((this[i + 2].toLong() and 0xFF) shl 8) or (this[i + 3].toLong() and 0xFF)

private val PNG_SIGNATURE = intArrayOf(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

/** Decodifica PNG não entrelaçado (8 bits; indexado também com 1, 2 e 4 bits). Devolve null se não der. */
internal fun decodePng(png: ByteArray, maxPixels: Long = 16_000_000L): PngData? {
    if (png.size < 33) return null
    for (k in 0 until 8) if ((png[k].toInt() and 0xFF) != PNG_SIGNATURE[k]) return null

    var w = 0
    var h = 0
    var depth = 0
    var ctype = -1
    var interlace = 0
    var plte: IntArray? = null
    var trns: ByteArray? = null
    val idat = ByteArrayOutputStream()

    var pos = 8
    while (pos + 8 <= png.size) {
        val len = png.be32(pos)
        val type = String(png, pos + 4, 4, Charsets.ISO_8859_1)
        val start = pos + 8
        if (len < 0 || start + len > png.size) break
        val l = len.toInt()
        when (type) {
            "IHDR" -> {
                if (l < 13) return null
                val ww = png.be32(start)
                val hh = png.be32(start + 4)
                if (ww <= 0 || hh <= 0 || ww * hh > maxPixels) return null
                w = ww.toInt()
                h = hh.toInt()
                depth = png[start + 8].toInt() and 0xFF
                ctype = png[start + 9].toInt() and 0xFF
                interlace = png[start + 12].toInt() and 0xFF
            }
            "PLTE" -> {
                val n = minOf(l / 3, 256)
                val p = IntArray(256)
                for (c in 0 until n) {
                    p[c] = (0xFF shl 24) or ((png[start + c * 3].toInt() and 0xFF) shl 16) or
                        ((png[start + c * 3 + 1].toInt() and 0xFF) shl 8) or (png[start + c * 3 + 2].toInt() and 0xFF)
                }
                plte = p
            }
            "tRNS" -> trns = png.copyOfRange(start, start + l)
            "IDAT" -> idat.write(png, start, l)
            "IEND" -> break
        }
        if (type == "IEND") break
        pos = start + l + 4
    }

    if (w <= 0 || h <= 0 || interlace != 0) return null
    val channels = when (ctype) { 0 -> 1; 2 -> 3; 3 -> 1; 4 -> 2; 6 -> 4; else -> return null }
    val depthOk = if (ctype == 3) depth == 1 || depth == 2 || depth == 4 || depth == 8 else depth == 8
    if (!depthOk) return null

    val bitsPerPixel = channels * depth
    val rowBytes = (w * bitsPerPixel + 7) / 8
    val bpp = maxOf(1, bitsPerPixel / 8)
    val rawSize = (rowBytes + 1).toLong() * h
    if (rawSize > 64L * 1024 * 1024) return null

    // descompacta
    val raw = ByteArray(rawSize.toInt())
    val inflater = Inflater()
    try {
        inflater.setInput(idat.toByteArray())
        var total = 0
        while (total < raw.size && !inflater.finished()) {
            val n = inflater.inflate(raw, total, raw.size - total)
            if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
            total += n
        }
        if (total < raw.size) return null
    } catch (e: java.util.zip.DataFormatException) {
        return null
    } finally {
        inflater.end()
    }

    // desfaz os filtros de linha
    val img = ByteArray(rowBytes * h)
    for (y in 0 until h) {
        val ft = raw[y * (rowBytes + 1)].toInt() and 0xFF
        val src = y * (rowBytes + 1) + 1
        val dst = y * rowBytes
        for (x in 0 until rowBytes) {
            val a = if (x >= bpp) img[dst + x - bpp].toInt() and 0xFF else 0
            val b = if (y > 0) img[dst - rowBytes + x].toInt() and 0xFF else 0
            val c = if (x >= bpp && y > 0) img[dst - rowBytes + x - bpp].toInt() and 0xFF else 0
            val v = raw[src + x].toInt() and 0xFF
            val out = when (ft) {
                0 -> v
                1 -> v + a
                2 -> v + b
                3 -> v + (a + b) / 2
                4 -> {
                    val p = a + b - c
                    val pa = abs(p - a)
                    val pb = abs(p - b)
                    val pc = abs(p - c)
                    v + if (pa <= pb && pa <= pc) a else if (pb <= pc) b else c
                }
                else -> return null
            }
            img[dst + x] = out.toByte()
        }
    }

    fun px(o: Int) = img[o].toInt() and 0xFF
    if (ctype == 3) {
        val idx = ByteArray(w * h)
        val mask = (1 shl depth) - 1
        for (y in 0 until h) {
            for (x in 0 until w) {
                val bit = x * depth
                val byte = px(y * rowBytes + bit / 8)
                idx[y * w + x] = ((byte shr (8 - depth - bit % 8)) and mask).toByte()
            }
        }
        var pal = plte
        val t = trns
        if (pal != null && t != null) {
            pal = pal.copyOf()
            for (k in 0 until minOf(t.size, 256)) pal[k] = (pal[k] and 0x00FFFFFF) or ((t[k].toInt() and 0xFF) shl 24)
        }
        return PngData(w, h, idx, pal, null)
    }

    val out = IntArray(w * h)
    for (i in out.indices) {
        val o = (i / w) * rowBytes + (i % w) * channels
        out[i] = when (ctype) {
            0 -> { val g = px(o); (0xFF shl 24) or (g shl 16) or (g shl 8) or g }
            4 -> { val g = px(o); (px(o + 1) shl 24) or (g shl 16) or (g shl 8) or g }
            2 -> (0xFF shl 24) or (px(o) shl 16) or (px(o + 1) shl 8) or px(o + 2)
            else -> (px(o + 3) shl 24) or (px(o) shl 16) or (px(o + 1) shl 8) or px(o + 2)
        }
    }
    return PngData(w, h, null, plte, out)
}
