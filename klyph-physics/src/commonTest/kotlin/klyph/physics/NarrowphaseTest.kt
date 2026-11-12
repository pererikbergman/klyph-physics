package klyph.physics

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

class NarrowphaseTest {
    private val narrow = Narrowphase()
    private val m = Manifold()

    @Test
    fun aBoxOnABoxGivesTwoContactPointsAtItsCorners() {
        val box = Shape.box(20f, 20f)
        // a box sunk half a unit into the one below it
        assertTrue(narrow.collide(box, 0f, 0f, 0f, box, 0f, 19.5f, 0f, m))
        assertEquals(2, m.count)
        assertEquals(0f, m.normalX, 1e-6f)
        assertEquals(1f, m.normalY, 1e-6f, "from the top box down to the bottom one")
        for (i in 0 until 2) assertEquals(-0.5f, m.separation(i), 1e-4f)
        val xs = listOf(m.pointBX(0), m.pointBX(1)).sorted()
        assertEquals(-10f, xs[0], 1e-4f)
        assertEquals(10f, xs[1], 1e-4f)
    }

    @Test
    fun aSmallBoxOnABigOneTouchesAtTheSmallOnesCorners() {
        assertTrue(narrow.collide(Shape.box(100f, 20f), 0f, 0f, 0f, Shape.box(10f, 10f), 20f, -14.8f, 0f, m))
        assertEquals(2, m.count)
        assertEquals(-1f, m.normalY, 1e-6f, "up, from the big box to the small one on it")
        val xs = listOf(m.pointAX(0), m.pointAX(1)).sorted()
        assertEquals(15f, xs[0], 1e-4f)
        assertEquals(25f, xs[1], 1e-4f)
    }

    @Test
    fun aBoxBalancedOnItsCornerTouchesAtOnePoint() {
        val box = Shape.box(20f, 20f)
        val diagonal = 10f * sqrt(2f)
        assertTrue(narrow.collide(box, 0f, 0f, 0f, box, 0f, -10f - diagonal + 0.3f, (kotlin.math.PI / 4).toFloat(), m))
        assertEquals(1, m.count)
        assertEquals(-0.3f, m.separation(0), 1e-3f)
        assertEquals(0f, m.pointBX(0), 1e-3f)
    }

    @Test
    fun twoCirclesTouchAlongTheLineBetweenThem() {
        assertTrue(narrow.collide(Shape.circle(5f), 0f, 0f, 0f, Shape.circle(5f), 6f, 8f, 0f, m))
        assertEquals(1, m.count)
        assertEquals(0.6f, m.normalX, 1e-5f)
        assertEquals(0.8f, m.normalY, 1e-5f)
        assertEquals(0f, m.separation(0), 1e-5f, "just touching, 10 apart")
        assertFalse(narrow.collide(Shape.circle(5f), 0f, 0f, 0f, Shape.circle(4.9f), 6f, 8f, 0f, m))
    }

    @Test
    fun aCircleOnABoxsFaceAndOffItsCorner() {
        val box = Shape.box(20f, 20f)
        val ball = Shape.circle(5f)
        assertTrue(narrow.collide(ball, 3f, -14f, 0f, box, 0f, 0f, 0f, m))
        assertEquals(0f, m.normalX, 1e-6f)
        assertEquals(1f, m.normalY, 1e-6f, "down onto the face")
        assertEquals(-1f, m.separation(0), 1e-5f)
        // off the top-right corner, along the diagonal
        assertTrue(narrow.collide(ball, 13f, -13f, 0f, box, 0f, 0f, 0f, m))
        assertEquals(-sqrt(0.5f), m.normalX, 1e-5f)
        assertEquals(sqrt(0.5f), m.normalY, 1e-5f)
        assertEquals(sqrt(18f) - 5f, m.separation(0), 1e-4f)
        assertEquals(10f, m.pointBX(0), 1e-4f, "on the corner")
    }

    @Test
    fun aCircleInsideABoxIsPushedOutThroughTheNearestFace() {
        assertTrue(narrow.collide(Shape.circle(2f), 7f, 0f, 0f, Shape.box(20f, 20f), 0f, 0f, 0f, m))
        assertEquals(-1f, m.normalX, 1e-6f, "the box goes left and the circle right, out through its right face")
        assertEquals(-5f, m.separation(0), 1e-5f, "3 inside the face, plus its radius")
    }

