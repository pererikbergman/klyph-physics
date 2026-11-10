package klyph.physics

import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CollisionTest {
    private val dt = TestBodies.DT
    private val tile = TestBodies.TILE
    private val world = PhysicsWorld()
    private val bodies = world.softBodies
    private val ps = world.particles

    /** A room [cols] tiles wide and [rows] tall, with a floor and two walls. */
    private fun room(cols: Int, rows: Int) = SolidGrid(cols, rows, tile).apply {
        for (c in 0 until cols) fill(c, rows - 1)
        for (r in 0 until rows) {
            fill(0, r)
            fill(cols - 1, r)
        }
    }

    private fun dome(x: Float, baseY: Float) = world.softBody(TestBodies.dome(), x, baseY)

    /** A soft box 32 wide and 16 tall: a flat top to land on. */
    private fun box(x: Float, baseY: Float) = world.softBody(SoftBodies.box(32f, 16f, across = 8, down = 4, coreMass = 20f), x, baseY)

    /** How deep particle [p] is inside [body]'s outline, or 0 when it's outside. */
    private fun depthInside(p: Int, body: Int): Float {
        val ring = bodies.ring(body)
        if (!Outline.contains(ring, ps.x(p), ps.y(p))) return 0f
        return sqrt(Outline.nearestPoint(ring, ps.x(p), ps.y(p), FloatArray(2)))
    }

    /** The deepest any particle of any body is inside any other body. */
    private fun deepestOverlap(blobs: List<Int>): Float {
        var deepest = 0f
        for (a in blobs) for (b in blobs) {
            if (a == b) continue
            for (i in 0..bodies.ringCount(a)) deepest = maxOf(deepest, depthInside(bodies.particle(a, i), b))
        }
        return deepest
    }

    @Test
    fun aBlobDroppedOnAnotherLandsOnItAndNeitherEverSinksIntoTheOther() {
        // Contacts have no friction yet (#15), so the one on top doesn't stay there: once it has
        // landed it slides off. What this checks is that it lands on the other, not through it.
        world.solids = room(20, 12)
        val floor = 11 * tile
        val below = dome(160f, floor)
        val above = dome(160f, floor - 60f)
        var landed = false
        repeat(240) { step ->
            world.step(dt)
            val overlap = deepestOverlap(listOf(below, above))
            assertTrue(overlap < 1f, "step $step: $overlap units into each other")
            if (bodies.meanVelocityY(above) < 0f && bodies.maxY(above) < floor - 8f) landed = true
            if (step < 20) assertTrue(bodies.centerY(above) < bodies.centerY(below) - 8f, "step $step: still above it")
        }
        assertTrue(landed, "it bounced off the other blob, above the floor")
        assertTrue(deepestOverlap(listOf(below, above)) < 0.5f)
    }

    @Test
    fun twoBlobsPushedIntoEachOtherComeApartAgain() {
        world.solids = room(30, 12)
        val floor = 11 * tile
        val left = dome(200f, floor)
        val right = dome(260f, floor)
        repeat(30) { world.step(dt) }
        bodies.addVelocity(left, 300f, 0f)
        bodies.addVelocity(right, -300f, 0f)
        var worst = 0f
        repeat(240) {
            world.step(dt)
            worst = maxOf(worst, deepestOverlap(listOf(left, right)))
        }
        assertTrue(worst < 4f, "they sank $worst units into each other at worst")
        assertTrue(deepestOverlap(listOf(left, right)) < 0.5f, "and came apart: ${deepestOverlap(listOf(left, right))}")
        assertTrue(bodies.centerX(left) < bodies.centerX(right), "neither went through the other")
    }

    @Test
    fun aPileOfTwentyBlobsSettlesWithoutAnyPassingThroughAnother() {
        world.solids = room(16, 30)
        val blobs = mutableListOf<Int>()
        // Five rows of four, one above the other, a little apart, dropped at once.
        for (row in 0 until 5) for (col in 0 until 4) blobs += dome((3 + col * 3.2f) * tile + row * 4f, (8 + row * 4) * tile)
        repeat(600) { step ->
            world.step(dt)
            for (a in blobs) for (b in blobs) {
                if (a == b) continue
                // A core may graze another blob's skin when its own is squashed flat, but never go in.
                val depth = depthInside(bodies.core(a), b)
                assertTrue(depth < 1.5f, "step $step: the core of $a is $depth inside $b")
            }
            for (b in blobs) assertTrue(bodies.isFinite(b))
        }
        assertTrue(deepestOverlap(blobs) < 1.5f, "settled with a particle ${deepestOverlap(blobs)} units inside another blob")
        // Friction can hold a squashed blob in a stretched pose at the bottom of the pile (about 2.2x on
        // one edge, steady for many seconds). That is a held pose, not a tear: a tear keeps growing.
        for (b in blobs) assertTrue(bodies.maxEdgeStretch(b) < 2.5f, "blob $b is stretched ${bodies.maxEdgeStretch(b)} times")
        val speed = blobs.maxOf { kotlin.math.abs(bodies.meanVelocityY(it)) }
        assertTrue(speed < 20f, "settled: still moving at $speed")
    }

    @Test
    fun grainsOfSandPileUpInsteadOfPassingThroughEachOther() {
        world.solids = room(12, 20)
        val grains = (0 until 200).map { ps.add(40f + (it % 20) * 6f, 40f + (it / 20) * 6f, radius = 2f, collides = true) }
        repeat(400) { world.step(dt) }
        var closest = Float.MAX_VALUE
        for (a in grains) for (b in grains) {
            if (a >= b) continue
            val dx = ps.x(a) - ps.x(b)
            val dy = ps.y(a) - ps.y(b)
            closest = minOf(closest, sqrt(dx * dx + dy * dy))
        }
        assertTrue(closest > 3f, "two grains 4 units across are $closest apart")
        val floor = 19 * tile
        val top = grains.minOf { ps.y(it) }
        // 200 grains 4 wide on a floor 160 wide: about five layers, not one
        assertTrue(top < floor - 15f, "the pile is ${floor - top} high")
    }

    @Test
    fun aGrainDroppedOnABlobRestsOnItAndAPointThatDoesNotCollidePassesThrough() {
        world.solids = room(20, 12)
        val floor = 11 * tile
        val blob = box(160f, floor)
        val grain = ps.add(160f, 40f, radius = 2f, collides = true)
        val ghost = ps.add(162f, 40f)
        repeat(180) { world.step(dt) }
        val ring = bodies.ring(blob)
        assertFalse(Outline.contains(ring, ps.x(grain), ps.y(grain)), "not inside it")
        assertTrue(ps.y(grain) < bodies.centerY(blob), "on top of it")
        val gap = sqrt(Outline.nearestPoint(ring, ps.x(grain), ps.y(grain), FloatArray(2)))
        assertEquals(2f, gap, 0.5f, "a radius off its skin")
        assertTrue(world.events.isOnFloor(grain), "and standing on it")
        assertEquals(floor, ps.y(ghost), 0.01f, "the other fell through to the floor")
    }

    @Test
    fun aBlobLandingOnAnotherIsOnAFloor() {
        world.solids = room(20, 12)
        val floor = 11 * tile
        dome(160f, floor)
        val above = dome(160f, floor - 60f)
        var onTheOther = false
        repeat(25) {
            world.step(dt)
            // standing on something while nowhere near the solid floor: the other blob
            if (bodies.isOnFloor(above) && bodies.maxY(above) < floor - 8f) onTheOther = true
        }
        assertTrue(onTheOther, "it could jump off the other blob")
        assertFalse(bodies.touched(above, 1), "the other blob has no tags")
    }

    @Test
    fun selfCollisionPushesAFoldedSkinApartOnlyWhenItIsOn() {
        fun gap(selfCollision: Boolean): Pair<Float, Float> {
            // Constraints so soft they barely act, so only the collision moves anything.
            val w = PhysicsWorld(PhysicsConfig(gravity = Vec.ZERO, substeps = 1, iterations = 1, damping = 0f))
            val soft = SoftBodyTuning(edgeCompliance = 10f, spokeCompliance = 10f, areaCompliance = 10f)
            val b = w.softBodies.add(SoftBodies.box(60f, 20f, across = 6, down = 2, coreMass = 1f), 0f, 0f, soft)
            w.softBodies.setSelfCollision(b, selfCollision)
            // Fold particle 0 (the box's top-left corner) down onto the bottom edge, a hair above it.
            val p = w.softBodies.particle(b, 0)
            val bottomY = w.softBodies.maxY(b)
            w.particles.moveTo(p, 15f, bottomY - 0.2f)
            w.step(dt)
            return (bottomY - w.particles.y(p)) to w.softBodies.selfThickness(b)
        }
        val (off, _) = gap(selfCollision = false)
        val (on, thickness) = gap(selfCollision = true)
        assertTrue(off < thickness, "off: left $off from the bottom edge")
        assertTrue(on >= thickness * 0.5f, "on: pushed to $on from the bottom edge, thickness $thickness")
        assertTrue(on > off)
    }

    @Test
    fun selfCollisionIsOffUnlessAsked() {
        val b = dome(0f, 0f)
        assertFalse(bodies.hasSelfCollision(b))
    }
}
