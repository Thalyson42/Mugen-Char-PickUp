package com.ikemenselect.core

/** Dados úteis do system.def do motif (screenpack). */
data class MotifInfo(val rows: Int?, val columns: Int?, val selectFile: String?)

/** Acesso somente leitura a uma pasta do Ikemen GO; caminhos relativos, com '/', sem diferenciar maiúsculas. */
interface ReadOnlyFs {
    fun exists(path: String): Boolean
    fun readText(path: String): String?

    /** Nomes das subpastas de [path] (usado para achar screenpacks desconhecidos em data/). */
    fun listDirs(path: String): List<String> = emptyList()
}

data class Located(val selectPath: String, val systemDefPath: String?, val rows: Int?, val columns: Int?)

fun parseSystemDef(text: String): MotifInfo {
    var section = ""
    var rows: Int? = null
    var cols: Int? = null
    var select: String? = null
    for (raw in text.lines()) {
        val line = raw.substringBefore(';').trim()
        if (line.isEmpty()) continue
        if (line.startsWith("[")) {
            section = line.substringBefore(']').removePrefix("[").trim().lowercase()
            continue
        }
        val eq = line.indexOf('=')
        if (eq < 0) continue
        val key = line.substring(0, eq).trim().lowercase()
        val value = line.substring(eq + 1).trim()
        when {
            section == "files" && key == "select" -> select = value.trim('"')
            section == "select info" && key == "rows" -> rows = value.toIntOrNull()
            section == "select info" && key == "columns" -> cols = value.toIntOrNull()
        }
    }
    return MotifInfo(rows, cols, select)
}

/** Lê o caminho do motif (ex.: "data/ikemen1/system.def") de dentro do save/config.json. */
fun motifFromConfig(json: String): String? =
    Regex("\"motif\"\\s*:\\s*\"([^\"]+)\"", RegexOption.IGNORE_CASE).find(json)?.groupValues?.get(1)

/** Lê `Motif = data/ikemen1/system.def` de um config.ini (Ikemen GO antigo) ou do data/mugen.cfg (M.U.G.E.N). */
fun motifFromIni(ini: String): String? =
    Regex("""^\s*motif\s*=\s*([^;\r\n]+)""", setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE))
        .find(ini)?.groupValues?.get(1)?.trim()?.trim('"')?.takeIf { it.isNotEmpty() }

object Locator {
    /** Lugares onde procurar o system.def do motif, do mais para o menos provável. */
    fun systemDefCandidates(fs: ReadOnlyFs): List<String> {
        val out = ArrayList<String>()
        fs.readText("save/config.json")?.let { motifFromConfig(it) }?.let { out += it }
        fs.readText("save/config.ini")?.let { motifFromIni(it) }?.let { out += it }
        fs.readText("data/mugen.cfg")?.let { motifFromIni(it) }?.let { out += it }
        out += "data/ikemen1/system.def" // motif padrão do Ikemen GO
        out += "data/system.def" // M.U.G.E.N e motifs antigos
        for (dir in fs.listDirs("data").sortedBy { it.lowercase() }) out += "data/$dir/system.def" // outros screenpacks
        return out.map { it.replace('\\', '/').trim().trimStart('/') }.filter { it.isNotEmpty() }.distinct()
    }

    /**
     * Descobre qual select.def o Ikemen GO realmente usa.
     * Segundo a wiki do Ikemen GO, no Android a cópia dentro da pasta do motif
     * (ex.: data/ikemen1/select.def) é a que vale; por isso ela tem prioridade.
     */
    fun locate(fs: ReadOnlyFs): Located? {
        val systemPath = systemDefCandidates(fs).firstOrNull { fs.exists(it) }
        val info = systemPath?.let { fs.readText(it) }?.let { parseSystemDef(it) }
        val motifDir = systemPath?.substringBeforeLast('/', "") ?: "data"
        val selectName = (info?.selectFile ?: "select.def").replace('\\', '/')
        val candidates = listOf("$motifDir/$selectName", "data/$selectName", "data/select.def").distinct()
        val found = candidates.firstOrNull { fs.exists(it) } ?: return null
        return Located(found, systemPath, info?.rows, info?.columns)
    }
}
