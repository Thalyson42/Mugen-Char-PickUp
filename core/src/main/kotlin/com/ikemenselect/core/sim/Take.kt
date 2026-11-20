package com.ikemenselect.core.sim

import com.ikemenselect.core.SndRef

/** Um som gravado: toca no tick [tick]; [side] diz de qual personagem é o .snd (1 = P1, 2 = P2). */
class TakeSound(val tick: Int, val ref: SndRef, val side: Int)

/**
 * Uma simulação gravada: o que cada lutador, helper e efeito fazia a cada tick.
 * O Character View só reproduz isto; quem decide o que acontece é o [Sim].
 */
class SimTake(
    val frames: List<SimFrame>,
    /** Quantos ticks foram gravados. */
    val ticks: Int,
    val sounds: List<TakeSound>,
    val log: List<String>,
    /** true se a gravação parou porque ninguém mais estava fazendo nada (false = chegou ao limite de segurança). */
    val settled: Boolean,
    /** O lado 1 ativou algum HitDef: o estado é um ataque (se não for, "não acertou" não faz sentido). */
    val tried: Boolean = false,
    /** A gravação foi cortada porque nada mais mudava (estado esperando algo que a simulação não fornece). */
    val stuck: Boolean = false,
) {
    /** Quadros agrupados por tick (índice = tick). */
    val byTick: List<List<SimFrame>> = run {
        val out = ArrayList<MutableList<SimFrame>>()
        repeat(ticks) { out.add(ArrayList()) }
        for (f in frames) if (f.tick in 0 until ticks) out[f.tick].add(f)
        out
    }

    /** Animações usadas, por lado (1 = personagem do P1, 2 = personagem do P2). */
    val animsBySide: Map<Int, Set<Int>> =
        frames.filter { it.anim >= 0 }.groupBy { it.side }.mapValues { (_, l) -> l.map { it.anim }.toSet() }

    /** Estados por onde o lutador do lado 2 passou, em ordem. */
    val enemyStates: List<Int> = run {
        val out = ArrayList<Int>()
        for (f in frames) if (f.side == 2 && f.kind == "char" && f.who == "P2" && (out.isEmpty() || out.last() != f.state)) out.add(f.state)
        out
    }

    /** Estados por onde o lutador do lado 1 passou, em ordem. */
    val attackerStates: List<Int> = run {
        val out = ArrayList<Int>()
        for (f in frames) if (f.kind == "char" && f.who == "P1" && (out.isEmpty() || out.last() != f.state)) out.add(f.state)
        out
    }

    val hit: Boolean get() = log.any { it.contains("acerta") }
}

object SimRunner {
    /** Limite de segurança: 30 segundos de jogo. */
    const val SAFETY_TICKS = 1800

    /** Quantos ticks seguidos sem nenhuma mudança contam como "travado" (2 segundos). */
    const val STUCK_TICKS = 120

    private val sndRegex = Regex("""^\s*([FfSs])?\s*(-?\d+)\s*,\s*(-?\d+)""")

    fun parseSound(text: String): SndRef? {
        val m = sndRegex.find(text) ?: return null
        val g = m.groupValues[2].toIntOrNull() ?: return null
        val n = m.groupValues[3].toIntOrNull() ?: return null
        return SndRef(m.groupValues[1].equals("F", ignoreCase = true), g, n)
    }

    /**
     * Roda o estado [stateNo] de [p1] (ou, com [helperId], um helper nele) contra [p2] e grava tudo.
     * Sem [p2], o adversário usa o mesmo personagem (só para o simulador ter alguém a quem bater; o lado 2 não é mostrado).
     * Para quando todos ficam parados por um tempo ou ao chegar em [maxTicks].
     */
    fun record(
        p1: SimChar,
        p2: SimChar?,
        stateNo: Int,
        helperId: Int? = null,
        vars: Map<Int, Int> = emptyMap(),
        rootVars: Map<Int, Int> = emptyMap(),
        maxTicks: Int = SAFETY_TICKS,
        spacing: Double = 30.0,
        coordY: Double = 240.0,
        seed: Long = 12345L,
        /** false = o HitDef acerta sem precisar das caixas se tocarem (só para testes). */
        collide: Boolean = true,
    ): SimTake {
        val sim = Sim(p1.book, p1.anims, collide = collide, consts = p1.consts, coordY = coordY, seed = seed)
        sim.enemyChar = p2
        val root = sim.spawnRoot(-spacing)
        sim.spawnEnemy(spacing)
        for ((k, v) in rootVars) if (k in root.vars.indices) root.vars[k] = v
        if (helperId == null) {
            for ((k, v) in vars) if (k in root.vars.indices) root.vars[k] = v
            sim.enter(root, stateNo, root)
        } else {
            val h = sim.spawnHelper(root, helperId, "helper$helperId", stateNo, 0.0, 0.0, 1)
            for ((k, v) in vars) if (k in h.vars.indices) h.vars[k] = v
            sim.entities += sim.pending
            sim.pending.clear()
        }
        var calm = 0
        var settled = false
        var stuck = false
        var still = 0
        var lastSig: List<Int>? = null
        while (sim.tick < maxTicks) {
            val from = sim.frames.size
            sim.step()
            // estagnação: ninguém muda de estado, animação, posição ou lado, e todas as animações já tocaram inteiras
            val now = sim.frames.subList(from, sim.frames.size)
            val sig = now.flatMap { listOf(it.uid, it.state, it.anim, it.x.toInt(), it.y.toInt(), it.facing) }
            val played = now.all { f ->
                val total = ((if (f.side == 2 && p2 != null) p2 else p1).anims(f.anim))?.totalTicks ?: 0
                f.animTick >= total
            }
            still = if (sig == lastSig && played) still + 1 else 0
            lastSig = sig
            if (still >= STUCK_TICKS && sim.tick > 10) { stuck = true; break }
            val quiet = sim.fx.isEmpty() &&
                sim.entities.none { it.helperId != null && it.alive } &&
                (root.state == 0 || root.ctrl) && root.moveType == "I" && (sim.enemy.state == 0 || sim.enemy.ctrl) &&
                sim.enemy.moveType == "I" && sim.enemy.hitPause <= 0
            calm = if (quiet && sim.tick > 10) calm + 1 else 0
            if (calm >= 12) { settled = true; break }
        }
        val sounds = sim.sounds.mapNotNull { sd -> parseSound(sd.text)?.let { TakeSound(sd.tick, it, sd.side) } }
        return SimTake(sim.frames.toList(), sim.tick, sounds, sim.log.toList(), settled, sim.attackTried, stuck)
    }
}