    @Test
    fun aCapsuleLyingOnABoxTouchesAtTwoPoints() {
        assertTrue(narrow.collide(Shape.capsule(30f, 5f), 0f, -14.8f, 0f, Shape.box(100f, 20f), 0f, 0f, 0f, m))
        assertEquals(2, m.count, "lying flat it would rock on one point")
        assertEquals(1f, m.normalY, 1e-5f)
        for (i in 0 until 2) assertEquals(-0.2f, m.separation(i), 1e-4f)
        val xs = listOf(m.pointAX(0), m.pointAX(1)).sorted()
        assertEquals(-15f, xs[0], 1e-3f)
        assertEquals(15f, xs[1], 1e-3f)
    }

    @Test
    fun aCapsulesRoundEndOnABoxsCornerTouchesAtOnePointAlongTheDiagonal() {
        // the capsule's left end at (14, -14): 4 right of and 4 above the box's top-right corner (10, -10)
        assertTrue(narrow.collide(Shape.capsule(20f, 6f), 24f, -14f, 0f, Shape.box(20f, 20f), 0f, 0f, 0f, m))
        assertEquals(1, m.count)
        assertEquals(sqrt(32f) - 6f, m.separation(0), 1e-4f)
        assertEquals(-sqrt(0.5f), m.normalX, 1e-4f)
        assertEquals(sqrt(0.5f), m.normalY, 1e-4f)
    }

    @Test
    fun twoCapsulesSideBySideTouchAtTwoPoints() {
        val capsule = Shape.capsule(30f, 4f)
        assertTrue(narrow.collide(capsule, 0f, 0f, 0f, capsule, 5f, 7.5f, 0f, m))
        assertEquals(2, m.count)
        assertEquals(1f, m.normalY, 1e-4f)
        for (i in 0 until 2) assertEquals(-0.5f, m.separation(i), 1e-4f)
    }

    @Test
    fun theSameContactKeepsItsFeatureIdsAsTheShapesShiftALittle() {
        val box = Shape.box(20f, 20f)
        narrow.collide(box, 0f, 0f, 0f, box, 3f, 19.5f, 0.02f, m)
        val before = setOf(m.id(0), m.id(1))
        narrow.collide(box, 0f, 0f, 0f, box, 3.4f, 19.3f, 0.03f, m)
        assertEquals(before, setOf(m.id(0), m.id(1)))
        assertEquals(2, before.size, "two different points")
    }

    @Test
    fun withAMarginShapesThatNearlyTouchMakeAContactWithAPositiveSeparation() {
        val near = Narrowphase().apply { margin = 0.5f }
        val box = Shape.box(20f, 20f)
        assertFalse(narrow.collide(box, 0f, 0f, 0f, box, 0f, 20.3f, 0f, m))
        assertTrue(near.collide(box, 0f, 0f, 0f, box, 0f, 20.3f, 0f, m))
        assertEquals(2, m.count)
        assertEquals(0.3f, m.separation(0), 1e-4f)
        assertTrue(near.collide(Shape.circle(5f), 0f, 0f, 0f, Shape.circle(5f), 10.4f, 0f, 0f, m))
        assertEquals(0.4f, m.separation(0), 1e-4f)
    }

    /** Shapes of every kind, made at odd places around their origins, to try against each other. */
    private val zoo = listOf(
        Shape.circle(7f),
        Shape.circle(3f, x = 2f, y = -1f),
        Shape.box(16f, 10f),
        Shape.box(30f, 6f, x = 3f, angle = 0.4f),
        Shape.polygon(-8f, 6f, 0f, -9f, 9f, 5f),
        Shape.polygon(-6f, -6f, 4f, -8f, 9f, 0f, 3f, 8f, -7f, 5f),
        Shape.capsule(20f, 4f),
        Shape.capsule(8f, 6f, y = 2f, angle = 1f),
    )

    /** How far ([x], [y]) is outside [shape] at ([sx], [sy]) turned by [angle]: negative inside. */
    private fun distance(shape: Shape, sx: Float, sy: Float, angle: Float, x: Float, y: Float): Float {
        val c = cos(angle)
        val s = sin(angle)
        val dx = x - sx
        val dy = y - sy
        return shape.distance(c * dx + s * dy, -s * dx + c * dy)
    }

