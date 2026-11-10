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
        repeat(500) { world.particles.add(it.toFloat(), 0f) }
        val bytes = measure { repeat(10_000) { world.step(1f / 60f) } }
        assertTrue(bytes < 1024, "10 000 steps allocated $bytes bytes")
    }
}
