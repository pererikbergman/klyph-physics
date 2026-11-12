package klyph.physics

import kotlin.math.PI
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ShapeTest {
    private val pi = PI.toFloat()

    @Test
    fun aCirclesMassAndInertiaMatchTheClosedForms() {
        val circle = Shape.circle(10f)
        assertEquals(pi * 100f, circle.area, 1e-3f)
        // m r² / 2
        assertEquals(circle.area * 100f / 2f, circle.inertia, 1e-1f)
        assertEquals(0f, circle.centroidX)
    }

    @Test
    fun aBoxsMassAndInertiaMatchTheClosedForms() {
        val box = Shape.box(30f, 10f)
        assertEquals(300f, box.area, 1e-3f)
        // m (w² + h²) / 12
        assertEquals(300f * (900f + 100f) / 12f, box.inertia, 1f)
        assertEquals(0f, box.centroidX, 1e-5f)
        assertEquals(0f, box.centroidY, 1e-5f)
    }

    @Test
    fun aTurnedBoxWeighsTheSameAndTurnsJustAsHard() {
        val box = Shape.box(30f, 10f)
        val turned = Shape.box(30f, 10f, x = 5f, y = -3f, angle = 0.7f)
        assertEquals(box.area, turned.area, 1e-2f)
        assertEquals(box.inertia, turned.inertia, 1f)
        assertEquals(5f, turned.centroidX, 1e-4f)
        assertEquals(-3f, turned.centroidY, 1e-4f)
    }

    @Test
    fun aTrianglesMassAndInertiaMatchTheClosedForms() {
        // a right triangle with legs a and b: area ab/2, centroid a third of the way along each leg,
        // inertia about the centroid m (a² + b²) / 18
        val a = 30f
        val b = 12f
        val triangle = Shape.polygon(0f, 0f, a, 0f, 0f, b)
        assertEquals(a * b / 2f, triangle.area, 1e-3f)
        assertEquals(a / 3f, triangle.centroidX, 1e-4f)
        assertEquals(b / 3f, triangle.centroidY, 1e-4f)
        assertEquals(triangle.area * (a * a + b * b) / 18f, triangle.inertia, 0.5f)
    }

    @Test
    fun aCapsulesMassAndInertiaMatchABoxAndTwoHalfDiscs() {
        val length = 40f
        val r = 6f
        val capsule = Shape.capsule(length, r)
        assertEquals(2f * r * length + pi * r * r, capsule.area, 1e-2f)
        // numerically: sum r² over a fine grid of cells inside it
        var sum = 0.0
        val cell = 0.05
        var x = -(length / 2 + r) + cell / 2
        while (x < length / 2 + r) {
            var y = -r + cell / 2
            while (y < r) {
                if (capsule.contains(x.toFloat(), y.toFloat())) sum += (x * x + y * y) * cell * cell
                y += cell
            }
            x += cell
        }
        assertEquals(sum.toFloat(), capsule.inertia, capsule.inertia * 0.002f)
    }

    @Test
    fun aRegularPolygonTendsToTheCircle() {
        val n = 16
        val r = 10f
        val points = FloatArray(2 * n) { k ->
            val angle = 2 * PI * (k / 2) / n
            (if (k % 2 == 0) kotlin.math.cos(angle) else kotlin.math.sin(angle)).toFloat() * r
        }
        val polygon = Shape.polygon(*points)
        val circle = Shape.circle(r)
        assertEquals(circle.area, polygon.area, circle.area * 0.03f)
        assertEquals(circle.inertia, polygon.inertia, circle.inertia * 0.06f)
    }

    @Test
    fun pointsInEitherOrderMakeTheSameClockwisePolygon() {
        val clockwise = Shape.polygon(0f, 0f, 10f, 0f, 10f, 10f, 0f, 10f)
        val anticlockwise = Shape.polygon(0f, 0f, 0f, 10f, 10f, 10f, 10f, 0f)
        assertEquals(clockwise.area, anticlockwise.area)
        // the first edge's normal points out of the shape
        val mx = (anticlockwise.vertexX(0) + anticlockwise.vertexX(1)) / 2f
        val my = (anticlockwise.vertexY(0) + anticlockwise.vertexY(1)) / 2f
        assertTrue((mx - 5f) * anticlockwise.normalX(0) + (my - 5f) * anticlockwise.normalY(0) > 0f)
    }

    @Test
    fun aConcaveListOfPointsIsRefusedWithAHelpfulMessage() {
        // an arrowhead: the point at (10, 5) is a dent
        val error = assertFailsWith<IllegalArgumentException> { Shape.polygon(0f, 0f, 20f, 0f, 10f, 5f, 20f, 10f, 0f, 10f) }
        val message = error.message!!
        assertTrue("dent" in message && "(10.0, 5.0)" in message && "compound" in message, message)
    }

    @Test
    fun aCrossingAStraightCornerAndTooFewPointsAreRefused() {
        val star = FloatArray(10) { k ->
            val angle = 2 * PI * 2 * (k / 2) / 5
            (if (k % 2 == 0) kotlin.math.cos(angle) else kotlin.math.sin(angle)).toFloat() * 10f
        }
        assertTrue("crosses" in assertFailsWith<IllegalArgumentException> { Shape.polygon(*star) }.message!!)
        assertTrue("in a line" in assertFailsWith<IllegalArgumentException> { Shape.polygon(0f, 0f, 5f, 0f, 10f, 0f, 10f, 10f) }.message!!)
        assertFailsWith<IllegalArgumentException> { Shape.polygon(0f, 0f, 10f, 0f) }
        assertFailsWith<IllegalArgumentException> { Shape.polygon(0f, 0f, 10f, 0f, 5f) }
        assertFailsWith<IllegalArgumentException> { Shape.circle(0f) }
        assertFailsWith<IllegalArgumentException> { Shape.box(10f, -1f) }
        assertFailsWith<IllegalArgumentException> { Shape.capsule(0f, 5f) }
    }

    @Test
    fun distanceIsNegativeInsideAndZeroOnTheEdge() {
        val box = Shape.box(20f, 10f)
        assertEquals(-5f, box.distance(0f, 0f), 1e-5f)
        assertEquals(0f, box.distance(10f, 0f), 1e-5f)
        assertEquals(sqrt(2f), box.distance(11f, 6f), 1e-5f)
        val capsule = Shape.capsule(20f, 5f)
        assertEquals(-5f, capsule.distance(3f, 0f), 1e-5f)
        assertEquals(1f, capsule.distance(16f, 0f), 1e-5f)
        assertTrue(Shape.circle(4f, x = 10f).contains(12f, 0f))
        assertFalse(Shape.circle(4f, x = 10f).contains(0f, 0f))
    }

    @Test
    fun aCompoundBodyBalancesAtItsCentreOfMassAndAddsUpItsInertia() {
        // a hammer: a handle and a head, both boxes
        val handle = Shape.box(6f, 40f, y = 10f)
        val head = Shape.box(30f, 10f, y = -15f)
        val mass = MassProperties(listOf(handle, head), density = 2f)
        assertEquals(2f * (240f + 300f), mass.mass, 1e-2f)
        val centre = (240f * 10f + 300f * -15f) / 540f
        assertEquals(centre, mass.centerY, 1e-3f)
        val expected = 2f * (handle.inertia + 240f * (10f - centre) * (10f - centre) + head.inertia + 300f * (-15f - centre) * (-15f - centre))
        assertEquals(expected, mass.inertia, expected * 1e-4f)
    }

    @Test
    fun aBodyMadeOfShapesWeighsWhatTheyDoAndSitsAtItsCentreOfMass() {
        val world = PhysicsWorld()
        val bodies = world.rigidBodies
        val crate = world.rigidBody(Shape.box(32f, 32f), 100f, 50f, density = 0.05f)
        assertEquals(1f / (0.05f * 1024f), bodies.inverseMass(crate), 1e-6f)
        assertEquals(1f / (0.05f * 1024f * 2048f / 12f), bodies.inverseInertia(crate), 1e-8f)
        assertEquals(100f, bodies.x(crate))
        // a hammer whose shapes are made around its handle's middle sits where it balances, near its head
        val handle = Shape.box(6f, 40f, y = 10f)
        val head = Shape.box(30f, 10f, y = -15f)
        val hammer = world.rigidBody(listOf(handle, head), 200f, 100f)
        val centre = (240f * 10f + 300f * -15f) / 540f
        assertEquals(100f + centre, bodies.y(hammer), 1e-3f)
        assertEquals(2, bodies.shapeCount(hammer))
        // and its shapes are kept around that point
        assertEquals(10f - centre, bodies.shape(hammer, 0).centroidY, 1e-3f)
    }

    @Test
    fun aTurnedCompoundBodyPutsItsCentreOfMassWhereItsShapesAre() {
        val world = PhysicsWorld()
        val bodies = world.rigidBodies
        // a box made 10 to the right of the origin, turned a quarter turn clockwise: it's 10 below
        val b = world.rigidBody(Shape.box(4f, 4f, x = 10f), 0f, 0f, angle = (PI / 2).toFloat())
        assertEquals(0f, bodies.x(b), 1e-4f)
        assertEquals(10f, bodies.y(b), 1e-4f)
        assertTrue(bodies.contains(b, 0f, 11f))
        assertFalse(bodies.contains(b, 10f, 0f))
        assertEquals(1f, bodies.localX(b, 0f, 11f), 1e-4f, "one further along its own x")
    }

    @Test
    fun spinningABodyFromItsShapeKeepsTheSpinAndItsShape() {
        val world = PhysicsWorld(PhysicsConfig(gravity = Vec.ZERO, damping = 0f))
        val bodies = world.rigidBodies
        val capsule = world.rigidBody(Shape.capsule(30f, 5f), 0f, 0f)
        bodies.setAngularVelocity(capsule, 2f)
        repeat(60) { world.step(dt) }
        assertEquals(2f, bodies.angularVelocity(capsule), 1e-4f)
        // an end of it, 15 along its own x, has gone round 2 radians
        assertEquals(15f * kotlin.math.cos(2f), bodies.worldX(capsule, 15f, 0f), 1e-2f)
        assertEquals(15f * kotlin.math.sin(2f), bodies.worldY(capsule, 15f, 0f), 1e-2f)
    }

    private val dt = TestBodies.DT
}
