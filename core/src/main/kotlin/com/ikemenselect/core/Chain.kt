package com.ikemenselect.core

/** Algo que o golpe cria ou faz no oponente durante um estado. */
class Spawn(
    /** "explod", "helper", "projectile" ou "target" (estado imposto ao P2 por TargetState). */
    val kind: String,
    /** Animação que ele toca, se o valor for um número que dá para ler. */
    val anim: Int?,
    /** Estado associado (stateno do Helper, destino do TargetState). */
    val stateNo: Int?,
)

/** Um passo da cadeia de um golpe: um estado, as animações que ele toca e o que ele cria. */
class ChainStep(val stateNo: Int, val anims: List<Int>, val spawns: List<Spawn>)

/**
 * Cadeia estática de um golpe: parte do estado de destino do comando e segue os ChangeState que acontecem quando
 * a animação acaba. Não simula nada: condições como Time, Vel e var() não são avaliadas, e MoveHit/MoveContact
 * valem como verdadeiros (o showcase finge que o golpe acertou).
 */
object Chain {
    /** Estados comuns do jogo (andar, pular, levar golpe...): chegar neles significa que o golpe terminou. */
    private fun isCommonState(n: Int) = n in 0..199 || n >= 5000

    /** Triggers agrupados: cada grupo = os triggerall + as linhas de um triggerN (o controlador vale se algum grupo vale). */
    private fun groups(c: Controller): List<List<String>> {
        val all = c.entries.filter { it.first.equals("triggerall", true) }.map { it.second }
        val byIndex = LinkedHashMap<String, MutableList<String>>()
        for ((k, v) in c.entries) {
            val low = k.lowercase()
            if (low.startsWith("trigger") && low != "triggerall") byIndex.getOrPut(low) { ArrayList() }.add(v)
        }
        return byIndex.values.map { all + it }
    }

    /** O ChangeState só acontece "quando a animação acaba" (AnimTime) e não depende de comando, sorteio ou variável. */
    private fun endsWithAnim(c: Controller): Boolean = groups(c).any { g ->
        val t = g.joinToString(" && ").lowercase()
        "animtime" in t && "command" !in t && "random" !in t && "var(" !in t
    }

    private fun literalAnim(s: String?): Int? = s?.trim()?.toIntOrNull()

    private fun step(st: StateDef): ChainStep {
        val anims = ArrayList<Int>()
        st.anim?.let { anims.add(it) }
        val spawns = ArrayList<Spawn>()
        for (c in st.controllers) when (c.type) {
            "changeanim", "changeanim2" -> literalAnim(c["value"])?.let { if (it !in anims) anims.add(it) }
            "explod" -> literalAnim(c["anim"])?.let { spawns.add(Spawn("explod", it, null)) }
            "projectile" -> spawns.add(Spawn("projectile", literalAnim(c["projanim"]), null))
            "helper" -> spawns.add(Spawn("helper", null, literalAnim(c["stateno"])))
            "targetstate" -> spawns.add(Spawn("target", null, literalAnim(c["value"])))
        }
        return ChainStep(st.number, anims, spawns)
    }

    /** Segue a cadeia a partir de [start]. Para ao voltar a um estado comum, ao repetir um estado ou depois de [maxSteps]. */
    fun follow(states: Map<Int, StateDef>, start: Int, maxSteps: Int = 12): List<ChainStep> {
        val out = ArrayList<ChainStep>()
        val seen = HashSet<Int>()
        var cur: Int? = start
        while (cur != null && out.size < maxSteps && seen.add(cur)) {
            val st = states[cur] ?: break
            out.add(step(st))
            cur = st.controllers.firstOrNull { c ->
                (c.type == "changestate" || c.type == "selfstate") && endsWithAnim(c) &&
                    (c.int("value") ?: -1).let { it >= 0 && it != st.number && !isCommonState(it) }
            }?.int("value")
        }
        return out
    }

    /** Animações dos estados que um Helper roda (cada Helper tem a sua própria cadeia). */
    fun helperChain(states: Map<Int, StateDef>, spawn: Spawn): List<ChainStep> =
        spawn.stateNo?.let { follow(states, it) }.orEmpty()
}
