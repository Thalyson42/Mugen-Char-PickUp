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


    @Test
    fun explodExistsCountsAndExpires() {
        val s = forced(
            """
            [Statedef 100]
            anim = 200
            [State 100, e]
            type = Explod
            trigger1 = !time
            id = 77
            anim = 210
            pos = 10, -5
            removetime = 5
            [State 100, n]
            type = VarSet
            trigger1 = numexplod(77) = 1
            var(3) = 9
            """,
            100, ticks = 3,
        )
        assertEquals(1, s.fx.count { it.kind == "explod" && it.id == 77 })
        assertEquals(9, s.root.vars[3])
        s.run(10)
        assertEquals(0, s.fx.size)
    }

    @Test
    fun projectileTravelsAndHitsTheEnemyOnContact() {
        val s = forced(
            """
            [Statedef 100]
            anim = 200
            [State 100, p]
            type = Projectile
            trigger1 = !time
            projid = 5
            projanim = 800
            velocity = 10, 0
            offset = 20, -40
            p2stateno = 400
            p2facing = 1
            [Statedef 400]
            anim = 220
            """,
            100, ticks = 40,
        )
        assertEquals(400, s.enemy.state)
        assertEquals(1, s.enemy.facing)
        assertEquals(0, s.fx.size) // sumiu ao acertar
        assertTrue(s.log.any { it.contains("dispara projétil 5") })
    }

    @Test
    fun bindToParentFollowsTheParent() {
        val s = forced(
            """
            [Statedef 100]
            anim = 200
            [State 100, v]
            type = VelSet
            trigger1 = 1
            x = 2
            [Statedef 300]
            anim = 210
            [State 300, b]
            type = BindToParent
            trigger1 = 1
            time = 100
            pos = 15, 0
            """,
            300, helper = 9, ticks = 3,
        )
        val h = s.entities.first { it.helperId == 9 }
        assertTrue(Math.abs(h.x - (s.root.x + 15)) < 0.01, "helper colado no pai: ${h.x} vs ${s.root.x}")
    }

    @Test
    fun pauseFreezesEverythingButThePauser() {
        val s = forced(
            """
            [Statedef 100]
            anim = 200
            [State 100, p]
            type = Pause
            trigger1 = !time
            time = 10
            movetime = 10
            [State 100, v]
            type = VarAdd
            trigger1 = 1
            v = 1
            value = 1
            """,
            100, ticks = 8,
        )
        assertTrue(s.enemy.time <= 1, "o adversário ficou parado")
        assertTrue(s.root.time >= 6, "quem pausou continua andando")
    }

    @Test
    fun constantsMathAndDistancesAreUnderstood() {
        val consts = Sim.parseConsts(listOf("[Size]\nxscale = 0.5\n[Movement]\nyaccel = .5\n[Velocity]\nrun.fwd = 4.6, 0\n"))
        assertEquals(0.5, consts["size.xscale"])
        assertEquals(4.6, consts["velocity.run.fwd.x"])
        val sim = Sim(book("[Statedef 0]\nanim = 0\n"), { air[it] }, consts = consts)
        sim.spawnRoot(0.0)
        sim.spawnEnemy(30.0)
        assertEquals(0.5, sim.yaccel)
        assertEquals(0.5, sim.debugEval(sim.root, "const(size.xscale)"))
        assertEquals(Math.PI, sim.debugEval(sim.root, "pi"))
        assertEquals(0.0, (sim.debugEval(sim.root, "atan(0)") as Double))
        assertEquals(1.0, sim.debugEval(sim.root, "const720p(720) / 240 * 240 / 240"))
        assertTrue(sim.unknown.isEmpty(), "desconhecidos: ${sim.unknown}")
    }

    @Test
    fun animElemTriggersOnlyAtTheStartOfTheElement() {
        val sim = Sim(book("[Statedef 0]\nanim = 0\n"), { air[it] })
        sim.spawnRoot(0.0)
        assertEquals(1.0, sim.debugEval(sim.root, "animelem"))   // tick 0 = início do elemento 1
        sim.run(2)
        assertEquals(-1.0, sim.debugEval(sim.root, "animelem"))
        sim.run(3)  // o primeiro passo do estado recém-criado não avança a animação
        assertEquals(2.0, sim.debugEval(sim.root, "animelem"))   // elemento 2 começa no tick 4
    }

    @Test
    fun getHitVarCarriesTheHitDefValuesIntoTheHitState() {
        val s = forced(
            """
            [Statedef 100]
            anim = 200
            [State 100, h]
            type = HitDef
            trigger1 = 1
            p2stateno = 400
            ground.velocity = -3, -1
            fall = 1
            pausetime = 0, 0
            [Statedef 400]
            anim = 220
            [State 400, v]
            type = VelSet
            trigger1 = !time
            x = gethitvar(xvel)
            y = gethitvar(yvel)
            [State 400, f]
            type = VarSet
            trigger1 = gethitvar(fall)
            var(1) = 7
            """,
            100, ticks = 5,
        )
        assertEquals(7, s.enemy.vars[1])
        assertEquals(-3.0, s.enemy.vx)
    }

    @Test
    fun withoutP2StateTheDefenderGoesToTheCommonHitState() {
        val s = forced(
            """
            [Statedef 100]
            anim = 200
            [State 100, h]
            type = HitDef
            trigger1 = 1
            [Statedef 5000]
            anim = 220
            """,
            100, ticks = 5,
        )
        assertEquals(5000, s.enemy.state)
    }

    @Test
    fun hitFlagThatDoesNotCoverTheDefenderMissesAndHitPauseFreezes() {
        val miss = forced(
            """
            [Statedef 100]
            anim = 200
            [State 100, h]
            type = HitDef
            trigger1 = 1
            hitflag = A
            p2stateno = 400
            [Statedef 400]
            anim = 220
            """,
            100, ticks = 5,
        )
        assertEquals(0, miss.enemy.state) // o adversário está em pé: golpe só para o ar não pega
        val pause = forced(
            """
            [Statedef 100]
            anim = 200
            [State 100, h]
            type = HitDef
            trigger1 = 1
            p2stateno = 400
            pausetime = 8, 8
            [Statedef 400]
            anim = 220
            """,
            100, ticks = 5,
        )
        assertEquals(400, pause.enemy.state)
        assertTrue(pause.enemy.time <= 1, "quem apanha ficou parado pelo tremor")
    }

    @Test
    fun hitDefAttrTriggerMatchesTheActiveHitDefOfTheRedirectedPlayer() {
        val sim = Sim(book("[Statedef 0]\nanim = 0\n"), { air[it] })
        sim.spawnRoot(0.0)
        sim.spawnEnemy(30.0)
        assertEquals(0.0, sim.debugEval(sim.root, "enemynear,hitdefattr = SCA, NA, SA"))
        sim.enemy.hitDef = HitDefData(null, null, 0, 0, strs = mapOf("attr" to "S, NA"))
        assertEquals(1.0, sim.debugEval(sim.root, "enemynear,hitdefattr = SCA, NA, SA"))
        assertEquals(0.0, sim.debugEval(sim.root, "enemynear,hitdefattr = SCA, AA, AP"))
        assertEquals(1.0, sim.debugEval(sim.root, "enemynear,hitdefattr != SCA, AA, AP"))
        assertTrue(sim.unknown.isEmpty(), "desconhecidos: ${sim.unknown}")
    }

    private val hitBook = """
        [Statedef 0]
        ctrl = 1
        anim = 0
        [Statedef 100]
        anim = 200
        [State 100, h]
        type = HitDef
        trigger1 = 1
        damage = 40, 10
        guardflag = MA
        hitflag = MAF
        p2stateno = 400
        pausetime = 0, 0
        givepower = 20, 5
        getpower = 30, 8
        [Statedef 150]
        anim = 210
        [Statedef 400]
        anim = 220
    """

    @Test
    fun damageAndPowerAreApplied() {
        val s = forced(hitBook, 100, ticks = 5)
        assertEquals(960, s.enemy.life)
        assertEquals(20, s.enemy.power)
        assertEquals(30, s.root.power)
        val sim = Sim(book("[Statedef 0]\nanim = 0\n[Statedef 1]\n[State 1, a]\ntype = LifeAdd\ntrigger1 = 1\nvalue = -50\n[State 1, p]\ntype = PowerAdd\ntrigger1 = 1\nvalue = 100\n"), { air[it] })
        sim.spawnRoot(0.0, state = 1)
        sim.run(1)
        assertEquals(950, sim.root.life)
        assertEquals(100, sim.root.power)
    }

    @Test
    fun guardingEnemyGoesToGuardStateWithReducedDamage() {
        val sim = Sim(book(hitBook), { air[it] })
        sim.enemyMode = "guard"
        sim.spawnRoot(-70.0, state = 100)
        sim.spawnEnemy(70.0)
        sim.run(5)
        assertEquals(150, sim.enemy.state)
        assertEquals(990, sim.enemy.life) // dano de defesa = 10
        assertEquals(1.0, sim.debugEval(sim.enemy, "gethitvar(guarded)"))
        assertEquals(1.0, sim.debugEval(sim.root, "moveguarded"))
    }

    @Test
    fun attackingEnemyHitsTheFirstPlayer() {
        val sim = Sim(book(hitBook), { air[it] })
        sim.enemyMode = "attack"
        sim.enemyAttackState = 100
        sim.enemyAttackEvery = 10
        sim.spawnRoot(-70.0)
        sim.spawnEnemy(70.0)
        sim.run(5)
        assertEquals(0, sim.root.state)
        assertEquals(1.0, sim.debugEval(sim.root, "p2movetype = I || p2movetype = A"))
        sim.run(20)
        assertEquals(400, sim.root.state)
        assertEquals(960, sim.root.life)
    }

    @Test
    fun wallsHoldPlayersAndScreenBoundOffFreesThem() {
        val run = """
            [Statedef 0]
            ctrl = 1
            physics = N
            [State 0, v]
            type = VelSet
            trigger1 = 1
            x = 20
        """
        val sim = Sim(book(run), { air[it] })
        sim.rightWall = 100.0
        sim.spawnRoot(0.0)
        sim.spawnEnemy(-50.0)
        sim.run(30)
        assertEquals(100.0, sim.root.x)
        val free = Sim(book(run + "\n[State 0, b]\ntype = ScreenBound\ntrigger1 = 1\nvalue = 0\n"), { air[it] })
        free.rightWall = 100.0
        free.spawnRoot(0.0)
        free.spawnEnemy(-50.0)
        free.run(30)
        assertTrue(free.root.x > 300.0)
    }

    @Test
    fun bodiesPushEachOtherApartAndEdgeDistancesFollowTheWalls() {
        val sim = Sim(book("[Statedef 0]\nctrl = 1\n"), { air[it] })
        sim.spawnRoot(0.0)
        sim.spawnEnemy(5.0)
        sim.run(2)
        assertTrue(sim.enemy.x - sim.root.x >= 31.0, "separados: ${sim.enemy.x - sim.root.x}")
        val front = sim.debugEval(sim.root, "frontedgedist") as Double
        val back = sim.debugEval(sim.root, "backedgedist") as Double
        assertEquals(600.0, front + back)
    }

    @Test
    fun roundStatesAndKnockoutWinTriggers() {
        val sim = Sim(book("[Statedef 0]\nctrl = 1\n[Statedef 180]\nanim = 0\n[Statedef 5150]\nanim = 0\n[Statedef 100]\nanim = 200\n[State 100, h]\ntype = HitDef\ntrigger1 = 1\ndamage = 2000\np2stateno = 400\n[Statedef 400]\nanim = 220\n"), { air[it] })
        sim.introTicks = 10
        sim.spawnRoot(-20.0, state = 100)
        sim.spawnEnemy(20.0)
        assertEquals(0.0, sim.debugEval(sim.root, "roundstate"))
        sim.run(6)
        assertEquals(1.0, sim.debugEval(sim.root, "roundstate"))
        sim.run(10)
        assertEquals(3.0, sim.debugEval(sim.root, "roundstate"))
        assertEquals(1.0, sim.debugEval(sim.root, "win"))
        assertEquals(1.0, sim.debugEval(sim.enemy, "lose"))
        sim.run(70)
        assertEquals(4.0, sim.debugEval(sim.root, "roundstate"))
        assertEquals(180, sim.root.state)
    }

    @Test
    fun hitOverrideSendsTheDefenderToItsStateInsteadOfTheHit() {
        val sim = Sim(
            book(
                """
                [Statedef 0]
                ctrl = 1
                [Statedef 1]
                [State 1, o]
                type = HitOverride
                trigger1 = 1
                attr = SCA, NA, SA
                slot = 0
                stateno = 700
                time = 5
                [Statedef 100]
                anim = 200
                [State 100, h]
                type = HitDef
                trigger1 = 1
                attr = S, NA
                damage = 50
                p2stateno = 400
                [Statedef 400]
                [Statedef 700]
                anim = 210
                """,
            ),
            { air[it] },
        )
        sim.spawnRoot(-20.0, state = 100)
        sim.spawnEnemy(20.0)
        sim.enemyOverride(1)
        sim.run(5)
        assertEquals(700, sim.enemy.state)
        assertEquals(1000, sim.enemy.life) // sem dano: o golpe foi ignorado
    }

    @Test
    fun remapPalIsRecordedAndExplodPostypesAndAccelWork() {
        val sim = Sim(
            book(
                """
                [Statedef 0]
                ctrl = 1
                [Statedef 1]
                [State 1, r]
                type = RemapPal
                trigger1 = 1
                source = 1, 1
                dest = 1, 3
                [State 1, e]
                type = Explod
                trigger1 = !time
                id = 5
                anim = 210
                postype = right
                pos = -20, -10
                accel = 0.5, 0
                removetime = 3
                supermovetime = 0
                [State 1, f]
                type = Explod
                trigger1 = !time
                id = 6
                anim = F6068
                removeongethit = 1
                """,
            ),
            { air[it] },
        )
        sim.spawnRoot(0.0, state = 1)
        sim.spawnEnemy(50.0)
        sim.run(1)
        assertEquals("1,3", sim.root.palRemaps["1,1"])
        val e = sim.fx.first { it.id == 5 }
        assertTrue(Math.abs(e.x - 140.0) < 1.0, "x=${e.x}") // borda direita da tela (160) - 20
        assertEquals(1, sim.fx.count { it.id == 6 }) // animação do fightfx também é contada
        sim.run(5)
        assertEquals(0, sim.fx.count { it.id == 5 })
    }
}
