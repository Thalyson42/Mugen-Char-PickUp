package com.ikemenselect.core.sim

import com.ikemenselect.core.Animation
import com.ikemenselect.core.Controller
import com.ikemenselect.core.Expr
import com.ikemenselect.core.ExprContext
import com.ikemenselect.core.StateBook

/**
 * Simulador mínimo do M.U.G.E.N (prova de conceito): executa os estados de verdade, tick a tick, com P1, helpers e um
 * adversário, em vez de adivinhar o resultado lendo o .cns. O acerto do HitDef é forçado (o adversário está sempre ao alcance).
 */
class HitDefData(val p1stateno: Int?, val p2stateno: Int?, val p1facing: Int, val p2facing: Int)

class SimEntity(val uid: Int, val name: String, val helperId: Int?, val parent: SimEntity?, val root: SimEntity?) {
    var state = 0
    var prev = 0
    var time = 0
    var anim = 0
    var animTick = 0

    /** De quem é o conjunto de animações que toca agora (ChangeAnim2 usa o do atacante). */
    var animOwner: SimEntity = this

    /** Quem definiu o estado atual (o alvo roda estados do atacante). */
    var stateOwner: SimEntity = this
    val vars = IntArray(60)
    val fvars = DoubleArray(40)
    var x = 0.0
    var y = 0.0
    var vx = 0.0
    var vy = 0.0
    var facing = 1
    var ctrl = false
    var moveType = "I"
    var stateType = "S"
    var sprPriority = 0
    var alive = true
    var fresh = true
    var hitDef: HitDefData? = null
    var hitDone = false
    var moveHit = false
    val targets = ArrayList<SimEntity>()
    var bindTo: SimEntity? = null
    var bindDx = 0.0
    var bindDy = 0.0
    var bindLeft = 0
    val persist = HashMap<Controller, Int>()
    var posFrozen = false
    val isHelper: Boolean get() = parent != null
}

/** Efeito vivo que não roda estados: um Explod (kind "explod") ou um projétil (kind "proj"). */
class SimFx(
    val kind: String, val id: Int, val owner: SimEntity, var anim: Int,
    var x: Double, var y: Double, var vx: Double, var vy: Double, var facing: Int,
) {
    var left = -1            // ticks restantes (-1 = sem limite, -2 = até a animação acabar)
    var bindLeft = 0
    var bindDx = 0.0
    var bindDy = 0.0
    var animTick = 0
    var prio = 0
    var hit: HitDefData? = null
    var alive = true
}

class SimFrame(
    val tick: Int, val who: String, val state: Int, val anim: Int, val animFrom: String,
    val x: Double, val y: Double, val facing: Int, val prio: Int,
)

