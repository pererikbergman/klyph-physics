package klyph.physics

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RigidBodiesTest {
    private val dt = TestBodies.DT

    /** No gravity and no damping: nothing should change a body's motion but the solver. */
    private fun space() = PhysicsWorld(PhysicsConfig(gravity = Vec.ZERO, damping = 0f))

    @Test
    fun aSpinningBodyKeepsItsAngularVelocityWithoutGravity() {
        val world = space()
        val bodies = world.rigidBodies
        val b = bodies.add(100f, 100f, inertia = 50f)
        bodies.setAngularVelocity(b, 3f)
        repeat(600) { world.step(dt) }
        assertEquals(3f, bodies.angularVelocity(b), 1e-4f)
        assertEquals(3f * 600 * dt, bodies.angle(b), 1e-2f, "turned 3 rad/s for ten seconds")
        assertEquals(100f, bodies.x(b), 1e-4f, "spinning in place")
        assertEquals(100f, bodies.y(b), 1e-4f)
    }

    @Test
    fun aMovingBodyKeepsGoingInAStraightLine() {
        val world = space()
        val bodies = world.rigidBodies
        val b = bodies.add(0f, 0f)
        bodies.setVelocity(b, 60f, -30f)
        repeat(120) { world.step(dt) }
        assertEquals(120f, bodies.x(b), 0.01f)
        assertEquals(-60f, bodies.y(b), 0.01f)
        assertEquals(60f, bodies.velocityX(b), 1e-3f)
    }

    @Test
    fun aDynamicBodyFallsLikeAParticle() {
        val world = PhysicsWorld(PhysicsConfig(damping = 0f))
        val b = world.rigidBodies.add(50f, 0f)
        val p = world.particles.add(80f, 0f)
        repeat(30) { world.step(dt) }
        assertEquals(world.particles.y(p), world.rigidBodies.y(b), 0.01f, "the same gravity, the same fall")
        assertEquals(0.5f * 1500f * 0.25f, world.rigidBodies.y(b), 2f, "about g t² / 2 after half a second")
        assertEquals(0f, world.rigidBodies.angle(b), "falling doesn't turn it")
    }

    @Test
    fun dampingSlowsSpinningTheWayItSlowsMoving() {
        val world = PhysicsWorld(PhysicsConfig(gravity = Vec.ZERO, damping = 1f))
        val bodies = world.rigidBodies
        val b = bodies.add(0f, 0f)
        bodies.setVelocity(b, 10f, 0f)
        bodies.setAngularVelocity(b, 10f)
        repeat(60) { world.step(dt) }
        assertEquals(10f * kotlin.math.exp(-1f), bodies.velocityX(b), 0.01f, "a share of e⁻¹ left after a second")
        assertEquals(bodies.velocityX(b), bodies.angularVelocity(b), 1e-4f)
    }

    @Test
    fun aKinematicBodyFollowsItsPathExactlyAndIgnoresGravity() {
        val world = PhysicsWorld()
        val bodies = world.rigidBodies
        fun pathX(t: Float) = 200f + 80f * cos(t)
        fun pathY(t: Float) = 150f + 80f * sin(t)
        val b = bodies.add(pathX(0f), pathY(0f), kind = BodyKind.KINEMATIC)
        var t = 0f
        repeat(300) {
            t += dt
            bodies.moveKinematic(b, pathX(t), pathY(t), angle = t)
            world.step(dt)
            assertEquals(pathX(t), bodies.x(b), 1e-3f, "x at step $it")
            assertEquals(pathY(t), bodies.y(b), 1e-3f, "y at step $it")
            assertEquals(t, bodies.angle(b), 1e-4f)
        }
        assertEquals(1f, bodies.angularVelocity(b), 1e-3f, "turning at the 1 rad/s it was told")
    }

    @Test
    fun aKinematicBodyLeftAloneKeepsItsVelocity() {
        val world = PhysicsWorld()
        val bodies = world.rigidBodies
        val b = bodies.add(0f, 0f, kind = BodyKind.KINEMATIC)
        bodies.setVelocity(b, 30f, 0f)
        repeat(60) { world.step(dt) }
        assertEquals(30f, bodies.x(b), 0.01f)
        assertEquals(0f, bodies.y(b), "no gravity on a kinematic body")
        assertEquals(0f, bodies.inverseMass(b), "nothing pushes it")
    }

    @Test
    fun aStaticBodyNeverMoves() {
        val world = PhysicsWorld()
        val bodies = world.rigidBodies
        val b = bodies.add(10f, 20f, angle = 1f, kind = BodyKind.STATIC)
        bodies.setVelocity(b, 100f, 100f)
        bodies.setAngularVelocity(b, 5f)
        repeat(60) { world.step(dt) }
        assertEquals(10f, bodies.x(b))
        assertEquals(20f, bodies.y(b))
        assertEquals(1f, bodies.angle(b))
        assertEquals(BodyKind.STATIC, bodies.kind(b))
    }

    @Test
    fun onlyAKinematicBodyCanBeToldWhereToGo() {
        val bodies = PhysicsWorld().rigidBodies
        val b = bodies.add(0f, 0f)
        assertFailsWith<IllegalArgumentException> { bodies.moveKinematic(b, 1f, 1f) }
    }

    @Test
    fun aFastBodyMovesNoFurtherThanMaxStepASubstep() {
        val world = PhysicsWorld(PhysicsConfig(gravity = Vec.ZERO, damping = 0f, maxStep = 4f))
        val b = world.rigidBodies.add(0f, 0f)
        world.rigidBodies.setVelocity(b, 100_000f, 0f)
        world.step(dt)
        assertEquals(16f, world.rigidBodies.x(b), 1e-3f, "four substeps of at most 4 units")
    }

    @Test
    fun theVelocityAtAPointAddsTheTurning() {
        val bodies = space().rigidBodies
        val b = bodies.add(0f, 0f)
        bodies.setAngularVelocity(b, 2f)
        // y grows down, so turning clockwise on screen moves a point to the right of the centre down
        assertEquals(0f, bodies.pointVelocityX(b, 10f, 0f))
        assertEquals(20f, bodies.pointVelocityY(b, 10f, 0f))
        assertEquals(-20f, bodies.pointVelocityX(b, 0f, 10f))
    }

    @Test
    fun aRemovedBodysHandleIsReused() {
        val bodies = PhysicsWorld().rigidBodies
        val a = bodies.add(0f, 0f)
        val b = bodies.add(1f, 1f)
        bodies.remove(a)
        assertFalse(bodies.isAlive(a))
        assertEquals(1, bodies.count)
        assertEquals(a, bodies.add(2f, 2f))
        assertTrue(bodies.isAlive(b))
    }

    @Test
    fun aBodyNeedsAMassAndInertiaAndAPlace() {
        val bodies = PhysicsWorld().rigidBodies
        assertFailsWith<IllegalArgumentException> { bodies.add(0f, 0f, mass = 0f) }
        assertFailsWith<IllegalArgumentException> { bodies.add(0f, 0f, inertia = -1f) }
        assertFailsWith<IllegalArgumentException> { bodies.add(Float.NaN, 0f) }
        assertFailsWith<IllegalArgumentException> { bodies.add(0f, 0f, angle = PI.toFloat() / 0f) }
    }
}
