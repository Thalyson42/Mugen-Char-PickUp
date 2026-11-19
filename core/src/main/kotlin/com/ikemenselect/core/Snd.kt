package com.ikemenselect.core

/** Áudio PCM já pronto para tocar. [bits] é 8 (sem sinal) ou 16 (com sinal, little-endian). */
class Pcm(val channels: Int, val sampleRate: Int, val bits: Int, val data: ByteArray)

object Wav {
    private fun u16(b: ByteArray, o: Int) = (b[o].toInt() and 255) or ((b[o + 1].toInt() and 255) shl 8)
    private fun u32(b: ByteArray, o: Int) = u16(b, o).toLong() or (u16(b, o + 2).toLong() shl 16)

    /** Lê um WAV PCM (8 ou 16 bits). null se não for um formato que dá para tocar. */
    fun parse(b: ByteArray): Pcm? {
        if (b.size < 12 || String(b, 0, 4, Charsets.ISO_8859_1) != "RIFF" || String(b, 8, 4, Charsets.ISO_8859_1) != "WAVE") return null
        var p = 12
        var channels = 0
        var rate = 0
        var bits = 0
        var pcm = false
        while (p + 8 <= b.size) {
            val id = String(b, p, 4, Charsets.ISO_8859_1)
            val len = u32(b, p + 4)
            val body = p + 8
            if (id == "fmt " && body + 16 <= b.size) {
                val tag = u16(b, body)
                channels = u16(b, body + 2)
                rate = u32(b, body + 4).toInt()
                bits = u16(b, body + 14)
                pcm = tag == 1 || tag == 0xFFFE
            } else if (id == "data") {
                if (!pcm || channels !in 1..2 || rate !in 4000..192000 || (bits != 8 && bits != 16)) return null
                // só quadros inteiros (o AudioTrack recusa o resto) e um teto de 8 MB por som
                val frame = channels * bits / 8
                var size = (minOf(b.size.toLong(), body + len) - body).toInt()
                size -= size % frame
                if (size <= 0 || size > 8 * 1024 * 1024) return null
                return Pcm(channels, rate, bits, b.copyOfRange(body, body + size))
            }
            p = (body + len + (len and 1L)).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        }
        return null
    }
}

/**
 * Arquivo .snd do Mugen: cabeçalho "ElecbyteSnd", e uma lista encadeada de sons (grupo, número, WAV).
 * Só o índice é lido na abertura; os dados de cada som são lidos quando pedidos (um .snd pode ter centenas de MB).
 */
class SndFile private constructor(private val src: RandomAccessSource, private val index: Map<Long, Entry>) {

    private class Entry(val offset: Long, val length: Int)

    val count: Int get() = index.size

    private fun key(group: Int, number: Int) = (group.toLong() shl 32) or (number.toLong() and 0xFFFFFFFFL)

    fun has(group: Int, number: Int) = index.containsKey(key(group, number))

    /** Bytes do WAV do som (grupo, número), ou null. */
    fun wav(group: Int, number: Int): ByteArray? {
        val e = index[key(group, number)] ?: return null
        val bytes = src.read(e.offset, e.length)
        return if (bytes.size == e.length) bytes else null
    }

    fun pcm(group: Int, number: Int): Pcm? = wav(group, number)?.let { Wav.parse(it) }

    fun close() = src.close()

    companion object {
        private fun i32(b: ByteArray, o: Int) =
            (b[o].toInt() and 255) or ((b[o + 1].toInt() and 255) shl 8) or ((b[o + 2].toInt() and 255) shl 16) or ((b[o + 3].toInt() and 255) shl 24)

        fun open(src: RandomAccessSource): SndFile? {
            val h = src.read(0, 24)
            if (h.size < 24 || String(h, 0, 11, Charsets.ISO_8859_1) != "ElecbyteSnd") return null
            val count = i32(h, 16)
            var off = i32(h, 20).toLong() and 0xFFFFFFFFL
            val index = LinkedHashMap<Long, Entry>()
            var n = 0
            while (n < count && off in 1 until src.length) {
                val e = src.read(off, 16)
                if (e.size < 16) break
                val next = i32(e, 0).toLong() and 0xFFFFFFFFL
                var len = i32(e, 4)
                val group = i32(e, 8)
                val number = i32(e, 12)
                if (len <= 0) len = ((if (next > off) next else src.length) - off - 16).toInt()
                if (len > 0 && off + 16 + len <= src.length) index.putIfAbsent((group.toLong() shl 32) or (number.toLong() and 0xFFFFFFFFL), Entry(off + 16, len))
                n++
                if (next <= off) break
                off = next
            }
            return SndFile(src, index)
        }
    }
}
