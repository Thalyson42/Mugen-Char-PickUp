package com.ikemenselect.core

/** Um trecho do plano: toca [anim] por [ticks] ticks (-1 = até o fim natural da animação). */
class AnimStep(val anim: Int, val ticks: Int, val useOwner: Boolean)

/** Prende o alvo ao atacante: de [start] por [ticks] ticks, em ([dx], [dy]) à frente/abaixo da origem do atacante. */
class Bind(val start: Int, val ticks: Int, val dx: Float, val dy: Float)

/** O que o adversário faz quando o golpe encosta: entra em [stateNo] no tick [startTick] do atacante. */
class TargetPlan(val startTick: Int, val stateNo: Int, val steps: List<AnimStep>)

class MovePlan(
    val stateNo: Int,
    /** Estados percorridos pelo atacante, em ordem. */
    val states: List<Int>,
    val steps: List<AnimStep>,
    /** Duração total do plano em ticks. */
    val totalTicks: Int,
    val target: TargetPlan?,
    val binds: List<Bind>,
) {
    /** Todas as animações que o plano usa (para carregar os sprites antes de tocar). */
    fun animsUsed(owner: Boolean): Set<Int> {
        val out = LinkedHashSet<Int>()
        for (s in steps) if (s.useOwner == owner || !owner) out.add(s.anim)
        return out
    }
}

class PlanOptions(
    /** Supõe que o golpe acertou (movehit = 1, numtarget = 1). Liga continuações e a interação com o P2. */
    val assumeHit: Boolean = true,
    val maxTicks: Int = 900,
    /** Quantos ticks segurar uma animação que nunca termina. */
    val holdInfinite: Int = 60,
)

/**
 * Planejador de golpes: segue os controladores de um estado (ChangeAnim, ChangeState, TargetState, TargetBind...)
 * avaliando só os gatilhos que dá para entender sem o jogo rodando (time, animtime, animelem, anim, stateno...).
 * Variáveis (var/fvar) valem 0 e o que não se sabe fica "desconhecido": para trocas de animação e de estado,
 * desconhecido = não dispara; para o que o adversário sofre, desconhecido = dispara.
 */
object MovePlanner {

    /** Planeja o golpe do estado [stateNo]. null se o estado não existe. */
    fun plan(book: StateBook, stateNo: Int, anims: (Int) -> Animation?, opts: PlanOptions = PlanOptions()): MovePlan? {
        if (book.states[stateNo] == null) return null
        val run = Runner(book, anims, opts, asTarget = false, start = stateNo).run()

        // primeiro contato do atacante com o alvo: TargetState ou HitDef com p2stateno
        val first = run.hits.minByOrNull { it.tick }
        var target: TargetPlan? = null
        if (first != null && book.states[first.stateNo] != null) {
            val t = Runner(book, anims, opts, asTarget = true, start = first.stateNo).run()
            target = TargetPlan(first.tick, first.stateNo, t.steps)
        }
        return MovePlan(stateNo, run.states, run.steps, run.totalTicks, target, run.binds)
    }

    private const val MAX_STATE_CHANGES = 12
    private const val NO_ANIM_TICKS = 20

    private class Hit(val tick: Int, val stateNo: Int)

    private class RunResult(
        val steps: List<AnimStep>,
        val states: List<Int>,
        val totalTicks: Int,
        val hits: List<Hit>,
        val binds: List<Bind>,
    )

