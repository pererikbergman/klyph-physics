package klyph.physics

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Asking the world what is where: raycasts, point and box queries, against brute force. */
class QueriesTest {
    private val hit = RayHit()
    private val found = Overlaps()

    /** No gravity: what's put somewhere stays there. */
    private fun space() = PhysicsWorld(PhysicsConfig(gravity = Vec(0f, 0f)))

    // --- raycasts, one thing at a time ---

    @Test
    fun aRayHitsABoxOnTheFaceItComesAt() {
        val world = space()
        val box = world.rigidBody(Shape.box(20f, 10f), 100f, 50f, kind = BodyKind.STATIC)
        assertTrue(world.raycast(0f, 50f, 200f, 50f, hit))
        assertEquals(HitKind.RIGID_BODY, hit.what)
        assertEquals(box, hit.body)
        assertEquals(0, hit.shape)
        assertEquals(90f, hit.x, 1e-3f)
        assertEquals(50f, hit.y, 1e-3f)
        assertEquals(0.45f, hit.fraction, 1e-5f)
        assertEquals(-1f, hit.normalX, 1e-5f)
        assertEquals(0f, hit.normalY, 1e-5f)
    }

    @Test
    fun aRayHitsATurnedBoxWithTheTurnedNormal() {
        val world = space()
        world.rigidBody(Shape.box(20f, 20f), 100f, 100f, angle = (PI / 4).toFloat(), kind = BodyKind.STATIC)
        // straight down onto its top corner's right-hand face
        assertTrue(world.raycast(105f, 0f, 105f, 200f, hit))
        val half = sqrt(2f) * 10f
        assertEquals(100f - half + 5f, hit.y, 1e-3f)
        assertEquals(sqrt(0.5f), hit.normalX, 1e-4f)
        assertEquals(-sqrt(0.5f), hit.normalY, 1e-4f)
    }

    @Test
    fun aRayHitsACircleWhereItsRoundSideFacesIt() {
        val world = space()
        world.rigidBody(Shape.circle(10f), 100f, 100f, kind = BodyKind.STATIC)
        assertTrue(world.raycast(100f - 6f, 0f, 100f - 6f, 200f, hit))
        // 6 off the middle, the circle's top is 8 above it
        assertEquals(92f, hit.y, 1e-3f)
        assertEquals(-0.6f, hit.normalX, 1e-4f)
        assertEquals(-0.8f, hit.normalY, 1e-4f)
    }

    @Test
    fun aRayHitsACapsuleOnItsSideAndOnItsEnd() {
        val world = space()
        world.rigidBody(Shape.capsule(40f, 5f), 100f, 100f, kind = BodyKind.STATIC)
        assertTrue(world.raycast(110f, 0f, 110f, 200f, hit))
        assertEquals(95f, hit.y, 1e-3f)
        assertEquals(-1f, hit.normalY, 1e-5f)
        assertTrue(world.raycast(300f, 100f, 0f, 100f, hit))
        assertEquals(125f, hit.x, 1e-3f)
        assertEquals(1f, hit.normalX, 1e-5f)
    }

    @Test
    fun aRayFindsTheShapeOfACompoundBodyItHits() {
        val world = space()
        // the handle from 90 to 130 below the origin, the head from 82 to 90
        world.rigidBody(listOf(Shape.box(4f, 40f, y = 10f), Shape.box(20f, 8f, y = -14f)), 100f, 100f, kind = BodyKind.STATIC)
        assertTrue(world.raycast(0f, 120f, 200f, 120f, hit))
        assertEquals(0, hit.shape)
        assertTrue(world.raycast(0f, 86f, 200f, 86f, hit))
        assertEquals(1, hit.shape)
    }

    @Test
    fun aRayHitsTheNearerOfTwoAndStopsShortOfAnyItDoesNotReach() {
        val world = space()
        val far = world.rigidBody(Shape.circle(5f), 200f, 0f, kind = BodyKind.STATIC)
        val near = world.rigidBody(Shape.circle(5f), 100f, 0f, kind = BodyKind.STATIC)
        world.raycast(0f, 0f, 300f, 0f, hit)
        assertEquals(near, hit.body)
        world.raycast(300f, 0f, 0f, 0f, hit)
        assertEquals(far, hit.body)
        assertFalse(world.raycast(0f, 0f, 90f, 0f, hit))
        assertEquals(HitKind.NOTHING, hit.what)
        assertEquals(-1, hit.body)
    }

