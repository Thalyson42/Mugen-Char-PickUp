package com.ikemenselect.core

import com.ikemenselect.core.Ini.first

/** Dados do .def de um personagem que o visualizador precisa. */
class CharDef(
    val name: String,
    /** Arquivos da seção [Files], com chave em minúsculas (sprite, anim, cns, cmd, st, st0..st9, stcommon, pal1...). */
    val files: Map<String, String>,
    /** localcoord do personagem (padrão 320x240). */
    val localCoordX: Int,
    val localCoordY: Int,
) {
    val sprite: String? get() = files["sprite"]
    val anim: String? get() = files["anim"]
    val cns: String? get() = files["cns"]
    val cmd: String? get() = files["cmd"]

    companion object {
        fun parse(text: String): CharDef {
            val sections = Ini.parse(text)
            val info = sections.first("Info")
            val files = LinkedHashMap<String, String>()
            sections.first("Files")?.entries?.forEach { (k, v) ->
                val value = v.trim().trim('"').replace('\\', '/')
                if (value.isNotEmpty()) files.putIfAbsent(k.lowercase(), value)
            }
            val coord = info?.ints("localcoord")
            val name = (info?.get("displayname") ?: info?.get("name") ?: "").trim().trim('"')
            return CharDef(
                name = name,
                files = files,
                localCoordX = coord?.getOrNull(0)?.takeIf { it > 0 } ?: 320,
                localCoordY = coord?.getOrNull(1)?.takeIf { it > 0 } ?: 240,
            )
        }
    }
}

/** Escala do personagem, da seção [Size] do .cns. */
class CharSize(val xscale: Float, val yscale: Float) {
    companion object {
        val DEFAULT = CharSize(1f, 1f)

        fun parse(cnsText: String): CharSize {
            val size = Ini.parse(cnsText).first("Size") ?: return DEFAULT
            return CharSize(
                xscale = size.float("xscale")?.takeIf { it > 0f } ?: 1f,
                yscale = size.float("yscale")?.takeIf { it > 0f } ?: 1f,
            )
        }
    }
}

/** Nomes das animações pelas convenções do M.U.G.E.N. Cada personagem pode usar os números de outro jeito. */
object AnimNames {
    private val known = mapOf(
        0 to "Parado",
        5 to "Virar (em pé)",
        6 to "Virar (agachado)",
        10 to "Agachando",
        11 to "Agachado",
        12 to "Levantando",
        20 to "Andar para frente",
        21 to "Andar para trás",
        40 to "Início do pulo",
        41 to "Pulo parado",
        42 to "Pulo para frente",
        43 to "Pulo para trás",
        47 to "Aterrissagem",
        100 to "Correr para frente",
        105 to "Recuo rápido",
        120 to "Início da defesa",
        130 to "Defesa em pé",
        131 to "Defesa agachado",
        132 to "Defesa no ar",
        200 to "Soco fraco (em pé)",
        210 to "Soco forte (em pé)",
        230 to "Chute fraco (em pé)",
        240 to "Chute forte (em pé)",
        400 to "Soco fraco (agachado)",
        410 to "Soco forte (agachado)",
        430 to "Chute fraco (agachado)",
        440 to "Chute forte (agachado)",
        600 to "Soco fraco (no ar)",
        610 to "Soco forte (no ar)",
        630 to "Chute fraco (no ar)",
        640 to "Chute forte (no ar)",
        5000 to "Levando golpe (em pé, fraco)",
        5100 to "Levando golpe (em pé, forte)",
        5300 to "Caindo",
    )

    /** Nome só quando a convenção é bem conhecida; senão null. */
    fun exact(id: Int): String? = known[id]

    fun describe(id: Int): String = known[id] ?: when (id) {
        in 0..99 -> "Movimento"
        in 100..199 -> "Corrida / defesa"
        in 200..399 -> "Golpe em pé"
        in 400..599 -> "Golpe agachado"
        in 600..799 -> "Golpe no ar"
        in 800..999 -> "Outro golpe"
        in 1000..2999 -> "Golpe especial"
        in 3000..4999 -> "Golpe super"
        in 5000..5999 -> "Reação a golpe"
        else -> "Outra"
    }

    /** Grupo de exibição (para agrupar botões). */
    fun group(id: Int): String = when (id) {
        in 0..199 -> "Movimento"
        in 200..799 -> "Normais"
        in 800..999 -> "Outros"
        in 1000..2999 -> "Especiais"
        in 3000..4999 -> "Supers"
        in 5000..5999 -> "Reações"
        else -> "Outras"
    }
}
