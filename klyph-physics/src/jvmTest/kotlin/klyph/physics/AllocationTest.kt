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
        cs.pin(0)
        cs.area(IntArray(50) { ps.add(400f + 10f * kotlin.math.cos(it * 0.1257f), 10f * kotlin.math.sin(it * 0.1257f)) })
        // a floor and a moving solid under all of it, some particles round, so collisions run too
        world.solids = SolidGrid(40, 10, 16f).apply {
            for (c in 0 until cols) fill(c, 9)
            fill(5, 8, SolidShape.RISING)
            dynamic.add(Solid.moving(100f, 100f, 140f, 120f))
        }
        repeat(50) { ps.add(200f + it, 50f, radius = 2f) }
        val bytes = measure { repeat(10_000) { world.step(1f / 60f) } }
        assertTrue(bytes < 1024, "10 000 steps allocated $bytes bytes")
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