    @Test
    fun aRayFromInsideABodyGoesOutAndHitsTheNextOne() {
        val world = space()
        world.rigidBody(Shape.box(20f, 20f), 0f, 0f, kind = BodyKind.STATIC)
        val next = world.rigidBody(Shape.box(20f, 20f), 100f, 0f, kind = BodyKind.STATIC)
        assertTrue(world.raycast(0f, 0f, 300f, 0f, hit))
        assertEquals(next, hit.body)
        assertEquals(90f, hit.x, 1e-3f)
    }

    @Test
    fun aRayBouncedOffASurfaceDoesNotHitItAgain() {
        val world = space()
        val mirror = world.rigidBody(Shape.box(100f, 2f), 100f, 100f, angle = 0.3f, kind = BodyKind.STATIC)
        assertTrue(world.raycast(100f, 0f, 100f, 300f, hit))
        assertEquals(mirror, hit.body)
        val dx = 0f
        val dy = 300f
        val along = dx * hit.normalX + dy * hit.normalY
        val rx = dx - 2f * along * hit.normalX
        val ry = dy - 2f * along * hit.normalY
        assertFalse(world.raycast(hit.x, hit.y, hit.x + rx, hit.y + ry, hit), "it hit ${hit.what} ${hit.body} at ${hit.fraction}")
    }

    @Test
    fun aRayHitsASoftBodysOutline() {
        val world = space()
        val blob = world.softBody(SoftBodies.circle(20f, 32, coreMass = 4f), 100f, 120f)
        assertTrue(world.raycast(0f, 100f, 300f, 100f, hit))
        assertEquals(HitKind.SOFT_BODY, hit.what)
        assertEquals(blob, hit.body)
        assertEquals(80f, hit.x, 0.5f)
        assertTrue(hit.normalX < -0.95f)
        // from inside, out through it to nothing
        assertFalse(world.raycast(100f, 100f, 300f, 100f, hit))
    }

    @Test
    fun aRayHitsATileOnItsOpenFaceAndSaysWhichTile() {
        val world = space()
        val grid = SolidGrid(10, 10, 16f)
        for (c in 0 until 10) grid.fill(c, 9)
        val slope = grid.fill(3, 8, SolidShape.RISING)
        world.solids = grid
        assertTrue(world.raycast(100f, 10f, 100f, 300f, hit))
        assertEquals(HitKind.SOLID, hit.what)
        assertSame(grid.at(6, 9), hit.solid)
        assertEquals(144f, hit.y, 1e-3f)
        assertEquals(-1f, hit.normalY, 1e-5f)
        // the slope rises to the right: at x 56, 8 into the tile, it's half way up
        assertTrue(world.raycast(56f, 10f, 56f, 300f, hit))
        assertSame(slope, hit.solid)
        assertEquals(136f, hit.y, 1e-3f)
        assertEquals(-sqrt(0.5f), hit.normalX, 1e-4f)
        assertEquals(-sqrt(0.5f), hit.normalY, 1e-4f)
        // the face between two floor tiles is buried: a ray along inside the floor hits nothing
        assertFalse(world.raycast(1f, 150f, 159f, 150f, hit))
    }

    @Test
    fun aRayHitsAMovingSolid() {
        val world = space()
        val grid = SolidGrid(10, 10, 16f)
        val crusher = Solid.moving(40f, 20f, 80f, 40f)
        grid.dynamic += crusher
        world.solids = grid
        assertTrue(world.raycast(60f, 100f, 60f, 0f, hit))
        assertSame(crusher, hit.solid)
        assertEquals(40f, hit.y, 1e-3f)
        assertEquals(1f, hit.normalY, 1e-5f)
    }