    /** Whether some point of a fine grid is deeper than [depth] inside both shapes. */
    private fun overlap(a: Shape, ax: Float, ay: Float, aa: Float, b: Shape, bx: Float, by: Float, ba: Float, depth: Float): Boolean {
        val step = 0.2f
        var y = minOf(ay, by) - 30f
        while (y < maxOf(ay, by) + 30f) {
            var x = minOf(ax, bx) - 30f
            while (x < maxOf(ax, bx) + 30f) {
                if (distance(a, ax, ay, aa, x, y) < -depth && distance(b, bx, by, ba, x, y) < -depth) return true
                x += step
            }
            y += step
        }
        return false
    }

    @Test
    fun everyPairOfShapesAgreesWithBruteForceSampling() {
        val random = Random(24)
        var touching = 0
        repeat(400) { trial ->
            val a = zoo[random.nextInt(zoo.size)]
            val b = zoo[random.nextInt(zoo.size)]
            val aa = random.nextFloat() * 6.3f
            val ba = random.nextFloat() * 6.3f
            val bx = (random.nextFloat() - 0.5f) * 36f
            val by = (random.nextFloat() - 0.5f) * 36f
            val hit = narrow.collide(a, 0f, 0f, aa, b, bx, by, ba, m)
            val where = "trial $trial: ${a.kind} turned $aa against ${b.kind} at ($bx, $by) turned $ba"
            if (!hit) {
                assertFalse(overlap(a, 0f, 0f, aa, b, bx, by, ba, 0.15f), "$where: no contact, but they overlap")
                return@repeat
            }
            touching++
            val deepest = (0 until m.count).minOf { m.separation(it) }
            // every point is on its shape, or inside it where a round end leans across the end of the
            // face it's clipped to, and its two ends are its separation apart along the normal
            for (i in 0 until m.count) {
                assertTrue(distance(a, 0f, 0f, aa, m.pointAX(i), m.pointAY(i)) < 0.05f, "$where: point $i is off A")
                assertTrue(distance(b, bx, by, ba, m.pointBX(i), m.pointBY(i)) < 0.05f, "$where: point $i is off B")
                val along = (m.pointBX(i) - m.pointAX(i)) * m.normalX + (m.pointBY(i) - m.pointAY(i)) * m.normalY
                assertEquals(m.separation(i), along, 1e-3f, "$where: point $i's ends")
            }
            // between sharp polygons, every point is exactly on both surfaces
            if (a.radius == 0f && b.radius == 0f) for (i in 0 until m.count) {
                assertEquals(0f, distance(a, 0f, 0f, aa, m.pointAX(i), m.pointAY(i)), 0.05f, "$where: point $i isn't on A")
                assertEquals(0f, distance(b, bx, by, ba, m.pointBX(i), m.pointBY(i)), 0.05f, "$where: point $i isn't on B")
            }
            assertEquals(1f, m.normalX * m.normalX + m.normalY * m.normalY, 1e-4f, "$where: the normal is a unit")
            if (deepest < -0.3f) assertTrue(overlap(a, 0f, 0f, aa, b, bx, by, ba, 0f), "$where: a contact ${-deepest} deep, but they don't overlap")
            // moved apart along the normal by the depth, and a little more, they no longer overlap
            val apart = -deepest + 0.3f
            assertFalse(
                overlap(a, 0f, 0f, aa, b, bx + m.normalX * apart, by + m.normalY * apart, ba, 0.15f),
                "$where: moved ${-deepest} apart along the normal, they still overlap",
            )
            // and moved less than the depth, they still do: it's the least move that parts them
            if (deepest < -0.6f) {
                val short = -deepest - 0.4f
                assertTrue(
                    overlap(a, 0f, 0f, aa, b, bx + m.normalX * short, by + m.normalY * short, ba, 0f),
                    "$where: the depth ${-deepest} is more than it takes to part them",
                )
            }
        }
        if (touching < 100) fail("only $touching of 400 pairs touched: the test isn't trying hard enough")
    }

    @Test
    fun separatedShapesMakeNoContact() {
        for (a in zoo) for (b in zoo) {
            assertFalse(narrow.collide(a, 0f, 0f, 0.3f, b, 60f, 5f, 1.2f, m), "${a.kind} and ${b.kind} 60 apart")
            assertEquals(0, m.count)
        }
    }
}
