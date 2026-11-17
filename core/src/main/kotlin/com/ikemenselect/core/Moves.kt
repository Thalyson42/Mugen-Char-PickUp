package com.ikemenselect.core

import com.ikemenselect.core.Ini.first

/** Um controlador de estado ([State N, nome]) do .cns/.st/.cmd, ainda sem interpretar as condições. */
class Controller(
    val type: String,
    /** Todas as linhas da seção, na ordem (triggerall, trigger1..., value, anim...). */
    val entries: List<Pair<String, String>>,
) {
    operator fun get(key: String): String? = entries.firstOrNull { it.first.equals(key, true) }?.second
    val triggers: List<String> get() = entries.filter { it.first.lowercase().startsWith("trigger") }.map { it.second }
    fun int(key: String): Int? = get(key)?.trim()?.toIntOrNull()
}

/** Um [Statedef N] com seus controladores. [anim] é o `anim =` do Statedef (se for número). */
class StateDef(val number: Int, val anim: Int?, val controllers: List<Controller>) {
    /** Primeira animação que o estado toca: o `anim` do Statedef ou o primeiro ChangeAnim com valor numérico. */
    val firstAnim: Int?
        get() = anim ?: controllers.firstOrNull { it.type == "changeanim" && it.int("value") != null }?.int("value")
}

object States {
    /** Lê todos os estados de um texto .cns/.st/.cmd. Estados repetidos (em arquivos diferentes) vão se somando em [mergeInto]. */
    fun parse(text: String): Map<Int, StateDef> {
        val out = LinkedHashMap<Int, StateDef>()
        var number: Int? = null
        var anim: Int? = null
        var ctrls = ArrayList<Controller>()
        fun flush() {
            number?.let { n -> if (n !in out) out[n] = StateDef(n, anim, ctrls) } // o primeiro vence
            ctrls = ArrayList()
        }
        for (sec in Ini.parse(text)) {
            val low = sec.name.lowercase()
            when {
                low.startsWith("statedef") -> {
                    flush()
                    number = low.removePrefix("statedef").trim().toIntOrNull()
                    anim = sec["anim"]?.trim()?.toIntOrNull()
                }
                low.startsWith("state ") || low.startsWith("state\t") -> {
                    if (number != null) ctrls.add(Controller((sec["type"] ?: "").trim().lowercase(), sec.entries))
                }
            }
        }
        flush()
        return out
    }

    /** Junta vários arquivos; o primeiro arquivo que define um estado vence (regra do M.U.G.E.N), exceto o -1, que soma. */
    fun mergeAll(texts: List<String>): Map<Int, StateDef> {
        val out = LinkedHashMap<Int, StateDef>()
        for (t in texts) for ((n, s) in parse(t)) {
            val old = out[n]
            out[n] = if (old == null) s else if (n < 0) StateDef(n, old.anim ?: s.anim, old.controllers + s.controllers) else old
        }
        return out
    }
}

/** Um golpe que o usuário pode escolher: o estado para onde o(s) comando(s) levam. */
class MoveItem(
    val stateNo: Int,
    /** Nomes dos comandos do .cmd que disparam este estado (ex.: "qcfx"). */
    val commandNames: List<String>,
    /** Sequências de botões legíveis (ex.: "↓ ↘ → + x"), uma por definição do comando. */
    val sequences: List<String>,
    /** Primeira animação do estado, se der para saber sem simular. */
    val anim: Int?,
)

object Moves {
    private val HOLD = setOf("holdfwd", "holdback", "holdup", "holddown", "fwd", "back", "up", "down")
    private val CMD_REF = Regex("""(?<![!<>=])\bcommand\s*=\s*"([^"]+)"""", RegexOption.IGNORE_CASE)

    /** Nome do comando → todas as sequências que o definem (o mesmo nome pode ser definido várias vezes). */
    fun parseCommands(cmdText: String): Map<String, List<String>> {
        val out = LinkedHashMap<String, MutableList<String>>()
        for (sec in Ini.parse(cmdText)) {
            if (!sec.name.equals("Command", true)) continue
            val name = sec["name"]?.trim()?.trim('"')?.lowercase() ?: continue
            val seq = sec["command"]?.trim() ?: continue
            out.getOrPut(name) { ArrayList() }.add(pretty(seq))
        }
        return out
    }

    /** ~D, DF, F, x  →  "↓ ↘ → x" (o "~" de soltar e o "/" de segurar viram texto simples). */
    fun pretty(seq: String): String {
        val arrows = mapOf("D" to "↓", "DF" to "↘", "F" to "→", "UF" to "↗", "U" to "↑", "UB" to "↖", "B" to "←", "DB" to "↙")
        return seq.split(',').joinToString(" ") { part ->
            part.trim().split('+').joinToString("+") { tok ->
                val t = tok.trim()
                val prefix = t.takeWhile { it == '~' || it == '/' || it == '$' || it == '>' }
                val core = t.substring(prefix.length)
                val hold = if ('/' in prefix) "(segurar) " else ""
                val release = if ('~' in prefix) "~" else ""
                hold + release + (arrows[core.uppercase()] ?: core)
            }
        }
    }

    /**
     * Lista de golpes: percorre o [Statedef -1] (de todos os arquivos) e, para cada ChangeState com valor numérico
     * que depende de `command = "nome"`, registra o estado de destino. Ignora as condições (energia, variáveis...).
     */
    fun build(cmdText: String, otherTexts: List<String>): List<MoveItem> {
        val commands = parseCommands(cmdText)
        val states = States.mergeAll(listOf(cmdText) + otherTexts)
        val byState = LinkedHashMap<Int, LinkedHashSet<String>>()
        for (c in states[-1]?.controllers.orEmpty()) {
            if (c.type != "changestate" && c.type != "selfstate") continue
            val target = c.int("value") ?: continue
            if (target < 0) continue
            val names = c.entries.filter { it.first.lowercase().startsWith("trigger") }
                .flatMap { CMD_REF.findAll(it.second).map { m -> m.groupValues[1].lowercase() }.toList() }
                .filter { it !in HOLD && !it.startsWith("hold") }
            if (names.isEmpty()) continue
            byState.getOrPut(target) { LinkedHashSet() }.addAll(names)
        }
        return byState.map { (no, names) ->
            MoveItem(
                stateNo = no,
                commandNames = names.toList(),
                sequences = names.flatMap { commands[it].orEmpty() }.distinct(),
                anim = states[no]?.firstAnim,
            )
        }
    }
}
