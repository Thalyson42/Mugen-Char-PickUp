package com.ikemenselect.core

/** Uma seção de um arquivo .def/.cns: nome (sem colchetes) e pares chave = valor, na ordem do arquivo. */
class IniSection(val name: String, val entries: List<Pair<String, String>>) {
    /** Primeiro valor da chave (sem diferenciar maiúsculas). */
    operator fun get(key: String): String? = entries.firstOrNull { it.first.equals(key, ignoreCase = true) }?.second

    /** Lista de inteiros de "a, b, c"; itens que não são número viram null. */
    fun ints(key: String): List<Int?>? = get(key)?.split(',')?.map { it.trim().toIntOrNull() }

    fun int(key: String): Int? = get(key)?.trim()?.toIntOrNull()

    fun float(key: String): Float? = get(key)?.trim()?.toFloatOrNull()
}

/** Leitor simples de arquivos no estilo .ini do M.U.G.E.N (comentários com ';'). */
object Ini {
    fun parse(text: String): List<IniSection> {
        val out = ArrayList<IniSection>()
        var name: String? = null
        var cur = ArrayList<Pair<String, String>>()
        fun flush() {
            name?.let { out.add(IniSection(it, cur)) }
            cur = ArrayList()
        }
        for (raw in text.lineSequence()) {
            val line = stripComment(raw).trim().trimStart('﻿')
            if (line.isEmpty()) continue
            if (line.startsWith("[")) {
                flush()
                name = line.substringAfter('[').substringBefore(']').trim()
                continue
            }
            val eq = line.indexOf('=')
            if (eq < 0) continue
            cur.add(line.substring(0, eq).trim() to line.substring(eq + 1).trim())
        }
        flush()
        return out
    }

    /** Tira o comentário (a partir de ';'), respeitando aspas. */
    private fun stripComment(line: String): String {
        var inQuote = false
        for (i in line.indices) {
            val c = line[i]
            if (c == '"') inQuote = !inQuote
            else if (c == ';' && !inQuote) return line.substring(0, i)
        }
        return line
    }

    fun List<IniSection>.first(name: String): IniSection? = firstOrNull { it.name.equals(name, ignoreCase = true) }
}
