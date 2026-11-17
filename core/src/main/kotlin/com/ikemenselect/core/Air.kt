package com.ikemenselect.core

/** Caixa de colisão (Clsn1 = ataque, Clsn2 = corpo), com x1<=x2 e y1<=y2. */
class Box(val x1: Int, val y1: Int, val x2: Int, val y2: Int)

enum class Blend { NONE, ADD, ADD1, SUB, ALPHA }

class Frame(
    val group: Int,
    val image: Int,
    val x: Int,
    val y: Int,
    /** Duração em ticks (60 por segundo). -1 = para sempre. */
    val time: Int,
    val flipH: Boolean,
    val flipV: Boolean,
    val blend: Blend,
    /** Para [Blend.ALPHA]: origem e destino, de 0 a 256. */
    val alphaSrc: Int,
    val alphaDst: Int,
    val clsn1: List<Box>,
    val clsn2: List<Box>,
)

class Animation(val id: Int, val frames: List<Frame>, val loopStart: Int) {

    /** A última imagem dura para sempre (time = -1): a animação não termina. */
    val infinite: Boolean = frames.lastOrNull()?.time?.let { it < 0 } ?: false

    private val lengths: IntArray = IntArray(frames.size) { if (frames[it].time < 0) 0 else maxOf(1, frames[it].time) }
    private val starts: IntArray = IntArray(frames.size).also { a ->
        var acc = 0
        for (i in frames.indices) {
            a[i] = acc
            acc += lengths[i]
        }
    }

    /** Tick (desde o início da animação) em que o frame [i] começa. */
    fun frameStart(i: Int): Int = starts.getOrElse(i) { 0 }

    /** Duração total em ticks, sem contar a imagem infinita do fim. */
    val totalTicks: Int = lengths.sum()

    /** Índice do frame que está na tela no tick [tick] (0 = primeiro tick). Com [loop], repete a partir de loopStart. */
    fun frameIndexAt(tick: Int, loop: Boolean = true): Int {
        if (frames.isEmpty()) return -1
        var t = maxOf(0, tick)
        if (infinite) {
            if (t >= totalTicks) return frames.lastIndex
        } else if (t >= totalTicks) {
            if (!loop) return frames.lastIndex
            val ls = starts.getOrElse(loopStart.coerceIn(0, frames.lastIndex)) { 0 }
            val span = totalTicks - ls
            t = if (span <= 0) 0 else ls + (t - ls) % span
        }
        // procura o frame que contém t
        var lo = 0
        var hi = frames.lastIndex
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (starts[mid] <= t) lo = mid else hi = mid - 1
        }
        // pula frames de duração zero (infinito no meio não existe)
        return lo
    }
}

/** Leitor de arquivos .air (e das seções [Begin Action] que alguns cenários trazem no próprio .def). */
object AirParser {

    private val begin = Regex("""^\[\s*begin\s+action\s+(-?\d+)\s*]""", RegexOption.IGNORE_CASE)
    private val frameLine = Regex("""^(-?\d+)\s*,\s*(-?\d+)\s*,\s*(-?\d+)\s*,\s*(-?\d+)\s*,\s*(-?\d+)\s*(?:,(.*))?$""")
    private val clsnHeader = Regex("""^clsn([12])(default)?\s*:\s*(\d+)""", RegexOption.IGNORE_CASE)
    private val clsnBox = Regex("""^clsn([12])(?:default)?\s*\[\s*\d+\s*]\s*=\s*(.*)$""", RegexOption.IGNORE_CASE)
    private val alphaRe = Regex("""^as(\d+)d(\d+)$""", RegexOption.IGNORE_CASE)

    fun parse(text: String): Map<Int, Animation> {
        val out = LinkedHashMap<Int, Animation>()

        var id = 0
        var active = false
        var frames = ArrayList<Frame>()
        var loopStart = 0
        val default = arrayOf(ArrayList<Box>(), ArrayList<Box>())
        val pending: Array<ArrayList<Box>?> = arrayOfNulls(2)
        // última declaração de cada tipo: true = "Default", false = só para o próximo frame
        val targetIsDefault = booleanArrayOf(false, false)

        fun finish() {
            if (active && frames.isNotEmpty()) out.putIfAbsent(id, Animation(id, frames, loopStart.coerceIn(0, frames.lastIndex)))
            active = false
        }

        for (raw in text.lineSequence()) {
            val line = raw.substringBefore(';').trim().trimStart('﻿')
            if (line.isEmpty()) continue

            if (line.startsWith("[")) {
                finish()
                val m = begin.find(line)
                if (m != null) {
                    id = m.groupValues[1].toInt()
                    active = true
                    frames = ArrayList()
                    loopStart = 0
                    default[0].clear(); default[1].clear()
                    pending[0] = null; pending[1] = null
                    targetIsDefault[0] = false; targetIsDefault[1] = false
                }
                continue
            }
            if (!active) continue

            if (line.equals("loopstart", ignoreCase = true)) {
                loopStart = frames.size
                continue
            }

            val header = clsnHeader.find(line)
            if (header != null) {
                val k = header.groupValues[1].toInt() - 1
                val isDefault = header.groupValues[2].isNotEmpty()
                targetIsDefault[k] = isDefault
                if (isDefault) default[k].clear() else pending[k] = ArrayList()
                continue
            }

            val box = clsnBox.find(line)
            if (box != null) {
                val k = box.groupValues[1].toInt() - 1
                val n = box.groupValues[2].split(',').map { it.trim().toIntOrNull() }
                if (n.size >= 4 && n.take(4).all { it != null }) {
                    val a = n.map { it!! }
                    val b = Box(minOf(a[0], a[2]), minOf(a[1], a[3]), maxOf(a[0], a[2]), maxOf(a[1], a[3]))
                    if (targetIsDefault[k]) default[k].add(b)
                    else (pending[k] ?: ArrayList<Box>().also { pending[k] = it }).add(b)
                }
                continue
            }

            val f = frameLine.find(line) ?: continue
            val g = f.groupValues
            var flipH = false
            var flipV = false
            var blend = Blend.NONE
            var aSrc = 256
            var aDst = 0
            for (tok in g[6].split(',').map { it.trim() }.filter { it.isNotEmpty() }) {
                val u = tok.uppercase()
                when {
                    u.all { it == 'H' || it == 'V' } -> {
                        flipH = flipH || 'H' in u
                        flipV = flipV || 'V' in u
                    }
                    u == "A" -> { blend = Blend.ADD; aSrc = 256; aDst = 256 }
                    u == "A1" -> { blend = Blend.ADD1; aSrc = 256; aDst = 128 }
                    u == "S" -> { blend = Blend.SUB; aSrc = 256; aDst = 256 }
                    alphaRe.matches(u) -> {
                        val am = alphaRe.find(u)!!
                        blend = Blend.ALPHA
                        aSrc = am.groupValues[1].toInt().coerceIn(0, 256)
                        aDst = am.groupValues[2].toInt().coerceIn(0, 256)
                    }
                }
            }
            frames.add(
                Frame(
                    group = g[1].toInt(), image = g[2].toInt(), x = g[3].toInt(), y = g[4].toInt(), time = g[5].toInt(),
                    flipH = flipH, flipV = flipV, blend = blend, alphaSrc = aSrc, alphaDst = aDst,
                    clsn1 = (pending[0] ?: default[0]).toList(),
                    clsn2 = (pending[1] ?: default[1]).toList(),
                ),
            )
            pending[0] = null
            pending[1] = null
        }
        finish()
        return out
    }
}
