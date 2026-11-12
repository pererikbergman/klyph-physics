package klyph.physics

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Rigid bodies meeting the floor and each other: landing, stacking, sliding, rolling, bouncing, toppling. */
class StackingTest {
    private val dt = TestBodies.DT
    private val tile = TestBodies.TILE

    /** A room 40 tiles wide and 30 high with a floor along its bottom two rows: its top at y = 448. */
    private fun room(config: PhysicsConfig = PhysicsConfig(), material: Material = Material.DEFAULT) = PhysicsWorld(config).apply {
        solids = SolidGrid(40, 30, tile).apply {
            for (c in 0 until cols) for (r in 28 until 30) fill(c, r, material = material)
            for (r in 0 until 30) {
                fill(0, r)
                fill(cols - 1, r)
            }
        }
    }

    private val floor = 28 * 16f

    @Test
    fun aBoxDroppedOnTheFloorLandsAndRestsFlat() {
        val world = room()
        val bodies = world.rigidBodies
        val box = world.rigidBody(Shape.box(32f, 32f), 300f, 200f, angle = 0.3f)
        repeat(300) { world.step(dt) }
        assertEquals(floor - 16f, bodies.y(box), 0.3f, "on the floor, not in it")
        val turned = bodies.angle(box) - (PI / 2).toFloat() * kotlin.math.round(bodies.angle(box) / (PI / 2).toFloat())
        assertEquals(0f, turned, 0.01f, "flat on a face")
        assertTrue(abs(bodies.velocityY(box)) < 1f && abs(bodies.angularVelocity(box)) < 0.05f, "at rest")
    }

    @Test
    fun aTowerOfTenBoxesStandsForAMinute() {
        val world = room()
        val bodies = world.rigidBodies
        val size = 24f
        val tower = IntArray(10) { world.rigidBody(Shape.box(size, size), 320f, floor - size / 2f - it * size) }
        repeat(3600) { world.step(dt) }
        for ((i, b) in tower.withIndex()) {
            assertEquals(320f, bodies.x(b), 1f, "box $i stays over the first")
            assertEquals(floor - size / 2f - i * size, bodies.y(b), 1f, "box $i keeps its height")
            assertEquals(0f, bodies.angle(b), 0.02f, "box $i stays level")
        }
    }

    @Test
    fun aPyramidOfTwentyRowsSettlesAndStands() {
        val world = room()
        val bodies = world.rigidBodies
        val size = 12f
        val rows = 20
        val boxes = ArrayList<Int>()
        for (row in 0 until rows) for (k in 0 until rows - row) {
            val x = 320f - (rows - row - 1) * size / 2f + k * size
            boxes += world.rigidBody(Shape.box(size, size), x, floor - size / 2f - row * size)
        }
        val top = boxes.last()
        repeat(300) { world.step(dt) }
        val settledX = bodies.x(top)
        val settledY = bodies.y(top)
        repeat(1800) { world.step(dt) }
        assertEquals(settledX, bodies.x(top), 1f, "the top box stays put")
        assertEquals(settledY, bodies.y(top), 1f, "and doesn't sink")
        assertEquals(floor - size / 2f - (rows - 1) * size, bodies.y(top), 3f, "the pyramid holds its height")
        for (b in boxes) assertTrue(abs(bodies.angle(b)) < 0.1f, "box $b stays level")
    }

    @Test
    fun dominoesToppleInOrder() {
        val world = room()
        val bodies = world.rigidBodies
        val dominoes = IntArray(8) { world.rigidBody(Shape.box(6f, 36f), 200f + it * 24f, floor - 18f) }
        // the first tipped over about its foot: its middle, 18 up, moves with the turn
        bodies.setAngularVelocity(dominoes[0], 2.5f)
        bodies.setVelocity(dominoes[0], 2.5f * 18f, 0f)
        val fellAt = IntArray(dominoes.size) { -1 }
        repeat(600) { step ->
            world.step(dt)
            for ((i, d) in dominoes.withIndex()) if (fellAt[i] < 0 && bodies.angle(d) > 0.6f) fellAt[i] = step
        }
        for (i in dominoes.indices) assertTrue(fellAt[i] >= 0, "domino $i fell: ${fellAt.toList()}")
        for (i in 1 until dominoes.size) assertTrue(fellAt[i] > fellAt[i - 1], "domino $i fell after ${i - 1}: ${fellAt.toList()}")
    }

