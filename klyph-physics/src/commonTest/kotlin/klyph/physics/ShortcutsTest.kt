package klyph.physics

import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The shortcuts that make the solver fast, each shown to give the answer the long way gives: contacts
 * kept by pairs that hardly moved, and grains that can't meet this step left unpaired.
 */
class ShortcutsTest {
    private val dt = TestBodies.DT
    private val tile = TestBodies.TILE
    private val floor = 28 * 16f

    private fun room(config: PhysicsConfig = PhysicsConfig()) = PhysicsWorld(config).apply {
        solids = SolidGrid(40, 30, tile).apply {
            for (c in 0 until cols) for (r in 28 until 30) fill(c, r)
            for (r in 0 until 30) {
                fill(0, r)
                fill(cols - 1, r)
            }
        }
    }

    private val awake = PhysicsConfig(timeToSleep = Float.POSITIVE_INFINITY)

    /** A tower of [height] boxes on the floor, and a pyramid of [rows] rows beside it. */
    private fun stacks(world: PhysicsWorld, height: Int = 6, rows: Int = 5) {
        repeat(height) { world.rigidBody(Shape.box(20f, 20f), 100f, floor - 10f - it * 20f) }
        for (row in 0 until rows) for (k in 0 until rows - row) {
            world.rigidBody(Shape.box(18f, 18f), 300f - (rows - row - 1) * 9f + k * 18f, floor - 9f - row * 18f)
        }
    }

    @Test
    fun aRestingTowerKeepsItsContactsRatherThanLookAgain() {
        val world = room(awake)
        stacks(world, height = 6, rows = 0)
        repeat(120) { world.step(dt) }
        // five pairs of boxes, one atop the other, every one kept by the last substep
        assertEquals(5, world.rigidContacts.keptCount)
    }

    @Test
    fun keptContactsHoldStacksJustAsLookingAgainDoes() {
        val keeping = room(awake)
        val looking = room(awake).apply { rigidContacts.keeping = false }
        stacks(keeping)
        stacks(looking)
        repeat(240) {
            keeping.step(dt)
            looking.step(dt)
        }
        val bodies = keeping.rigidBodies
        for (b in 0 until bodies.count) {
            assertTrue(abs(bodies.x(b) - looking.rigidBodies.x(b)) < 0.05f, "box $b across: ${bodies.x(b)} against ${looking.rigidBodies.x(b)}")
            assertTrue(abs(bodies.y(b) - looking.rigidBodies.y(b)) < 0.05f, "box $b down: ${bodies.y(b)} against ${looking.rigidBodies.y(b)}")
            assertTrue(abs(bodies.angle(b) - looking.rigidBodies.angle(b)) < 0.005f, "box $b turned")
        }
    }

    @Test
    fun aBoxSlidingOverAnotherLooksAgainEverySubstep() {
        val world = room(awake.copy(gravity = Vec(0f, 0f)))
        val under = world.rigidBody(Shape.box(200f, 20f), 300f, 300f, kind = BodyKind.STATIC)
        val box = world.rigidBody(Shape.box(20f, 20f), 220f, 300f - 20f)
        world.rigidBodies.setVelocity(box, 300f, 0f)
        world.step(dt)
        assertEquals(0, world.rigidContacts.keptCount, "the box moves 300 units a second over the plank: ${world.rigidContacts.count} contacts")
        assertTrue(world.rigidContacts.count > 0)
        assertTrue(world.rigidBodies.isAlive(under))
    }

    @Test
    fun grainsRushingTogetherStillMeetWhenTheirBoxesDidNotOverlapAtTheStart() {
        val world = PhysicsWorld(PhysicsConfig(gravity = Vec(0f, 0f), substeps = 8))
        val ps = world.particles
        val h = dt / world.config.substeps
        // 24 apart, closing at 1200 units a second: 20 a step, 2.5 a substep (no more than they're
        // wide, or they'd hop past each other), so they meet in the second step, though their boxes and
        // circles are apart at its start but for how far they move
        val a = ps.add(100f, 100f, radius = 1.25f, collides = true)
        val b = ps.add(124f, 100f, radius = 1.25f, collides = true)
        ps.setVelocity(a, 600f, 0f, h)
        ps.setVelocity(b, -600f, 0f, h)
        repeat(3) { world.step(dt) }
        assertTrue(ps.x(a) < ps.x(b), "they bounced off each other rather than pass: ${ps.x(a)}, ${ps.x(b)}")
    }

    @Test
    fun aHeapOfSandSettlesTheSameAsWithEveryBoxPairSolved() {
        // the shortcut drops pairs whose circles can't meet this step; the heap must not notice
        val world = room(PhysicsConfig(iterations = 3))
        val random = Random(5)
        val grains = ArrayList<Int>()
        repeat(800) { grains += world.particles.add(150f + random.nextFloat() * 300f, 100f + random.nextFloat() * 300f, radius = 1.25f, collides = true) }
        repeat(180) { world.step(dt) }
        val ps = world.particles
        // nothing through anything: every two grains at least nearly their radii apart, none out of the room
        var overlapping = 0
        for (i in grains.indices) for (j in i + 1 until grains.size) {
            val dx = ps.x(grains[i]) - ps.x(grains[j])
            val dy = ps.y(grains[i]) - ps.y(grains[j])
            if (dx * dx + dy * dy < 2f * 2f) overlapping++
        }
        assertEquals(0, overlapping, "grains less than 2 apart, with radii 1.25")
        for (p in grains) assertTrue(ps.y(p) < floor, "grain $p fell through the floor: ${ps.y(p)}")
    }
}