class Sim(
    val book: StateBook,
    val anims: (Int) -> Animation?,
    /** Comando "apertado" (para `command = "nome"`); null = nenhum. */
    var command: String? = null, // nomes separados por '+' quando vários são apertados juntos
    /** Roda os estados -3, -2 e -1 do P1 a cada tick (como o jogo): é por eles que o comando vira golpe e que os helpers nascem. */
    val runNegative: Boolean = false,
    /** Nome do personagem (`name = "..."` nos gatilhos dos helpers). */
    val rootName: String = "P1",
    /** true = o HitDef só acerta quando as caixas Clsn1 do atacante tocam as Clsn2 do alvo; false = acerto forçado. */
    val collide: Boolean = false,
    /** Constantes do personagem (`size.xscale`, `movement.yaccel`...), de [parseConsts]. */
    val consts: Map<String, Double> = emptyMap(),
    /** Altura da coordenada local do personagem (para `const720p` etc.). */
    val coordY: Double = 240.0,
) {
    val entities = ArrayList<SimEntity>()
    private val pending = ArrayList<SimEntity>()
    lateinit var root: SimEntity
    lateinit var enemy: SimEntity
    var tick = 0
    val log = ArrayList<String>()
    val unknown = LinkedHashSet<String>()
    val ignoredTypes = LinkedHashSet<String>()
    val frames = ArrayList<SimFrame>()
    val sounds = ArrayList<Pair<Int, String>>()
    /** Explods e projéteis vivos. */
    val fx = ArrayList<SimFx>()

    /** Aceleração vertical da gravidade (padrão do M.U.G.E.N quando o personagem não diz outra). */
    var yaccel = consts["movement.yaccel"] ?: 0.44
    private var pauseLeft = 0
    private var pauseMoveLeft = 0
    private var pauser: SimEntity? = null
    private var rnd = 12345L
    private var uids = 0

    fun random(): Int {
        rnd = (rnd * 1103515245L + 12345L) and 0x7fffffffL
        return ((rnd shr 8) % 1000L).toInt()
    }

    // ------------------------------------------------------------------ montagem

    fun spawnRoot(x: Double, state: Int = 0): SimEntity {
        val e = SimEntity(uids++, "P1", null, null, null)
        e.x = x
        e.facing = 1
        entities += e
        root = e
        enter(e, state, e)
        return e
    }

    fun spawnEnemy(x: Double): SimEntity {
        val e = SimEntity(uids++, "P2", null, null, null)
        e.x = x
        e.facing = -1
        entities += e
        enemy = e
        enter(e, 0, e)
        return e
    }

    fun spawnHelper(creator: SimEntity, id: Int, name: String, state: Int, dx: Double, dy: Double, facingParam: Int): SimEntity {
        val r = creator.root ?: creator
        val h = SimEntity(uids++, name, id, creator, r)
        h.x = creator.x + creator.facing * dx
        h.y = creator.y + dy
        h.facing = if (facingParam < 0) -creator.facing else creator.facing
        pending += h
        enter(h, state, h)
        log("t=$tick ${creator.name} cria helper $id ($name) no estado $state")
        return h
    }

    private fun log(s: String) { log += s }

    private val cmdTest = Regex("""command\s*(!?=)\s*"([^"]*)"""", RegexOption.IGNORE_CASE)

    /** `command = "x"` vira 1/0 conforme os comandos apertados agora (vários ao mesmo tempo). */
    private fun prep(text: String): String {
        if (!text.contains("command", ignoreCase = true)) return text
        val on = command?.split('+')?.map { it.trim().lowercase() }?.toSet().orEmpty()
        return cmdTest.replace(text) { m ->
            val has = m.groupValues[2].lowercase() in on
            if ((m.groupValues[1] == "=") == has) "1" else "0"
        }
    }

    private fun ev(text: String, ctx: Ctx): Any? = Expr.eval(prep(text), ctx)

    /** Depuração: avalia uma expressão no contexto de [e] (null = não deu para saber). */
    fun debugEval(e: SimEntity, text: String): Any? = Expr.eval(prep(text), Ctx(e))

    // ------------------------------------------------------------------ estados

    private fun enter(e: SimEntity, no: Int, owner: SimEntity) {
        e.prev = e.state
        e.state = no
        e.time = 0
        e.animTick = 0
        e.fresh = true
        e.stateOwner = owner
        e.persist.clear()
        e.hitDef = null
        e.hitDone = false
        e.moveHit = false
        val def = book.states[no] ?: return
        val ctx = Ctx(e)
        def.props["type"]?.trim()?.firstOrNull()?.uppercaseChar()?.let { if (it != 'U') e.stateType = it.toString() }
        def.props["movetype"]?.trim()?.firstOrNull()?.uppercaseChar()?.let { if (it != 'U') e.moveType = it.toString() }
        def.props["ctrl"]?.let { s -> (Expr.eval(s, ctx) as? Double)?.let { e.ctrl = it != 0.0 } }
        def.props["sprpriority"]?.let { s -> (Expr.eval(s, ctx) as? Double)?.let { e.sprPriority = it.toInt() } }
        def.props["velset"]?.let { s ->
            val p = splitTop(s)
            (p.getOrNull(0)?.let { Expr.eval(it, ctx) } as? Double)?.let { e.vx = it }
            (p.getOrNull(1)?.let { Expr.eval(it, ctx) } as? Double)?.let { e.vy = it }
        }
        def.props["anim"]?.let { s ->
            (Expr.eval(s, ctx) as? Double)?.let { e.anim = it.toInt(); e.animOwner = e }
        }
    }

    // ------------------------------------------------------------------ passo

    fun step() {
        val paused = pauseLeft > 0
        val pauserMoves = paused && pauseMoveLeft > 0
        if (paused) { pauseLeft--; if (pauseMoveLeft > 0) pauseMoveLeft-- }
        for (e in entities.toList()) {
            if (!e.alive) continue
            if (paused && !(e === pauser && pauserMoves)) continue
            if (e.fresh) e.fresh = false else { e.time++; e.animTick++ }
            if (e.bindLeft > 0) {
                e.bindTo?.let { b -> e.x = b.x + b.facing * e.bindDx; e.y = b.y + e.bindDy }
                e.bindLeft--
            }
            e.posFrozen = false
            if (runNegative && e === root) runNegative(e)
            runState(e)
            if (!e.posFrozen) { e.x += e.vx * e.facing; e.y += e.vy }
        }
        if (!paused) stepFx()
        resolveHits()
        entities.removeAll { !it.alive }
        entities += pending
        pending.clear()
        for (e in entities) {
            frames += SimFrame(tick, e.name, e.state, e.anim, e.animOwner.name, e.x, e.y, e.facing, e.sprPriority)
        }
        for (f in fx) frames += SimFrame(tick, "${f.kind}#${f.id}", 0, f.anim, f.owner.name, f.x, f.y, f.facing, f.prio)
        tick++
    }

    private fun stepFx() {
        for (f in fx.toList()) {
            if (!f.alive) continue
            f.animTick++
            if (f.bindLeft != 0) {
                f.x = f.owner.x + f.owner.facing * f.bindDx
                f.y = f.owner.y + f.bindDy
                if (f.bindLeft > 0) f.bindLeft--
            }
            f.x += f.vx * f.facing
            f.y += f.vy
            when {
                f.left > 0 -> if (--f.left == 0) f.alive = false
                f.left == -2 -> anims(f.anim)?.let { an -> if (!an.infinite && f.animTick >= an.totalTicks) f.alive = false }
            }
            if (f.kind == "proj" && Math.abs(f.x - f.owner.x) > 1200) f.alive = false // saiu da tela
        }
        // projéteis: acerto no adversário
        for (f in fx.toList()) {
            val hd = f.hit ?: continue
            if (!f.alive || f.kind != "proj") continue
            val d = enemyOf(f.owner)
            if (collide && !projOverlaps(f, d)) continue
            log("t=$tick projétil ${f.id} de ${f.owner.name} acerta ${d.name} (p2=${hd.p2stateno} p2facing=${hd.p2facing})")
            if (d !in f.owner.targets) f.owner.targets += d
            if (hd.p2facing != 0) d.facing = if (hd.p2facing > 0) f.facing else -f.facing
            hd.p2stateno?.takeIf { it >= 0 }?.let { enter(d, it, f.owner) }
            f.alive = false
        }
        fx.removeAll { !it.alive }
    }

    private fun projOverlaps(f: SimFx, d: SimEntity): Boolean {
        val an = anims(f.anim) ?: return false
        val fr = an.frames.getOrNull(an.frameIndexAt(f.animTick, loop = true)) ?: return false
        val atk = fr.clsn1.map { b ->
            val xa = f.x + f.facing * b.x1; val xb = f.x + f.facing * b.x2
            doubleArrayOf(minOf(xa, xb), f.y + b.y1, maxOf(xa, xb), f.y + b.y2)
        }
        val body = worldBoxes(d, frameOf(d)?.clsn2.orEmpty())
        return atk.any { p -> body.any { q -> p[0] <= q[2] && q[0] <= p[2] && p[1] <= q[3] && q[1] <= p[3] } }
    }

    fun run(ticks: Int) { repeat(ticks) { step() } }

    private fun runNegative(e: SimEntity) {
        for (no in intArrayOf(-3, -2, -1)) {
            val def = book.states[no] ?: continue
            val before = e.state
            for (c in def.controllers.toList()) {
                if (!e.alive) return
                if (!fires(e, c)) continue
                exec(e, c)
                if (e.state != before) break // ChangeState: o resto deste Statedef não roda
            }
        }
    }

    private fun runState(e: SimEntity) {
        var guard = 0
        var changed: Boolean
        do {
            changed = false
            val def = book.states[e.state] ?: return
            val stateBefore = e.state
            for (c in def.controllers.toList()) {
                if (!e.alive) return
                if (!fires(e, c)) continue
                exec(e, c)
                if (e.state != stateBefore || (e.fresh && e.time == 0 && e.state != stateBefore)) { changed = true; break }
            }
        } while (changed && ++guard < 40)
    }

    private fun fires(e: SimEntity, c: Controller): Boolean {
        val ctx = Ctx(e)
        for (t in c.triggerAll) if (Expr.truth(prep(t), ctx) != true) return false
        if (c.triggerGroups.isEmpty()) return false
        if (c.triggerGroups.none { g -> g.all { Expr.truth(prep(it), ctx) == true } }) return false
        val pers = c.param("persistent")?.trim()?.toIntOrNull() ?: 1
        if (pers == 0) {
            if (e.persist.containsKey(c)) return false
            e.persist[c] = 1
        } else if (pers > 1) {
            val n = e.persist[c] ?: 0
            e.persist[c] = (n + 1) % pers
            if (n != 0) return false
        }
        return true
    }

    // ------------------------------------------------------------------ controladores

    private fun num(c: Controller, key: String, ctx: Ctx): Double? = c.param(key)?.let { Expr.eval(it, ctx) as? Double }

    private fun pair(c: Controller, key: String, ctx: Ctx): Pair<Double?, Double?> {
        val p = splitTop(c.param(key) ?: return null to null)
        return (p.getOrNull(0)?.let { Expr.eval(it, ctx) } as? Double) to (p.getOrNull(1)?.let { Expr.eval(it, ctx) } as? Double)
    }

    private fun exec(e: SimEntity, c: Controller) {
        val ctx = Ctx(e)
        when (c.type) {
            "changestate", "selfstate" -> {
                val n = num(c, "value", ctx)?.toInt() ?: return
                log("t=$tick ${e.name} ${c.type} ${e.state} -> $n")
                enter(e, n, if (c.type == "selfstate") e else e.stateOwner)
                num(c, "ctrl", ctx)?.let { e.ctrl = it != 0.0 }
                num(c, "anim", ctx)?.let { e.anim = it.toInt(); e.animTick = 0; e.animOwner = e }
            }
            "changeanim" -> num(c, "value", ctx)?.let { e.anim = it.toInt(); e.animTick = 0; e.animOwner = e }
            "changeanim2" -> num(c, "value", ctx)?.let { e.anim = it.toInt(); e.animTick = 0; e.animOwner = e.stateOwner }
            "varset", "varadd", "parentvarset", "rootvarset" -> varOp(e, c, ctx)
            "varrandom" -> {
                val idx = num(c, "v", ctx)?.toInt() ?: return
                val r = splitTop(c.param("range") ?: "0,1000")
                val lo = (r.getOrNull(0)?.let { Expr.eval(it, ctx) } as? Double)?.toInt() ?: 0
                val hi = (r.getOrNull(1)?.let { Expr.eval(it, ctx) } as? Double)?.toInt() ?: 1000
                if (idx in 0..59 && hi >= lo) e.vars[idx] = lo + random() % (hi - lo + 1)
            }
            "posset" -> { val (x, y) = pair(c, "pos", ctx).let { _ -> num(c, "x", ctx) to num(c, "y", ctx) }; x?.let { e.x = it }; y?.let { e.y = it } }
            "posadd" -> { num(c, "x", ctx)?.let { e.x += it * e.facing }; num(c, "y", ctx)?.let { e.y += it } }
            "velset" -> { num(c, "x", ctx)?.let { e.vx = it }; num(c, "y", ctx)?.let { e.vy = it } }
            "veladd" -> { num(c, "x", ctx)?.let { e.vx += it }; num(c, "y", ctx)?.let { e.vy += it } }
            "ctrlset" -> num(c, "value", ctx)?.let { e.ctrl = it != 0.0 }
            "statetypeset" -> {
                c.param("statetype")?.trim()?.firstOrNull()?.uppercaseChar()?.let { if (it != 'U') e.stateType = it.toString() }
                c.param("movetype")?.trim()?.firstOrNull()?.uppercaseChar()?.let { if (it != 'U') e.moveType = it.toString() }
            }
            "velmul" -> { num(c, "x", ctx)?.let { e.vx *= it }; num(c, "y", ctx)?.let { e.vy *= it } }
            "turn" -> e.facing = -e.facing
            "destroyself" -> e.alive = false
            "helper" -> {
                val id = num(c, "id", ctx)?.toInt() ?: 0
                val st = num(c, "stateno", ctx)?.toInt() ?: 0
                val (px, py) = pair(c, "pos", ctx)
                val nm = c.param("name")?.trim()?.trim('"')?.ifEmpty { null } ?: "helper$id"
                spawnHelper(e, id, nm, st, px ?: 0.0, py ?: 0.0, num(c, "facing", ctx)?.toInt() ?: 1)
            }
            "hitdef" -> {
                if (e.moveHit || e.hitDone) return
                e.hitDef = HitDefData(
                    num(c, "p1stateno", ctx)?.toInt(), num(c, "p2stateno", ctx)?.toInt(),
                    num(c, "p1facing", ctx)?.toInt() ?: 0, num(c, "p2facing", ctx)?.toInt() ?: 0,
                )
            }
            "targetstate" -> {
                val n = num(c, "value", ctx)?.toInt() ?: return
                for (t in e.targets) if (t.alive) {
                    log("t=$tick ${e.name} manda ${t.name} para o estado $n")
                    enter(t, n, e)
                }
            }
            "targetbind" -> {
                val (dx, dy) = pair(c, "pos", ctx)
                val time = num(c, "time", ctx)?.toInt() ?: 1
                for (t in e.targets) if (t.alive) { t.bindTo = e; t.bindDx = dx ?: 0.0; t.bindDy = dy ?: 0.0; t.bindLeft = time; t.x = e.x + e.facing * (dx ?: 0.0); t.y = e.y + (dy ?: 0.0) }
            }
            "targetfacing" -> {
                val v = num(c, "value", ctx)?.toInt() ?: return
                for (t in e.targets) if (t.alive) t.facing = if (v > 0) e.facing else if (v < 0) -e.facing else t.facing
            }
            "playsnd" -> c.param("value")?.let { sounds += tick to it.trim() }
            "bindtoparent", "bindtoroot", "bindtotarget" -> {
                val to = when (c.type) {
                    "bindtoparent" -> e.parent
                    "bindtoroot" -> e.root
                    else -> e.targets.firstOrNull { it.alive }
                } ?: return
                val (dx, dy) = pair(c, "pos", ctx)
                e.bindTo = to; e.bindDx = dx ?: 0.0; e.bindDy = dy ?: 0.0
                e.bindLeft = num(c, "time", ctx)?.toInt() ?: 1
                e.x = to.x + to.facing * e.bindDx; e.y = to.y + e.bindDy
                num(c, "facing", ctx)?.toInt()?.let { if (it > 0) e.facing = to.facing else if (it < 0) e.facing = -to.facing }
            }
            "pause", "superpause" -> {
                pauseLeft = num(c, "time", ctx)?.toInt()?.coerceIn(0, 300) ?: 30
                pauseMoveLeft = num(c, "movetime", ctx)?.toInt()?.coerceAtLeast(0) ?: 0
                pauser = e
                log("t=$tick ${e.name} ${c.type} por $pauseLeft ticks")
            }
            "posfreeze" -> if ((num(c, "value", ctx) ?: 1.0) != 0.0) e.posFrozen = true
            "gravity" -> e.vy += yaccel
            "sprpriority" -> num(c, "value", ctx)?.let { e.sprPriority = it.toInt() }
            "movehitreset" -> { e.moveHit = false; e.hitDone = false }
            "targetvelset" -> for (t in e.targets) if (t.alive) { num(c, "x", ctx)?.let { t.vx = it }; num(c, "y", ctx)?.let { t.vy = it } }
            "targetveladd" -> for (t in e.targets) if (t.alive) { num(c, "x", ctx)?.let { t.vx += it }; num(c, "y", ctx)?.let { t.vy += it } }
            "explod" -> makeExplod(e, c, ctx)
            "modifyexplod" -> modifyExplod(e, c, ctx)
            "removeexplod" -> {
                val id = num(c, "id", ctx)?.toInt()
                for (f in fx) if (f.kind == "explod" && f.owner === (e.root ?: e) && (id == null || f.id == id)) f.alive = false
            }
            "projectile" -> makeProjectile(e, c, ctx)
            in noEffect -> {}
            else -> ignoredTypes += c.type
        }
    }

    /** Controladores que não mudam nada do que o simulador acompanha (visual, vida, poder, som...). */
    private val noEffect = setOf(
        "null", "screenbound", "width", "hitby", "nothitby", "palfx", "allpalfx", "bgpalfx", "afterimage", "afterimagetime",
        "envcolor", "envshake", "fallenvshake", "displaytoclipboard", "appendtoclipboard", "debug", "trans", "offset",
        "forcefeedback", "lifeadd", "lifeset", "poweradd", "powerset", "targetlifeadd", "targetpoweradd", "defencemulset",
        "hitfalldamage", "hitfallset", "hitfallvel", "makedust", "gamemakeanim", "sndpan", "stopsnd", "assertspecial",
        "attackdist", "attackmulset", "fallrecover", "hitadd", "reversaldef", "playerpush", "targetdrop", "angledraw",
        "angleadd", "angleset", "anglemul", "text", "mapset", "modifybgctrl", "bgpalfx",
    )

    private val capFx = 200

    private fun parseAnimId(text: String?, ctx: Ctx): Int? {
        val t = text?.trim() ?: return null
        if (t.startsWith("f", ignoreCase = true) && t.drop(1).all { it.isDigit() } && t.length > 1) return null // animação do fightfx
        return (Expr.eval(t, ctx) as? Double)?.toInt()
    }

    private fun makeExplod(e: SimEntity, c: Controller, ctx: Ctx) {
        if (fx.size >= capFx) return
        val owner = e.root ?: e
        val id = num(c, "id", ctx)?.toInt() ?: -1
        val anim = parseAnimId(c.param("anim"), ctx) ?: return
        val (px, py) = pair(c, "pos", ctx)
        val (vx, vy) = pair(c, "vel", ctx)
        val postype = c.param("postype")?.trim()?.lowercase() ?: "p1"
        val base = if (postype == "p2") enemyOf(e) else e
        val facingParam = num(c, "facing", ctx)?.toInt() ?: 1
        val f = SimFx("explod", id, owner, anim, base.x + base.facing * (px ?: 0.0), base.y + (py ?: 0.0), vx ?: 0.0, vy ?: 0.0,
            if (facingParam < 0) -base.facing else base.facing)
        val rt = num(c, "removetime", ctx)?.toInt() ?: -2
        f.left = rt
        val bt = num(c, "bindtime", ctx)?.toInt() ?: 1
        if (bt != 0 && postype != "left" && postype != "right") { f.bindLeft = bt; f.bindDx = px ?: 0.0; f.bindDy = py ?: 0.0 }
        f.prio = num(c, "sprpriority", ctx)?.toInt() ?: 0
        fx += f
    }

    private fun modifyExplod(e: SimEntity, c: Controller, ctx: Ctx) {
        val id = num(c, "id", ctx)?.toInt()
        val owner = e.root ?: e
        val (px, py) = pair(c, "pos", ctx)
        for (f in fx) if (f.kind == "explod" && f.owner === owner && (id == null || f.id == id)) {
            parseAnimId(c.param("anim"), ctx)?.let { f.anim = it; f.animTick = 0 }
            px?.let { f.x = owner.x + owner.facing * it; f.bindDx = it }
            py?.let { f.y = owner.y + it; f.bindDy = it }
            num(c, "removetime", ctx)?.let { f.left = it.toInt() }
            num(c, "bindtime", ctx)?.let { f.bindLeft = it.toInt() }
        }
    }

    private fun makeProjectile(e: SimEntity, c: Controller, ctx: Ctx) {
        if (fx.size >= capFx) return
        val owner = e.root ?: e
        val id = num(c, "projid", ctx)?.toInt() ?: 0
        val anim = num(c, "projanim", ctx)?.toInt() ?: 0
        val (ox, oy) = pair(c, "offset", ctx)
        val (vx, vy) = pair(c, "velocity", ctx)
        val f = SimFx("proj", id, owner, anim, e.x + e.facing * (ox ?: 0.0), e.y + (oy ?: 0.0), vx ?: 0.0, vy ?: 0.0, e.facing)
        f.left = num(c, "projremovetime", ctx)?.toInt() ?: -1
        f.prio = num(c, "projsprpriority", ctx)?.toInt() ?: 3
        f.hit = HitDefData(
            num(c, "p1stateno", ctx)?.toInt(), num(c, "p2stateno", ctx)?.toInt(),
            num(c, "p1facing", ctx)?.toInt() ?: 0, num(c, "p2facing", ctx)?.toInt() ?: 0,
        )
        fx += f
        log("t=$tick ${e.name} dispara projétil $id (anim $anim)")
    }

    private fun varOp(e: SimEntity, c: Controller, ctx: Ctx) {
        val target = when (c.type) {
            "parentvarset" -> e.parent ?: return
            "rootvarset" -> e.root ?: e
            else -> e
        }
        val add = c.type == "varadd"
        val re = Regex("""^(var|fvar|sysvar|sysfvar)\((.+)\)$""")
        for ((k, v) in c.entries) {
            val m = re.find(k.trim())
            if (m != null) {
                val idx = (Expr.eval(m.groupValues[2], ctx) as? Double)?.toInt() ?: continue
                val value = Expr.eval(v, ctx) as? Double ?: continue
                setVar(target, m.groupValues[1], idx, value, add)
            }
        }
        val vIdx = num(c, "v", ctx)?.toInt()
        val fvIdx = num(c, "fv", ctx)?.toInt()
        val value = num(c, "value", ctx)
        if (value != null) {
            if (vIdx != null) setVar(target, "var", vIdx, value, add)
            if (fvIdx != null) setVar(target, "fvar", fvIdx, value, add)
        }
    }

    private fun setVar(t: SimEntity, kind: String, idx: Int, value: Double, add: Boolean) {
        when (kind) {
            "var" -> if (idx in 0..59) t.vars[idx] = (if (add) t.vars[idx] + value.toInt() else value.toInt())
            "fvar" -> if (idx in 0..39) t.fvars[idx] = (if (add) t.fvars[idx] + value else value)
        }
    }

    // ------------------------------------------------------------------ acerto forçado

    private fun frameOf(e: SimEntity) = anims(e.anim)?.let { an -> an.frames.getOrNull(an.frameIndexAt(e.animTick, loop = true)) }

    /** Caixas no mundo: o eixo x espelha com a direção; y cresce para baixo (chão = 0). */
    private fun worldBoxes(e: SimEntity, boxes: List<com.ikemenselect.core.Box>): List<DoubleArray> = boxes.map { b ->
        val xa = e.x + e.facing * b.x1
        val xb = e.x + e.facing * b.x2
        doubleArrayOf(minOf(xa, xb), e.y + b.y1, maxOf(xa, xb), e.y + b.y2)
    }

    private fun overlaps(a: SimEntity, d: SimEntity): Boolean {
        val atk = worldBoxes(a, frameOf(a)?.clsn1.orEmpty())
        val body = worldBoxes(d, frameOf(d)?.clsn2.orEmpty())
        return atk.any { p -> body.any { q -> p[0] <= q[2] && q[0] <= p[2] && p[1] <= q[3] && q[1] <= p[3] } }
    }

    private fun enemyOf(a: SimEntity): SimEntity = if (a === enemy || a.root === enemy) root else enemy

    private fun resolveHits() {
        for (a in entities.toList()) {
            val hd = a.hitDef ?: continue
            if (!a.alive || a.hitDone) continue
            val d0 = enemyOf(a)
            if (collide && !overlaps(a, d0)) continue
            a.hitDone = true
            a.moveHit = true
            val d = enemyOf(a)
            if (d !in a.targets) a.targets += d
            log("t=$tick ${a.name} acerta ${d.name} (HitDef p1=${hd.p1stateno} p2=${hd.p2stateno} p2facing=${hd.p2facing})")
            if (hd.p2facing != 0) d.facing = if (hd.p2facing > 0) a.facing else -a.facing
            if (hd.p1facing < 0) a.facing = -a.facing
            hd.p2stateno?.takeIf { it >= 0 }?.let { enter(d, it, a) }
            hd.p1stateno?.takeIf { it >= 0 }?.let { enter(a, it, a); a.moveHit = true; a.hitDone = true }
        }
    }

    // ------------------------------------------------------------------ expressões

    private inner class Ctx(val e: SimEntity) : ExprContext {
        override fun redirect(name: String, args: List<Any?>?): ExprContext? = when (name) {
            "root" -> Ctx(e.root ?: e)
            "parent" -> e.parent?.let { Ctx(it) }
            "target" -> e.targets.firstOrNull { it.alive }?.let { Ctx(it) }
            "enemy", "enemynear" -> Ctx(enemyOf(e))
            "helper" -> {
                val id = (args?.getOrNull(0) as? Double)?.toInt()
                val r = e.root ?: e
                entities.firstOrNull { it.alive && it.root === r && (id == null || it.helperId == id) }?.let { Ctx(it) }
            }
            else -> null
        }

        private fun b(v: Boolean) = if (v) 1.0 else 0.0

        override fun ident(name: String, args: List<Any?>?): Any? {
            val a0 = (args?.getOrNull(0) as? Double)
            val cur = anims(e.anim)
            val elapsed = e.animTick
            return when (name) {
                "a", "s", "c", "l", "i", "h", "u", "n", "f", "p", "d" -> if (args == null) name.uppercase() else unk(name)
                "time", "statetime" -> e.time.toDouble()
                "stateno" -> e.state.toDouble()
                "prevstateno" -> e.prev.toDouble()
                "anim" -> e.anim.toDouble()
                "animtime" -> when {
                    cur == null -> null
                    cur.infinite -> -1.0
                    else -> (elapsed - (cur.totalTicks - 1)).toDouble()
                }
                "animelemtime" -> {
                    val n = a0?.toInt()
                    if (cur == null || n == null || n < 1 || n > cur.frames.size) null else (elapsed - cur.frameStart(n - 1)).toDouble()
                }
                "animelemno" -> cur?.let { (it.frameIndexAt(elapsed, loop = true) + 1).toDouble() }
                "animexist", "selfanimexist" -> a0?.let { b(anims(it.toInt()) != null) }
                "var" -> a0?.toInt()?.let { if (it in 0..59) e.vars[it].toDouble() else 0.0 }
                "fvar" -> a0?.toInt()?.let { if (it in 0..39) e.fvars[it] else 0.0 }
                "sysvar", "sysfvar" -> 0.0
                "pos.x" -> e.x
                "pos.y" -> e.y
                "vel.x" -> e.vx
                "vel.y" -> e.vy
                "facing" -> e.facing.toDouble()
                "ctrl" -> b(e.ctrl)
                "alive" -> 1.0
                "life", "lifemax" -> 1000.0
                "power" -> 0.0
                "powermax" -> 3000.0
                "movetype" -> e.moveType
                "statetype" -> e.stateType
                "movehit", "movecontact" -> b(e.moveHit)
                "moveguarded", "movereversed", "hitfall", "hitcount", "uniqhitcount", "hitpausetime", "inguarddist" -> 0.0
                "numtarget" -> e.targets.count { it.alive }.toDouble()
                "numhelper" -> {
                    val r = e.root ?: e
                    entities.count { it.alive && it.root === r && (a0 == null || it.helperId == a0.toInt()) }.toDouble()
                }
                "numexplod" -> fx.count { it.alive && it.kind == "explod" && it.owner === (e.root ?: e) && (a0 == null || it.id == a0.toInt()) }.toDouble()
                "numproj" -> fx.count { it.alive && it.kind == "proj" && it.owner === (e.root ?: e) }.toDouble()
                "numprojid" -> fx.count { it.alive && it.kind == "proj" && it.owner === (e.root ?: e) && it.id == a0?.toInt() }.toDouble()
                "ailevel", "isbound", "aicontrol", "win", "lose", "drawgame", "matchover", "hitover" -> if (name == "hitover") 1.0 else 0.0
                "numenemy", "numpartner" -> 1.0
                "roundno", "matchno" -> 1.0
                "gethitvar" -> a0 ?: 0.0
                "damage", "hittime", "hitshaketime", "slidetime", "ctrltime", "recovertime", "xoff", "yoff", "xvel", "yvel", "yaccel",
                "hitid", "chainid", "guarded", "fall", "fall.damage", "fall.xvel", "fall.yvel", "fall.recover", "fall.time",
                "fall.recovertime", "animtype", "groundtype", "airtype", "fallcount", "isbound" -> 0.0
                "ishelper" -> if (a0 == null) b(e.isHelper) else b(e.helperId == a0.toInt())
                "command" -> command
                "name" -> if (e === root) rootName else e.name
                "random" -> random().toDouble()
                "roundstate" -> 2.0
                "gametime" -> tick.toDouble()
                "teamside" -> if (e === enemy) 2.0 else 1.0
                "const" -> a0 ?: 1.0
                "cond", "ifelse" -> (args?.getOrNull(0) as? Double)?.let { if (it != 0.0) args.getOrNull(1) else args.getOrNull(2) }
                "abs" -> a0?.let { Math.abs(it) }
                "floor" -> a0?.let { Math.floor(it) }
                "ceil" -> a0?.let { Math.ceil(it) }
                "sin" -> a0?.let { Math.sin(it) }
                "tan" -> a0?.let { Math.tan(it) }
                "asin" -> a0?.let { Math.asin(it) }
                "acos" -> a0?.let { Math.acos(it) }
                "atan" -> a0?.let { Math.atan(it) }
                "ln" -> a0?.let { Math.log(it) }
                "exp" -> a0?.let { Math.exp(it) }
                "log" -> (args?.getOrNull(1) as? Double)?.let { x -> a0?.let { Math.log(x) / Math.log(it) } }
                "pi" -> Math.PI
                "id" -> e.uid.toDouble()
                "animelem" -> cur?.let {
                    val i = it.frameIndexAt(elapsed, loop = true)
                    val total = it.totalTicks
                    val t = if (it.infinite || total <= 0) elapsed else elapsed % total
                    if (i >= 0 && it.frameStart(i) == t) (i + 1).toDouble() else -1.0
                }
                "parentdist.x" -> e.parent?.let { (it.x - e.x) * e.facing }
                "parentdist.y" -> e.parent?.let { it.y - e.y }
                "rootdist.x" -> (e.root ?: e).let { (it.x - e.x) * e.facing }
                "rootdist.y" -> (e.root ?: e).let { it.y - e.y }
                "const720p" -> a0?.let { it * coordY / 720.0 }
                "const480p" -> a0?.let { it * coordY / 480.0 }
                "const240p" -> a0?.let { it * coordY / 240.0 }
                "const1080p" -> a0?.let { it * coordY / 1080.0 }
                "cos" -> a0?.let { Math.cos(it) }
                "p2dist.x", "p2bodydist.x", "enemydist.x", "enemybodydist.x" -> (enemyOf(e).x - e.x) * e.facing
                "p2dist.y", "p2bodydist.y", "enemydist.y", "enemybodydist.y" -> enemyOf(e).y - e.y
                "p2stateno" -> enemyOf(e).state.toDouble()
                "p2statetype" -> enemyOf(e).stateType
                "p2movetype" -> enemyOf(e).moveType
                "playeridexist" -> 0.0
                "screenheight" -> 240.0
                "screenwidth" -> 320.0
                "backedgebodydist", "frontedgebodydist", "backedgedist", "frontedgedist" -> 100.0
                else -> consts[name] ?: defaultConsts[name] ?: if (name.startsWith("size.")) 1.0 else unk(name)
            }
        }

        private fun unk(name: String): Any? {
            unknown += name
            return null
        }
    }

    companion object {
        /** Valores padrão do M.U.G.E.N/Ikemen GO para constantes que o personagem não define (vêm do common1). */
        val defaultConsts = mapOf(
            "movement.stand.friction.threshold" to 2.0,
            "movement.crouch.friction.threshold" to 0.05,
            "movement.air.gethit.groundlevel" to 25.0,
            "movement.air.gethit.groundrecover.ground.threshold" to -20.0,
            "movement.air.gethit.groundrecover.groundlevel" to 10.0,
            "movement.air.gethit.airrecover.threshold" to -1.0,
            "movement.air.gethit.airrecover.yaccel" to 0.35,
            "movement.down.bounce.offset.x" to 0.0, "movement.down.bounce.offset.y" to 20.0,
            "movement.down.bounce.yaccel" to 0.4, "movement.down.bounce.groundlevel" to 12.0,
            "movement.down.friction.threshold" to 0.05,
        )

        /**
         * Lê as constantes de [Data], [Size], [Velocity], [Movement] e [Quotes] dos .cns: `size.xscale`, `movement.yaccel`...
         * Valores com vírgula viram `nome` (primeiro), `nome.x` e `nome.y`.
         */
        fun parseConsts(texts: List<String>): Map<String, Double> {
            val out = HashMap<String, Double>()
            for (text in texts) for (sec in com.ikemenselect.core.Ini.parse(text)) {
                val sn = sec.name.trim().lowercase()
                if (sn !in setOf("data", "size", "velocity", "movement")) continue
                for ((k, v) in sec.entries) {
                    val key = "$sn.${k.trim().lowercase()}"
                    val parts = v.split(',').map { it.trim().toDoubleOrNull() }
                    parts.getOrNull(0)?.let { out[key] = it }
                    parts.getOrNull(0)?.let { out["$key.x"] = it }
                    parts.getOrNull(1)?.let { out["$key.y"] = it }
                }
            }
            return out
        }

        /** Divide por vírgulas fora de parênteses. */
        fun splitTop(s: String): List<String> {
            val out = ArrayList<String>()
            var depth = 0
            var from = 0
            for ((i, ch) in s.withIndex()) {
                if (ch == '(') depth++ else if (ch == ')') depth-- else if (ch == ',' && depth == 0) { out += s.substring(from, i); from = i + 1 }
            }
            out += s.substring(from)
            return out
        }

        /**
         * Luta real a partir de um comando: o P1 começa parado (estado 0), o comando [cmd] é apertado de [pressFrom] a
         * [pressUntil] e os estados -3/-2/-1 decidem o que acontece. O adversário fica a [enemyDist] de distância.
         */
        fun fromCommand(
            book: StateBook, anims: (Int) -> Animation?, cmd: String, ticks: Int = 400,
            pressFrom: Int = 5, pressUntil: Int = 12, enemyDist: Double = 60.0, rootName: String = "P1",
            consts: Map<String, Double> = emptyMap(),
        ): Sim {
            val sim = Sim(book, anims, runNegative = true, rootName = rootName, collide = true, consts = consts)
            sim.spawnRoot(0.0).ctrl = true // no jogo o controle vem do início do round
            sim.spawnEnemy(enemyDist)
            repeat(ticks) { t ->
                sim.command = if (t in pressFrom until pressUntil) cmd else null
                sim.step()
            }
            return sim
        }

        /**
         * Cenário forçado: o estado [state] roda no P1 (ou num helper de id [asHelperId], criado pelo P1) com o adversário
         * ao alcance; o HitDef acerta na hora. Devolve a simulação para inspecionar o que aconteceu.
         */
        fun forced(book: StateBook, anims: (Int) -> Animation?, state: Int, asHelperId: Int? = null, ticks: Int = 200): Sim {
            val sim = Sim(book, anims)
            val root = sim.spawnRoot(-70.0)
            sim.spawnEnemy(70.0)
            if (asHelperId == null) sim.enter(root, state, root)
            else sim.spawnHelper(root, asHelperId, "helper$asHelperId", state, 0.0, 0.0, 1)
            sim.entities += sim.pending
            sim.pending.clear()
            sim.run(ticks)
            return sim
        }
    }
}
