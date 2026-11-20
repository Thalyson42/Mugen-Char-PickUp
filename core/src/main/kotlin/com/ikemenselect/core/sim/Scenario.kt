package com.ikemenselect.core.sim

import com.ikemenselect.core.Controller
import com.ikemenselect.core.StateBook

/**
 * Um ajuste que o cenário assume para o golpe poder acontecer (os "interruptores" do personagem).
 * [who] = "root" ou "h<id>" (helper de id tal).
 */
class Assumption(val who: String, val kind: Char, val index: Int, val value: Double) {
    val text: String
        get() {
            val v = if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()
            val name = when (kind) { 'v' -> "var($index)"; 'f' -> "fvar($index)"; else -> "power" }
            return (if (who == "root") "" else "helper ${who.drop(1)}: ") + "$name=$v"
        }
    val key get() = "$who/$kind/$index"
}

/**
 * Procura, por simulação, como fazer o estado [target] acontecer a partir de um botão: aperta os comandos que levam a
 * ele, roda os estados -3/-2/-1 como o jogo e, quando algum gatilho de variável/poder impede o caminho, assume o valor
 * que falta (ex.: `var(26)=1`) e tenta de novo. O que for assumido fica dito no resultado.
 */
object Scenario {
    /** Limite de segurança depois que o golpe começou (30 s de jogo). */
    const val POST_TICKS = 1800

    class Route(val rootState: Int, val presses: List<String>, val dist: Int)

