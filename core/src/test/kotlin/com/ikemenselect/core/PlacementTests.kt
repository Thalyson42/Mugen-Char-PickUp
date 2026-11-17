package com.ikemenselect.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlacementTests {

    private fun frame(x: Int = 0, y: Int = 0, flipH: Boolean = false, flipV: Boolean = false) =
        Frame(0, 0, x, y, 1, flipH, flipV, Blend.NONE, 256, 0, emptyList(), emptyList())

    private val info = SpriteInfo(0, 0, 40, 80, 10, 70) // eixo em (10, 70)

    @Test
    fun facingRightPutsTheAxisOnTheOrigin() {
        val r = Placement.sprite(100f, 200f, 1, frame(), info, 40, 80, 1f, 1f)
        assertEquals(90f, r.left)
        assertEquals(130f, r.top)
        assertEquals(40f, r.width)
        assertFalse(r.mirrorX)
    }

    @Test
    fun facingLeftMirrorsAroundTheAxis() {
        val r = Placement.sprite(100f, 200f, -1, frame(), info, 40, 80, 1f, 1f)
        // o eixo (10 pixels da esquerda) vira 30 pixels da esquerda depois de espelhar
        assertEquals(70f, r.left)
        assertTrue(r.mirrorX)
    }

    @Test
    fun frameOffsetGoesForwardInTheFacingDirection() {
        val right = Placement.sprite(100f, 200f, 1, frame(x = 5, y = -3), info, 40, 80, 1f, 1f)
        val left = Placement.sprite(100f, 200f, -1, frame(x = 5, y = -3), info, 40, 80, 1f, 1f)
        assertEquals(95f, right.left)
        assertEquals(127f, right.top)
        assertEquals(65f, left.left) // 100 - 5 - 30
    }

    @Test
    fun flipInTheFrameCancelsFacingLeft() {
        val r = Placement.sprite(100f, 200f, -1, frame(flipH = true), info, 40, 80, 1f, 1f)
        assertFalse(r.mirrorX)
        assertEquals(90f, r.left)
    }

    @Test
    fun scaleMultipliesSizeAndAxisDistance() {
        val r = Placement.sprite(100f, 200f, 1, frame(), info, 40, 80, 0.5f, 0.5f)
        assertEquals(95f, r.left)
        assertEquals(20f, r.width)
        assertEquals(40f, r.height)
        assertEquals(165f, r.top)
    }

    @Test
    fun boxesFollowFacing() {
        val b = Box(-10, -80, 10, 0)
        val r = Placement.box(100f, 200f, 1, b, 1f, 1f)
        assertEquals(90f, r.left)
        assertEquals(110f, r.right)
        assertEquals(120f, r.top)
        assertEquals(200f, r.bottom)
        val hit = Box(10, -70, 50, -60)
        val f = Placement.box(100f, 200f, -1, hit, 1f, 1f)
        assertEquals(50f, f.left)
        assertEquals(90f, f.right)
    }

    @Test
    fun charScaleConvertsCoordinateSystems() {
        assertEquals(1f, Placement.charScale(320, 320, 1f))
        assertEquals(0.5f, Placement.charScale(320, 640, 1f))
        assertEquals(1f, Placement.charScale(640, 640, 1f))
        assertEquals(0.75f, Placement.charScale(320, 320, 0.75f))
    }

    @Test
    fun tilesFiniteAndInfinite() {
        assertEquals(listOf(10f), Placement.tiles(10f, 50f, 0f, 0, 320f))
        assertEquals(listOf(10f, 60f, 110f), Placement.tiles(10f, 50f, 0f, 3, 320f))
        val inf = Placement.tiles(10f, 100f, 0f, 1, 320f)
        assertTrue(inf.first() <= 0f && inf.first() + 100f > 0f)
        assertTrue(inf.last() < 320f && inf.last() + 100f >= 320f)
    }
}
