package klyph.physics.demo

import kotlin.test.Test
import kotlin.test.assertTrue

/** Runs the scenes made of blobs without a window and checks nothing breaks. */
class SceneTest {
    private val dt = 1f / 60f
    private val roomWidth = Scenes.COLS * Scenes.TILE
    private val roomHeight = Scenes.ROWS * Scenes.TILE

    @Test
    fun everyBlobSceneRunsForTenSecondsWithNothingLostOrTorn() {
        for (scene in Scenes.all) {
            val run = scene.create() as? BlobRun ?: continue
            check(scene.name, run, steps = 600)
        }
    }

    @Test
    fun theBlobGardenRunsForTenMinutesWithNothingLostOrTorn() {
        check(BlobGardenScene.name, BlobGardenScene.create() as BlobRun, steps = 36_000)
    }

    /**
     * Steps [run] [steps] times. Every step, every blob must be finite and inside the room. A blob may
     * snag on a corner and stretch for a moment, but not stay stretched: that is tearing.
     */
    private fun check(name: String, run: BlobRun, steps: Int) {
        val stretchedFor = IntArray(run.blobs.size)
        repeat(steps) { step ->
            (run as SceneRun).step(dt)
            for ((i, blob) in run.blobs.withIndex()) {
                val body = blob.body
                val where = "$name, blob $i, step $step"
                assertTrue(body.isFinite(), "$where: not finite")
                assertTrue(body.minX() >= 0f && body.maxX() <= roomWidth && body.minY() >= 0f && body.maxY() <= roomHeight, "$where: left the room")
                val stretch = body.maxEdgeStretch()
                stretchedFor[i] = if (stretch > MAX_STRETCH) stretchedFor[i] + 1 else 0
                assertTrue(stretchedFor[i] <= MAX_STRETCHED_STEPS, "$where: torn, stretched $stretch times for ${stretchedFor[i]} steps")
            }
        }
    }

    private companion object {
        /** An outline edge more than this many times its rest length is over-stretched. */
        const val MAX_STRETCH = 2.5f

        /** Half a second: longer over-stretched than this and the body is not snagging, it is tearing. */
        const val MAX_STRETCHED_STEPS = 30
    }
}
