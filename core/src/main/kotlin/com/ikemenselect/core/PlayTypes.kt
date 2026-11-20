package com.ikemenselect.core

/** Um trecho do plano: toca [anim] por [ticks] ticks (-1 = até o fim natural da animação). */
class AnimStep(val anim: Int, val ticks: Int, val useOwner: Boolean)

/** Prende o alvo ao atacante: de [start] por [ticks] ticks, em ([dx], [dy]) à frente/abaixo da origem do atacante. */
class Bind(val start: Int, val ticks: Int, val dx: Float, val dy: Float)

/** Referência a um som: [common] = fight.snd do jogo (prefixo F); senão o .snd do personagem dono do estado (prefixo S). */
data class SndRef(val common: Boolean, val group: Int, val number: Int)

/** Som que toca no tick [tick] do golpe. */
class SoundEvent(val tick: Int, val ref: SndRef)

/** O que o adversário faz quando o golpe encosta: entra em [stateNo] no tick [startTick] do atacante. */
class TargetPlan(
    val startTick: Int,
    val stateNo: Int,
    val steps: List<AnimStep>,
    /** Outros estados que o atacante também manda no mesmo tick (ordens para helpers diferentes, por exemplo): vale o último. */
    val alternatives: List<Int> = emptyList(),
    /** `sprpriority` do estado do alvo: quem tem o maior valor é desenhado por cima. */
    val sprPriority: Int = 0,
    /** Controlador Offset do estado do alvo: desloca só o desenho (x à frente, y para baixo), em pixels do personagem. */
    val offsetX: Float = 0f,
    val offsetY: Float = 0f,
    /** Sons do estado do alvo, em ticks do próprio alvo (começam em 0). O .snd "próprio" é o do alvo. */
    val sounds: List<SoundEvent> = emptyList(),
    /** `p2facing` do HitDef que manda o alvo para este estado: 1 = mesmo lado do atacante, -1 = virado para ele, 0 = não muda. */
    val facing: Int = 0,
)
