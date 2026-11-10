package klyph.physics

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OutlineTest {
    private fun body(): SoftBody = TestBodies.dome().also { it.placeAt(100f, 50f) }

    private val left = FloatArray(TestBodies.ARC_SEGMENTS + TestBodies.BASE_SEGMENTS)
    private val right = FloatArray(TestBodies.ARC_SEGMENTS + TestBodies.BASE_SEGMENTS)

    private fun filledArea(b: SoftBody): Float {
        var covered = 0f
        for (row in floor(b.minY()).toInt() until ceil(b.maxY()).toInt()) {
            val count = Outline.spans(b, row + 0.5f, left, right)
            for (k in 0 until count) covered += right[k] - left[k]
        }
        return covered
    }

    /** Swaps two stretches of the outline's particles, which twists it into a bow-tie across the dome. */
    private fun fold(b: SoftBody) {
        for (i in 0 until 4) {
            val a = 2 + i
            val c = 9 - i
            val x = b.positionX(a)
            val y = b.positionY(a)
            b.setParticle(a, b.positionX(c), b.positionY(c))
            b.setParticle(c, x, y)
        }
    }

    @Test
    fun aRowThroughTheMiddleOfTheDomeIsOneStretchAroundTheCentre() {
        val b = body()
        assertEquals(1, Outline.spans(b, 40f, left, right))
        assertTrue(left[0] < 100f && right[0] > 100f)
    }

    @Test
    fun rowsAboveAndBelowTheBodyHaveNoStretch() {
        val b = body()
        assertEquals(0, Outline.spans(b, 10f, left, right))
        assertEquals(0, Outline.spans(b, 60f, left, right))
    }

    @Test
    fun theRowsOfAFilledBodyAddUpToItsArea() {
        val b = body()
        assertTrue(abs(filledArea(b) - b.area()) < b.area() * 0.05f, "rows cover ${filledArea(b)} of ${b.area()}")
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
        assertTrue(Outline.contains(b, 100f, 44f))
        assertTrue(!Outline.contains(b, 100f, 20f))
        assertTrue(!Outline.contains(b, 60f, 48f))
        assertTrue(!Outline.contains(b, 100f, 60f))
    }

    @Test
    fun insideMeansTheSameThingToTheFillAndToWhatPushesAgainstTheBodyEvenWhenItIsFolded() {
        val b = body()
        fold(b)
        var checked = 0
        var y = floor(b.minY()) - 2f
        while (y < ceil(b.maxY()) + 2f) {
            val yc = floor(y) + 0.5f
            val count = Outline.spans(b, yc, left, right)
            var x = b.minX() - 2f
            while (x < b.maxX() + 2f) {
                val inSpan = (0 until count).any { x >= left[it] && x < right[it] }
                // points on a stretch's edge may fall either way by rounding, so stay off them
                val nearEdge = (0 until count).any { abs(x - left[it]) < 0.01f || abs(x - right[it]) < 0.01f }
                if (!nearEdge) {
                    assertEquals(inSpan, Outline.contains(b, x, yc), "disagree at ($x, $yc)")
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
        val distanceSquared = Outline.nearestPoint(b, 100f, 46f, point)
        assertEquals(100f, point[0], 0.01f)
        assertEquals(50f, point[1], 0.01f)
        assertEquals(16f, distanceSquared, 0.01f)
    }

    @Test
    fun ringNeighboursWrapAroundTheEnds() {
        val b = body()
        assertEquals(0, b.next(b.ringCount - 1))
        assertEquals(b.ringCount - 1, b.previous(0))
        assertEquals(6, b.next(5))
        assertEquals(4, b.previous(5))
    }
}
