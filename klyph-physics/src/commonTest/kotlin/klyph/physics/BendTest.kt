package klyph.physics

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The bend: the angle three particles make at the middle one, held with a compliance per radian. */
class BendTest {
    /** A world with no gravity or damping, where one step is exactly one solve pass. */
    private val world = PhysicsWorld(PhysicsConfig(gravity = Vec.ZERO, damping = 0f, substeps = 1, iterations = 1, maxStep = 1000f))
    private val ps = world.particles
    private val cs = world.constraints

    private fun solveOnce() = world.step(1f / 60f)

    @Test
    fun aStraightLineWithAStraightRestIsLeftAlone() {
        val a = ps.add(0f, 0f)
        val b = ps.add(10f, 0f)
        val c = ps.add(20f, 0f)
        val bend = cs.bend(a, b, c)
        assertEquals(0f, cs.restAngle(bend), 1e-6f)
        repeat(10) { solveOnce() }
        assertEquals(0f, ps.y(a), 1e-6f)
        assertEquals(0f, ps.y(b), 1e-6f)
        assertEquals(0f, ps.y(c), 1e-6f)
    }

    @Test
    fun aRigidBendStraightensAFoldWithoutMovingOrSpinningTheWhole() {
        val a = ps.add(0f, 0f)
        val b = ps.add(10f, 0f)
        val c = ps.add(20f, 6f)
        val bend = cs.bend(a, b, c, rest = 0f)
        val before = abs(cs.bendAngle(bend))
        val cx = (ps.x(a) + ps.x(b) + ps.x(c)) / 3f
        val cy = (ps.y(a) + ps.y(b) + ps.y(c)) / 3f
        solveOnce()
        assertTrue(abs(cs.bendAngle(bend)) < before * 0.2f, "one pass takes most of it: ${cs.bendAngle(bend)} from $before")
        assertEquals(cx, (ps.x(a) + ps.x(b) + ps.x(c)) / 3f, 1e-4f, "the middle of the three stays put")
        assertEquals(cy, (ps.y(a) + ps.y(b) + ps.y(c)) / 3f, 1e-4f)
        repeat(20) { solveOnce() }
        assertEquals(0f, cs.bendAngle(bend), 0.001f)
    }

    @Test
    fun aBendCanBeHeldCurledAndPositiveTurnsClockwiseOnScreen() {
        // a and b can't move: only c swings round to make the quarter turn
        val a = ps.add(0f, 0f, mass = Particles.STATIC)
        val b = ps.add(10f, 0f, mass = Particles.STATIC)
        val c = ps.add(20f, 0f)
        cs.distance(b, c)
        val bend = cs.bend(a, b, c, rest = PI.toFloat() / 2f)
        repeat(60) { solveOnce() }
        assertEquals(PI.toFloat() / 2f, cs.bendAngle(bend), 0.01f)
        assertEquals(10f, ps.x(c), 0.1f)
        assertEquals(10f, ps.y(c), 0.1f, "heading right, a clockwise turn heads down, and y grows down")
    }

    @Test
    fun aBendTurnsBackTheShortWayRoundAcrossHalfATurn() {
        // rest just under half a turn one way, and it's now just under half a turn the other way:
        // 0.28 radians apart across ±π, not 6 the long way round through straight
        val a = ps.add(0f, 0f, mass = Particles.STATIC)
        val b = ps.add(10f, 0f, mass = Particles.STATIC)
        val c = ps.add(10f + 10f * cos(-3f), 10f * sin(-3f))
        cs.distance(b, c)
        val bend = cs.bend(a, b, c, rest = 3f)
        assertEquals(-3f, cs.bendAngle(bend), 0.01f)
        val startX = ps.x(c)
        repeat(30) { solveOnce() }
        assertEquals(3f, cs.bendAngle(bend), 0.01f)
        assertTrue(ps.x(c) < 10f && abs(ps.x(c) - startX) < 1f, "it swung a little past the back, not all the way round")
    }

