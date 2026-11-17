package com.ikemenselect.core

/**
 * Retângulo onde desenhar um sprite, em unidades do cenário (x cresce para a direita, y para baixo).
 * [mirrorX]/[mirrorY] dizem se a imagem deve ser desenhada espelhada dentro do retângulo.
 */
class SpriteRect(
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float,
    val mirrorX: Boolean,
    val mirrorY: Boolean,
)

/** Retângulo simples (para caixas de colisão), com left<=right e top<=bottom. */
class RectF(val left: Float, val top: Float, val right: Float, val bottom: Float)

object Placement {

    /**
     * Onde fica o sprite de [frame] de um personagem cuja origem (o ponto no chão) está em ([px], [py]).
     * [facing]: 1 = olha para a direita, -1 = para a esquerda. [sx]/[sy]: unidades do cenário por pixel do sprite.
     * [w]/[h]: tamanho do sprite decodificado; [info]: eixo do sprite no .sff.
     */
    fun sprite(
        px: Float, py: Float, facing: Int,
        frame: Frame, info: SpriteInfo, w: Int, h: Int,
        sx: Float, sy: Float,
    ): SpriteRect {
        val mirrorX = frame.flipH xor (facing < 0)
        val mirrorY = frame.flipV
        val posX = px + facing * frame.x * sx
        val posY = py + frame.y * sy
        val left = if (mirrorX) posX - (w - info.axisX) * sx else posX - info.axisX * sx
        val top = if (mirrorY) posY - (h - info.axisY) * sy else posY - info.axisY * sy
        return SpriteRect(left, top, w * sx, h * sy, mirrorX, mirrorY)
    }

    /** Caixa de colisão (coordenadas do .air: x para frente, y para baixo, origem no chão) em unidades do cenário. */
    fun box(px: Float, py: Float, facing: Int, b: Box, sx: Float, sy: Float): RectF {
        val xa = px + facing * b.x1 * sx
        val xb = px + facing * b.x2 * sx
        return RectF(minOf(xa, xb), py + b.y1 * sy, maxOf(xa, xb), py + b.y2 * sy)
    }

    /** Unidades do cenário por pixel de sprite do personagem. */
    fun charScale(stageCoord: Int, charCoord: Int, sizeScale: Float): Float =
        stageCoord.toFloat() / charCoord.coerceAtLeast(1) * sizeScale

    /** Posições (início) de uma camada de cenário repetida: quantas cópias e de onde, para cobrir [0, viewSize]. */
    fun tiles(origin: Float, size: Float, spacing: Float, tile: Int, viewSize: Float): List<Float> {
        val step = size + spacing
        if (tile <= 0 || step <= 0f) return listOf(origin)
        if (tile > 1) return List(tile) { origin + it * step }
        // tile == 1: infinito, só as cópias que aparecem na tela
        val first = Math.floorDiv(((0f - origin - size) / step).toInt() - 1, 1) // aproximação segura (começa antes da tela)
        val out = ArrayList<Float>()
        var k = first
        while (origin + k * step < viewSize && out.size < 64) {
            if (origin + k * step + size > 0f) out.add(origin + k * step)
            k++
        }
        return out.ifEmpty { listOf(origin) }
    }
}