    @Test
    fun aBoxSlidesAlongAFloorOfManyTilesWithoutCatchingOnTheSeams() {
        val world = room(PhysicsConfig(damping = 0f), Material.ICE)
        val bodies = world.rigidBodies
        val box = world.rigidBody(Shape.box(20f, 20f), 100f, floor - 10f, material = Material.ICE)
        bodies.setVelocity(box, 200f, 0f)
        repeat(90) {
            world.step(dt)
            assertEquals(200f, bodies.velocityX(box), 1f, "no seam slows it, step $it")
            assertEquals(floor - 10f, bodies.y(box), 0.3f, "no seam lifts it, step $it")
        }
    }

    @Test
    fun staticFrictionHoldsABoxOnASlopeAndIceLetsItSlide() {
        for ((material, holds) in listOf(Material.RUBBER to true, Material.ICE to false)) {
            val world = PhysicsWorld()
            world.solids = SolidGrid(40, 30, tile).apply {
                for (c in 0 until cols) fill(c, 29, material = material)
                // a 45° ramp down to the right, from row 10 at column 5
                for (k in 0 until 18) {
                    fill(5 + k, 10 + k, SolidShape.FALLING, material = material)
                    for (r in 11 + k until 29) fill(5 + k, r, material = material)
                }
            }
            val bodies = world.rigidBodies
            // A slab resting on the ramp, turned to lie along it, touching. A slab, not a square box: on
            // a 45° slope a square box's middle is right over its downhill corner, on the point of
            // tipping. And touching, not dropped: it would come down already sliding, and rubber's grip
            // once sliding, 0.9, is under the 1 a 45° slope needs to stop it.
            val x = 12.5f * tile
            val box = world.rigidBody(Shape.box(24f, 8f), x, x - 5 * tile + 10 * tile - 4f * sqrt(2f), angle = (PI / 4).toFloat(), material = material)
            val startX = bodies.x(box)
            repeat(120) { world.step(dt) }
            val moved = bodies.x(box) - startX
            if (holds) assertTrue(abs(moved) < 1f, "rubber holds: moved $moved") else assertTrue(moved > 40f, "ice slides: moved $moved")
        }
    }

    @Test
    fun aBallBouncesBackAsHighAsItsRestitutionSays() {
        val world = room(PhysicsConfig(damping = 0f))
        val bodies = world.rigidBodies
        val e = 0.7f
        val ball = world.rigidBody(Shape.circle(8f), 300f, 200f, material = Material(restitution = e))
        val drop = floor - 8f - 200f
        var bounced = false
        var apex = Float.MAX_VALUE
        repeat(240) {
            world.step(dt)
            if (bodies.velocityY(ball) < -1f) bounced = true
            if (bounced) apex = minOf(apex, bodies.y(ball))
        }
        val height = floor - 8f - apex
        assertEquals(e * e * drop, height, e * e * drop * 0.1f, "e² of the drop")
    }

