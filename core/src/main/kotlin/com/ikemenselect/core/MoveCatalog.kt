package com.ikemenselect.core

/** Um golpe da lista: o estado onde começa, o plano completo (com as continuações) e os comandos que o ativam. */
class CatalogEntry(
    val stateNo: Int,
    /** O estado não mostra nada (anim 9999 ou sem animação): controle interno. */
    val invisible: Boolean = false,
    val commands: List<String>,
    /** Estado iniciado por um Helper (agarrões de helper, projéteis...). */
    val helper: Boolean,
    /**
     * Etapa de outro golpe: algum golpe (com comando) ou helper chega nela por ChangeState/SelfState/TargetState.
     * A tela guarda essas etapas fora da lista principal, porque o golpe que leva a elas já as toca em sequência.
     */
    val internal: Boolean = false,
    /** Quando o mesmo estado tem caminhos diferentes por helper (ishelper(N)): o nome da variante e o id usado ao planejar. */
    val variant: String? = null,
    val helperId: Int? = null,
    /** Valores de var(N) (do personagem e do dono) com que esta variante foi planejada. */
    val vars: Map<Int, Int> = emptyMap(),
    val rootVars: Map<Int, Int> = emptyMap(),
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

        /** Animação inicial visível do estado: null = nenhuma; true também para expressões (só a simulação sabe). */
        fun visible(def: StateDef): Boolean {
            val lits = ArrayList<Int>()
            def.anim?.let { lits += it }
            for (c in def.controllers) if (c.type == "changeanim" || c.type == "changeanim2")
                c.param("value")?.trim()?.takeIf { intRe.matches(it) }?.toInt()?.let { lits += it }
            if (def.props["anim"] != null && def.anim == null) return true
            return lits.any { it != 9999 && anims(it) != null }
        }

        fun candidate(no: Int, def: StateDef): Boolean {
            if (no < 0) return false
            if (no in book.helperStates || commands.containsKey(no)) return true
            return def.props["anim"] != null || def.controllers.any { c ->
                (c.type == "changeanim" || c.type == "changeanim2") &&
                    c.param("value")?.trim()?.takeIf { intRe.matches(it) }?.toInt()?.let { anims(it) != null } == true
            }
        }

        // ids de helper que criam cada estado (Helper: id + stateno fixos) e o nome deles
        val spawnIds = HashMap<Int, MutableSet<Int>>()
        val helperNames = HashMap<Int, String>()
        for (st in book.states.values) for (c in st.controllers) {
            if (c.type != "helper") continue
            val id = c.param("id")?.trim()?.toIntOrNull() ?: continue
            val sn = c.param("stateno")?.trim()?.toIntOrNull()
            if (sn != null) spawnIds.getOrPut(sn) { LinkedHashSet() }.add(id)
            c.param("name")?.trim()?.trim('"')?.takeIf { it.isNotEmpty() }?.let { helperNames.putIfAbsent(id, it) }
        }
        val isHelperRe = Regex("""ishelper\s*\(\s*(\d+)\s*\)""", RegexOption.IGNORE_CASE)
        fun mentionedIds(def: StateDef): Set<Int> {
            val ids = LinkedHashSet<Int>()
            def.props.values.forEach { v -> isHelperRe.findAll(v).forEach { ids.add(it.groupValues[1].toInt()) } }
            for (c in def.controllers) for ((_, v) in c.entries) isHelperRe.findAll(v).forEach { ids.add(it.groupValues[1].toInt()) }
            return ids
        }

        val continuations = continuationStates(book)
        val out = ArrayList<CatalogEntry>()
        for (no in book.states.keys.sorted()) {
            val def = book.states[no] ?: continue
            if (!candidate(no, def)) continue
            fun mentionsHelper(c: Controller) =
                (c.triggerAll + c.triggerGroups.flatten()).any { it.contains("ishelper", true) } ||
                    ((c.type == "changestate" || c.type == "changeanim") && c.param("value")?.contains("ishelper", true) == true)
            val isHelper = no in helpers || def.props["anim"]?.contains("ishelper", true) == true ||
                def.controllers.any { c -> mentionsHelper(c) }
            val vis = visible(def)
            val internal = no in continuations
            val ids = if (isHelper) (mentionedIds(def) + spawnIds[no].orEmpty()).sorted() else emptyList()
            if (ids.size >= 2) {
                // um botão por helper que cria este estado (a simulação roda cada um com o id certo)
                for (id in ids) out += CatalogEntry(no, !vis, commands[no].orEmpty(), true, internal, variant = helperNames[id] ?: "id $id", helperId = id)
            } else {
                out += CatalogEntry(no, !vis, commands[no].orEmpty(), isHelper, internal)
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
