package com.ikemenselect.core

/** Um golpe da lista: o estado onde começa, o plano completo (com as continuações) e os comandos que o ativam. */
class CatalogEntry(
    val stateNo: Int,
    val plan: MovePlan,
    val commands: List<String>,
    /** Estado iniciado por um Helper (agarrões de helper, projéteis...). */
    val helper: Boolean,
)

/**
 * Monta a lista de golpes de um personagem. Cada golpe é planejado até o fim da cadeia de continuações
 * (240 -> 241 ...), e os estados que só aparecem como continuação de outro golpe saem da lista.
 */
object MoveCatalog {

    fun build(book: StateBook, anims: (Int) -> Animation?): List<CatalogEntry> {
        val commands = book.commandsByState
        val helpers = book.helperClosure
        val intRe = Regex("""^-?\d+$""")

        fun candidate(no: Int, def: StateDef): Boolean {
            if (no < 0) return false
            if (no in book.helperStates || commands.containsKey(no)) return true
            def.anim?.let { if (anims(it) != null) return true }
            if (def.props["anim"] != null) return true // expressão: o planejador resolve
            return def.controllers.any { c ->
                (c.type == "changeanim" || c.type == "changeanim2") &&
                    c.param("value")?.trim()?.takeIf { intRe.matches(it) }?.toInt()?.let { anims(it) != null } == true
            }
        }

        val out = ArrayList<CatalogEntry>()
        val hidden = HashSet<Int>()
        for (no in book.states.keys.sorted()) {
            val def = book.states[no] ?: continue
            if (no in hidden || !candidate(no, def)) continue
            // estados que escolhem o caminho com ishelper(...) são planejados como o caminho do helper (ex.: agarrão do helper)
            val isHelper = no in helpers || def.props["anim"]?.contains("ishelper", true) == true ||
                def.controllers.any { c -> (c.type == "changestate" || c.type == "changeanim") && c.param("value")?.contains("ishelper", true) == true }
            val plan = MovePlanner.plan(book, no, anims, PlanOptions(asHelper = isHelper)) ?: continue
            if (plan.steps.none { anims(it.anim) != null }) continue
            out += CatalogEntry(no, plan, commands[no].orEmpty(), isHelper)
            for (v in plan.states) {
                if (v > no && v !in book.helperStates && !commands.containsKey(v)) hidden.add(v)
            }
        }
        return out
    }
}
