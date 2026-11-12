package klyph.physics

import java.lang.management.ManagementFactory
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Checks that a step allocates nothing. Kotlin/Native can't count allocations, so this runs on the JVM,
 * where the thread's allocated bytes can be read: a step that allocated even one small object would add
 * at least 16 bytes each time, so ten thousand of them would show. (A planted `FloatArray(1)` kept in a
 * field shows as 240 000 bytes. One that never leaves the step may be optimised away by the JIT, so
 * this catches what reaches the heap, which is what costs a game its frame time.)
 */
class AllocationTest {
    private val threads = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean

    private fun allocatedBytes(): Long = threads.getThreadAllocatedBytes(Thread.currentThread().id)

    /** Bytes allocated while [block] runs, after running it once so classes are loaded and code compiled. */
    private fun measure(block: () -> Unit): Long {
        repeat(3) { block() }
        val before = allocatedBytes()
        block()
        return allocatedBytes() - before
    }

    @Test
    fun aStepAllocatesNothing() {
        val world = PhysicsWorld()
        val ps = world.particles
        val cs = world.constraints
        repeat(500) { ps.add(it.toFloat(), 0f) }
        for (p in 1 until 300) cs.distance(p - 1, p, compliance = 0.001f)
        for (p in 300 until 400) cs.rope(p - 1, p)
        for (p in 2 until 300) cs.bend(p - 2, p - 1, p, compliance = 0.01f)
        // a cloth whose links can tear, so the tear check runs every step
        world.cloth(600f, 0f, cols = 10, rows = 8, spacing = 6f, tearAt = 3f)
        cs.pin(0)
        cs.area(IntArray(50) { ps.add(400f + 10f * kotlin.math.cos(it * 0.1257f), 10f * kotlin.math.sin(it * 0.1257f)) })
        // a floor and a moving solid under all of it, some particles round, so collisions run too
        world.solids = SolidGrid(40, 10, 16f).apply {
            for (c in 0 until cols) fill(c, 9)
            fill(5, 8, SolidShape.RISING)
            dynamic.add(Solid.moving(100f, 100f, 140f, 120f))
        }
        repeat(50) { ps.add(200f + it, 50f, radius = 2f) }
        // blobs and colliding grains, so the broadphase and body collisions run too
        repeat(6) { world.softBody(SoftBodies.semiDisc(13f, 14, 8, 34f), 150f + it * 30f, 100f) }
        repeat(60) { ps.add(160f + it % 20 * 5f, 20f + it / 20 * 5f, radius = 2f, collides = true) }
        // and a hand dragging one of the blobs about
        world.grab(150f, 90f)
        var frame = 0
        val bytes = measure {
            repeat(10_000) {
                frame++
                world.moveGrab(150f + (frame % 120) * 2f, 60f)
                world.step(1f / 60f)
            }
        }
        assertTrue(bytes < 1024, "10 000 steps allocated $bytes bytes")
    }

    @Test
    fun aStepOfRigidBodiesAmongBlobsAndGrainsAllocatesNothing() {
        val world = PhysicsWorld()
        world.solids = SolidGrid(40, 30, 16f).apply {
            for (c in 0 until cols) fill(c, 29)
            for (r in 0 until 30) {
                fill(0, r)
                fill(cols - 1, r)
            }
            fill(10, 28, SolidShape.RISING)
            dynamic.add(Solid.moving(400f, 300f, 460f, 316f))
        }
        // a stack, a pile of every kind of shape, and a kinematic paddle sweeping through them
        repeat(6) { world.rigidBody(Shape.box(20f, 20f), 100f, 454f - it * 20f) }
        val shapes = arrayOf(Shape.box(16f, 16f), Shape.circle(8f), Shape.capsule(20f, 5f), Shape.polygon(-8f, 6f, 0f, -8f, 8f, 6f))
        repeat(40) { world.rigidBody(shapes[it % shapes.size], 250f + (it % 8) * 20f, 200f + (it / 8) * 24f, angle = it * 0.4f) }
        val paddle = world.rigidBody(Shape.box(60f, 8f), 300f, 380f, kind = BodyKind.KINEMATIC)
        world.rigidBodies.setAngularVelocity(paddle, 1f)
        // blobs and grains among them, and a rope tied to a crate, so the soft-rigid coupling runs too
        repeat(4) { world.softBody(SoftBodies.semiDisc(13f, 14, 8, 34f), 200f + it * 60f, 440f) }
        repeat(30) { world.particles.add(260f + it % 10 * 5f, 100f + it / 10 * 5f, radius = 2f, collides = true) }
        val knot = world.particles.add(100f, 330f)
        world.attach(knot, 0)
        // and joints of every kind, with limits, motors and a weld that breaks
        val arm = world.rigidBody(Shape.box(40f, 6f), 500f, 100f)
        val hinge = world.joints.hinge(arm, Joints.WORLD, 480f, 100f)
        world.joints.setLimits(hinge, -1f, 1f)
        world.joints.setMotor(hinge, 3f, 1e5f)
        val lift = world.rigidBody(Shape.box(30f, 6f), 560f, 100f)
        val slider = world.joints.slider(lift, Joints.WORLD, 560f, 100f, 0f, 1f)
        world.joints.setLimits(slider, -60f, 0f)
        world.joints.setMotor(slider, 20f, 1e5f)
        world.joints.weld(world.rigidBody(Shape.box(10f, 10f), 600f, 60f), Joints.WORLD, 600f, 55f, breakForce = 1e4f)
        world.joints.distance(arm, Joints.particle(knot), 510f, 100f, 100f, 330f)
        val bytes = measure { repeat(5_000) { world.step(1f / 60f) } }
        assertTrue(bytes < 1024, "5 000 steps allocated $bytes bytes")
    }

    @Test
    fun buildingTheBroadphaseAllocatesNothingOnceItHasGrown() {
        val bp = Broadphase()
        val random = kotlin.random.Random(1)
        val xs = FloatArray(2000) { random.nextFloat() * 1000f }
        val ys = FloatArray(2000) { random.nextFloat() * 1000f }
        val bytes = measure {
            repeat(1000) {
                bp.clear()
                for (i in xs.indices) bp.addMoving(i, xs[i], ys[i], 2f, 3f, 2f)
                bp.build()
            }
        }
        assertTrue(bytes < 1024, "1000 builds allocated $bytes bytes")
    }
}
