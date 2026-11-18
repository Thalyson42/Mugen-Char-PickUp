package com.ikemenselect.core

/** Um golpe da lista: o estado onde começa, o plano completo (com as continuações) e os comandos que o ativam. */
class CatalogEntry(
    val stateNo: Int,
    val plan: MovePlan,
    val commands: List<String>,
    /** Estado iniciado por um Helper (agarrões de helper, projéteis...). */
    val helper: Boolean,
    /**
     * Etapa de outro golpe: algum golpe (com comando) ou helper chega nela por ChangeState/SelfState/TargetState.
     * A tela guarda essas etapas fora da lista principal, porque o golpe que leva a elas já as toca em sequência.
     */
    val internal: Boolean = false,
)

/**
 * Monta a lista de golpes de um personagem. Cada golpe é planejado até o fim da cadeia de continuações
 * (240 -> 241 ...), e os estados que só aparecem como continuação de outro golpe saem da lista.
 */
object MoveCatalog {

    fun build(book: StateBook, isolated: Boolean = false, anims: (Int) -> Animation?): List<CatalogEntry> {
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

        val continuations = continuationStates(book)
        val out = ArrayList<CatalogEntry>()
        val hidden = HashSet<Int>()
        for (no in book.states.keys.sorted()) {
            val def = book.states[no] ?: continue
            if (no in hidden || !candidate(no, def)) continue
            // estados que escolhem o caminho com ishelper(...) são planejados como o caminho do helper (ex.: agarrão do helper)
            fun mentionsHelper(c: Controller) =
                (c.triggerAll + c.triggerGroups.flatten()).any { it.contains("ishelper", true) } ||
                    ((c.type == "changestate" || c.type == "changeanim") && c.param("value")?.contains("ishelper", true) == true)
            val isHelper = no in helpers || def.props["anim"]?.contains("ishelper", true) == true ||
                def.controllers.any { c -> mentionsHelper(c) }
            val plan = MovePlanner.plan(book, no, anims, PlanOptions(asHelper = isHelper, singleState = isolated)) ?: continue
            if (plan.steps.none { anims(it.anim) != null }) continue
            out += CatalogEntry(no, plan, commands[no].orEmpty(), isHelper, internal = no in continuations)
            for (v in plan.states) {
                if (!isolated && v > no && v !in book.helperStates && !commands.containsKey(v)) hidden.add(v)
            }
        }
        return out
    }

    /**
     * Estados (de 200 em diante) que só aparecem como etapa de outro golpe. Parte dos golpes com comando e dos helpers
     * e segue qualquer número que apareça no `value` de um ChangeState/SelfState/TargetState, mesmo quando a condição
     * não dá para avaliar sem o jogo rodando (variáveis, por exemplo).
     */
    fun continuationStates(book: StateBook): Set<Int> {
        val commanded = HashSet<Int>(book.commandsByState.keys)
        // um helper é criado por um golpe (projétil, parceiro...): o estado dele é etapa desse golpe, não um golpe à parte
        val reached = LinkedHashSet<Int>(book.helperStates.filter { it !in commanded && book.states.containsKey(it) })
        val seen = HashSet<Int>(commanded).also { it.addAll(book.helperStates) }
        val queue = ArrayDeque<Int>(seen)
        val number = Regex("""\d+""")
        while (queue.isNotEmpty()) {
            val st = book.states[queue.removeFirst()] ?: continue
            for (c in st.controllers) {
                if (c.type != "changestate" && c.type != "selfstate" && c.type != "targetstate") continue
                val raw = c.param("value") ?: continue
                for (m in number.findAll(raw)) {
                    val v = m.value.toIntOrNull() ?: continue
                    if (v < 200 || v >= 5000 || !book.states.containsKey(v)) continue
                    if (seen.add(v)) queue.addLast(v)
                    if (v !in commanded) reached.add(v)
                }
            }
        }
        return reached
    }
}
