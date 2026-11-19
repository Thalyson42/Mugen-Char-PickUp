package com.ikemenselect.core

/** Um trecho do plano: toca [anim] por [ticks] ticks (-1 = até o fim natural da animação). */
class AnimStep(val anim: Int, val ticks: Int, val useOwner: Boolean)

/** Prende o alvo ao atacante: de [start] por [ticks] ticks, em ([dx], [dy]) à frente/abaixo da origem do atacante. */
class Bind(val start: Int, val ticks: Int, val dx: Float, val dy: Float)

/** Referência a um som: [common] = fight.snd do jogo (prefixo F); senão o .snd do personagem dono do estado (prefixo S). */
data class SndRef(val common: Boolean, val group: Int, val number: Int)

/** Som que toca no tick [tick] do golpe. */
class SoundEvent(val tick: Int, val ref: SndRef)

/** O que o adversário faz quando o golpe encosta: entra em [stateNo] no tick [startTick] do atacante. */
class TargetPlan(
    val startTick: Int,
    val stateNo: Int,
    val steps: List<AnimStep>,
    /** Outros estados que o atacante também manda no mesmo tick (ordens para helpers diferentes, por exemplo): vale o último. */
    val alternatives: List<Int> = emptyList(),
    /** `sprpriority` do estado do alvo: quem tem o maior valor é desenhado por cima. */
    val sprPriority: Int = 0,
    /** Controlador Offset do estado do alvo: desloca só o desenho (x à frente, y para baixo), em pixels do personagem. */
    val offsetX: Float = 0f,
    val offsetY: Float = 0f,
    /** Sons do estado do alvo, em ticks do próprio alvo (começam em 0). O .snd "próprio" é o do alvo. */
    val sounds: List<SoundEvent> = emptyList(),
    /** `p2facing` do HitDef que manda o alvo para este estado: 1 = mesmo lado do atacante, -1 = virado para ele, 0 = não muda. */
    val facing: Int = 0,
)

class MovePlan(
    val stateNo: Int,
    /** Estados percorridos pelo atacante, em ordem. */
    val states: List<Int>,
    val steps: List<AnimStep>,
    /** Duração total do plano em ticks. */
    val totalTicks: Int,
    val target: TargetPlan?,
    val binds: List<Bind>,
    /**
     * Todas as vezes que o atacante manda o alvo para outro estado durante o golpe, em ordem de tick.
     * Um golpe encadeado (agarrão com várias etapas) troca o estado do alvo a cada etapa; [target] é só o primeiro.
     */
    val targets: List<TargetPlan> = listOfNotNull(target),
    /** `sprpriority` do estado do atacante. */
    val sprPriority: Int = 0,
    /** Sons do atacante (PlaySnd e o hitsound do HitDef quando acerta), em ticks do golpe. */
    val sounds: List<SoundEvent> = emptyList(),
) {
    /**
     * Prisões do alvo ao atacante. Se o golpe não tem TargetBind mas o alvo toca a animação do atacante (ChangeAnim2),
     * as duas animações foram feitas para ficarem juntas: o alvo fica na origem do atacante a partir dessa etapa.
     */
    val effectiveBinds: List<Bind>
        get() {
            val ph = targets.firstOrNull { p -> p.steps.any { it.useOwner } }
            if (binds.isNotEmpty()) {
                // A fase do alvo começa no tick 0 mas o TargetBind só no tick 1: sem isso o P2
                // aparece um tick fora do lugar e "teletransporta" a cada volta do loop.
                val first = binds.first()
                if (ph != null && first.start > ph.startTick && first.start - ph.startTick <= 3) {
                    return listOf(Bind(ph.startTick, first.ticks, first.dx, first.dy)) + binds.drop(1)
                }
                return binds
            }
            ph ?: return emptyList()
            return listOf(Bind(ph.startTick, Int.MAX_VALUE / 2, 0f, 0f))
        }

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
    /** O estado roda dentro de um Helper (ishelper = 1). */
    val asHelper: Boolean = false,
    /**
     * Planeja só o estado pedido: não segue os ChangeState para as etapas seguintes. A animação do estado toca até o fim
     * e o alvo vai para o(s) estado(s) que este estado manda (TargetState/HitDef dele), não os das etapas seguintes.
     */
    val singleState: Boolean = false,
    /** Id do helper que roda o estado: `ishelper(N)` só vale quando N é este id. null = qualquer id vale. */
    val helperId: Int? = null,
    /** Valores supostos de var(N) do próprio personagem e do dono (root) quando o estado roda num helper. O resto vale 0. */
    val vars: Map<Int, Int> = emptyMap(),
    val rootVars: Map<Int, Int> = emptyMap(),
    /** Números das animações que o adversário em tela tem: resolve `target, selfanimexist(N)`. null = não sei. */
    val targetAnims: Set<Int>? = null,
)

