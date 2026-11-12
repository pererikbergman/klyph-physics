package klyph.physics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Still islands of rigid bodies fall asleep, cost nothing, and wake when anything could move them. */
class SleepingTest {
    private val dt = TestBodies.DT
    private val tile = TestBodies.TILE

    /** A room 40 tiles wide and 30 high with a floor along its bottom two rows: its top at y = 448. */
    private fun room(config: PhysicsConfig = PhysicsConfig()) = PhysicsWorld(config).apply {
        solids = SolidGrid(40, 30, tile).apply {
            for (c in 0 until cols) for (r in 28 until 30) fill(c, r)
            for (r in 0 until 30) {
                fill(0, r)
                fill(cols - 1, r)
            }
        }
    }

    private val floor = 28 * 16f

    /** A pyramid of [rows] rows of boxes [size] wide, standing on the floor in the middle of the room. */
    private fun pyramid(world: PhysicsWorld, rows: Int = 10, size: Float = 24f): List<Int> {
        val boxes = ArrayList<Int>()
        for (row in 0 until rows) for (k in 0 until rows - row) {
            val x = 320f - (rows - row - 1) * size / 2f + k * size
            boxes += world.rigidBody(Shape.box(size, size), x, floor - size / 2f - row * size)
        }
        return boxes
    }

    private fun steps(world: PhysicsWorld, seconds: Float) = repeat((seconds / dt).toInt()) { world.step(dt) }

    @Test
    fun aSettledPyramidSleepsWithinTwoSecondsAndCostsNothing() {
        val world = room()
        val bodies = world.rigidBodies
        val boxes = pyramid(world)
        val inverseMass = bodies.inverseMass(boxes[0])
        steps(world, 2f)
        for (b in boxes) assertTrue(bodies.isAsleep(b), "box $b is asleep")
        assertEquals(inverseMass, bodies.inverseMass(boxes[0]), "asleep, it still says how heavy it is")
        assertEquals(boxes.size, bodies.sleepingCount)
        world.step(dt)
        assertEquals(0, world.rigidContacts.count, "no contact is even looked for")
    }

    @Test
    fun aSleepingPyramidWakesWhenHitAndFallsAsOne() {
        val world = room()
        val bodies = world.rigidBodies
        val boxes = pyramid(world)
        steps(world, 2f)
        val top = boxes.last()
        val bottomLeft = boxes.first()
        val topY = bodies.y(top)
        // a heavy ball, thrown at the pyramid's top from the left
        val ball = world.rigidBody(Shape.circle(10f), 250f, topY, density = 1f)
        bodies.setVelocity(ball, 900f, 0f)
        steps(world, 0.3f)
        assertFalse(bodies.isAsleep(top), "the box it hit woke")
        assertFalse(bodies.isAsleep(bottomLeft), "and with it, the whole pyramid: its island")
        assertTrue(bodies.x(top) > 330f, "the top box was knocked off: ${bodies.x(top)}")
        steps(world, 5f)
        for (b in boxes) assertTrue(bodies.y(b) < floor, "box $b is still above the floor, not through it")
    }

    @Test
    fun aBodyThatSleepsOnAnotherFallsWhenThatOneIsRemoved() {
        val world = room()
        val bodies = world.rigidBodies
        val lower = world.rigidBody(Shape.box(24f, 24f), 320f, floor - 12f)
        val upper = world.rigidBody(Shape.box(24f, 24f), 320f, floor - 36f)
        steps(world, 2f)
        assertTrue(bodies.isAsleep(upper))
        bodies.remove(lower)
        assertFalse(bodies.isAsleep(upper), "it wakes")
        steps(world, 1f)
        assertEquals(floor - 12f, bodies.y(upper), 0.5f, "and falls to the floor")
    }

    @Test
    fun aBodyThatSleepsOnAStaticOneFallsWhenThatOneIsRemovedOrMoved() {
        for (move in listOf(false, true)) {
            val world = room()
            val bodies = world.rigidBodies
            val shelf = world.rigidBody(Shape.box(80f, 8f), 320f, 300f, kind = BodyKind.STATIC)
            val box = world.rigidBody(Shape.box(24f, 24f), 320f, 284f)
            steps(world, 2f)
            assertTrue(bodies.isAsleep(box))
            if (move) bodies.moveTo(shelf, 500f, 300f) else bodies.remove(shelf)
            steps(world, 1f)
            assertEquals(floor - 12f, bodies.y(box), 0.5f, "it falls to the floor (moved: $move)")
        }
    }

    @Test
    fun aBodyThatSleepsOnTheSolidsFallsWhenItsTileIsRemoved() {
        val world = room()
        val bodies = world.rigidBodies
        val grid = world.solids!!
        // a ledge, one tile thick, at row 20
        for (c in 15 until 25) grid.fill(c, 20)
        val box = world.rigidBody(Shape.box(12f, 12f), 20 * tile + 8f, 20 * tile - 6f)
        steps(world, 2f)
        assertTrue(bodies.isAsleep(box))
        grid.removeAt(20, 20)
        steps(world, 1f)
        assertEquals(floor - 6f, bodies.y(box), 0.5f, "it falls through the gap to the floor")
    }

