package klyph.physics.demo

import klyph.physics.PhysicsWorld
import klyph.physics.SoftBodies
import klyph.physics.SoftBodyShape
import klyph.physics.SolidGrid
import kotlin.math.floor

/** Every scene of the sandbox, in the order the number keys choose them. */
object Scenes {
    val all: List<Scene> = listOf(BlobGardenScene, FountainScene, SpringsScene, PileScene, GripAndBounceScene, RopeSwingScene, ClothScene, SandScene)

    /** Every scene is laid out on a grid of these tiles, [COLS] by [ROWS]: 640 by 368 units. */
    const val TILE = 16f
    const val COLS = 40
    const val ROWS = 23

    /** The most things a right click adds to one run, so holding it down can't bury a scene. */
    const val MAX_SPAWNED = 24

    /** The slime-shaped dome most scenes are made of: half a disc, 1.3 times taller, its core as heavy as [coreMass]. */
    fun dome(coreMass: Float = 34f): SoftBodyShape =
        SoftBodies.semiDisc(radius = 13.3f, arcSegments = 14, baseSegments = 8, coreMass = coreMass, heightScale = 1.3f)

    /** How far a [dome]'s bottom is below its middle: where to put its base to centre it on a point. */
    const val DOME_HALF_HEIGHT = 9f

    /** Whether ([x], [y]) is inside the room and not inside a solid of [world]: somewhere a thing can be spawned. */
    fun isOpen(world: PhysicsWorld, x: Float, y: Float): Boolean {
        if (x < TILE || x > (COLS - 1) * TILE || y < 0f || y > (ROWS - 2) * TILE) return false
        val grid = world.solids ?: return true
        return grid.at(floor(x / grid.tileSize).toInt(), floor(y / grid.tileSize).toInt()) == null
    }

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
