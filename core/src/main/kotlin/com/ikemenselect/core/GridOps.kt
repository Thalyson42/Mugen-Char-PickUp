package com.ikemenselect.core

/** Um personagem encontrado na pasta `chars/`. */
data class Candidate(val folder: String, val defFile: String) {
    /** Texto que vai para o select.def: "kfm" quando o .def tem o nome da pasta, senão "pasta/arquivo.def". */
    val entryName: String
        get() = if (defFile.equals("$folder.def", ignoreCase = true)) folder else "$folder/$defFile"

    fun toCell(): Cell = Cell.forEntry(entryName)
}

data class AddResult(val cells: List<Cell?>, val added: List<Candidate>)

object GridOps {

    /** Garante que a lista cubra pelo menos rows*columns células e feche a última linha. */
    fun normalize(cells: List<Cell?>, rows: Int, columns: Int): List<Cell?> {
        val cols = columns.coerceAtLeast(1)
        val minSize = rows.coerceAtLeast(1) * cols
        val rowsNeeded = (cells.size + cols - 1) / cols
        val size = maxOf(minSize, rowsNeeded * cols)
        return List(size) { cells.getOrNull(it) }
    }

    /** Arrastar e soltar: se o destino está vazio o personagem se move, senão os dois trocam de lugar. */
    fun swap(cells: List<Cell?>, from: Int, to: Int): List<Cell?> {
        if (from == to || from !in cells.indices || to !in cells.indices) return cells
        val m = cells.toMutableList()
        val t = m[from]
        m[from] = m[to]
        m[to] = t
        return m
    }

    fun remove(cells: List<Cell?>, index: Int): List<Cell?> {
        if (index !in cells.indices) return cells
        return cells.toMutableList().also { it[index] = null }
    }

    fun place(cells: List<Cell?>, index: Int, cell: Cell): List<Cell?> {
        val m = cells.toMutableList()
        while (m.size <= index) m.add(null)
        m[index] = cell
        return m
    }

    fun firstBlank(cells: List<Cell?>): Int = cells.indexOfFirst { it.isBlankCell() }

    /**
     * "Update char list": coloca cada personagem de `chars/` que ainda não está no
     * select.def na primeira posição vazia do grid (ou no fim, se não houver vazias).
     */
    fun addMissing(cells: List<Cell?>, candidates: List<Candidate>, known: Set<String>): AddResult {
        val result = cells.toMutableList()
        val added = ArrayList<Candidate>()
        val seen = known.toMutableSet()
        var cursor = 0
        for (c in candidates.sortedBy { it.folder.lowercase() }) {
            if (!seen.add(c.folder.lowercase())) continue
            while (cursor < result.size && !result[cursor].isBlankCell()) cursor++
            if (cursor < result.size) result[cursor] = c.toCell() else result.add(c.toCell())
            added.add(c)
        }
        return AddResult(result, added)
    }

    /** Escolhe o .def principal de uma pasta de personagem: o que tem o nome da pasta, senão o primeiro em ordem alfabética. */
    fun pickDef(folder: String, files: List<String>): String? {
        val defs = files.filter { it.endsWith(".def", ignoreCase = true) }.sortedBy { it.lowercase() }
        if (defs.isEmpty()) return null
        return defs.firstOrNull { it.equals("$folder.def", ignoreCase = true) } ?: defs.first()
    }

    /**
     * Converte o caminho de um .def escolhido manualmente (relativo à pasta `chars/`)
     * no texto do select.def. Retorna null se não for um .def.
     */
    fun entryFromRelativePath(pathInsideChars: String): String? {
        val segs = pathInsideChars.replace('\\', '/').split('/').filter { it.isNotEmpty() }
        if (segs.isEmpty() || !segs.last().endsWith(".def", ignoreCase = true)) return null
        if (segs.size == 2 && segs[1].dropLast(4).equals(segs[0], ignoreCase = true)) return segs[0]
        return segs.joinToString("/")
    }
}
