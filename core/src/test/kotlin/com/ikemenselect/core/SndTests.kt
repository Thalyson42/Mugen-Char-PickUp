package com.ikemenselect.core

import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SndTests {

    private fun le(v: Int, n: Int) = ByteArray(n) { ((v ushr (8 * it)) and 255).toByte() }

    private fun wav(samples: ByteArray, bits: Int = 16, rate: Int = 22050): ByteArray {
        val o = ByteArrayOutputStream()
        o.write("RIFF".toByteArray()); o.write(le(36 + samples.size, 4)); o.write("WAVE".toByteArray())
        o.write("fmt ".toByteArray()); o.write(le(16, 4)); o.write(le(1, 2)); o.write(le(1, 2)); o.write(le(rate, 4))
        o.write(le(rate * bits / 8, 4)); o.write(le(bits / 8, 2)); o.write(le(bits, 2))
        o.write("data".toByteArray()); o.write(le(samples.size, 4)); o.write(samples)
        return o.toByteArray()
    }

    private fun snd(vararg items: Triple<Int, Int, ByteArray>): ByteArray {
        val o = ByteArrayOutputStream()
        o.write("ElecbyteSnd\u0000".toByteArray(Charsets.ISO_8859_1)); o.write(le(65537, 4)); o.write(le(items.size, 4)); o.write(le(512, 4))
        o.write(ByteArray(512 - 24))
        var pos = 512
        for ((i, it) in items.withIndex()) {
            val next = if (i == items.lastIndex) 0 else pos + 16 + it.third.size
            o.write(le(next, 4)); o.write(le(it.third.size, 4)); o.write(le(it.first, 4)); o.write(le(it.second, 4)); o.write(it.third)
            pos += 16 + it.third.size
        }
        return o.toByteArray()
    }

    private fun src(b: ByteArray) = object : RandomAccessSource {
        override val length = b.size.toLong()
        override fun read(position: Long, len: Int): ByteArray {
            if (position >= b.size) return ByteArray(0)
            return b.copyOfRange(position.toInt(), minOf(b.size.toLong(), position + len).toInt())
        }
    }

    @Test
    fun wavTrimsPartialFramesAndRejectsAbsurdRates() {
        val p = Wav.parse(wav(byteArrayOf(1, 0, 2, 0, 3)))!! // 16 bits, mono: sobra 1 byte
        assertEquals(4, p.data.size)
        assertNull(Wav.parse(wav(byteArrayOf(1, 0, 2, 0), rate = 3)))
        assertNull(Wav.parse(wav(byteArrayOf(1, 0, 2, 0), rate = 5_000_000)))
    }

    @Test
    fun wavParsesPcm16() {
        val p = Wav.parse(wav(byteArrayOf(1, 0, 2, 0)))!!
        assertEquals(1, p.channels); assertEquals(22050, p.sampleRate); assertEquals(16, p.bits); assertEquals(4, p.data.size)
        assertNull(Wav.parse(byteArrayOf(1, 2, 3)))
    }

    @Test
    fun sndIndexesSoundsByGroupAndNumber() {
        val a = wav(byteArrayOf(1, 0, 2, 0))
        val b = wav(byteArrayOf(5, 0), bits = 8, rate = 11025)
        val f = SndFile.open(src(snd(Triple(1, 0, a), Triple(300, 23, b))))!!
        assertEquals(2, f.count)
        assertTrue(f.has(300, 23)); assertTrue(!f.has(1, 1))
        assertEquals(8, f.pcm(300, 23)!!.bits)
        assertEquals(4, f.pcm(1, 0)!!.data.size)
        assertNull(SndFile.open(src(ByteArray(40))))
    }
}