    @Test
    fun aRayLooksOnlyAtWhatItIsAskedTo() {
        val world = space()
        val grid = SolidGrid(20, 10, 16f)
        grid.fill(15, 5)
        world.solids = grid
        world.softBody(SoftBodies.circle(10f, 16, coreMass = 4f), 160f, 98f)
        world.rigidBody(Shape.circle(5f), 100f, 88f, kind = BodyKind.STATIC)
        world.raycast(0f, 88f, 319f, 88f, hit)
        assertEquals(HitKind.RIGID_BODY, hit.what)
        world.raycast(0f, 88f, 319f, 88f, hit, Query.SOFT_BODIES or Query.SOLIDS)
        assertEquals(HitKind.SOFT_BODY, hit.what)
        world.raycast(0f, 88f, 319f, 88f, hit, Query.SOLIDS)
        assertEquals(HitKind.SOLID, hit.what)
    }

    // --- point and box queries ---

    @Test
    fun aPointQueryFindsEverythingThePointIsIn() {
        val world = space()
        val grid = SolidGrid(10, 10, 16f)
        val tile = grid.fill(2, 2)
        world.solids = grid
        val a = world.rigidBody(Shape.box(30f, 30f), 40f, 40f, kind = BodyKind.STATIC)
        val b = world.rigidBody(Shape.circle(10f), 45f, 45f, kind = BodyKind.STATIC)
        val blob = world.softBody(SoftBodies.circle(20f, 24, coreMass = 4f), 40f, 60f)
        assertEquals(4, world.queryPoint(40f, 40f, found))
        assertTrue(found.hasRigidBody(a) && found.hasRigidBody(b) && found.hasSoftBody(blob))
        assertSame(tile, found.solid(0))
        assertEquals(1, world.queryPoint(40f, 40f, found, Query.SOFT_BODIES))
        assertEquals(0, world.queryPoint(150f, 150f, found))
    }

    @Test
    fun aBoxQueryFindsWhatReachesIntoIt() {
        val world = space()
        val grid = SolidGrid(10, 10, 16f)
        val slope = grid.fill(5, 5, SolidShape.RISING)
        world.solids = grid
        val ball = world.rigidBody(Shape.circle(10f), 100f, 20f, kind = BodyKind.STATIC)
        // the ball reaches 90 to 110: a box from 108 in reaches it, one from 111 doesn't
        assertEquals(1, world.queryBox(108f, 15f, 130f, 25f, found))
        assertEquals(ball, found.rigidBody(0))
        assertEquals(0, world.queryBox(111f, 15f, 130f, 25f, found))
        // nor does a box over its round corner, outside the circle
        assertEquals(0, world.queryBox(108f, 28f, 120f, 40f, found))
        // the slope's empty top-left half isn't in it; its bottom-right corner is
        assertEquals(0, world.queryBox(81f, 81f, 86f, 86f, found))
        assertEquals(1, world.queryBox(90f, 90f, 95f, 95f, found))
        assertSame(slope, found.solid(0))
    }

    @Test
    fun aBoxQueryFindsASoftBodyItIsAllInside() {
        val world = space()
        val blob = world.softBody(SoftBodies.circle(30f, 24, coreMass = 4f), 100f, 130f)
        assertEquals(1, world.queryBox(95f, 95f, 105f, 105f, found))
        assertEquals(blob, found.softBody(0))
    }

    @Test
    fun overlapsGrowsToHoldAll() {
        val world = space()
        repeat(40) { world.rigidBody(Shape.circle(5f), 100f, 100f, kind = BodyKind.STATIC) }
        val small = Overlaps(initialCapacity = 2)
        assertEquals(40, world.queryPoint(100f, 100f, small))
        assertEquals(40, world.queryBox(0f, 0f, 200f, 200f, small))
    }

    // --- against brute force ---

