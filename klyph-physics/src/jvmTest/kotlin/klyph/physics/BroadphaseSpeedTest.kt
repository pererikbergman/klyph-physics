package klyph.physics

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * How long a build of 10 000 particles takes. The goal is under a millisecond on an M-series Mac; other
 * machines, such as the CI's, only have to stay under a few, so a slow runner doesn't fail the build.
 */
class BroadphaseSpeedTest {
    @Test
    fun tenThousandParticlesBuildInUnderAMillisecond() {
        val n = 10_000
        val random = Random(3)
        // a 1000 x 1000 room of particles 4 units across, each moving up to 4 units a step: a busy pile
        val xs = FloatArray(n) { random.nextFloat() * 1000f }
        val ys = FloatArray(n) { random.nextFloat() * 1000f }
        val dx = FloatArray(n) { random.nextFloat() * 8f - 4f }
        val dy = FloatArray(n) { random.nextFloat() * 8f - 4f }
        val bp = Broadphase(cellSize = 16f)
        fun build() {
            bp.clear()
            for (i in 0 until n) bp.addMoving(i, xs[i], ys[i], dx[i], dy[i], 2f)
            bp.build()
        }
        repeat(500) { build() }
        val times = LongArray(200) {
            val t0 = System.nanoTime()
            build()
            System.nanoTime() - t0
        }
        times.sort()
        val median = times[times.size / 2] / 1e6
        val isAppleSilicon = System.getProperty("os.name").startsWith("Mac") && System.getProperty("os.arch") == "aarch64"
        val limit = if (isAppleSilicon) 1.0 else 5.0
        println("broadphase: $n particles, ${bp.filedCount} filings, ${bp.pairCount} pairs, median build $median ms")
        assertTrue(median < limit, "median build took $median ms, over $limit ms")
    }
}