    @Test
    fun aBallRollsDownASlopeTurningAsItGoes() {
        val world = PhysicsWorld()
        world.solids = SolidGrid(40, 30, tile).apply {
            for (c in 0 until cols) fill(c, 29)
            for (k in 0 until 18) {
                fill(5 + k, 10 + k, SolidShape.FALLING)
                for (r in 11 + k until 29) fill(5 + k, r)
            }
        }
        val bodies = world.rigidBodies
        val ball = world.rigidBody(Shape.circle(8f), 8f * tile, 8f * tile)
        repeat(90) { world.step(dt) }
        assertTrue(bodies.velocityX(ball) > 50f, "rolling downhill: ${bodies.velocityX(ball)}")
        // rolling, not sliding: its spin keeps up with its speed, v = ω r
        val speed = sqrt(bodies.velocityX(ball) * bodies.velocityX(ball) + bodies.velocityY(ball) * bodies.velocityY(ball))
        assertEquals(speed, bodies.angularVelocity(ball) * 8f, speed * 0.1f, "rolls without slipping")
    }

    @Test
    fun aBoxRidesAMovingKinematicPlatform() {
        val world = PhysicsWorld()
        val bodies = world.rigidBodies
        val platform = world.rigidBody(Shape.box(120f, 10f), 200f, 300f, kind = BodyKind.KINEMATIC)
        val box = world.rigidBody(Shape.box(16f, 16f), 200f, 300f - 5f - 8f)
        repeat(60) { world.step(dt) }
        bodies.setVelocity(platform, 40f, 0f)
        repeat(60) { world.step(dt) }
        assertEquals(240f, bodies.x(platform), 0.1f)
        // It slips at first: friction takes a moment to bring it up to speed, 40 / (0.4 g), a fifteenth
        // of a second, over which it falls behind by about 1.3. Then it rides.
        assertEquals(40f, bodies.velocityX(box), 0.5f, "carried along at the platform's speed")
        assertEquals(bodies.x(platform) - 1.3f, bodies.x(box), 0.5f, "after the slip it took to get going")
        assertEquals(300f - 5f - 8f, bodies.y(box), 0.5f, "on top of it")
    }

    @Test
    fun aBoxOverTheEdgeOfALedgeTipsOff() {
        val world = PhysicsWorld()
        world.solids = SolidGrid(40, 30, tile).apply {
            for (c in 0 until cols) fill(c, 29)
            // a ledge from column 0 to 9: its lip at x = 160, top at y = 320
            for (c in 0 until 10) for (r in 20 until 29) fill(c, r)
        }
        val bodies = world.rigidBodies
        // two thirds of it hanging over
        val box = world.rigidBody(Shape.box(30f, 30f), 160f + 5f, 320f - 15f)
        repeat(240) { world.step(dt) }
        assertTrue(bodies.y(box) > 400f, "it tipped off the lip and fell to the floor: ${bodies.y(box)}")
        assertTrue(bodies.x(box) > 160f, "on the far side")
    }

    @Test
    fun boxesFallingOnEachOtherStayApart() {
        val world = room()
        val bodies = world.rigidBodies
        val shapes = listOf(Shape.box(20f, 20f), Shape.circle(10f), Shape.capsule(20f, 6f), Shape.polygon(-10f, 8f, 0f, -10f, 10f, 8f))
        val all = IntArray(40) { world.rigidBody(shapes[it % shapes.size], 250f + (it % 8) * 22f, 100f + (it / 8) * 30f, angle = it * 0.4f) }
        repeat(600) { world.step(dt) }
        val narrow = Narrowphase()
        val m = Manifold()
        for (a in all) for (b in all) {
            if (a >= b) continue
            if (!narrow.collide(bodies.shape(a, 0), bodies.x(a), bodies.y(a), bodies.angle(a), bodies.shape(b, 0), bodies.x(b), bodies.y(b), bodies.angle(b), m)) continue
            for (i in 0 until m.count) assertTrue(m.separation(i) > -1f, "bodies $a and $b overlap by ${-m.separation(i)}")
        }
        for (b in all) {
            assertTrue(bodies.y(b) < floor, "body $b is above the floor")
            assertTrue(abs(bodies.velocityY(b)) < 5f, "body $b has settled: ${bodies.velocityY(b)}")
        }
    }
}
