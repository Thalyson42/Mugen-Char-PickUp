package com.ikemenselect.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlannerTests {

    // ------------------------------------------------------------ expressões

    private fun ctx(vararg v: Pair<String, Any?>) = object : ExprContext {
        val m = v.toMap()
        override fun ident(name: String, args: List<Any?>?): Any? = m[name]
    }

    @Test
    fun exprArithmeticAndComparison() {
        val c = ctx("time" to 5.0, "x" to null)
        assertEquals(true, Expr.truth("time = 5", c))
        assertEquals(false, Expr.truth("time != 5", c))
        assertEquals(true, Expr.truth("time >= 3 && time < 10", c))
        assertEquals(true, Expr.truth("(time + 1) * 2 = 12", c))
        assertEquals(true, Expr.truth("time % 2 = 1", c))
        assertEquals(true, Expr.truth("!(time = 4)", c))
        assertEquals(true, Expr.truth("-time = -5", c))
    }

    @Test
    fun exprIntervals() {
        val c = ctx("time" to 5.0)
        assertEquals(true, Expr.truth("time = [3,5]", c))
        assertEquals(false, Expr.truth("time = [3,5)", c))
        assertEquals(true, Expr.truth("time != (5,9]", c))
        assertEquals(true, Expr.truth("time = (4,6)", c))
        assertEquals(true, Expr.truth("time = (2+3)", c)) // parênteses comuns, não intervalo
    }

    @Test
    fun exprThreeValuedLogic() {
        val c = ctx("time" to 5.0)
        assertNull(Expr.truth("foo = 1", c))
        assertEquals(false, Expr.truth("time = 1 && foo = 1", c)) // falso vence
        assertEquals(true, Expr.truth("time = 5 || foo = 1", c)) // verdadeiro vence
        assertNull(Expr.truth("time = 5 && foo = 1", c))
        assertNull(Expr.truth("1 = 1, 2", c)) // vírgula solta: não suportado
        assertNull(Expr.truth("command = \"x\"", c)) // texto contra desconhecido
    }

    @Test
    fun exprSymbolsAndFunctions() {
        val c = object : ExprContext {
            override fun ident(name: String, args: List<Any?>?): Any? = when (name) {
                "statetype" -> "A"
                "a" -> "A"
                "ifelse" -> if ((args!![0] as Double) != 0.0) args[1] else args[2]
                else -> null
            }
        }
        assertEquals(true, Expr.truth("statetype = a", c))
        assertEquals(true, Expr.truth("ifelse(1, 5, 6) = 5", c))
    }

    // ------------------------------------------------------------ leitor de estados

    private fun frames(vararg times: Int): List<Frame> =
        times.mapIndexed { i, t -> Frame(0, i, 0, 0, t, false, false, Blend.NONE, 256, 0, emptyList(), emptyList()) }

    private fun anim(id: Int, vararg times: Int) = Animation(id, frames(*times), 0)

    private val animMap = mapOf(
        0 to anim(0, 5, 5),
        200 to anim(200, 3, 2, 5), // 10 ticks
        201 to anim(201, 4, 4), // 8 ticks
        210 to anim(210, 6, 6), // 12 ticks
        300 to anim(300, 10, 10),
        800 to anim(800, 4, 4, 4),
        5000 to anim(5000, 6, 6),
        999 to anim(999, 2, -1), // nunca termina
    )

    private fun book(vararg files: String) = StateBook.parse(files.toList())

    private fun plan(b: StateBook, no: Int, opts: PlanOptions = PlanOptions()) =
        MovePlanner.plan(b, no, { animMap[it] }, opts)

    @Test
    fun stateBookReadsStatesControllersAndCommands() {
        val b = book(
            """
            [Command]
            name = "FF"
            command = F, F
            [Statedef -1]
            [State -1, dash]
            type = ChangeState
            value = 100
            trigger1 = command = "FF"
            trigger1 = statetype = S
            [Statedef 200]
            type = S
            anim = 200
            [State 200, fim]
            type = ChangeState
            value = 0
            trigger1 = animtime = 0
            """.trimIndent(),
        )
        assertEquals(setOf("FF"), b.commandNames)
        assertEquals(200, b.states[200]!!.anim)
        assertEquals("S", b.states[200]!!.stateType)
        assertEquals(1, b.states[200]!!.controllers.size)
        assertEquals(listOf("FF"), b.commandsByState[100])
    }

    @Test
    fun laterFileOverridesEarlierState() {
        val b = book("[Statedef 200]\nanim = 1\n", "[Statedef 200]\nanim = 2\n")
        assertEquals(2, b.states[200]!!.anim)
    }

    // ------------------------------------------------------------ planejador

    @Test
    fun simpleMovePlaysItsAnimationThenEndsOnReturnToStand() {
        val b = book(
            """
            [Statedef 200]
            type = S
            anim = 200
            [State 200, volta]
            type = ChangeState
            value = 0
            trigger1 = animtime = 0
            """.trimIndent(),
        )
        val p = plan(b, 200)!!
        assertEquals(listOf(200), p.steps.map { it.anim })
        assertEquals(-1, p.steps[0].ticks)
        assertEquals(10, p.totalTicks)
        assertNull(p.target)
    }

    @Test
    fun changeAnimAtTimeSplitsTheMove() {
        val b = book(
            """
            [Statedef 200]
            anim = 200
            [State 200, troca]
            type = ChangeAnim
            value = 201
            trigger1 = time = 4
            """.trimIndent(),
        )
        val p = plan(b, 200)!!
        assertEquals(listOf(200, 201), p.steps.map { it.anim })
        assertEquals(4, p.steps[0].ticks)
        assertEquals(4 + 8, p.totalTicks)
    }

    @Test
    fun changeStateChainsToTheNextState() {
        val b = book(
            """
            [Statedef 200]
            anim = 200
            [State 200, proximo]
            type = ChangeState
            value = 210
            trigger1 = animtime = 0
            [Statedef 210]
            anim = 210
            [State 210, fim]
            type = ChangeState
            value = 0
            trigger1 = animtime = 0
            """.trimIndent(),
        )
        val p = plan(b, 200)!!
        assertEquals(listOf(200, 210), p.states)
        assertEquals(listOf(200, 210), p.steps.map { it.anim })
        // animtime = 0 vale no último tick da animação: a troca de estado acontece nele
        assertEquals(9, p.steps[0].ticks)
        assertEquals(9 + 12, p.totalTicks)
    }

    @Test
    fun unknownTriggersDoNotFireButVarsAreZero() {
        val b = book(
            """
            [Statedef 200]
            anim = 200
            [State 200, comando]
            type = ChangeAnim
            value = 201
            trigger1 = command = "x"
            [State 200, flag]
            type = ChangeAnim
            value = 210
            trigger1 = var(5) = 0 && time = 2
            """.trimIndent(),
        )
        val p = plan(b, 200)!!
        assertEquals(listOf(200, 210), p.steps.map { it.anim })
    }

    @Test
    fun animElemFiresOnlyOnTheFirstTickOfTheElement() {
        val b = book(
            """
            [Statedef 200]
            anim = 200
            [State 200, elem]
            type = ChangeAnim
            value = 201
            trigger1 = animelem = 2
            """.trimIndent(),
        )
        val p = plan(b, 200)!!
        // elemento 2 de 200 começa no tick 3
        assertEquals(3, p.steps[0].ticks)
        assertEquals(201, p.steps[1].anim)
    }

    @Test
    fun throwGivesTheOpponentAStateWithOwnerAnimationAndBind() {
        val b = book(
            """
            [Statedef 300]
            anim = 300
            [State 300, agarra]
            type = TargetState
            value = 800
            trigger1 = animelem = 2
            [State 300, prende]
            type = TargetBind
            trigger1 = animelem = 2
            pos = 30, -5
            time = 4
            [State 300, fim]
            type = ChangeState
            value = 0
            trigger1 = animtime = 0
            [Statedef 800]
            anim = 5000
            [State 800, anim do dono]
            type = ChangeAnim2
            value = 300
            trigger1 = time = 5
            """.trimIndent(),
        )
        val p = plan(b, 300)!!
        val t = p.target!!
        assertEquals(10, t.startTick) // elemento 2 de 300 começa no tick 10
        assertEquals(800, t.stateNo)
        assertEquals(listOf(5000, 300), t.steps.map { it.anim })
        assertEquals(listOf(false, true), t.steps.map { it.useOwner })
        assertEquals(1, p.binds.size)
        assertEquals(10, p.binds[0].start)
        assertEquals(4, p.binds[0].ticks)
        assertEquals(30f, p.binds[0].dx)
        assertEquals(-5f, p.binds[0].dy)
    }

    @Test
    fun hitdefP2StateAlsoStartsTheTargetPlan() {
        val b = book(
            """
            [Statedef 300]
            anim = 300
            [State 300, hit]
            type = HitDef
            trigger1 = time = 0
            p2stateno = 800
            [Statedef 800]
            anim = 5000
            """.trimIndent(),
        )
        val p = plan(b, 300)!!
        assertEquals(800, p.target!!.stateNo)
    }

    @Test
    fun withoutAssumedHitThereIsNoContactFollowUp() {
        val b = book(
            """
            [Statedef 200]
            anim = 200
            [State 200, combo]
            type = ChangeState
            value = 210
            trigger1 = movehit = 1
            trigger1 = time = 3
            [Statedef 210]
            anim = 210
            """.trimIndent(),
        )
        assertEquals(listOf(200, 210), plan(b, 200, PlanOptions(assumeHit = true))!!.states)
        assertEquals(listOf(200), plan(b, 200, PlanOptions(assumeHit = false))!!.states)
    }

    @Test
    fun infiniteAnimationIsHeldForALimitedTime() {
        val b = book("[Statedef 900]\nanim = 999\n")
        val p = plan(b, 900, PlanOptions(holdInfinite = 20))!!
        assertTrue(p.totalTicks in 22..23)
    }

    @Test
    fun zeroLengthStepIsDropped() {
        val b = book(
            """
            [Statedef 200]
            anim = 200
            [State 200, troca]
            type = ChangeAnim
            value = 201
            trigger1 = time = 0
            """.trimIndent(),
        )
        assertEquals(listOf(201), plan(b, 200)!!.steps.map { it.anim })
    }

    @Test
    fun stateCyclesAreCut() {
        val b = book(
            """
            [Statedef 3000]
            anim = 200
            [State 3000, vai]
            type = ChangeState
            value = 3001
            trigger1 = time = 1
            [Statedef 3001]
            anim = 200
            [State 3001, volta]
            type = ChangeState
            value = 3000
            trigger1 = time = 1
            """.trimIndent(),
        )
        val p = plan(b, 3000)!!
        assertTrue(p.states.size <= 13)
        assertTrue(p.totalTicks < 100)
    }

    @Test
    fun stateWithoutAnimationEndsQuickly() {
        val p = plan(book("[Statedef 1000]\ntype = S\n"), 1000)!!
        assertTrue(p.steps.isEmpty())
        assertTrue(p.totalTicks <= 20)
    }

    @Test
    fun missingStateGivesNullPlan() {
        assertNull(plan(book("[Statedef 1]\nanim=0\n"), 77))
        assertNotNull(plan(book("[Statedef 1]\nanim=0\n"), 1))
    }

    // ------------------------------------------------------------ continuações e catálogo

    private val chainBook = """
        [Statedef 240]
        type = S
        anim = 200
        [State 240, a]
        type = ChangeAnim
        trigger1 = time = 20
        value = 201
        [State 240, b]
        type = ChangeState
        trigger1 = time > 15 && anim = 200 && frontedgebodydist <= 20
        value = 241
        [Statedef 241]
        type = S
        anim = 210
        [State 241, end]
        type = ChangeState
        trigger1 = animtime = 0
        value = 0
        [Statedef 242]
        type = S
        anim = 210
        [State 242, end]
        type = ChangeState
        trigger1 = time = 30
        value = 0
    """

    @Test
    fun animelemWithTimeAndAxisSuffixAreUnderstood() {
        val c = ctx("animelemtime" to 0.0, "p2bodydist.x" to 10.0)
        assertEquals(true, Expr.truth("animelem = 2, = 0", c))
        assertEquals(true, Expr.truth("animelem = 2, 0", c))
        assertEquals(true, Expr.truth("animelem = 2, >= 0", c))
        assertEquals(true, Expr.truth("p2bodydist x < 20", c))
    }

    @Test
    fun moveWaitsForLateTriggersAndChainsIntoTheContinuation() {
        val b = book(chainBook)
        val p = plan(b, 240)!!
        assertEquals(listOf(240, 241), p.states)
        assertTrue(p.totalTicks > 20)
    }

    @Test
    fun optimisticExitFollowsChangeStateWithUnknownTrigger() {
        val b = book(
            """
            [Statedef 300]
            type = S
            anim = 200
            [State 300, x]
            type = ChangeState
            trigger1 = enemyfoo > 3
            value = 242
            [Statedef 242]
            type = S
            anim = 201
            """,
        )
        assertEquals(listOf(300, 242), plan(b, 300)!!.states)
    }

    @Test
    fun catalogHidesContinuationsButKeepsHelpersAndNoAnimStates() {
        val b = book(
            chainBook,
            """
            [Statedef 800]
            type = S
            anim = cond(ishelper(3000), 800, 201)
            [State 800, end]
            type = ChangeState
            trigger1 = time = 5
            value = 0
            [Statedef 900]
            type = S
            [State 900, a]
            type = ChangeAnim
            trigger1 = time = 0
            value = 201
            [State 900, helper]
            type = Helper
            trigger1 = time = 0
            stateno = 800
            """,
        )
        val list = MoveCatalog.build(b) { animMap[it] }
        val nos = list.map { it.stateNo }
        assertTrue(240 in nos)
        assertTrue(241 !in nos) // continuação de 240
        assertTrue(242 in nos) // outro golpe
        assertTrue(800 in nos) // estado de helper
        assertTrue(900 in nos) // anim só por ChangeAnim
        assertTrue(list.first { it.stateNo == 800 }.helper)
        assertEquals(800, list.first { it.stateNo == 800 }.plan.steps.first().anim)
    }

    @Test
    fun catalogMarksStepsOfACommandMoveAsInternal() {
        val b = book(
            """
            [Command]
            name = "fb"
            command = ~D, F, x
            [Statedef -1]
            [State -1, fb]
            type = ChangeState
            triggerall = command = "fb"
            trigger1 = ctrl
            value = 1000
            [Statedef 1000]
            anim = 200
            [State 1000, next]
            type = ChangeState
            trigger1 = var(3) = 1
            value = 1001
            [Statedef 1001]
            anim = 201
            [State 1001, next]
            type = ChangeState
            trigger1 = var(4) = 1
            value = 1002
            [State 1001, grab]
            type = TargetState
            trigger1 = var(4) = 2
            value = 1500
            [Statedef 1002]
            anim = 210
            [Statedef 1500]
            anim = 300
            [Statedef 2000]
            anim = 800
            [State 1000, h]
            type = Helper
            trigger1 = time = 3
            stateno = 3000
            [Statedef 3000]
            anim = 800
            """,
        )
        val c = MoveCatalog.continuationStates(b)
        assertEquals(setOf(3000, 1001, 1002, 1500), c) // o helper do golpe 1000 também é etapa dele
        val list = MoveCatalog.build(b) { animMap[it] }
        assertEquals(false, list.first { it.stateNo == 1000 }.internal)
        assertEquals(true, list.first { it.stateNo == 1001 }.internal)
        assertEquals(false, list.first { it.stateNo == 2000 }.internal) // sem ligação com golpe nenhum: continua na lista
    }

    @Test
    fun planKeepsEveryTargetStateOfAChainedGrab() {
        val b = book(
            """
            [Statedef 300]
            anim = 200
            [State 300, t]
            type = TargetState
            trigger1 = time = 0
            value = 400
            [State 300, n]
            type = ChangeState
            trigger1 = animtime = 0
            value = 301
            [Statedef 301]
            anim = 201
            [State 301, t]
            type = TargetState
            trigger1 = time = 0
            value = 401
            [Statedef 400]
            anim = 210
            [Statedef 401]
            anim = 800
            """,
        )
        val plan = plan(b, 300)!!
        assertEquals(listOf(400, 401), plan.targets.map { it.stateNo })
        assertTrue(plan.targets[1].startTick > plan.targets[0].startTick)
        assertEquals(400, plan.target!!.stateNo) // compatível: o primeiro
        assertEquals(800, plan.targets[1].steps.first().anim)
    }

    @Test
    fun singleStateModePlansOnlyThatStateAndItsOwnTarget() {
        val b = book(
            """
            [Statedef 300]
            anim = 200
            [State 300, t]
            type = TargetState
            trigger1 = time = 0
            value = 400
            [State 300, n]
            type = ChangeState
            trigger1 = time = 2
            value = 301
            [Statedef 301]
            anim = 201
            [State 301, t]
            type = TargetState
            trigger1 = time = 0
            value = 401
            [Statedef 400]
            anim = 210
            [Statedef 401]
            anim = 800
            """,
        )
        val one = MovePlanner.plan(b, 300, { animMap[it] }, PlanOptions(singleState = true))!!
        assertEquals(listOf(300), one.states)
        assertEquals(listOf(400), one.targets.map { it.stateNo })
        assertEquals(10, one.totalTicks) // a animação 200 (10 ticks) toca inteira mesmo com a troca em time = 2
        val second = MovePlanner.plan(b, 301, { animMap[it] }, PlanOptions(singleState = true))!!
        assertEquals(listOf(401), second.targets.map { it.stateNo })
        // no catálogo isolado, 301 tem o próprio botão
        val list = MoveCatalog.build(b, isolated = true) { animMap[it] }
        assertTrue(list.any { it.stateNo == 301 })
    }

    @Test
    fun helperOnlyOrdersFireWhenPlannedAsHelperAndKeepAlternatives() {
        val b = book(
            """
            [Statedef 300]
            anim = 200
            [State 300, a]
            type = TargetState
            trigger1 = ishelper(3000)
            trigger2 = ishelper(3001)
            value = 400
            [State 300, b]
            type = TargetState
            trigger1 = ishelper(4000)
            value = 401
            [Statedef 400]
            anim = 210
            [Statedef 401]
            anim = 800
            """,
        )
        val plain = MovePlanner.plan(b, 300, { animMap[it] }, PlanOptions(singleState = true))!!
        assertEquals(emptyList(), plain.targets.map { it.stateNo }) // como personagem comum, nada dispara
        val list = MoveCatalog.build(b, isolated = true) { animMap[it] }
        val es = list.filter { it.stateNo == 300 }
        assertTrue(es.all { it.helper }) // o catálogo percebe o ishelper nos gatilhos
        // um botão por caminho: os ids 3000/3001 levam ao 400 e o 4000 ao 401
        assertEquals(listOf(3000, 4000), es.map { it.helperId })
        assertEquals(listOf(400), es[0].plan.targets.map { it.stateNo })
        assertEquals(listOf(401), es[1].plan.targets.map { it.stateNo })
    }

    @Test
    fun explicitBindStartingOneTickAfterTargetPhaseIsSnappedToPhaseStart() {
        val b = book(
            """
            [Statedef 300]
            anim = 200
            [State 300, t]
            type = TargetState
            trigger1 = time = 0
            value = 400
            [State 300, b]
            type = TargetBind
            trigger1 = time = 1
            pos = 0, -5
            [Statedef 400]
            [State 400, a]
            type = ChangeAnim2
            trigger1 = !time
            value = 201
            """,
        )
        val plan = MovePlanner.plan(b, 300, { animMap[it] }, PlanOptions(singleState = true))!!
        assertEquals(0, plan.targets[0].startTick)
        assertEquals(0, plan.effectiveBinds.first().start)
        assertEquals(-5f, plan.effectiveBinds.first().dy)
    }

    @Test
    fun ishelperIdSplitsTheMoveIntoOneVariantPerHelper() {
        val b = book(
            """
            [Statedef 100]
            [State 100, h1]
            type = Helper
            trigger1 = 1
            name = "Yoka"
            id = 3000
            stateno = 300
            [State 100, h2]
            type = Helper
            trigger1 = 1
            name = "Flhote"
            id = 4000
            stateno = 300
            [Statedef 300]
            anim = cond(ishelper(3000), 200, 201)
            """,
        )
        val es = MoveCatalog.build(b, isolated = true) { animMap[it] }.filter { it.stateNo == 300 }
        assertEquals(listOf(3000, 4000), es.map { it.helperId })
        assertEquals(listOf("Yoka", "Flhote"), es.map { it.variant })
        assertEquals(200, es[0].plan.steps.first().anim)
        assertEquals(201, es[1].plan.steps.first().anim)
    }

    @Test
    fun varComparedInTheCharacterSplitsTheMoveByValue() {
        val b = book(
            """
            [Statedef 300]
            anim = cond(var(27) = 5, 200, 201)
            """,
        )
        val es = MoveCatalog.build(b, isolated = true) { animMap[it] }.filter { it.stateNo == 300 }
        assertEquals(2, es.size)
        assertEquals(listOf(201, 200), es.map { it.plan.steps.first().anim })
        assertEquals(listOf(0, 5), es.map { it.vars[27] })
    }

    @Test
    fun rootVarIsReadFromTheOwnerWhenTheStateRunsInAHelper() {
        val b = book(
            """
            [Statedef 100]
            [State 100, h]
            type = Helper
            trigger1 = 1
            id = 3000
            stateno = 300
            [Statedef 300]
            anim = cond(root,var(27) = 6, 200, 201)
            """,
        )
        val es = MoveCatalog.build(b, isolated = true) { animMap[it] }.filter { it.stateNo == 300 }
        assertEquals(listOf(201, 200), es.map { it.plan.steps.first().anim })
        assertEquals(listOf(0, 6), es.map { it.rootVars[27] })
    }

    @Test
    fun valueCopiedFromAVarUsesTheLiteralsTheStateWritesIntoIt() {
        val b = book(
            """
            [Statedef 300]
            anim = 200
            [State 300, v]
            type = VarSet
            trigger1 = 1
            v = 2
            value = 400
            [State 300, t]
            type = TargetState
            trigger1 = !time
            value = var(2)
            [Statedef 400]
            anim = 210
            """,
        )
        val es = MoveCatalog.build(b, isolated = true) { animMap[it] }.filter { it.stateNo == 300 }
        assertTrue(es.any { e -> e.plan.targets.any { it.stateNo == 400 } })
    }

    @Test
    fun priorityOffsetAndImplicitBindFromTargetStates() {
        val b = book(
            """
            [Statedef 300]
            sprpriority = -20
            anim = 200
            [State 300, t]
            type = TargetState
            trigger1 = time = 0
            value = 400
            [Statedef 400]
            sprpriority = -18
            [State 400, o]
            type = Offset
            trigger1 = 1
            x = 3
            y = -35
            [State 400, a]
            type = ChangeAnim2
            trigger1 = !time
            value = 201
            """,
        )
        val plan = MovePlanner.plan(b, 300, { animMap[it] }, PlanOptions(singleState = true))!!
        assertEquals(-20, plan.sprPriority)
        assertEquals(-18, plan.targets[0].sprPriority)
        assertEquals(-35f, plan.targets[0].offsetY)
        assertEquals(3f, plan.targets[0].offsetX)
        assertTrue(plan.binds.isEmpty())
        // o alvo toca a animação do atacante e não há TargetBind: fica na origem do atacante
        assertEquals(1, plan.effectiveBinds.size)
        assertEquals(0f, plan.effectiveBinds[0].dx)
    }
}