/**
 * Planejador de golpes: segue os controladores de um estado (ChangeAnim, ChangeState, TargetState, TargetBind...)
 * avaliando só os gatilhos que dá para entender sem o jogo rodando (time, animtime, animelem, anim, stateno...).
 * Variáveis (var/fvar) valem 0 e o que não se sabe fica "desconhecido": para trocas de animação e de estado,
 * desconhecido = não dispara; para o que o adversário sofre, desconhecido = dispara.
 */
object MovePlanner {
    private fun priorityOf(book: StateBook, stateNo: Int): Int =
        book.states[stateNo]?.props?.get("sprpriority")?.trim()?.toIntOrNull() ?: 0


    /** Planeja o golpe do estado [stateNo]. null se o estado não existe. */
    fun plan(book: StateBook, stateNo: Int, anims: (Int) -> Animation?, opts: PlanOptions = PlanOptions()): MovePlan? {
        if (book.states[stateNo] == null) return null
        val run = Runner(book, anims, opts, asTarget = false, start = stateNo).run()

        // cada TargetState / HitDef com p2stateno do atacante põe o alvo em um estado; se vários caem no mesmo tick, vale o último
        val byTick = LinkedHashMap<Int, Hit>()
        val sameTick = HashMap<Int, LinkedHashSet<Int>>()
        for (h in run.hits.sortedBy { it.tick }) if (book.states[h.stateNo] != null) {
            byTick[h.tick] = h
            sameTick.getOrPut(h.tick) { LinkedHashSet() }.add(h.stateNo)
        }
        val phases = ArrayList<TargetPlan>()
        var prevState = -1
        for (h in byTick.values) {
            if (h.stateNo == prevState) continue // repetir o mesmo estado não é uma etapa nova
            val t = Runner(book, anims, opts, asTarget = true, start = h.stateNo).run()
            val off = book.states[h.stateNo]?.controllers?.firstOrNull { it.type == "offset" }
            phases.add(
                TargetPlan(
                    h.tick, h.stateNo, t.steps, sameTick[h.tick].orEmpty().filter { it != h.stateNo },
                    sprPriority = priorityOf(book, h.stateNo),
                    offsetX = off?.param("x")?.trim()?.toFloatOrNull() ?: 0f,
                    offsetY = off?.param("y")?.trim()?.toFloatOrNull() ?: 0f,
                    sounds = t.sounds.distinctBy { it.tick to it.ref },
                    facing = book.p2FacingByState[h.stateNo] ?: 0,
                ),
            )
            prevState = h.stateNo
        }
        val target = phases.firstOrNull()
        return MovePlan(
            stateNo, run.states, run.steps, run.totalTicks, target, run.binds, phases,
            sprPriority = priorityOf(book, stateNo),
            sounds = run.sounds.distinctBy { it.tick to it.ref },
        )
    }

    private const val MAX_STATE_CHANGES = 12
    private const val MAX_HORIZON = 400
    private val horizonTypes = setOf("changeanim", "changeanim2", "changestate", "selfstate", "targetstate", "hitdef", "targetbind")
    private val timeCmp = Regex("""\btime\s*(>=|>|=|<=|<)\s*(\d+)""", RegexOption.IGNORE_CASE)
    private const val NO_ANIM_TICKS = 20

    private class Hit(val tick: Int, val stateNo: Int)

