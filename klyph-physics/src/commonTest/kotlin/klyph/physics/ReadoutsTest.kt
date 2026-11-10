package klyph.physics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** What the world tells a debug view: contacts and their normals, pin targets, area rings, the broadphase. */
class ReadoutsTest {
    private val dt = TestBodies.DT

    /** A 10 x 10 room with a floor along its bottom row and a wall down its left column. */
    private fun room() = PhysicsWorld().apply {
        solids = TestBodies.grid().apply {
            for (c in 0 until 10) fill(c, 9)
            for (r in 0 until 9) fill(0, r)
        }
    }

    @Test
    fun aParticleOnTheFloorIsTouchingASolidWithItsNormalPointingUp() {
        val world = room()
        val p = world.particles.add(80f, 140f, radius = 2f)
        repeat(30) { world.step(dt) }
        val events = world.events
        assertTrue(events.isTouching(p))
        assertEquals(0f, events.contactNormalX(p), 0.001f)
        assertEquals(-1f, events.contactNormalY(p), 0.001f)
        assertEquals(-1, events.touchedParticle(p), "a solid, not a particle")
    }

    @Test
    fun aParticleAgainstAWallIsTouchingButNotOnAFloor() {
        val world = PhysicsWorld(PhysicsConfig(gravity = Vec(-1500f, 0f))).apply { solids = room().solids }
        val p = world.particles.add(40f, 60f, radius = 2f)
        repeat(30) { world.step(dt) }
        assertTrue(world.events.isTouching(p))
        assertFalse(world.events.isOnFloor(p))
        assertEquals(1f, world.events.contactNormalX(p), 0.001f, "pushed out of the wall, to the right")
    }

    @Test
    fun twoParticlesThatMeetTouchEachOtherAndAFreeOneTouchesNothing() {
        val world = PhysicsWorld(PhysicsConfig(gravity = Vec.ZERO))
        val ps = world.particles
        val a = ps.add(100f, 100f, radius = 4f, collides = true)
        val b = ps.add(106f, 100f, radius = 4f, collides = true)
        val free = ps.add(300f, 300f, radius = 4f, collides = true)
        world.step(dt)
        assertTrue(world.events.isTouching(a) || world.events.isTouching(b))
        val touched = if (world.events.isTouching(b)) world.events.touchedParticle(b) else world.events.touchedParticle(a)
        assertTrue(touched == a || touched == b)
        assertFalse(world.events.isTouching(free))
        // once apart, nothing is touching any more
        repeat(10) { world.step(dt) }
        assertFalse(world.events.isTouching(a))
        assertFalse(world.events.isTouching(b))
    }

    @Test
    fun aPinSaysWhereItHoldsAndAnAreaSaysWhatItsRingIs() {
        val world = PhysicsWorld()
        val ps = world.particles
        val cs = world.constraints
        val p = ps.add(0f, 0f)
        val pin = cs.pin(p, 30f, 40f)
        assertEquals(30f, cs.pinX(pin))
        assertEquals(40f, cs.pinY(pin))
        cs.movePin(pin, 50f, 60f)
        assertEquals(50f, cs.pinX(pin))
        val ring = IntArray(4) { ps.add(if (it % 3 == 0) 0f else 10f, if (it < 2) 0f else 10f) }
        val area = cs.area(ring)
        assertEquals(4, cs.ringSize(area))
        for (i in 0 until 4) assertEquals(ring[i], cs.ringParticle(area, i))
    }

    @Test
    fun theBroadphaseHoldsABoxForEveryBodyAndEveryCollidingParticle() {
        val world = room()
        world.softBody(TestBodies.dome(), 80f, 100f)
        world.particles.add(120f, 60f, radius = 2f, collides = true)
        world.particles.add(130f, 60f, radius = 2f)
        world.step(dt)
        assertEquals(2, world.broadphase.count, "the body and the colliding particle, not the plain one")
    }
}
