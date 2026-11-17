package com.ikemenselect.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AirTests {

    private val air = """
        ; comentário
        [Begin Action 0] ; parado
        Clsn2Default: 1
         Clsn2[0] = -10, 0, 10, -80
        0,0, 0,0, 4
        0,1, 0,0, 4, H
        0,2, 1,2, 6, , A

        [Begin Action 200]
        Clsn2Default: 1
         Clsn2[0] = -10, 0, 10, -80
        200,0, 0,0, 3
        Clsn1: 1
         Clsn1[0] = 10,-70, 50,-60
        Clsn2: 0
        200,1, 0,0, 2
        Loopstart
        200,2, 0,0, 5, HV, AS128D64
        200,3, 0,0, -1, , S

        [Begin Action 5000]
        5000,0, 0,0, 7
        [Begin Action 6000]
        6000,0, 0,0, 1
        [Foo]
        x = 1
    """.trimIndent()

    @Test
    fun parsesActionsAndFrames() {
        val a = AirParser.parse(air)
        assertEquals(listOf(0, 200, 5000, 6000), a.keys.toList())
        val f = a.getValue(0).frames
        assertEquals(3, f.size)
        assertEquals(4, f[0].time)
        assertTrue(f[1].flipH)
        assertFalse(f[1].flipV)
        assertEquals(1, f[2].x)
        assertEquals(2, f[2].y)
        assertEquals(Blend.ADD, f[2].blend)
    }

    @Test
    fun defaultBoxesApplyToEveryFrameAndExplicitOnesOverride() {
        val a = AirParser.parse(air).getValue(200).frames
        // frame 0: só o Default
        assertEquals(1, a[0].clsn2.size)
        assertEquals(0, a[0].clsn1.size)
        // frame 1: Clsn1 explícito, Clsn2: 0 explícito (vazio) sobrepõe o Default
        assertEquals(1, a[1].clsn1.size)
        assertEquals(10, a[1].clsn1[0].x1)
        assertEquals(-70, a[1].clsn1[0].y1)
        assertEquals(0, a[1].clsn2.size)
        // frame 2: volta ao Default
        assertEquals(1, a[2].clsn2.size)
        assertEquals(0, a[2].clsn1.size)
    }

    @Test
    fun boxCornersAreNormalized() {
        val a = AirParser.parse(air).getValue(0).frames[0]
        val b = a.clsn2[0]
        assertEquals(-10, b.x1)
        assertEquals(-80, b.y1)
        assertEquals(10, b.x2)
        assertEquals(0, b.y2)
    }

    @Test
    fun parsesFlipAndBlendModes() {
        val f = AirParser.parse(air).getValue(200).frames
        assertTrue(f[2].flipH && f[2].flipV)
        assertEquals(Blend.ALPHA, f[2].blend)
        assertEquals(128, f[2].alphaSrc)
        assertEquals(64, f[2].alphaDst)
        assertEquals(Blend.SUB, f[3].blend)
        assertEquals(-1, f[3].time)
    }

    @Test
    fun loopRestartsAtLoopstart() {
        val a = AirParser.parse(air).getValue(0)
        assertEquals(0, a.loopStart)
        // 4 + 4 + 6 = 14 ticks, depois volta ao início
        assertEquals(0, a.frameIndexAt(0))
        assertEquals(0, a.frameIndexAt(3))
        assertEquals(1, a.frameIndexAt(4))
        assertEquals(2, a.frameIndexAt(8))
        assertEquals(2, a.frameIndexAt(13))
        assertEquals(0, a.frameIndexAt(14))
        assertEquals(1, a.frameIndexAt(14 + 5))
        assertEquals(2, a.frameIndexAt(100, loop = false))
    }

    @Test
    fun loopstartSkipsTheIntroFrames() {
        val b = AirParser.parse(
            """
            [Begin Action 1]
            1,0, 0,0, 2
            Loopstart
            1,1, 0,0, 3
            1,2, 0,0, 3
            """.trimIndent(),
        ).getValue(1)
        assertEquals(1, b.loopStart)
        // 2 + 3 + 3 = 8; depois do tick 8 volta ao frame 1 (tick 2)
        assertEquals(1, b.frameIndexAt(8))
        assertEquals(2, b.frameIndexAt(8 + 3))
        assertEquals(1, b.frameIndexAt(8 + 6))
    }

    @Test
    fun infiniteLastFrameStaysThere() {
        val a = AirParser.parse(air).getValue(200)
        assertTrue(a.infinite)
        // 3 + 2 + 5 = 10 ticks finitos, depois o frame infinito
        assertEquals(0, a.frameIndexAt(0))
        assertEquals(1, a.frameIndexAt(3))
        assertEquals(2, a.frameIndexAt(5))
        assertEquals(3, a.frameIndexAt(10))
        assertEquals(3, a.frameIndexAt(10_000))
    }

    @Test
    fun ignoresStrayTextAndMissingActions() {
        val a = AirParser.parse("0,0,0,0,1\n[Begin Action 3]\nnot a frame\n")
        assertTrue(a.isEmpty())
    }

    // ------------------------------------------------------------ .def de personagem / cenário

    @Test
    fun charDefFilesAndCoords() {
        val d = CharDef.parse(
            """
            [Info]
            name = "Ako"
            displayname = "Ako Tamaki"
            localcoord = 640, 480
            [Files]
            cmd = Ako.cmd
            cns = ako.cns
            sprite = Sff.sff
            anim = Anims\Ako.air
            """.trimIndent(),
        )
        assertEquals("Ako Tamaki", d.name)
        assertEquals(640, d.localCoordX)
        assertEquals(480, d.localCoordY)
        assertEquals("Sff.sff", d.sprite)
        assertEquals("Anims/Ako.air", d.anim)
    }

    @Test
    fun charDefDefaultsTo320x240() {
        val d = CharDef.parse("[Files]\nsprite = a.sff\n")
        assertEquals(320, d.localCoordX)
        assertEquals(240, d.localCoordY)
    }

    @Test
    fun charSizeReadsScale() {
        val s = CharSize.parse("[Data]\nlife = 1000\n[Size]\nxscale = 0.5\nyscale = 0.5\nground.back = 15\n")
        assertEquals(0.5f, s.xscale)
        assertEquals(0.5f, s.yscale)
        assertEquals(1f, CharSize.parse("[Data]\n").xscale)
    }

    @Test
    fun stageDefLayersAndGround() {
        val st = StageDef.parse(
            """
            [Info]
            name = "Dojo"
            localcoord = 320, 240
            [Camera]
            startx = 0
            [PlayerInfo]
            p1startx = -80
            p2startx = 80
            [StageInfo]
            zoffset = 200
            [BGdef]
            spr = dojo.sff
            [BG sky]
            type = normal
            spriteno = 0, 0
            layerno = 0
            start = 0, 0
            delta = 0.5, 1
            tile = 1, 0
            [BG torch]
            type = anim
            actionno = 10
            layerno = 1
            start = -30, 120
            trans = add
            [Begin Action 10]
            10,0, 0,0, 5
            10,1, 0,0, 5
            """.trimIndent(),
        )
        assertEquals("Dojo", st.name)
        assertEquals("dojo.sff", st.sprite)
        assertEquals(200, st.zoffset)
        assertEquals(-80, st.p1StartX)
        assertEquals(80, st.p2StartX)
        assertEquals(2, st.layers.size)
        val sky = st.layers[0]
        assertEquals("sky", sky.name)
        assertEquals(0.5f, sky.deltaX)
        assertEquals(1f, sky.deltaY)
        assertEquals(1, sky.tileX)
        val torch = st.layers[1]
        assertEquals("anim", torch.type)
        assertEquals(10, torch.actionNo)
        assertEquals(1, torch.layerNo)
        assertEquals(-30f, torch.startX)
        assertEquals(120f, torch.startY)
        assertEquals(Blend.ADD, torch.blend)
        assertNotNull(st.animations[10])
        assertEquals(2, st.animations.getValue(10).frames.size)
    }

    @Test
    fun animNamesCoverAllRanges() {
        assertEquals("Parado", AnimNames.describe(0))
        assertEquals("Golpe especial", AnimNames.describe(1100))
        assertEquals("Especiais", AnimNames.group(1100))
        assertEquals("Reações", AnimNames.group(5010))
    }
}
