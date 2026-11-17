package com.ikemenselect.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MovesTests {
    private val cmd = """
        [Command]
        name = "qcfx"
        command = ~D, DF, F, x
        time = 15
        [Command]
        name = "qcfx"
        command = ~D, DF, F, ~x
        [Command]
        name = "holdfwd"
        command = /${'$'}F
        [Command]
        name = "x"
        command = x

        [Statedef -1]
        [State -1, Fireball]
        type = ChangeState
        value = 1000
        triggerall = command = "qcfx"
        triggerall = power >= 1000 ; comentário
        trigger1 = ctrl
        [State -1, Walk helper]
        type = ChangeState
        value = 20
        triggerall = command = "holdfwd"
        trigger1 = 1
        [State -1, Stand x]
        type = ChangeState
        value = 200
        triggerall = command = "x"
        triggerall = command != "holddown"
        trigger1 = statetype = S
        [State -1, Dynamic]
        type = ChangeState
        value = 100 + var(1)
        triggerall = command = "x"
        trigger1 = 1
    """.trimIndent()

    private val cns = """
        [Statedef 1000]
        type = S
        anim = 1000
        [State 1000, 1]
        type = ChangeAnim
        trigger1 = 1
        value = 1001
        [Statedef 200]
        type = S
        [State 200, a]
        type = ChangeAnim
        trigger1 = Time = 0
        value = 205
        [Statedef 1000]
        type = S
        anim = 9999
    """.trimIndent()

    @Test
    fun listsMovesFromStateMinusOne() {
        val moves = Moves.build(cmd, listOf(cns))
        assertEquals(listOf(1000, 200), moves.map { it.stateNo })
        assertEquals(listOf("qcfx"), moves[0].commandNames)
        assertEquals(listOf("~↓ ↘ → x", "~↓ ↘ → ~x"), moves[0].sequences)
    }

    @Test
    fun ignoresHoldCommandsAndNegativeConditionsAndDynamicTargets() {
        val moves = Moves.build(cmd, listOf(cns))
        assertEquals(listOf("x"), moves[1].commandNames) // "!= holddown" não conta
        assertNull(moves.firstOrNull { it.stateNo == 20 })
    }

    @Test
    fun animComesFromStatedefOrFirstChangeAnim() {
        val moves = Moves.build(cmd, listOf(cns))
        assertEquals(1000, moves[0].anim) // primeiro Statedef 1000 vence
        assertEquals(205, moves[1].anim) // sem anim no Statedef: usa o ChangeAnim
    }
}
