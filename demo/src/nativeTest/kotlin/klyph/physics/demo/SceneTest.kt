package klyph.physics.demo

import kotlin.test.Test
import kotlin.test.assertTrue

/** Runs every scene made of blobs without a window and checks nothing breaks. */
class SceneTest {
    private val dt = 1f / 60f
    private val roomWidth = Scenes.COLS * Scenes.TILE
    private val roomHeight = Scenes.ROWS * Scenes.TILE

    @Test
    fun everyBlobSceneRunsForTenSecondsWithNothingLostOrTorn() {
        for (scene in Scenes.all) {
            val run = scene.create() as? BlobRun ?: continue
            repeat(600) { step ->
                (run as SceneRun).step(dt)
                for ((i, blob) in run.blobs.withIndex()) {
                    val body = blob.body
                    val where = "${scene.name}, blob $i, step $step"
                    assertTrue(body.isFinite(), "$where: not finite")
                    assertTrue(body.minX() >= 0f && body.maxX() <= roomWidth && body.minY() >= 0f && body.maxY() <= roomHeight, "$where: left the room")
                    assertTrue(body.maxEdgeStretch() < MAX_STRETCH, "$where: torn, stretched ${body.maxEdgeStretch()} times")
                }
            }
        }
    }

    private companion object {
        /** An outline edge more than this many times its rest length means the body is tearing. */
        const val MAX_STRETCH = 2.5f
    }
}
