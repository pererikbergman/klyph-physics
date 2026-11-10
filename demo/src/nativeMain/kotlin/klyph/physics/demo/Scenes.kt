package klyph.physics.demo

import klyph.physics.PhysicsWorld
import klyph.physics.SolidGrid

/** Every scene of the sandbox, in the order the number keys choose them. */
object Scenes {
    val all: List<Scene> = listOf(BlobGardenScene, FountainScene, SpringsScene)

    /** Every scene is laid out on a grid of these tiles, [COLS] by [ROWS]: 640 by 368 units. */
    const val TILE = 16f
    const val COLS = 40
    const val ROWS = 23

    /** A grid of [COLS] by [ROWS] with a two-tile floor at the bottom and walls on both sides. */
    fun room(): SolidGrid = SolidGrid(COLS, ROWS, TILE).apply {
        for (c in 0 until COLS) for (r in ROWS - 2 until ROWS) fill(c, r)
        for (r in 0 until ROWS) {
            fill(0, r)
            fill(COLS - 1, r)
        }
    }
}

/** A run made of soft bodies in a world, so tests can look at them. */
interface BlobRun {
    val world: PhysicsWorld

    /** The handles of its soft bodies in [world]. */
    val blobs: IntArray
}