    @Test
    fun aBodyHeldByAJointFallsWhenItIsRemoved() {
        val world = room()
        val bodies = world.rigidBodies
        val ball = world.rigidBody(Shape.circle(12f), 320f, 200f)
        val weld = world.joints.weld(ball, Joints.WORLD, 320f, 200f)
        steps(world, 2f)
        assertTrue(bodies.isAsleep(ball), "held still, it sleeps")
        world.joints.remove(weld)
        steps(world, 1f)
        assertEquals(floor - 12f, bodies.y(ball), 0.5f, "let go, it falls")
    }

    @Test
    fun aBodyOnABlobFallsWhenTheBlobIsPulledAway() {
        // damped hard, so the plank stops rocking on the blob and sleeps: undamped, it rocks for ages
        val world = room(PhysicsConfig(damping = 3f))
        val bodies = world.rigidBodies
        val blob = world.softBody(TestBodies.dome(), 320f, floor)
        steps(world, 1f)
        // a plank wide enough to lie on the dome without sliding off
        val box = world.rigidBody(Shape.box(40f, 6f), 320f, floor - 30f, density = 0.02f)
        steps(world, 3f)
        assertTrue(bodies.isAsleep(box), "resting on the blob, it sleeps")
        val restY = bodies.y(box)
        world.grab(world.particles.x(world.softBodies.core(blob)), world.particles.y(world.softBodies.core(blob)))
        world.moveGrab(500f, floor - 20f)
        steps(world, 2f)
        assertTrue(bodies.y(box) > restY + 10f, "with the blob gone from under it, it fell: ${bodies.y(box)}, rested at $restY")
    }

    @Test
    fun aBodyOnAKinematicPlatformNeverSleepsAndRidesItDown() {
        val world = room()
        val bodies = world.rigidBodies
        val platform = world.rigidBody(Shape.box(80f, 8f), 320f, 300f, kind = BodyKind.KINEMATIC)
        val box = world.rigidBody(Shape.box(24f, 24f), 320f, 284f)
        steps(world, 2f)
        assertFalse(bodies.isAsleep(box), "it might move off at any time")
        bodies.setVelocity(platform, 0f, 60f)
        steps(world, 1f)
        assertEquals(344f, bodies.y(box), 2f, "it rode the platform down 60")
    }

    @Test
    fun grabbingSettingAVelocityOrWakingWakesABody() {
        val world = room()
        val bodies = world.rigidBodies
        val boxes = IntArray(3) { world.rigidBody(Shape.box(24f, 24f), 100f + it * 100f, floor - 12f) }
        steps(world, 2f)
        for (b in boxes) assertTrue(bodies.isAsleep(b))
        world.grab(100f, floor - 12f)
        bodies.setVelocity(boxes[1], 100f, 0f)
        bodies.wake(boxes[2])
        for (b in boxes) assertFalse(bodies.isAsleep(b), "box $b is awake")
        world.release()
        steps(world, 0.2f)
        assertTrue(bodies.x(boxes[1]) > 205f, "and moves as it was told: ${bodies.x(boxes[1])}")
    }

    @Test
    fun aMotorKeepsItsBodiesAwake() {
        val world = room()
        val bodies = world.rigidBodies
        val wheel = world.rigidBody(Shape.circle(20f), 320f, 200f)
        val hinge = world.joints.hinge(wheel, Joints.WORLD, 320f, 200f)
        world.joints.setMotor(hinge, 0f, 1e6f)
        steps(world, 2f)
        assertFalse(bodies.isAsleep(wheel), "held still by its motor, it's ready to turn")
        world.joints.setMotor(hinge, 2f, 1e6f)
        steps(world, 0.5f)
        assertEquals(2f, kotlin.math.abs(bodies.angularVelocity(wheel)), 0.1f)
    }

    @Test
    fun aBodyThatNeverStopsNeverSleepsAndSleepingCanBeTurnedOff() {
        val world = room(PhysicsConfig(timeToSleep = Float.POSITIVE_INFINITY))
        val box = world.rigidBody(Shape.box(24f, 24f), 320f, floor - 12f)
        steps(world, 3f)
        assertFalse(world.rigidBodies.isAsleep(box), "with timeToSleep infinite, nothing sleeps")
        val rolling = room()
        val ball = rolling.rigidBody(Shape.circle(10f), 100f, floor - 10f, material = Material(friction = 0f, staticFriction = 0f))
        rolling.rigidBodies.setVelocity(ball, 50f, 0f)
        rolling.rigidBodies.setAngularVelocity(ball, 0f)
        steps(rolling, 2f)
        assertFalse(rolling.rigidBodies.isAsleep(ball), "a ball still sliding across the floor stays awake")
    }
}
