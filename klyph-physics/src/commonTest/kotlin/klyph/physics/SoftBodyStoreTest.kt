package klyph.physics

import kotlin.math.abs
import kotlin.math.ln
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** The soft body, ported onto the world's stores: the old [SoftBodyTest]'s checks, through handles. */
class SoftBodyStoreTest {
    private val dt = 1f / 60f
    private val world = PhysicsWorld(PhysicsConfig(gravity = Vec.ZERO))
    private val bodies = world.softBodies

    private fun dome(): SoftBodyShape = SoftBodies.semiDisc(
        TestBodies.RADIUS, TestBodies.ARC_SEGMENTS, TestBodies.BASE_SEGMENTS, TestBodies.CORE_MASS, TestBodies.HEIGHT_SCALE,
    )

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
    fun thePortedBodyMovesLikeTheOldOne() {
        // The same dome, the same gravity, the same 4 x 6 passes and the same damping per substep: the
        // old standalone body and the one made of world particles and constraints should agree.
        val h = dt / 4
        val damping = -ln(0.999f) / h
        val w = PhysicsWorld(PhysicsConfig(gravity = Vec(0f, TestBodies.GRAVITY), substeps = 4, iterations = 6, damping = damping))
        val ported = w.softBody(dome(), 50f, 0f)
        val old = TestBodies.dome().apply { placeAt(50f, 0f) }
        val p0 = w.particles.x(w.softBodies.particle(ported, 0))
        assertEquals(old.positionX(0), p0, 0.001f)
        repeat(60) {
            w.step(dt)
            repeat(4) { old.substep(h, TestBodies.GRAVITY) {} }
        }
        for (i in 0..old.ringCount) {
            val p = w.softBodies.particle(ported, i)
            assertEquals(old.positionX(i), w.particles.x(p), 0.05f, "particle $i x")
            assertEquals(old.positionY(i), w.particles.y(p), 0.05f, "particle $i y")
        }
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