    private class Runner(
        val book: StateBook,
        val anims: (Int) -> Animation?,
        val opts: PlanOptions,
        val asTarget: Boolean,
        val start: Int,
    ) : ExprContext {

        var state = start
        var prevState = 0
        var stateTime = 0
        var g = 0
        var anim = -1
        var animStart = 0
        var animOwner = false

        val steps = ArrayList<AnimStep>()
        var stepStart = 0
        val visited = ArrayList<Int>()
        val hits = ArrayList<Hit>()
        val binds = ArrayList<Bind>()
        val firedOnce = HashSet<Controller>()

        fun run(): RunResult {
            val def0 = book.states[start]
            visited.add(start)
            def0?.anim?.let { setAnim(it, false) }
            var endTick = -1

            outer@ while (g < opts.maxTicks) {
                var changes = 0
                while (true) {
                    val def = book.states[state] ?: break@outer
                    var changed = false
                    for (c in def.controllers) {
                        when (c.type) {
                            "changeanim", "changeanim2" -> {
                                if (c.type == "changeanim2" && !asTarget) continue
                                if (!fires(c, optimistic = false)) continue
                                val v = intParam(c, "value") ?: continue
                                if (v != anim) setAnim(v, c.type == "changeanim2")
                            }
                            "changestate", "selfstate" -> {
                                if (!fires(c, optimistic = false)) continue
                                val v = intParam(c, "value") ?: continue
                                if (v == state) continue
                                // voltou para um estado comum de movimento: o golpe terminou
                                if ((v in 0..199 && v != start) || book.states[v] == null) {
                                    endTick = g + 1
                                    break@outer
                                }
                                if (visited.size >= MAX_STATE_CHANGES) {
                                    endTick = g
                                    break@outer
                                }
                                prevState = state
                                state = v
                                stateTime = 0
                                visited.add(v)
                                book.states[v]?.anim?.let { if (it != anim || animOwner) setAnim(it, false) }
                                changed = true
                            }
                            "targetstate" -> if (!asTarget && firedOnce.add(c) ) {
                                if (fires(c, optimistic = true)) {
                                    intParam(c, "value")?.let { hits.add(Hit(g, it)) }
                                } else firedOnce.remove(c)
                            }
                            "hitdef" -> if (!asTarget && firedOnce.add(c)) {
                                if (fires(c, optimistic = true)) {
                                    intParam(c, "p2stateno")?.let { hits.add(Hit(g + 1, it)) }
                                } else firedOnce.remove(c)
                            }
                            "targetbind" -> if (!asTarget && fires(c, optimistic = true)) {
                                val pos = c.param("pos")?.split(',')
                                val x = pos?.getOrNull(0)?.let { Expr.eval(it, this) as? Double }
                                val y = pos?.getOrNull(1)?.let { Expr.eval(it, this) as? Double } ?: 0.0
                                val t = c.param("time")?.let { Expr.eval(it, this) as? Double }?.toInt() ?: 1
                                if (x != null) addBind(g, t.coerceAtLeast(1), x.toFloat(), y.toFloat())
                            }
                        }
                        if (changed) break
                    }
                    if (!changed) break
                    if (++changes > 8) break
                }

                // estado que nunca definiu animação: não há o que mostrar
                if (anim < 0 && g >= NO_ANIM_TICKS) {
                    endTick = g
                    break
                }
                // fim natural da animação atual sem nenhuma troca: o golpe acabou
                val a = anims(anim)
                if (a != null) {
                    val elapsed = g - animStart
                    if (a.infinite) {
                        if (elapsed >= a.totalTicks + opts.holdInfinite) { endTick = g + 1; break }
                    } else if (elapsed >= a.totalTicks - 1) {
                        endTick = g + 1
                        break
                    }
                }
                g++
                stateTime++
            }
            val total = if (endTick >= 0) endTick else g
            closeStep(total, natural = endTick >= 0)
            return RunResult(steps, visited, total, hits, binds)
        }

        /** Junta binds seguidos na mesma posição (um controlador que dispara a cada tick) em um só. */
        private fun addBind(tick: Int, ticks: Int, dx: Float, dy: Float) {
            val last = binds.lastOrNull()
            if (last != null && last.dx == dx && last.dy == dy && tick <= last.start + last.ticks) {
                binds[binds.lastIndex] = Bind(last.start, maxOf(last.ticks, tick + ticks - last.start), dx, dy)
            } else {
                binds.add(Bind(tick, ticks, dx, dy))
            }
        }

        private fun setAnim(n: Int, owner: Boolean) {
            if (anim >= 0 && g - stepStart > 0) steps.add(AnimStep(anim, g - stepStart, animOwner))
            anim = n
            animOwner = owner
            animStart = g
            stepStart = g
        }

        private fun closeStep(total: Int, natural: Boolean) {
            if (anim >= 0) steps.add(AnimStep(anim, if (natural) -1 else total - stepStart, animOwner))
        }

        private fun intParam(c: Controller, key: String): Int? =
            c.param(key)?.let { (Expr.eval(it, this) as? Double)?.toInt() }

        private fun fires(c: Controller, optimistic: Boolean): Boolean {
            fun ok(line: String): Boolean {
                val r = Expr.truth(line, this)
                return r ?: optimistic
            }
            for (t in c.triggerAll) if (!ok(t)) return false
            if (c.triggerGroups.isEmpty()) return false
            for (group in c.triggerGroups) if (group.all { ok(it) }) return true
            return false
        }

        // ------------------------------------------------------------ valores para as expressões

        private val symbols = setOf("a", "s", "c", "l", "i", "h", "u", "n", "f")

        override fun ident(name: String, args: List<Any?>?): Any? {
            if (args == null && name in symbols) return name.uppercase()
            val cur = anims(anim)
            val elapsed = g - animStart
            return when (name) {
                "time", "statetime" -> stateTime.toDouble()
                "stateno" -> state.toDouble()
                "prevstateno" -> prevState.toDouble()
                "anim" -> anim.toDouble()
                "animtime" -> when {
                    cur == null -> null
                    cur.infinite -> -1.0
                    else -> (elapsed - (cur.totalTicks - 1)).toDouble()
                }
                "animelem" -> {
                    if (cur == null) null else {
                        val i = cur.frameIndexAt(elapsed, loop = false)
                        if (i >= 0 && cur.frameStart(i) == elapsed) (i + 1).toDouble() else -1.0
                    }
                }
                "animelemno" -> cur?.let { (it.frameIndexAt(elapsed, loop = false) + 1).toDouble() }
                "animelemtime" -> {
                    val n = (args?.getOrNull(0) as? Double)?.toInt()
                    if (cur == null || n == null || n < 1 || n > cur.frames.size) null
                    else (elapsed - cur.frameStart(n - 1)).toDouble()
                }
                "alive" -> 1.0
                "roundstate" -> 2.0
                "numenemy" -> 1.0
                "ishelper" -> 0.0
                "var", "fvar", "sysvar", "sysfvar" -> 0.0
                "movehit", "movecontact" -> if (opts.assumeHit) 1.0 else 0.0
                "moveguarded" -> 0.0
                "numtarget" -> if (opts.assumeHit) 1.0 else 0.0
                "numhelper", "numexplod", "numproj", "numprojid" -> 0.0
                "statetype" -> book.states[state]?.stateType
                "movetype" -> book.states[state]?.moveType
                "abs" -> (args?.getOrNull(0) as? Double)?.let { Math.abs(it) }
                "floor" -> (args?.getOrNull(0) as? Double)?.let { Math.floor(it) }
                "ceil" -> (args?.getOrNull(0) as? Double)?.let { Math.ceil(it) }
                "ifelse" -> {
                    val c = args?.getOrNull(0) as? Double
                    if (c == null) null else if (c != 0.0) args.getOrNull(1) else args.getOrNull(2)
                }
                else -> null
            }
        }
    }
}
