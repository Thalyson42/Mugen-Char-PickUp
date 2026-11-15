package com.ikemenselect.core

/** Tipos de entrada que ocupam uma célula do grid de seleção. */
enum class CellKind { CHAR, RANDOM, SKIP, SLOT, EMPTY }

/**
 * Uma célula do grid = uma entrada da seção [Characters] do select.def.
 *
 * @param lines   linhas cruas da entrada (1 linha, ou o bloco inteiro `slot = { ... }`).
 * @param leading comentários/linhas em branco logo acima; viajam junto com a entrada.
 * @param key     pasta do personagem em minúsculas (ex.: "kfm"), usada para comparar.
 * @param members todas as chaves contidas na célula (mais de uma em blocos `slot`).
 */
data class Cell(
    val kind: CellKind,
    val lines: List<String>,
    val leading: List<String> = emptyList(),
    val key: String? = null,
    val members: Set<String> = emptySet(),
    val label: String = "",
) {
    companion object {
        /** Cria uma célula de personagem a partir do texto da entrada (ex.: "kfm" ou "ryu/ryu_alt.def"). */
        fun forEntry(entry: String): Cell {
            val name = entry.trim()
            val key = SelectDef.keyOf(name)
            return Cell(CellKind.CHAR, listOf(name), key = key, members = setOf(key), label = SelectDef.displayName(name))
        }
    }
}

fun Cell?.isBlankCell(): Boolean = this == null || this.kind == CellKind.EMPTY

/**
 * Representação do select.def que preserva tudo fora da lista de personagens
 * (cabeçalho, [ExtraStages], [Options], comentários...) e só reescreve a seção [Characters].
 *
 * Observação: personagens com `exclude=1` não ocupam o grid; ao salvar eles são
 * escritos depois das células do grid.
 */
class SelectDef(
    val head: List<String>,
    val cells: List<Cell?>,
    val excluded: List<Cell>,
    val trailing: List<String>,
    val tail: List<String>,
    val eol: String,
    val finalNewline: Boolean,
    val hasCharactersSection: Boolean,
) {
    fun withCells(newCells: List<Cell?>): SelectDef =
        SelectDef(head, newCells, excluded, trailing, tail, eol, finalNewline, hasCharactersSection)

    /** Chaves (pastas em minúsculas) de todos os personagens que já estão no arquivo. */
    fun knownKeys(): Set<String> {
        val keys = HashSet<String>()
        for (c in cells) if (c != null) keys.addAll(c.members)
        for (c in excluded) keys.addAll(c.members)
        return keys
    }

    fun write(): String {
        val out = ArrayList<String>(head)
        if (hasCharactersSection) {
            val last = cells.indexOfLast { it != null && (it.kind != CellKind.EMPTY || it.leading.isNotEmpty()) }
            for (i in 0..last) {
                val c = cells[i]
                if (c == null) {
                    out.add(EMPTY_TOKEN)
                } else {
                    out.addAll(c.leading)
                    out.addAll(c.lines)
                }
            }
            for (c in excluded) {
                out.addAll(c.leading)
                out.addAll(c.lines)
            }
            out.addAll(trailing)
        }
        out.addAll(tail)
        return out.joinToString(eol) + if (finalNewline) eol else ""
    }

    companion object {
        /**
         * Texto que o app grava em uma posição vazia do grid.
         * Se um dia for preciso trocar o marcador de "slot vazio", é só aqui.
         */
        const val EMPTY_TOKEN = "empty"

        private val SECTION = Regex("""^\s*\[[^\]]*\]""")
        private val CHARACTERS = Regex("""^\s*\[\s*characters\s*\]""", RegexOption.IGNORE_CASE)
        private val SLOT_START = Regex("""^slot\s*=\s*\{.*""", RegexOption.IGNORE_CASE)
        private val EXCLUDE = Regex("""(^|,)\s*exclude\s*=\s*1\b""", RegexOption.IGNORE_CASE)
        private val LINE_BREAK = Regex("\r\n|\n|\r")

        private fun stripComment(line: String) = line.substringBefore(';')
        private fun nameOf(code: String) = code.substringBefore(',').trim()

        private fun folderOf(name: String): String {
            var n = name.trim().replace('\\', '/')
            if (n.lowercase().startsWith("chars/")) n = n.substring(6)
            n = n.substringBefore('/')
            if (n.lowercase().endsWith(".def")) n = n.dropLast(4)
            return n
        }

        fun keyOf(name: String): String = folderOf(name).lowercase()
        fun displayName(name: String): String = folderOf(name)

        fun parse(text: String): SelectDef {
            val eol = if (text.contains("\r\n")) "\r\n" else "\n"
            val finalNewline = text.endsWith("\n") || text.endsWith("\r")
            var lines = text.split(LINE_BREAK)
            if (finalNewline) lines = lines.dropLast(1)

            val start = lines.indexOfFirst { CHARACTERS.containsMatchIn(it) }
            if (start < 0) {
                return SelectDef(lines, emptyList(), emptyList(), emptyList(), emptyList(), eol, finalNewline, false)
            }
            var end = lines.size
            for (i in start + 1 until lines.size) {
                if (SECTION.containsMatchIn(lines[i])) { end = i; break }
            }

            val cells = ArrayList<Cell?>()
            val excluded = ArrayList<Cell>()
            var pending = ArrayList<String>()
            var i = start + 1
            while (i < end) {
                val raw = lines[i]
                val code = stripComment(raw).trim()
                if (code.isEmpty()) { pending.add(raw); i++; continue }

                if (SLOT_START.matches(code)) {
                    val block = arrayListOf(raw)
                    i++
                    val keys = LinkedHashSet<String>()
                    var label = ""
                    while (i < end) {
                        val r = lines[i]
                        block.add(r)
                        i++
                        val c = stripComment(r).trim()
                        if (c == "}") break
                        if (c.isNotEmpty()) {
                            val n = nameOf(c)
                            keys.add(keyOf(n))
                            if (label.isEmpty()) label = displayName(n)
                        }
                    }
                    cells.add(Cell(CellKind.SLOT, block, pending, keys.firstOrNull(), keys, label))
                    pending = ArrayList()
                    continue
                }

                val name = nameOf(code)
                val lower = name.lowercase()
                val cell = when {
                    lower == EMPTY_TOKEN || lower == "blank" -> Cell(CellKind.EMPTY, listOf(raw), pending)
                    lower == "randomselect" -> Cell(CellKind.RANDOM, listOf(raw), pending, label = "?")
                    lower == "skipslot" || lower == "dummyslot" -> Cell(CellKind.SKIP, listOf(raw), pending, label = lower)
                    else -> {
                        val key = keyOf(name)
                        Cell(CellKind.CHAR, listOf(raw), pending, key, setOf(key), displayName(name))
                    }
                }
                pending = ArrayList()
                if (cell.kind == CellKind.CHAR && EXCLUDE.containsMatchIn(code)) excluded.add(cell) else cells.add(cell)
                i++
            }

            return SelectDef(
                head = lines.subList(0, start + 1).toList(),
                cells = cells,
                excluded = excluded,
                trailing = pending,
                tail = lines.subList(end, lines.size).toList(),
                eol = eol,
                finalNewline = finalNewline,
                hasCharactersSection = true,
            )
        }
    }
}
