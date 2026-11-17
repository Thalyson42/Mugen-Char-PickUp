package com.ikemenselect.core

/** Edição mínima do system.def do motif: só `rows` e `columns` da seção [Select Info]. */
object SystemDefEdit {

    private val SECTION = Regex("""^\s*\[([^\]]*)\]""")
    private val LINE_BREAK = Regex("\r\n|\n|\r")

    private fun keyRegex(key: String) = Regex("""^(\s*$key\s*=\s*)(-?\d+)(.*)$""", RegexOption.IGNORE_CASE)

    /**
     * Troca (ou cria) `rows` e `columns` em [Select Info], mantendo o resto do arquivo igual:
     * comentários no fim da linha, espaçamento, quebras de linha (LF/CRLF) e as outras seções.
     */
    fun setGridSize(text: String, rows: Int, columns: Int): String {
        val eol = if (text.contains("\r\n")) "\r\n" else "\n"
        val finalNewline = text.endsWith("\n") || text.endsWith("\r")
        val lines = text.split(LINE_BREAK).toMutableList()
        if (finalNewline) lines.removeAt(lines.size - 1)

        val start = lines.indexOfFirst {
            SECTION.find(it)?.groupValues?.get(1)?.trim().equals("select info", ignoreCase = true)
        }
        if (start < 0) {
            if (lines.isNotEmpty() && lines.last().isNotBlank()) lines.add("")
            lines.add("[Select Info]")
            lines.add("rows = $rows")
            lines.add("columns = $columns")
            return lines.joinToString(eol) + eol
        }

        var end = lines.size
        for (i in start + 1 until lines.size) {
            if (SECTION.containsMatchIn(lines[i])) { end = i; break }
        }

        fun set(key: String, value: Int) {
            val re = keyRegex(key)
            for (i in start + 1 until end) {
                val m = re.find(lines[i]) ?: continue
                lines[i] = m.groupValues[1] + value + m.groupValues[3]
                return
            }
            var at = end
            while (at - 1 > start && lines[at - 1].isBlank()) at--
            lines.add(at, "$key = $value")
            end++
        }
        set("rows", rows)
        set("columns", columns)
        return lines.joinToString(eol) + if (finalNewline) eol else ""
    }
}
