package com.ikemenselect.core

/** Como achar os arquivos de um personagem a partir da linha dele no select.def. */
object CharFiles {

    /**
     * Caminho do .def do personagem relativo à pasta `chars/`.
     * "kfm" -> "kfm/kfm.def"; "ryu/ryu_alt.def" -> igual; "chars/kfm/kfm.def" -> "kfm/kfm.def".
     */
    fun defPath(entry: String): String? {
        var e = entry.substringBefore(',').trim().replace('\\', '/').trimStart('/')
        if (e.isEmpty()) return null
        if (e.lowercase().startsWith("chars/")) e = e.substring(6)
        if (e.isEmpty()) return null
        return if (e.endsWith(".def", ignoreCase = true)) e else "$e/${e.substringAfterLast('/')}.def"
    }

    /** Valor de `sprite =` na seção [Files] do .def do personagem. */
    fun spriteFile(defText: String): String? {
        var inFiles = false
        for (raw in defText.lines()) {
            val line = raw.substringBefore(';').trim()
            if (line.startsWith("[")) {
                inFiles = line.substringBefore(']').removePrefix("[").trim().equals("files", ignoreCase = true)
                continue
            }
            if (!inFiles) continue
            val eq = line.indexOf('=')
            if (eq < 0) continue
            if (line.substring(0, eq).trim().equals("sprite", ignoreCase = true)) {
                val v = line.substring(eq + 1).trim().trim('"').replace('\\', '/')
                return v.ifEmpty { null }
            }
        }
        return null
    }
}
