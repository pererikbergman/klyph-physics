package klyph.physics

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OutlineTest {
    private val world = PhysicsWorld()
    private val bodies = world.softBodies
    private val ps = world.particles

    private fun body(): Int = world.softBody(TestBodies.dome(), centerX = 100f, baseY = 50f)

    private fun ring(b: Int): Ring = bodies.ring(b)

    private val left = FloatArray(TestBodies.ARC_SEGMENTS + TestBodies.BASE_SEGMENTS)
    private val right = FloatArray(TestBodies.ARC_SEGMENTS + TestBodies.BASE_SEGMENTS)

    private fun filledArea(b: Int): Float {
        var covered = 0f
        for (row in floor(bodies.minY(b)).toInt() until ceil(bodies.maxY(b)).toInt()) {
            val count = Outline.spans(ring(b), row + 0.5f, left, right)
            for (k in 0 until count) covered += right[k] - left[k]
        }
        return covered
    }

    /** Swaps two stretches of the outline's particles, which twists it into a bow-tie across the dome. */
    private fun fold(b: Int) {
        for (i in 0 until 4) {
            val a = 2 + i
            val c = 9 - i
            val pa = bodies.particle(b, a)
            val pc = bodies.particle(b, c)
            val x = ps.x(pa)
            val y = ps.y(pa)
            ps.moveTo(pa, ps.x(pc), ps.y(pc))
            ps.moveTo(pc, x, y)
        }
    }

    @Test
    fun aRowThroughTheMiddleOfTheDomeIsOneStretchAroundTheCentre() {
        val b = body()
        assertEquals(1, Outline.spans(ring(b), 40f, left, right))
        assertTrue(left[0] < 100f && right[0] > 100f)
    }

    @Test
    fun rowsAboveAndBelowTheBodyHaveNoStretch() {
        val b = body()
        assertEquals(0, Outline.spans(ring(b), 10f, left, right))
        assertEquals(0, Outline.spans(ring(b), 60f, left, right))
    }

    @Test
    fun theRowsOfAFilledBodyAddUpToItsArea() {
        val b = body()
        assertTrue(abs(filledArea(b) - bodies.area(b)) < bodies.area(b) * 0.05f, "rows cover ${filledArea(b)} of ${bodies.area(b)}")
    }

    @Test
    fun anOutlineFoldedOverItselfIsStillFilledSolid() {
        val b = body()
        val before = filledArea(b)
        fold(b)
        assertTrue(filledArea(b) > before * 0.6f, "only ${filledArea(b)} of $before is filled after the fold")
    }

    @Test
    fun theMiddleOfTheBodyIsInsideAndThePointsAroundItAreNot() {
        val b = body()
        assertTrue(Outline.contains(ring(b), 100f, 44f))
        assertTrue(!Outline.contains(ring(b), 100f, 20f))
        assertTrue(!Outline.contains(ring(b), 60f, 48f))
        assertTrue(!Outline.contains(ring(b), 100f, 60f))
    }

    @Test
    fun insideMeansTheSameThingToTheFillAndToWhatPushesAgainstTheBodyEvenWhenItIsFolded() {
        val b = body()
        fold(b)
        var checked = 0
        var y = floor(bodies.minY(b)) - 2f
        while (y < ceil(bodies.maxY(b)) + 2f) {
            val yc = floor(y) + 0.5f
            val count = Outline.spans(ring(b), yc, left, right)
            var x = bodies.minX(b) - 2f
            while (x < bodies.maxX(b) + 2f) {
                val inSpan = (0 until count).any { x >= left[it] && x < right[it] }
                // points on a stretch's edge may fall either way by rounding, so stay off them
                val nearEdge = (0 until count).any { abs(x - left[it]) < 0.01f || abs(x - right[it]) < 0.01f }
                if (!nearEdge) {
                    assertEquals(inSpan, Outline.contains(ring(b), x, yc), "disagree at ($x, $yc)")
                    checked++
                }
                x += 0.7f
            }
            y += 1f
        }
        assertTrue(checked > 200, "only $checked points compared")
    }

    @Test
    fun theNearestPointOnTheOutlineOfAPointInsideIsOnTheOutline() {
        val b = body()
        val point = FloatArray(2)
        // 4 units above the flat base in the middle: the base is the nearest edge
        val distanceSquared = Outline.nearestPoint(ring(b), 100f, 46f, point)
        assertEquals(100f, point[0], 0.01f)
        assertEquals(50f, point[1], 0.01f)
        assertEquals(16f, distanceSquared, 0.01f)
    }
}
