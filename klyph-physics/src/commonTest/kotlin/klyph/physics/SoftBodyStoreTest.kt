package klyph.physics

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Soft bodies: rings and cores of world particles, held by world constraints, reached through handles. */
class SoftBodyStoreTest {
    private val dt = 1f / 60f
    private val world = PhysicsWorld(PhysicsConfig(gravity = Vec.ZERO))
    private val bodies = world.softBodies

    private fun dome(): SoftBodyShape = TestBodies.dome()

    @Test
    fun theRestShapeIsADomeAsWideAsTwiceItsRadiusAndHeightScaleTimesItsRadiusTall() {
        val b = world.softBody(dome(), 0f, 0f)
        assertEquals(TestBodies.ARC_SEGMENTS + TestBodies.BASE_SEGMENTS, bodies.ringCount(b))
        assertEquals(2 * TestBodies.RADIUS, bodies.maxX(b) - bodies.minX(b), 0.01f)
        assertEquals(TestBodies.RADIUS * TestBodies.HEIGHT_SCALE, bodies.maxY(b) - bodies.minY(b), 0.01f)
    }

    @Test
    fun aBodyIsItsParticlesEdgesSpokesAndArea() {
        val b = world.softBody(dome(), 0f, 0f)
        val n = bodies.ringCount(b)
        assertEquals(n + 1, world.particles.count, "a ring and a core")
        assertEquals(2 * n, world.constraints.linkCount, "an edge and a spoke per ring particle")
        assertEquals(1, world.constraints.areaCount)
        assertEquals(1f / TestBodies.CORE_MASS, world.particles.inverseMass(bodies.core(b)), 0.0001f)
        assertEquals(bodies.particle(b, 1), world.constraints.linkB(bodies.edge(b, 0)))
        assertEquals(bodies.core(b), world.constraints.linkB(bodies.spoke(b, 0)))
    }

    @Test
    fun placingPutsTheBaseOnTheGivenLineAndTheBodyAtRest() {
        val b = world.softBody(dome(), 0f, 0f)
        bodies.addVelocity(b, 50f, 50f)
        bodies.placeAt(b, centerX = 100f, baseY = 50f)
        assertEquals(50f, bodies.maxY(b), 0.01f)
        assertEquals(100f, (bodies.minX(b) + bodies.maxX(b)) / 2f, 0.01f)
        assertEquals(0f, bodies.meanVelocityY(b), 0.001f)
    }

    @Test
    fun turningABodyAQuarterTurnStandsItOnItsSideAboutItsMiddleAndAtRest() {
        // a box 30 wide and 10 tall, turned clockwise: its top becomes its right side
        val b = world.softBody(SoftBodies.box(30f, 10f, across = 6, down = 2, coreMass = 4f), 100f, 50f)
        bodies.addVelocity(b, 50f, 50f)
        val cx = bodies.centerX(b)
        val cy = bodies.centerY(b)
        bodies.rotate(b, kotlin.math.PI.toFloat() / 2f)
        assertEquals(10f, bodies.maxX(b) - bodies.minX(b), 0.01f)
        assertEquals(30f, bodies.maxY(b) - bodies.minY(b), 0.01f)
        assertEquals(cx, bodies.centerX(b), 0.01f)
        assertEquals(cy, bodies.centerY(b), 0.01f)
        val topLeft = bodies.particle(b, 0)
        assertTrue(world.particles.x(topLeft) > cx && world.particles.y(topLeft) < cy, "the top-left corner is now the top-right one")
        assertEquals(0f, bodies.meanVelocityY(b), 0.001f)
    }

    @Test
    fun aSquashedBodyStaysFiniteAndSpringsBackToMostOfItsArea() {
        val b = world.softBody(dome(), 0f, 0f)
        val rest = bodies.area(b)
        for (i in 0..bodies.ringCount(b)) {
            val p = bodies.particle(b, i)
            world.particles.moveTo(p, world.particles.x(p), world.particles.y(p) * 0.5f)
        }
        assertTrue(bodies.area(b) < rest * 0.6f)
        var sum = 0f
        repeat(60) { step ->
            world.step(dt)
            assertTrue(bodies.isFinite(b))
            if (step >= 45) sum += bodies.area(b)
        }
        assertTrue(sum / 15f > rest * 0.85f, "settled around ${sum / 15f} of $rest")
    }

    @Test
    fun anUndisturbedBodyWithNoGravityStaysWhereItIs() {
        val b = world.softBody(dome(), 0f, 0f)
        val rest = bodies.area(b)
        repeat(10) { world.step(dt) }
        assertTrue(abs(bodies.area(b) - rest) < rest * 0.01f)
        assertEquals(1f, bodies.maxEdgeStretch(b), 0.01f)
    }

    @Test
    fun theOutlineWorksOnAWorldBody() {
        val b = world.softBody(dome(), 100f, 100f)
        val ring = bodies.ring(b)
        assertSame(ring, bodies.ring(b), "the same view every time")
        assertTrue(Outline.contains(ring, bodies.centerX(b), 95f))
        assertFalse(Outline.contains(ring, 100f, 200f))
        val left = FloatArray(ring.ringCount)
        val right = FloatArray(ring.ringCount)
        assertEquals(1, Outline.spans(ring, 95f, left, right))
    }

    @Test
    fun removingABodyRemovesItsParticlesAndConstraints() {
        val a = world.softBody(dome(), 0f, 0f)
        val b = world.softBody(dome(), 100f, 0f)
        bodies.remove(a)
        assertFalse(bodies.isAlive(a))
        assertEquals(1, bodies.count)
        assertEquals(bodies.ringCount(b) + 1, world.particles.count)
        assertEquals(2 * bodies.ringCount(b), world.constraints.linkCount)
        assertEquals(1, world.constraints.areaCount)
        assertTrue(bodies.isFinite(b))
        assertEquals(a, world.softBody(dome(), 200f, 0f), "the handle is reused")
    }

    @Test
    fun circlesAndBoxesHoldTheirShape() {
        val ball = world.softBody(SoftBodies.circle(20f, 16, coreMass = 10f), 0f, 100f)
        val box = world.softBody(SoftBodies.box(40f, 20f, across = 4, down = 2, coreMass = 10f), 100f, 100f)
        assertEquals(40f, bodies.maxX(ball) - bodies.minX(ball), 0.01f)
        assertEquals(100f, bodies.maxY(ball), 0.01f)
        assertEquals(12, bodies.ringCount(box))
        assertEquals(800f, bodies.area(box), 0.01f)
        repeat(30) { world.step(dt) }
        assertEquals(1f, bodies.maxEdgeStretch(ball), 0.01f)
        assertEquals(800f, bodies.area(box), 8f)
    }
}
