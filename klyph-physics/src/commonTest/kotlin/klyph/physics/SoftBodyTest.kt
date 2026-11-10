package klyph.physics

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SoftBodyTest {
    private val h = TestBodies.H

    private fun body(): SoftBody = TestBodies.dome()

    @Test
    fun theRestShapeIsADomeAsWideAsTwiceItsRadiusAndHeightScaleTimesItsRadiusTall() {
        val b = body()
        assertEquals(TestBodies.ARC_SEGMENTS + TestBodies.BASE_SEGMENTS, b.ringCount)
        assertEquals(2 * TestBodies.RADIUS, b.maxX() - b.minX(), 0.01f)
        assertEquals(TestBodies.RADIUS * TestBodies.HEIGHT_SCALE, b.maxY() - b.minY(), 0.01f)
    }

    @Test
    fun placingPutsTheBaseOnTheGivenLineAndTheBodyAtRest() {
        val b = body()
        b.placeAt(centerX = 100f, baseY = 50f)
        assertEquals(50f, b.maxY(), 0.01f)
        assertEquals(100f, (b.minX() + b.maxX()) / 2f, 0.01f)
        assertEquals(0f, b.velocityY(0, h))
    }

    @Test
    fun gravityPullsEveryParticleDownAndASubstepNeverMovesOneFartherThanTheCap() {
        val b = body()
        b.placeAt(0f, 0f)
        val before = b.positionY(3)
        b.addVelocityAll(0f, 100_000f, h)
        b.substep(h, TestBodies.GRAVITY) {}
        val moved = b.positionY(3) - before
        assertTrue(moved > 0f, "moved $moved")
        assertTrue(moved <= b.tuning.maxStep + 0.05f, "moved $moved")
    }

    @Test
    fun aSquashedBodyStaysFiniteAndSpringsBackToMostOfItsArea() {
        val b = body()
        b.placeAt(0f, 0f)
        val rest = b.area()
        for (i in 0 until b.size) b.setParticle(i, b.positionX(i), b.positionY(i) * 0.5f)
        assertTrue(b.area() < rest * 0.6f)
        var sum = 0f
        repeat(240) { step ->
            b.substep(h, 0f) {}
            assertTrue(b.isFinite())
            if (step >= 180) sum += b.area()
        }
        assertTrue(sum / 60f > rest * 0.85f, "settled around ${sum / 60f} of $rest")
    }

    @Test
    fun anUndisturbedBodyWithNoGravityStaysWhereItIs() {
        val b = body()
        b.placeAt(0f, 0f)
        val rest = b.area()
        repeat(40) { b.substep(h, 0f) {} }
        assertTrue(abs(b.area() - rest) < rest * 0.01f)
        assertEquals(1f, b.maxEdgeStretch(), 0.01f)
    }

    @Test
    fun aSubstepRunsTheCollisionStepAfterEveryPassSoItGetsTheLastWord() {
        val b = body()
        b.placeAt(0f, 0f)
        var calls = 0
        b.substep(h, TestBodies.GRAVITY) {
            calls++
            b.setParticle(0, 123f, 45f)
        }
        assertEquals(b.tuning.relaxIterations, calls)
        assertEquals(123f, b.positionX(0))
        assertEquals(45f, b.positionY(0))
    }

    @Test
    fun aParticleIsPlacedAtRestByDefaultAndWithSpeedWhenGivenWhereItCameFrom() {
        val b = body()
        b.setParticle(0, 10f, 20f)
        assertEquals(10f, b.positionX(0))
        assertEquals(20f, b.positionY(0))
        assertEquals(0f, b.movedX(0))
        assertEquals(0f, b.movedY(0))
        b.setParticle(1, 10f, 20f, previousX = 4f, previousY = 25f)
        assertEquals(6f, b.movedX(1), 0.001f)
        assertEquals(-5f, b.movedY(1), 0.001f)
    }

    @Test
    fun pushingAParticleOutMovesItAlongTheNormalAndDropsOnlyTheSpeedGoingIn() {
        val b = body()
        // arriving at (50, 82) from (45, 70): 5 across and 12 down, into a floor whose normal points up
        b.setParticle(0, 50f, 82f, previousX = 45f, previousY = 70f)
        b.pushOut(0, nx = 0f, ny = -1f, depth = 2f)
        assertEquals(50f, b.positionX(0), 0.001f)
        assertEquals(80f, b.positionY(0), 0.001f)
        assertEquals(5f, b.movedX(0), 0.001f, "the sideways speed is kept")
        assertEquals(0f, b.movedY(0), 0.001f, "the speed into the floor is gone")
    }

    @Test
    fun pushingOutAParticleThatIsAlreadyLeavingKeepsItsSpeed() {
        val b = body()
        b.setParticle(0, 50f, 82f, previousX = 50f, previousY = 90f)
        b.pushOut(0, nx = 0f, ny = -1f, depth = 2f)
        assertEquals(-8f, b.movedY(0), 0.001f)
    }

    @Test
    fun scalingHorizontalSpeedChangesOnlyThatAndNeverMovesAnything() {
        val b = body()
        b.setParticle(0, 10f, 10f, previousX = 4f, previousY = 7f)
        b.setParticle(1, 30f, 10f, previousX = 34f, previousY = 10f)
        b.scaleVelocityX(0.5f)
        assertEquals(10f, b.positionX(0))
        assertEquals(10f, b.positionY(0))
        assertEquals(3f, b.movedX(0), 0.001f)
        assertEquals(3f, b.movedY(0), 0.001f, "vertical speed is untouched")
        assertEquals(-2f, b.movedX(1), 0.001f)
    }

    @Test
    fun onlyTheParticleAfterTheOutlineIsTheCore() {
        val b = body()
        assertTrue(b.isCore(b.ringCount))
        assertTrue(!b.isCore(0))
        assertTrue(!b.isCore(b.ringCount - 1))
    }
}
