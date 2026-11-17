package com.ikemenselect.core

import com.ikemenselect.core.Ini.first

/** Uma camada de fundo ([BG nome]) de um cenário. */
class BgLayer(
    val name: String,
    /** normal, anim, parallax, dummy */
    val type: String,
    val spriteGroup: Int,
    val spriteNo: Int,
    val actionNo: Int,
    /** 0 = atrás dos personagens, 1 = na frente. */
    val layerNo: Int,
    val startX: Float,
    val startY: Float,
    val deltaX: Float,
    val deltaY: Float,
    /** Repetição: 0 = não repete, 1 = infinito, n>1 = n vezes. */
    val tileX: Int,
    val tileY: Int,
    val tileSpacingX: Int,
    val tileSpacingY: Int,
    val blend: Blend,
    val alphaSrc: Int,
    val alphaDst: Int,
)

class StageDef(
    val name: String,
    /** Arquivo .sff dos sprites do cenário ([BGdef] spr). */
    val sprite: String?,
    val localCoordX: Int,
    val localCoordY: Int,
    /** Altura do chão (y onde os personagens ficam), em unidades do cenário. */
    val zoffset: Int,
    val p1StartX: Int,
    val p2StartX: Int,
    val startX: Int,
    val startY: Int,
    val layers: List<BgLayer>,
    /** Animações definidas dentro do próprio .def do cenário. */
    val animations: Map<Int, Animation>,
) {
    companion object {
        fun parse(text: String): StageDef {
            val sections = Ini.parse(text)
            val info = sections.first("Info")
            val stageInfo = sections.first("StageInfo")
            val bgdef = sections.first("BGdef")
            val player = sections.first("PlayerInfo")
            val camera = sections.first("Camera")
            val coord = info?.ints("localcoord")

            val layers = sections.filter { it.name.startsWith("BG ", ignoreCase = true) }.map { s ->
                val spr = s.ints("spriteno")
                val start = s.ints("start")
                val tile = s.ints("tile")
                val tileSp = s.ints("tilespacing")
                var blend = Blend.NONE
                var aSrc = 256
                var aDst = 0
                s["trans"]?.trim()?.uppercase()?.let { t ->
                    when {
                        t == "ADD" -> { blend = Blend.ADD; aSrc = 256; aDst = 256 }
                        t == "ADD1" -> { blend = Blend.ADD1; aSrc = 256; aDst = 128 }
                        t == "SUB" -> { blend = Blend.SUB; aSrc = 256; aDst = 256 }
                        t == "ALPHA" -> {
                            blend = Blend.ALPHA
                            val a = s.ints("alpha")
                            aSrc = a?.getOrNull(0) ?: 256
                            aDst = a?.getOrNull(1) ?: 0
                        }
                    }
                }
                BgLayer(
                    name = s.name.substring(3).trim(),
                    type = (s["type"] ?: "normal").trim().lowercase(),
                    spriteGroup = spr?.getOrNull(0) ?: 0,
                    spriteNo = spr?.getOrNull(1) ?: 0,
                    actionNo = s.int("actionno") ?: 0,
                    layerNo = s.int("layerno") ?: 0,
                    startX = start?.getOrNull(0)?.toFloat() ?: 0f,
                    startY = start?.getOrNull(1)?.toFloat() ?: 0f,
                    deltaX = deltaFloat(s, 0),
                    deltaY = deltaFloat(s, 1),
                    tileX = tile?.getOrNull(0) ?: 0,
                    tileY = tile?.getOrNull(1) ?: 0,
                    tileSpacingX = tileSp?.getOrNull(0) ?: 0,
                    tileSpacingY = tileSp?.getOrNull(1) ?: 0,
                    blend = blend, alphaSrc = aSrc, alphaDst = aDst,
                )
            }

            return StageDef(
                name = (info?.get("displayname") ?: info?.get("name") ?: "").trim().trim('"'),
                sprite = bgdef?.get("spr")?.trim()?.trim('"')?.replace('\\', '/')?.ifEmpty { null },
                localCoordX = coord?.getOrNull(0)?.takeIf { it > 0 } ?: 320,
                localCoordY = coord?.getOrNull(1)?.takeIf { it > 0 } ?: 240,
                zoffset = stageInfo?.int("zoffset") ?: 0,
                p1StartX = player?.int("p1startx") ?: -70,
                p2StartX = player?.int("p2startx") ?: 70,
                startX = camera?.int("startx") ?: 0,
                startY = camera?.int("starty") ?: 0,
                layers = layers,
                animations = AirParser.parse(text),
            )
        }

        /** "delta" pode ter decimais ("0.5, 1"), por isso lê como número de ponto flutuante. */
        private fun deltaFloat(s: IniSection, i: Int): Float =
            s["delta"]?.split(',')?.getOrNull(i)?.trim()?.toFloatOrNull() ?: 1f
    }
}
