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
        fun signature(pl: MovePlan) =
            pl.steps.map { it.anim to it.ticks } to pl.targets.map { t -> t.stateNo to t.steps.map { it.anim to it.useOwner } }

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
            val ids = if (isHelper) (mentionedIds(def) + spawnIds[no].orEmpty()).sorted() else emptyList()
            val idOptions: List<Int?> = if (ids.size >= 2) ids else listOf(null)
            val vars = varCandidates(def)
            // cada combinação (helper x valores de var) é planejada; as que dão o mesmo resultado viram um botão só
            class Cand(val id: Int?, val v: Map<Int, Int>, val root: Map<Int, Int>, val plan: MovePlan)
            val groups = LinkedHashMap<Any, MutableList<Cand>>()
            for (id in idOptions) for (combo in combos(vars)) {
                val own = combo.filterKeys { !it.second }.mapKeys { it.key.first }
                val root = combo.filterKeys { it.second }.mapKeys { it.key.first }
                val pl = MovePlanner.plan(
                    book, no, anims,
                    PlanOptions(asHelper = isHelper, singleState = isolated, helperId = id, vars = own, rootVars = root),
                ) ?: continue
                if (pl.steps.none { anims(it.anim) != null }) continue
                val c = Cand(id, own, root, pl)
                if (groups.size < MAX_VARIANTS || signature(pl) in groups) groups.getOrPut(signature(pl)) { ArrayList() }.add(c)
            }
            if (groups.size >= 2) {
                for (g in groups.values) {
                    val parts = ArrayList<String>()
                    if (ids.size >= 2) {
                        val nm = g.mapNotNull { it.id }.distinct().map { helperNames[it] ?: "id $it" }.distinct()
                        parts += nm.take(2).joinToString("/") + if (nm.size > 2) " +${nm.size - 2}" else ""
                    }
                    for ((key, values) in vars) {
                        if (values.size < 2) continue
                        val seen = g.map { (if (key.second) it.root else it.v)[key.first] ?: 0 }.distinct().sorted()
                        if (seen.size == values.size) continue // todos os valores dão o mesmo: a variável não distingue este botão
                        parts += (if (key.second) "root,var(${key.first})" else "var(${key.first})") + "=" + seen.take(3).joinToString("/")
                    }
                    val first = g[0]
                    out += CatalogEntry(
                        no, first.plan, commands[no].orEmpty(), isHelper, internal = no in continuations,
                        variant = parts.joinToString(" · ").ifEmpty { null }, helperId = first.id, vars = first.v, rootVars = first.root,
                    )
                }
            } else {
                out += CatalogEntry(no, plan, commands[no].orEmpty(), isHelper, internal = no in continuations)
            }
            for (v in plan.states) {
                if (!isolated && v > no && v !in book.helperStates && !commands.containsKey(v)) hidden.add(v)
            }
        }
        return out
    }

    private const val MAX_VARIANTS = 8
    private const val MAX_VALUES = 5
    private val varRef = Regex("""(root\s*,\s*|parent\s*,\s*)?\bvar\s*\(\s*(\d+)\s*\)""", RegexOption.IGNORE_CASE)
    private val decisionTypes = setOf("changestate", "selfstate", "targetstate", "changeanim", "changeanim2", "helper")

    /**
     * Valores de var(N) que mudam o que o estado faz, lidos do próprio personagem: os números com que o estado compara a
     * variável (var(27)=5) e os valores fixos que ele mesmo grava nela (VarSet), quando o destino é copiado da variável.
     * A chave é (índice, é do dono?). O 0 (valor inicial) sempre entra, e vem primeiro.
     */
    private fun varCandidates(def: StateDef): Map<Pair<Int, Boolean>, List<Int>> {
        val texts = ArrayList<String>()
        def.props["anim"]?.let { texts += it }
        for (c in def.controllers) if (c.type in decisionTypes) for ((_, v) in c.entries) texts += v
        val out = LinkedHashMap<Pair<Int, Boolean>, MutableSet<Int>>()
        for (t in texts) for (m in varRef.findAll(t)) {
            val key = m.groupValues[2].toInt() to m.groupValues[1].isNotEmpty()
            val set = out.getOrPut(key) { linkedSetOf(0) }
            val n = Regex.escape(m.groupValues[2])
            val after = Regex("""^\s*(!=|<=|>=|=|<|>)\s*(?:\[\s*(-?\d+)\s*,\s*(-?\d+)\s*\]|(-?\d+))""").find(t.substring(m.range.last + 1))
            if (after != null) {
                val op = after.groupValues[1]
                val lits = listOf(after.groupValues[2], after.groupValues[3], after.groupValues[4]).mapNotNull { it.toIntOrNull() }
                for (c in lits) { set.add(c); if (op != "=" && op != "!=") { set.add(c - 1); set.add(c + 1) } }
            }
            val before = Regex("""(-?\d+)\s*(?:!=|=)\s*$""").find(t.substring(0, m.range.first))
            before?.groupValues?.get(1)?.toIntOrNull()?.let { set.add(it) }
            // valor copiado do var: os VarSet do próprio estado com valor fixo
            if (Regex("""^\s*$""").matches(t.replace(m.value, "").trim()) || t.trim().startsWith(m.value)) {
                for (c in def.controllers) {
                    if (c.type != "varset") continue
                    val idx = c.param("v")?.trim()?.toIntOrNull() ?: Regex("""var\(\s*(\d+)\s*\)""", RegexOption.IGNORE_CASE)
                        .find(c.entries.firstOrNull { it.first.startsWith("var(") }?.first.orEmpty())?.groupValues?.get(1)?.toIntOrNull()
                    if (idx != m.groupValues[2].toInt()) continue
                    val lit = (c.param("value") ?: c.entries.firstOrNull { it.first.startsWith("var(") }?.second)?.trim()?.toIntOrNull()
                    if (lit != null) set.add(lit)
                }
            }
        }
        // no máximo duas variáveis por estado e poucos valores por variável
        return out.entries.filter { it.value.size >= 2 }.take(2).associate { it.key to it.value.take(MAX_VALUES) }
    }

    /** Todas as combinações de valores; sem variáveis, uma combinação vazia. */
    private fun combos(vars: Map<Pair<Int, Boolean>, List<Int>>): List<Map<Pair<Int, Boolean>, Int>> {
        var acc: List<Map<Pair<Int, Boolean>, Int>> = listOf(emptyMap())
        for ((k, values) in vars) acc = acc.flatMap { m -> values.map { v -> m + (k to v) } }
        return acc
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
