package com.ikemenselect.core.sim

import com.ikemenselect.core.AirParser
import com.ikemenselect.core.StateBook
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SimTests {
    private val air = AirParser.parse(
        listOf(0, 200, 210, 220, 300, 800).joinToString("\n") { "[Begin Action $it]\n$it,0, 0,0, 4\n$it,1, 0,0, -1\n" },
    )
    private fun book(text: String) = StateBook.parse(listOf(text))
    private fun forced(text: String, state: Int, helper: Int? = null, ticks: Int = 30) =
        Sim.forced(book(text), { air[it] }, state, helper, ticks)

    @Test
    fun hitDefMovesTargetAndAppliesP2Facing() {
        val s = forced(
            """
            [Statedef 100]
            anim = 200
            [State 100, h]
            type = HitDef
            trigger1 = 1
            p2stateno = 400
            p1stateno = 300
            p2facing = 1
            [Statedef 300]
            anim = 210
            [Statedef 400]
            anim = 220
            """,
            100,
        )
        assertEquals(400, s.enemy.state)
        assertEquals(300, s.root.state)
        assertEquals(1, s.enemy.facing) // mesmo lado do atacante
    }

    @Test
    fun withoutAHitDefTheTargetKeepsItsFacing() {
        // o contexto importa: entrar direto num estado do meio da cadeia não passa pelo HitDef que virou o alvo
        val s = forced(
            """
            [Statedef 300]
            anim = 210
            [State 300, t]
            type = TargetState
            trigger1 = !time
            value = 400
            [Statedef 400]
            anim = 220
            """,
            300,
        )
        assertEquals(-1, s.enemy.facing)
        assertEquals(0, s.root.targets.size)
    }

    @Test
    fun helperIsCreatedAndIshelperUsesItsId() {
        val s = forced(
            """
            [Statedef 100]
            [State 100, h]
            type = Helper
            trigger1 = !time
            id = 3000
            stateno = 200
            [Statedef 200]
            anim = cond(ishelper(3000), 210, 220)
            """,
            100,
        )
        val h = s.entities.first { it.helperId == 3000 }
        assertEquals(210, h.anim)
    }

    @Test
    fun varsFlowAlongTheRealPath() {
        val s = forced(
            """
            [Statedef 100]
            [State 100, v]
            type = VarSet
            trigger1 = !time
            var(27) = 5
            [State 100, c]
            type = ChangeState
            trigger1 = var(27) = 5
            value = 300
            [Statedef 300]
            anim = 210
            """,
            100,
        )
        assertEquals(300, s.root.state)
        assertEquals(5, s.root.vars[27])
    }

    @Test
    fun targetIsNotAHelperAndChangeAnim2UsesTheAttackerAnimation() {
        val s = forced(
            """
            [Statedef 100]
            anim = 200
            [State 100, h]
            type = HitDef
            trigger1 = 1
            p2stateno = 400
            [Statedef 400]
            [State 400, a]
            type = ChangeAnim2
            triggerall = !ishelper
            trigger1 = !time
            value = 800
            """,
            100,
        )
        assertEquals(800, s.enemy.anim)
        assertEquals("P1", s.enemy.animOwner.name)
    }

    @Test
    fun rootVarIsReadFromTheOwnerInAHelper() {
        val s = forced(
            """
            [Statedef 100]
            [State 100, v]
            type = VarSet
            trigger1 = !time
            var(27) = 6
            [State 100, h]
            type = Helper
            trigger1 = !time
            id = 3001
            stateno = 200
            [Statedef 200]
            anim = cond(root,var(27) = 6, 210, 220)
            """,
            100,
        )
        assertEquals(210, s.entities.first { it.helperId == 3001 }.anim)
    }

    @Test
    fun targetBindKeepsTheTargetOnTheAttacker() {
        val s = forced(
            """
            [Statedef 100]
            anim = 200
            [State 100, h]
            type = HitDef
            trigger1 = 1
            p2stateno = 400
            [State 100, b]
            type = TargetBind
            trigger1 = time > 0
            pos = 20, 0
            [Statedef 400]
            [State 400, a]
            type = ChangeAnim2
            trigger1 = !time
            value = 800
            """,
            100,
        )
        assertTrue(s.log.any { it.contains("acerta") })
        assertTrue(kotlin.math.abs(s.root.x + 20 - s.enemy.x) < 0.001)
    }
    @Test
    fun commandInStateMinusOneStartsTheMoveAndMinusTwoSpawnsHelpers() {
        val b = book(
            """
            [Statedef -2]
            [State -2, h]
            type = Helper
            trigger1 = numhelper(9000) = 0
            id = 9000
            stateno = 200
            [Statedef -1]
            [State -1, m]
            type = ChangeState
            value = 300
            triggerall = helper(9000), var(1) = 0 && ctrl
            trigger1 = command = "golpe"
            [Statedef 0]
            [Statedef 200]
            [Statedef 300]
            anim = 210
            """,
        )
        val s = Sim.fromCommand(b, { air[it] }, "golpe", ticks = 20)
        assertEquals(300, s.root.state)
        assertEquals(210, s.root.anim)
        assertEquals(1, s.entities.count { it.helperId == 9000 })
    }

}