    /** A level with walls, tiles and slopes, a moving solid, rigid bodies of every shape and some soft bodies, settled a little. */
    private fun clutter(seed: Int): PhysicsWorld {
        val random = Random(seed)
        val world = PhysicsWorld()
        val grid = SolidGrid(40, 25, 16f)
        for (c in 0 until 40) {
            grid.fill(c, 0)
            grid.fill(c, 24)
        }
        for (r in 0 until 25) {
            grid.fill(0, r)
            grid.fill(39, r)
        }
        repeat(60) {
            val shape = SolidShape.entries[random.nextInt(3)]
            grid.fill(1 + random.nextInt(38), 1 + random.nextInt(23), shape)
        }
        grid.dynamic += Solid.moving(300f, 200f, 340f, 216f)
        world.solids = grid
        repeat(30) {
            val shape = when (it % 5) {
                0 -> Shape.circle(3f + random.nextFloat() * 12f)
                1 -> Shape.box(2f + random.nextFloat() * 40f, 2f + random.nextFloat() * 20f)
                2 -> Shape.capsule(5f + random.nextFloat() * 30f, 1f + random.nextFloat() * 6f)
                3 -> Shape.polygon(0f, 0f, 10f + random.nextFloat() * 20f, 0f, random.nextFloat() * 15f, 8f + random.nextFloat() * 20f)
                else -> null
            }
            val shapes = shape?.let { s -> listOf(s) } ?: listOf(Shape.box(4f, 30f), Shape.circle(8f, y = -18f))
            val kind = if (it % 3 == 0) BodyKind.STATIC else BodyKind.DYNAMIC
            world.rigidBody(shapes, 40f + random.nextFloat() * 560f, 40f + random.nextFloat() * 320f, angle = random.nextFloat() * 6f, kind = kind)
        }
        repeat(4) {
            world.softBody(SoftBodies.circle(14f + random.nextFloat() * 10f, 20, coreMass = 4f), 60f + random.nextFloat() * 520f, 60f + random.nextFloat() * 300f)
        }
        // settle a little, so the soft bodies have dents in them
        repeat(20) { world.step(TestBodies.DT) }
        return world
    }

    /** Whether ([x], [y]) is in the thing [hit] says it hit. */
    private fun isInHitThing(world: PhysicsWorld, x: Float, y: Float): Boolean {
        world.queryPoint(x, y, found)
        return when (hit.what) {
            HitKind.RIGID_BODY -> found.hasRigidBody(hit.body)
            HitKind.SOFT_BODY -> found.hasSoftBody(hit.body)
            HitKind.SOLID -> (0 until found.solidCount).any { found.solid(it) === hit.solid }
            HitKind.NOTHING -> false
        }
    }

    @Test
    fun raycastsMatchBruteForce() {
        val samples = 2000
        for (seed in 0 until 3) {
            val world = clutter(seed)
            val random = Random(100 + seed)
            var hits = 0
            var rays = 0
            while (rays < 100) {
                val ox = 20f + random.nextFloat() * 600f
                val oy = 20f + random.nextFloat() * 360f
                // brute force can't tell what a ray starts inside from what it enters: start in the open
                if (world.queryPoint(ox, oy, found) > 0) continue
                rays++
                val angle = random.nextFloat() * 2f * PI.toFloat()
                val length = 50f + random.nextFloat() * 650f
                val tx = ox + cos(angle) * length
                val ty = oy + sin(angle) * length
                val didHit = world.raycast(ox, oy, tx, ty, hit)
                val end = if (didHit) hit.fraction else 1f
                // brute force: walk it, and nothing is anywhere along it before the hit
                for (k in 0..samples) {
                    val t = k.toFloat() / samples
                    if (t * length > end * length - 0.01f) break
                    val n = world.queryPoint(ox + (tx - ox) * t, oy + (ty - oy) * t, found)
                    assertEquals(0, n, "seed $seed ray $rays: something at $t along, before the ray's ${if (didHit) "hit at $end" else "end"}")
                }
                if (!didHit) continue
                hits++
                // and the hit is on a surface of what it says it hit: just in from it is inside
                assertEquals(1f, sqrt(hit.normalX * hit.normalX + hit.normalY * hit.normalY), 1e-4f)
                assertTrue(hit.normalX * (tx - ox) + hit.normalY * (ty - oy) < 0f, "seed $seed ray $rays: the normal faces away from the ray")
                val inside = isInHitThing(world, hit.x - hit.normalX * 0.02f, hit.y - hit.normalY * 0.02f) ||
                    isInHitThing(world, hit.x + (tx - ox) / length * 0.02f, hit.y + (ty - oy) / length * 0.02f)
                assertTrue(inside, "seed $seed ray $rays: just in from the hit at (${hit.x}, ${hit.y}) isn't inside the ${hit.what} it hit")
            }
            assertTrue(hits > 60, "only $hits of the rays hit anything: the test isn't testing much")
        }
    }

