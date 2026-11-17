package com.ikemenselect.core

import kotlin.test.Test
import kotlin.test.assertEquals

class ChainTests {
    private val st = States.mergeAll(
        listOf(
            """
            [Statedef 1000]
            anim = 1000
            [State 1000, a]
            type = Explod
            trigger1 = Time = 3
            anim = 1005
            [State 1000, b]
            type = ChangeState
            trigger1 = Command = "x"
            value = 777
            [State 1000, c]
            type = ChangeState
            trigger1 = AnimTime = 0
            value = 1010
            [Statedef 1010]
            [State 1010, a]
            type = ChangeAnim
            trigger1 = Time = 0
            value = 1011
            [State 1010, h]
            type = Helper
            stateno = 1100
            [State 1010, p]
            type = Projectile
            projanim = 1020
            [State 1010, t]
            type = TargetState
            value = 800
            [State 1010, e]
            type = ChangeState
            triggerall = Vel Y > 0
            trigger1 = AnimTime = 0
            value = 0
            [Statedef 1100]
            anim = 1101
            [Statedef 1000]
            anim = 1
            """.trimIndent(),
        ),
    )

    @Test
    fun followsAnimTimeChangesAndStopsAtCommonStates() {
        val chain = Chain.follow(st, 1000)
        assertEquals(listOf(1000, 1010), chain.map { it.stateNo })
        assertEquals(listOf(1000), chain[0].anims)
        assertEquals(listOf(1011), chain[1].anims)
    }

    @Test
    fun collectsSpawnsAndTargets() {
        val chain = Chain.follow(st, 1000)
        assertEquals(listOf("explod"), chain[0].spawns.map { it.kind })
        assertEquals(1005, chain[0].spawns[0].anim)
        assertEquals(listOf("helper", "projectile", "target"), chain[1].spawns.map { it.kind })
        assertEquals(1100, chain[1].spawns[0].stateNo)
        assertEquals(1020, chain[1].spawns[1].anim)
        assertEquals(800, chain[1].spawns[2].stateNo)
    }

    @Test
    fun helperHasItsOwnChain() {
        val h = Chain.follow(st, 1010).first { true }.spawns.first { it.kind == "helper" }
        assertEquals(listOf(1101), Chain.helperChain(st, h).first().anims)
    }

    @Test
    fun loopsDoNotHang() {
        val loop = States.mergeAll(listOf("[Statedef 300]\nanim=1\n[State 300,a]\ntype=ChangeState\ntrigger1=AnimTime=0\nvalue=301\n[Statedef 301]\nanim=2\n[State 301,a]\ntype=ChangeState\ntrigger1=AnimTime=0\nvalue=300"))
        assertEquals(listOf(300, 301), Chain.follow(loop, 300).map { it.stateNo })
    }
}