    private class RunResult(
        val steps: List<AnimStep>,
        val states: List<Int>,
        val totalTicks: Int,
        val hits: List<Hit>,
        val binds: List<Bind>,
        val sounds: List<SoundEvent>,
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
        val sounds = ArrayList<SoundEvent>()
        /** PlaySnd já disparados neste estado (um PlaySnd com gatilho sempre verdadeiro tocaria a cada tick). */
        val soundDone = HashSet<Controller>()

        fun run(): RunResult {
            val def0 = book.states[start]
            visited.add(start)
            startAnim(def0)?.let { setAnim(it, false) }
            var endTick = -1

            outer@ while (g < opts.maxTicks) {
                var changes = 0
                var leaving = false
                while (true) {
                    val def = book.states[state] ?: break@outer
                    var changed = false
                    for (c in def.controllers) {
                        if (leaving && (c.type == "changestate" || c.type == "selfstate")) continue
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
                                if (opts.singleState) {
                                    leaving = true // sai deste estado, mas o resto do tick dele ainda vale
                                    continue
                                }
                                // voltou para um estado comum de movimento: o golpe terminou
                                if ((v in 0..199 && v != start) || book.states[v] == null) {
                                    endTick = g + 1
                                    break@outer
                                }
                                if (visited.size >= MAX_STATE_CHANGES) {
                                    endTick = g
                                    break@outer
                                }
                                enter(v)
                                changed = true
                            }
                            "playsnd" -> if (!soundDone.contains(c) && fires(c, optimistic = false)) {
                                soundDone.add(c)
                                parseSnd(c.param("value"), commonByDefault = false)?.let { sounds.add(SoundEvent(g, it)) }
                            }
                            "targetstate" -> if (!asTarget && firedOnce.add(c) ) {
                                if (fires(c, optimistic = true)) {
                                    intParam(c, "value")?.let { hits.add(Hit(g, it)) }
                                } else firedOnce.remove(c)
                            }
                            "hitdef" -> if (!asTarget && firedOnce.add(c)) {
                                if (fires(c, optimistic = true)) {
                                    intParam(c, "p2stateno")?.let { hits.add(Hit(g + 1, it)) }
                                    // som de quando acerta: só existe contato se há um adversário
                                    if (opts.assumeHit) parseSnd(c.param("hitsound"), commonByDefault = true)?.let { sounds.add(SoundEvent(g + 1, it)) }
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
                if (leaving) {
                    // a animação do estado toca até o fim natural, mesmo que a troca de estado já tenha disparado
                    val a = anims(anim)
                    val natural = when {
                        a == null -> NO_ANIM_TICKS
                        a.infinite -> a.totalTicks + opts.holdInfinite
                        else -> a.totalTicks
                    }
                    endTick = maxOf(g + 1, animStart + natural)
                    break@outer
                }

                // estado que nunca definiu animação: não há o que mostrar
                if (anim < 0 && g >= NO_ANIM_TICKS) {
                    endTick = g
                    break
                }
                // fim da animação atual (e do tempo que os gatilhos do estado esperam) sem nenhuma troca
                val a = anims(anim)
                val elapsed = g - animStart
                val animDone = when {
                    a == null -> elapsed >= NO_ANIM_TICKS
                    a.infinite -> elapsed >= a.totalTicks + opts.holdInfinite
                    else -> elapsed >= a.totalTicks - 1
                }
                if (animDone && stateTime >= horizon(state)) {
                    val next = if (!opts.singleState && visited.size < MAX_STATE_CHANGES) optimisticExit() else null
                    if (next != null) {
                        enter(next)
                        continue@outer
                    }
                    endTick = g + 1
                    break
                }
                g++
                stateTime++
            }
            val total = if (endTick >= 0) endTick else g
            closeStep(total, natural = endTick >= 0)
            return RunResult(steps, visited, total, hits, binds, sounds)
        }

        private fun enter(v: Int) {
            prevState = state
            state = v
            stateTime = 0
            soundDone.clear()
            visited.add(v)
            startAnim(book.states[v])?.let { if (it != anim || animOwner) setAnim(it, false) }
        }

        /** "F7, 0" / "S1,4" / "5, 0": prefixo F = som comum, S = do personagem; sem prefixo vale [commonByDefault]. */
        private fun parseSnd(raw: String?, commonByDefault: Boolean): SndRef? {
            var t = raw?.trim() ?: return null
            if (t.isEmpty()) return null
            var common = commonByDefault
            if (t.length > 1 && t[0].lowercaseChar() in "fs" && (t[1].isDigit() || t[1] == '(')) {
                common = t[0].lowercaseChar() == 'f'
                t = t.substring(1)
            }
            val parts = splitTop(t)
            if (parts.size < 2) return null
            val g = (Expr.eval(parts[0], this) as? Double)?.toInt() ?: return null
            val n = (Expr.eval(parts[1], this) as? Double)?.toInt() ?: return null
            if (g < 0 || n < 0) return null // -1 = sem som
            return SndRef(common, g, n)
        }

        private fun splitTop(t: String): List<String> {
            val out = ArrayList<String>()
            var depth = 0
            var from = 0
            for ((i, ch) in t.withIndex()) {
                if (ch == '(') depth++ else if (ch == ')') depth-- else if (ch == ',' && depth == 0) { out.add(t.substring(from, i)); from = i + 1 }
            }
            out.add(t.substring(from))
            return out
        }

        private fun startAnim(def: StateDef?): Int? {
            if (def == null) return null
            def.anim?.let { return it }
            val raw = def.props["anim"] ?: return null
            return (Expr.eval(raw, this) as? Double)?.toInt()
        }

        private val horizons = HashMap<Int, Int>()

        /** Maior `time` que os gatilhos de troca/alvo do estado esperam: até lá a animação segura o último quadro. */
        private fun horizon(no: Int): Int = horizons.getOrPut(no) {
            var best = 0
            val def = book.states[no] ?: return@getOrPut 0
            for (c in def.controllers) {
                if (c.type !in horizonTypes) continue
                for (line in c.triggerAll + c.triggerGroups.flatten()) {
                    for (m in timeCmp.findAll(line)) {
                        val n = m.groupValues[2].toIntOrNull() ?: continue
                        val v = when (m.groupValues[1]) { ">" -> n + 1; else -> n }
                        if (v > best) best = v
                    }
                }
            }
            best.coerceAtMost(MAX_HORIZON)
        }

        /**
         * O estado acabou e nenhum gatilho trocou de estado (por não sabermos coisas como a distância do adversário):
         * segue o primeiro ChangeState para um estado de golpe cujos gatilhos não são conhecidos como falsos.
         */
        private fun optimisticExit(): Int? {
            val def = book.states[state] ?: return null
            for (c in def.controllers) {
                if (c.type != "changestate" && c.type != "selfstate") continue
                val v = intParam(c, "value") ?: continue
                if (v !in 200..4999 || v == state || v in visited || book.states[v] == null) continue
                if (v in book.helperStates) continue
                val text = (c.triggerAll + c.triggerGroups.flatten()).joinToString(" ")
                if (text.contains("command", ignoreCase = true)) continue
                if (fires(c, optimistic = true)) return v
            }
            return null
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

        /** Contexto do dono: só sabe as variáveis supostas (`root, var(27)`). */
        private val rootCtx = object : ExprContext {
            override fun ident(name: String, args: List<Any?>?): Any? = when (name) {
                "var" -> opts.rootVars[(args?.getOrNull(0) as? Double)?.toInt()]?.toDouble() ?: 0.0
                "fvar", "sysvar", "sysfvar" -> 0.0
                else -> null
            }
        }

        /** Adversário: só sabe quais animações ele tem (selfanimexist). */
        private val targetCtx = object : ExprContext {
            override fun ident(name: String, args: List<Any?>?): Any? {
                val set = opts.targetAnims ?: return null
                return if (name == "selfanimexist") (args?.getOrNull(0) as? Double)?.toInt()?.let { if (it in set) 1.0 else 0.0 } else null
            }
        }

        override fun redirect(name: String, args: List<Any?>?): ExprContext? = when (name) {
            "target", "enemy", "enemynear" -> if (opts.targetAnims != null) targetCtx else null
            "root", "parent" -> if (opts.asHelper) rootCtx else this
            else -> null
        }

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
                "random" -> 0.0
                "alive" -> 1.0
                "roundstate" -> 2.0
                "numenemy" -> 1.0
                "ishelper" -> if (!opts.asHelper) 0.0 else {
                    val want = opts.helperId
                    val asked = (args?.getOrNull(0) as? Double)?.toInt()
                    if (want == null || asked == null || asked == want) 1.0 else 0.0
                }
                "cond" -> {
                    val c = args?.getOrNull(0) as? Double
                    if (c == null) null else if (c != 0.0) args.getOrNull(1) else args.getOrNull(2)
                }
                "frontedgebodydist", "frontedgedist", "backedgebodydist", "backedgedist" -> 0.0
                "p2bodydist.x", "p2bodydist", "enemybodydist.x", "enemybodydist" -> if (opts.assumeHit) 10.0 else null
                "p2dist.x", "p2dist", "enemydist.x", "enemydist" -> if (opts.assumeHit) 40.0 else null
                "p2bodydist.y", "p2dist.y", "enemybodydist.y", "enemydist.y" -> if (opts.assumeHit) 0.0 else null
                "var" -> opts.vars[(args?.getOrNull(0) as? Double)?.toInt()]?.toDouble() ?: 0.0
                "fvar", "sysvar", "sysfvar" -> 0.0
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