    @Test
    fun boxQueriesMatchBruteForce() {
        val world = clutter(7)
        val random = Random(8)
        val spacing = 0.5f
        val bs = world.rigidBodies
        val sb = world.softBodies
        val grid = world.solids!!
        val nearest = FloatArray(2)
        repeat(60) { attempt ->
            val left = 20f + random.nextFloat() * 580f
            val top = 20f + random.nextFloat() * 340f
            val right = left + 1f + random.nextFloat() * 30f
            val bottom = top + 1f + random.nextFloat() * 30f
            world.queryBox(left, top, right, bottom, found)
            val across = ((right - left) / spacing).toInt() + 1
            val down = ((bottom - top) / spacing).toInt() + 1
            fun sample(i: Int, j: Int): Pair<Float, Float> = (left + (right - left) * i / across) to (top + (bottom - top) * j / down)
            // anything a point of the box is inside, it found
            val atPoint = Overlaps()
            for (i in 0..across) for (j in 0..down) {
                val (x, y) = sample(i, j)
                world.queryPoint(x, y, atPoint)
                for (k in 0 until atPoint.rigidBodyCount) assertTrue(found.hasRigidBody(atPoint.rigidBody(k)), "box $attempt missed rigid body ${atPoint.rigidBody(k)} at ($x, $y)")
                for (k in 0 until atPoint.softBodyCount) assertTrue(found.hasSoftBody(atPoint.softBody(k)), "box $attempt missed soft body ${atPoint.softBody(k)} at ($x, $y)")
                for (k in 0 until atPoint.solidCount) assertTrue((0 until found.solidCount).any { found.solid(it) === atPoint.solid(k) }, "box $attempt missed a solid at ($x, $y)")
            }
            // and everything it found reaches within a sample's spacing of some point of it
            val near = spacing
            for (k in 0 until found.rigidBodyCount) {
                val b = found.rigidBody(k)
                var closest = Float.MAX_VALUE
                for (i in 0..across) for (j in 0..down) {
                    val (x, y) = sample(i, j)
                    val lx = bs.localX(b, x, y)
                    val ly = bs.localY(b, x, y)
                    for (s in 0 until bs.shapeCount(b)) closest = minOf(closest, bs.shape(b, s).distance(lx, ly))
                }
                assertTrue(closest <= near, "box $attempt found rigid body $b, but it's $closest from it")
            }
            for (k in 0 until found.softBodyCount) {
                val b = found.softBody(k)
                var closest = Float.MAX_VALUE
                for (i in 0..across) for (j in 0..down) {
                    val (x, y) = sample(i, j)
                    closest = minOf(closest, if (Outline.contains(sb.ring(b), x, y)) 0f else sqrt(Outline.nearestPoint(sb.ring(b), x, y, nearest)))
                }
                assertTrue(closest <= near, "box $attempt found soft body $b, but it's $closest from it")
            }
            for (k in 0 until found.solidCount) {
                val s = found.solid(k)
                var reached = false
                for (i in 0..across) for (j in 0..down) {
                    val (x, y) = sample(i, j)
                    if (s.overlaps(x, y, near)) reached = true
                }
                assertTrue(reached, "box $attempt found the solid at ${s.left}, ${s.top}, but it isn't near")
            }
            assertTrue(grid.cols > 0)
        }
    }

    @Test
    fun aRayThatHitsNothingForgetsWhatTheLastOneHit() {
        val world = space()
        val grid = SolidGrid(4, 4, 16f)
        grid.fill(2, 2)
        world.solids = grid
        world.raycast(0f, 40f, 63f, 40f, hit)
        assertEquals(HitKind.SOLID, hit.what)
        world.raycast(0f, 0f, 10f, 0f, hit)
        assertNull(hit.solid)
        assertTrue(abs(hit.fraction - 1f) < 1e-6f)
    }
}