    private val cmdRe = Regex("""(?<![!<>])command\s*=\s*"([^"]+)"""", RegexOption.IGNORE_CASE)
    private val atomRe = Regex(
        """^\(?\s*(?:(root|parent)\s*,\s*|helper\(\s*(\d+)\s*\)\s*,\s*)?(var|fvar)\(\s*(\d+)\s*\)\s*(>=|<=|!=|==|=|>|<)\s*(-?\d+(?:\.\d+)?)\s*\)?$""",
        RegexOption.IGNORE_CASE,
    )
    private val bareRe = Regex("""^(!)?\s*(?:(root|parent)\s*,\s*|helper\(\s*(\d+)\s*\)\s*,\s*)?(var|fvar)\(\s*(\d+)\s*\)$""", RegexOption.IGNORE_CASE)
    private val powerRe = Regex("""^\(?\s*power\s*(>=|>)\s*(-?\d+)\s*\)?$""", RegexOption.IGNORE_CASE)
    private val waitRe = Regex("""\b(time|animtime|animelem|random|gametime|stateno|prevstateno|movecontact|moveguarded|movehit)\b""", RegexOption.IGNORE_CASE)

    private fun literal(s: String?): Int? = s?.trim()?.let { Regex("""^-?\d+""").find(it)?.value?.toIntOrNull() }

    /** Arestas do grafo de estados: ChangeState/SelfState, Helper (stateno) e p1stateno. */
    fun edges(book: StateBook): Map<Int, Set<Int>> {
        val out = HashMap<Int, MutableSet<Int>>()
        for ((no, st) in book.states) for (c in st.controllers) {
            val t = when (c.type) {
                "changestate", "selfstate" -> literal(c.param("value"))
                "helper" -> literal(c.param("stateno"))
                "hitdef" -> literal(c.param("p1stateno"))
                else -> null
            } ?: continue
            if (t in book.states) out.getOrPut(no) { LinkedHashSet() } += t
        }
        return out
    }

    private fun dist(edges: Map<Int, Set<Int>>, starts: Collection<Int>, target: Int, hubs: Boolean = false): Int? {
        val d = HashMap<Int, Int>()
        val q = ArrayDeque<Int>()
        for (s in starts) { d[s] = 0; q += s }
        while (q.isNotEmpty()) {
            val n = q.removeFirst()
            if (n == target) return d[n]
            for (m in edges[n].orEmpty()) if (m !in d && (hubs || m == target || m in 100..4999)) { d[m] = d[n]!! + 1; q += m }
        }
        return null
    }

    /** Estados que chegam ao [target] (inclusive). */
    private fun ancestors(edges: Map<Int, Set<Int>>, target: Int, hubs: Boolean = false): Set<Int> {
        val rev = HashMap<Int, MutableSet<Int>>()
        for ((a, bs) in edges) for (b in bs) rev.getOrPut(b) { HashSet() } += a
        val seen = HashSet<Int>()
        val q = ArrayDeque<Int>()
        seen += target; q += target
        while (q.isNotEmpty()) for (a in rev[q.removeFirst()].orEmpty()) if ((hubs || a >= 100 && a < 5000 || a < 0) && seen.add(a)) q += a
        return seen
    }

    /** Botões que, via estado -1, levam o P1 a algum estado de onde se chega ao [target]; mais curtos primeiro. */
    fun routes(book: StateBook, target: Int, hubs: Boolean = false): List<Route> {
        val edges = edges(book)
        val neg = book.states[-1] ?: return emptyList()
        val out = LinkedHashMap<String, Route>()
        for (c in neg.controllers) {
            if (c.type != "changestate" && c.type != "selfstate") continue
            val r = literal(c.param("value")) ?: continue
            val d = dist(edges, listOf(r), target, hubs) ?: continue
            val groups = if (c.triggerGroups.isEmpty()) listOf(emptyList()) else c.triggerGroups
            for (g in groups) {
                val names = (c.triggerAll + g).flatMap { l -> cmdRe.findAll(l).map { it.groupValues[1] }.toList() }.distinct()
                if (names.isEmpty()) continue
                out.getOrPut("$r:${names.sorted()}") { Route(r, names, d) }
            }
        }
        return out.values.sortedBy { it.dist }
    }

    // ------------------------------------------------------------------ gatilhos -> suposições

    private fun splitTop(s: String, op: String): List<String> {
        val out = ArrayList<String>()
        var depth = 0
        var from = 0
        var q = false
        var i = 0
        while (i < s.length) {
            val ch = s[i]
            if (ch == '"') q = !q
            else if (!q) {
                if (ch == '(') depth++ else if (ch == ')') depth--
                else if (depth == 0 && s.startsWith(op, i)) { out += s.substring(from, i); i += op.length; from = i; continue }
            }
            i++
        }
        out += s.substring(from)
        return out.map { it.trim() }.filter { it.isNotEmpty() }
    }

    private fun owner(e: SimEntity, root: String?, hid: String?): String? = when {
        root != null && root.isNotEmpty() -> "root"
        hid != null && hid.isNotEmpty() -> "h$hid"
        e.helperId == null && e.parent == null -> "root"
        e.helperId != null -> "h${e.helperId}"
        else -> null
    }

    /** Uma parte de gatilho que falhou vira um ajuste; null se não dá para consertar com uma variável. */
    private fun fix(e: SimEntity, part: String): Assumption? {
        val p = part.trim()
        bareRe.find(p)?.let { m ->
            val who = owner(e, m.groupValues[2], m.groupValues[3]) ?: return null
            val k = if (m.groupValues[4].lowercase() == "var") 'v' else 'f'
            return Assumption(who, k, m.groupValues[5].toInt(), if (m.groupValues[1] == "!") 0.0 else 1.0)
        }
        atomRe.find(p)?.let { m ->
            val who = owner(e, m.groupValues[1], m.groupValues[2]) ?: return null
            val k = if (m.groupValues[3].lowercase() == "var") 'v' else 'f'
            val n = m.groupValues[6].toDouble()
            val v = when (m.groupValues[5]) { "=", "==", ">=", "<=" -> n; ">" -> n + 1; "<" -> n - 1; else -> n + 1 }
            return Assumption(who, k, m.groupValues[4].toInt(), v)
        }
        powerRe.find(p)?.let { m ->
            val n = m.groupValues[2].toDouble()
            return Assumption("root", 'p', 0, if (m.groupValues[1] == ">") n + 1 else n)
        }
        return null
    }

    private class Diag(val dist: Int, val fails: List<Assumption>)

    /** Olha os controladores que levam ao alvo e diz o que faltaria (o melhor, mais perto do alvo e com menos faltas). */
    private fun diagnose(sim: Sim, e: SimEntity, def: Map<Int, Int>, pressing: Boolean, book: StateBook): Diag? {
        var best: Diag? = null
        val states = if (e === sim.root) listOf(e.state, -3, -2, -1) else listOf(e.state)
        for (sn in states) {
            if (sn == -1 && !pressing) continue
            val d0 = book.states[sn] ?: continue
            for (c in d0.controllers) {
                val t = when (c.type) {
                    "changestate", "selfstate" -> literal(c.param("value"))
                    "helper" -> literal(c.param("stateno"))
                    "hitdef" -> literal(c.param("p1stateno"))
                    else -> null
                } ?: continue
                val dd = def[t] ?: continue
                if (c.triggerGroups.isEmpty()) continue
                val groups = c.triggerGroups
                for (g in groups) {
                    val fails = ArrayList<Assumption>()
                    var ok = true
                    var hopeless = false
                    for (line in c.triggerAll + g) {
                        for (part in splitTop(line, "&&")) {
                            if (sim.truthOf(e, part) == true) continue
                            val a = fix(e, part)
                            when {
                                a != null -> fails += a
                                waitRe.containsMatchIn(part) || part.contains("command", true) -> {} // depende do tempo ou do botão
                                else -> { hopeless = true; ok = false }
                            }
                        }
                    }
                    if (!ok || hopeless || fails.isEmpty()) continue
                    if (best == null || dd < best.dist || (dd == best.dist && fails.size < best.fails.size)) best = Diag(dd, fails)
                }
            }
        }
        return best
    }

    private class Run(val sim: Sim, val reached: Int, val diag: Diag?, val ticks: Int)

    private fun apply(e: SimEntity, a: Assumption) {
        when (a.kind) {
            'v' -> if (a.index in e.vars.indices) e.vars[a.index] = a.value.toInt()
            'f' -> if (a.index in e.fvars.indices) e.fvars[a.index] = a.value
            else -> e.power = a.value.toInt()
        }
    }

    private fun run(
        p1: SimChar, p2: SimChar?, target: Int, helperId: Int?, route: Route?, assumed: List<Assumption>, def: Map<Int, Int>, desc: Set<Int>,
        coordY: Double, seed: Long, spacing: Double, maxTicks: Int, deadline: Long,
    ): Run {
        val sim = Sim(p1.book, p1.anims, runNegative = true, rootName = p1.name, collide = true, consts = p1.consts, coordY = coordY, seed = seed)
        sim.enemyChar = p2
        val root = sim.spawnRoot(-spacing)
        root.ctrl = true
        sim.spawnEnemy(spacing)
        var reached = -1
        var diag: Diag? = null
        var calm = 0
        var idle = 0
        var lastStates: List<Int>? = null
        var lastChange = 0
        var still = 0
        var lastSig: List<Int>? = null
        val pressFrom = System.getenv("PF")?.toInt() ?: 70
        val press = route?.presses?.joinToString("+")
        while (sim.tick < maxTicks && System.currentTimeMillis() < deadline) {
            for (e in sim.entities + sim.pending) for ((i, a) in assumed.withIndex()) {
                val mine = if (a.who == "root") e === root else e.helperId != null && "h${e.helperId}" == a.who
                if (mine) apply(e, a)
            }
            val t = sim.tick
            val pressing = press != null && t >= pressFrom && (t - pressFrom) % 150 < 8
            sim.command = if (pressing) press else null
            val from = sim.frames.size
            sim.step()
            if (reached < 0 && sim.entities.any { it.alive && it.state == target && (if (helperId == null) it === root else it.helperId == helperId) }) reached = t
            if (reached < 0 && (t % 10 == 0 || pressing)) {
                for (e in sim.entities) {
                    if (!e.alive || e === sim.enemy) continue
                    diagnose(sim, e, def, pressing, p1.book)?.let { d ->
                        if (diag == null || d.dist < diag!!.dist || (d.dist == diag!!.dist && d.fails.size < diag!!.fails.size)) diag = d
                    }
                }
            }
            val now = sim.frames.subList(from, sim.frames.size)
            val sig = now.flatMap { listOf(it.uid, it.state, it.anim, it.x.toInt(), it.y.toInt(), it.facing) }
            still = if (sig == lastSig) still + 1 else 0
            lastSig = sig
            val quiet = sim.fx.isEmpty() && sim.entities.none { it.helperId != null && it.alive } &&
                (root.state == 0 || root.ctrl) && root.moveType == "I" && sim.enemy.hitPause <= 0
            calm = if (quiet && t > pressFrom + 10) calm + 1 else 0
            val afterPress = press == null || t > pressFrom + 160
            if (reached < 0 && afterPress && (calm >= 20 || still >= SimRunner.STUCK_TICKS)) break
            if (reached >= 0) {
                // acabou quando o golpe (e o que ele chama) saiu de cena e o adversário se recuperou
                val active = sim.entities.any { it.alive && it !== sim.enemy && (it.state == target || (it.state >= 100 && it.state in desc)) }
                val enemyBusy = sim.enemy.state in 5000..5999 || sim.enemy.hitPause > 0
                idle = if (!active && !enemyBusy) idle + 1 else 0
                val ss = sim.entities.filter { it.alive }.map { it.uid * 100000 + it.state }
                if (ss != lastStates) { lastStates = ss; lastChange = sim.tick }
                if (idle >= 30 || (!enemyBusy && root.state == 0 && sim.tick - lastChange > 150) || still >= SimRunner.STUCK_TICKS || sim.tick - reached > POST_TICKS) break
            }
        }
        return Run(sim, reached, diag, sim.tick)
    }

    /**
     * Tenta chegar ao estado [target] (do P1, ou de um helper de id [helperId]). Devolve a gravação cortada logo antes de o
     * estado começar, com a descrição do cenário; null se nenhum caminho foi achado dentro do orçamento de tempo.
     */
    fun record(
        p1: SimChar, p2: SimChar?, target: Int, helperId: Int? = null, initial: List<Assumption> = emptyList(),
        coordY: Double = 240.0, seed: Long = 12345L, budgetMs: Long = 25_000, maxTicks: Int = 1500, lead: Int = 20,
    ): SimTake? {
        // primeiro só por caminhos "de golpe" (estados 100-4999); se não houver, deixa passar por estados básicos (0, 20...)
        val t0 = System.currentTimeMillis()
        search(p1, p2, target, helperId, initial, coordY, seed, budgetMs, maxTicks, lead, false)?.let { return it }
        val left = minOf(budgetMs - (System.currentTimeMillis() - t0), 6_000L)
        return if (left > 500) search(p1, p2, target, helperId, initial, coordY, seed, left, maxTicks, lead, true) else null
    }

    private fun search(
        p1: SimChar, p2: SimChar?, target: Int, helperId: Int?, initial: List<Assumption>,
        coordY: Double, seed: Long, budgetMs: Long, maxTicks: Int, lead: Int, hubs: Boolean,
    ): SimTake? {
        val book = p1.book
        val edges = edges(book)
        val anc = ancestors(edges, target, hubs)
        val def = HashMap<Int, Int>()
        for (s in anc) dist(edges, listOf(s), target, hubs)?.let { def[s] = it }
        val desc = HashSet<Int>().also { out ->
            // o que o golpe chama logo em seguida (até 3 passos); mais longe já é outra coisa (a Yoka volta a ciclar, etc.)
            var layer = listOf(target); out += target
            repeat(3) { layer = layer.flatMap { edges[it].orEmpty() }.filter { out.add(it) } }
        }
        val deadline = System.currentTimeMillis() + budgetMs
        val candidates: List<Route?> = routes(book, target, hubs).take(if (hubs) 3 else 6) +
            (if (dist(edges, listOf(0, -2, -3), target, hubs) != null) listOf<Route?>(null) else emptyList())
        for (route in candidates) {
            val assumed = ArrayList(initial)
            for (iter in 0 until 8) {
                if (System.currentTimeMillis() >= deadline) return null
                var r = run(p1, p2, target, helperId, route, assumed, def, desc, coordY, seed, 70.0, maxTicks, deadline)
                if (r.reached >= 0) {
                    // golpe que não acertou: tenta de mais perto
                    if (r.sim.attackTried && !r.sim.log.any { it.contains("acerta") }) {
                        for (sp in listOf(50.0, 35.0, 25.0)) {
                            val r2 = run(p1, p2, target, helperId, route, assumed, def, desc, coordY, seed, sp, maxTicks, deadline)
                            if (r2.reached >= 0 && r2.sim.log.any { it.contains("acerta") }) { r = r2; break }
                        }
                    }
                    return build(r, route, assumed, lead, p1, p2)
                }
                val d = r.diag ?: break
                val fresh = d.fails.filter { f -> assumed.none { it.key == f.key && it.value == f.value } }
                if (fresh.isEmpty()) break
                // um ajuste novo para a mesma variável substitui o antigo
                assumed.removeAll { a -> fresh.any { it.key == a.key } }
                assumed += fresh
            }
        }
        return null
    }

    private fun build(r: Run, route: Route?, assumed: List<Assumption>, lead: Int, p1: SimChar, p2: SimChar?): SimTake {
        val sim = r.sim
        val cut = (r.reached - lead).coerceAtLeast(0)
        val frames = sim.frames.filter { it.tick >= cut }.map { it.withTick(it.tick - cut) }
        val sounds = sim.sounds.mapNotNull { sd -> if (sd.tick >= cut) SimRunner.parseSound(sd.text)?.let { TakeSound(sd.tick - cut, it, sd.side) } else null }
        val desc = buildList {
            if (route != null) add(route.presses.joinToString("+"))
            addAll(assumed.map { it.text })
        }.joinToString("; ")
        val still = r.ticks - cut
        return SimTake(frames, still, sounds, sim.log.toList(), true, sim.attackTried, false, desc, cut)
    }
}
