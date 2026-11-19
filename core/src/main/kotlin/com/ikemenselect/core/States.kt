package com.ikemenselect.core

/** Um controlador de estado: `[State N, nome]` com seus gatilhos. */
class Controller(
    val type: String,
    val name: String,
    val entries: List<Pair<String, String>>,
) {
    /** triggerall: todos precisam ser verdadeiros. */
    val triggerAll: List<String> = entries.filter { it.first == "triggerall" }.map { it.second }

    /** trigger1, trigger2...: o controlador dispara se algum grupo tiver todas as linhas verdadeiras. */
    val triggerGroups: List<List<String>> = entries
        .filter { it.first.startsWith("trigger") && it.first != "triggerall" }
        .groupBy { it.first.removePrefix("trigger").toIntOrNull() ?: 0 }
        .toSortedMap()
        .values.map { g -> g.map { it.second } }

    fun param(key: String): String? = entries.firstOrNull { it.first == key }?.second
}

class StateDef(val no: Int, val props: Map<String, String>, val controllers: MutableList<Controller>) {
    /** Animação inicial do estado, quando é um número. */
    val anim: Int? get() = props["anim"]?.trim()?.toIntOrNull()
    val stateType: String? get() = props["type"]?.trim()?.uppercase()?.take(1)?.ifEmpty { null }
    val moveType: String? get() = props["movetype"]?.trim()?.uppercase()?.take(1)?.ifEmpty { null }
}

/** Todos os estados lidos dos arquivos .cns/.st/.cmd de um personagem, e os comandos do .cmd. */
class StateBook(val states: Map<Int, StateDef>, val commandNames: Set<String>) {

    /** Nomes de comando que levam a cada estado (via ChangeState no estado -1). */
    val commandsByState: Map<Int, List<String>> by lazy {
        val out = LinkedHashMap<Int, MutableSet<String>>()
        val neg = states[-1] ?: return@lazy emptyMap()
        val ignore = setOf("holdfwd", "holdback", "holddown", "holdup", "fwd", "back", "up", "down")
        val re = Regex("""command\s*=\s*"([^"]+)"""", RegexOption.IGNORE_CASE)
        for (c in neg.controllers) {
            if (c.type != "changestate" && c.type != "selfstate") continue
            val target = c.param("value")?.trim()?.let { Regex("""^-?\d+""").find(it)?.value?.toIntOrNull() } ?: continue
            val names = (c.triggerAll + c.triggerGroups.flatten()).flatMap { l -> re.findAll(l).map { it.groupValues[1] }.toList() }
            for (n in names) if (n.lowercase() !in ignore) out.getOrPut(target) { LinkedHashSet() }.add(n)
        }
        out.mapValues { it.value.toList() }
    }

    /** Estados que algum controlador Helper inicia (`stateno = N`). */
    val helperStates: Set<Int> by lazy {
        val out = LinkedHashSet<Int>()
        for (st in states.values) for (c in st.controllers) {
            if (c.type != "helper") continue
            c.param("stateno")?.trim()?.toIntOrNull()?.let { out.add(it) }
        }
        out
    }

    /**
     * Para onde o alvo fica virado em cada estado que um HitDef manda (`p2stateno`): `p2facing` = 1 vira para o mesmo lado
     * do atacante, -1 para o atacante. O valor passa para os estados seguintes da cadeia (ChangeState/TargetState com número fixo).
     */
    val p2FacingByState: Map<Int, Int> by lazy {
        val out = HashMap<Int, Int>()
        // (estado, direção, é do atacante?): o atacante segue por p1stateno e manda o alvo adiante com TargetState
        val queue = ArrayDeque<Triple<Int, Int, Boolean>>()
        val seenAtk = HashSet<Int>()
        for (st in states.values) for (c in st.controllers) {
            if (c.type != "hitdef") continue
            val f = c.param("p2facing")?.trim()?.toIntOrNull()?.takeIf { it != 0 } ?: continue
            c.param("p2stateno")?.trim()?.toIntOrNull()?.let { if (out.putIfAbsent(it, f) == null) queue.addLast(Triple(it, f, false)) }
            c.param("p1stateno")?.trim()?.toIntOrNull()?.let { if (seenAtk.add(it)) queue.addLast(Triple(it, f, true)) }
        }
        while (queue.isNotEmpty()) {
            val (no, f, atk) = queue.removeFirst()
            for (c in states[no]?.controllers.orEmpty()) {
                val v = c.param("value")?.trim()?.toIntOrNull() ?: continue
                if (v < 200 || v in 5000..5999 || !states.containsKey(v)) continue
                when (c.type) {
                    "changestate", "selfstate" ->
                        if (atk) { if (seenAtk.add(v)) queue.addLast(Triple(v, f, true)) }
                        else if (out.putIfAbsent(v, f) == null) queue.addLast(Triple(v, f, false))
                    "targetstate" -> if (out.putIfAbsent(v, f) == null) queue.addLast(Triple(v, f, false))
                }
            }
        }
        out
    }

    /** Estados dos Helpers e os que eles alcançam por ChangeState (só de 200 em diante, para não pegar o movimento comum). */
    val helperClosure: Set<Int> by lazy {
        val out = LinkedHashSet<Int>(helperStates)
        val queue = ArrayDeque(helperStates)
        while (queue.isNotEmpty()) {
            val st = states[queue.removeFirst()] ?: continue
            for (c in st.controllers) {
                if (c.type != "changestate" && c.type != "selfstate") continue
                val raw = c.param("value")?.trim() ?: continue
                // valor fixo ou expressão (cond(...)): vale qualquer número que aparece nela
                for (m in Regex("""\d+""").findAll(raw)) {
                    val v = m.value.toIntOrNull() ?: continue
                    if (v >= 200 && states.containsKey(v) && out.add(v)) queue.addLast(v)
                }
            }
        }
        out
    }

    companion object {
        val EMPTY = StateBook(emptyMap(), emptySet())

        /**
         * Lê os arquivos na ordem do jogo (stcommon, cmd, cns, st...): um estado definido em vários arquivos
         * fica com a definição do último. Os controladores de cada [Statedef] ficam na ordem do arquivo.
         */
        fun parse(texts: List<String>): StateBook {
            val states = LinkedHashMap<Int, StateDef>()
            val commands = LinkedHashSet<String>()
            for (text in texts) {
                var current: StateDef? = null
                val replaced = HashSet<Int>()
                for (sec in Ini.parse(text)) {
                    val name = sec.name
                    val lower = name.lowercase()
                    when {
                        lower == "command" -> sec["name"]?.trim()?.trim('"')?.let { commands.add(it) }
                        lower.startsWith("statedef") -> {
                            val no = name.substring(8).trim().toIntOrNull()
                            if (no == null) {
                                current = null
                            } else {
                                val props = LinkedHashMap<String, String>()
                                for ((k, v) in sec.entries) props.putIfAbsent(k.lowercase(), v)
                                current = StateDef(no, props, ArrayList())
                                states[no] = current // o último arquivo vence
                                replaced.add(no)
                            }
                        }
                        lower.startsWith("state") && current != null -> {
                            val entries = sec.entries.map { it.first.lowercase() to it.second }
                            val type = entries.firstOrNull { it.first == "type" }?.second?.trim()?.lowercase().orEmpty()
                            current.controllers.add(Controller(type, name, entries))
                        }
                    }
                }
            }
            return StateBook(states, commands)
        }
    }
}