    @Test
    fun aRestOutsideHalfATurnEitherWayIsTheSameAngle() {
        val a = ps.add(0f, 0f)
        val b = ps.add(10f, 0f)
        val c = ps.add(20f, 0f)
        assertEquals(-PI.toFloat() / 2f, cs.restAngle(cs.bend(a, b, c, rest = 3f * PI.toFloat() / 2f)), 1e-5f)
    }

    /**
     * A rod clamped level at one end, with a weight on the other and a bend at the clamp. The bend's
     * pull is a torque, and it gives `compliance × torque`: θ = α · m g L cos θ.
     */
    private fun droop(substeps: Int, compliance: Float): Float {
        val w = PhysicsWorld(PhysicsConfig(substeps = substeps, damping = 5f))
        val a = w.particles.add(0f, 0f, mass = Particles.STATIC)
        val b = w.particles.add(10f, 0f, mass = Particles.STATIC)
        val c = w.particles.add(50f, 0f)
        w.constraints.distance(b, c)
        val bend = w.constraints.bend(a, b, c, compliance = compliance)
        repeat(600) { w.step(TestBodies.DT) }
        return w.constraints.bendAngle(bend)
    }

    @Test
    fun complianceIsPerRadianOfTorqueWhateverTheSubsteps() {
        // α = 0.00001, m = 1, g = 1500, L = 40: θ = 0.6 cos θ, so θ = 0.521
        for (substeps in listOf(2, 4, 16)) assertEquals(0.521f, droop(substeps, 0.00001f), 0.01f, "at $substeps substeps")
        assertTrue(droop(4, 0f) < 0.01f, "a rigid bend barely gives")
    }

    @Test
    fun aStiffRopeHeldAtOneEndSticksOutAndASlackOneHangs() {
        // 12 particles 8 apart, the first two clamped, held by rigid links; bends at every inside particle
        fun tip(bendCompliance: Float?): Pair<Float, Float> {
            val w = PhysicsWorld()
            val p = IntArray(12) { w.particles.add(it * 8f, 0f, mass = if (it < 2) Particles.STATIC else 1f) }
            for (i in 1 until p.size) w.constraints.distance(p[i - 1], p[i])
            if (bendCompliance != null) for (i in 2 until p.size) w.constraints.bend(p[i - 2], p[i - 1], p[i], compliance = bendCompliance)
            repeat(600) { w.step(TestBodies.DT) }
            return w.particles.x(p.last()) to w.particles.y(p.last())
        }
        val length = 11 * 8f
        val (stiffX, stiffY) = tip(0f)
        val (softX, softY) = tip(0.0000005f)
        val (slackX, slackY) = tip(null)
        assertTrue(stiffY < 0.25f * length && stiffX > 0.9f * length, "stiff sticks out: tip at ($stiffX, $stiffY)")
        assertTrue(softY > stiffY + 5f && softY < slackY, "soft droops between: tip at ($softX, $softY)")
        assertTrue(slackY > 0.7f * length && abs(slackX - 8f) < 0.3f * length, "slack hangs: tip at ($slackX, $slackY)")
    }

    @Test
    fun removingAParticleTakesItsBendsWithIt() {
        val a = ps.add(0f, 0f)
        val b = ps.add(10f, 0f)
        val c = ps.add(20f, 0f)
        val bend = cs.bend(a, b, c)
        world.removeParticle(c)
        assertFalse(cs.isBendAlive(bend))
        assertEquals(0, cs.bendCount)
    }

    @Test
    fun aBendRefusesWhatCannotWork() {
        val a = ps.add(0f, 0f)
        val b = ps.add(10f, 0f)
        assertFailsWith<IllegalArgumentException> { cs.bend(a, b, a) }
        assertFailsWith<IllegalArgumentException> { cs.bend(a, b, ps.add(20f, 0f), compliance = -1f) }
    }

    @Test
    fun manyBendsGrowTheirStore() {
        val p = IntArray(100) { ps.add(it * 5f, 0f) }
        for (i in 2 until p.size) cs.bend(p[i - 2], p[i - 1], p[i])
        assertEquals(98, cs.bendCount)
        assertEquals(p[50], cs.bendB(49))
    }
}
