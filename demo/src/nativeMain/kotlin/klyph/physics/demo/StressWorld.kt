package klyph.physics.demo

import klyph.physics.PhysicsConfig
import klyph.physics.PhysicsWorld
import klyph.physics.Shape
import klyph.physics.SoftBodies

/**
 * The world the performance targets are measured on: up to [BOXES] rigid boxes in a block on the
 * left, [BLOBS] soft balls above, and [GRAINS] grains of sand heaped on the right, in the sandbox's
 * room, cut into [SUBSTEPS] substeps a step: see [TARGET_MILLIS]. [boxes], [blobs] and [grains] pick
 * how many of each, so the same layout times each kind on its own.
 */
class StressWorld(
    val boxes: Int = BOXES,
    val blobs: Int = BLOBS,
    val grains: Int = GRAINS,
    config: PhysicsConfig = CONFIG,
) {
    val world = PhysicsWorld(config).apply { solids = Scenes.room() }

    private val t = Scenes.TILE
    private val floor = (Scenes.ROWS - 2) * t

    /** The handles of the boxes, in [world]'s rigid bodies. */
    val boxHandles = IntArray(boxes)

    init {
        // the boxes: a block COLUMNS wide from the left wall, a hair apart so none starts in another
        val step = BOX + BOX_GAP
        for (i in 0 until boxes) {
            val col = i % COLUMNS
            val row = i / COLUMNS
            boxHandles[i] = world.rigidBody(Shape.box(BOX, BOX), t + step * col + step / 2f, floor - step * row - step / 2f)
        }
        // the sand: a hex heap from the right wall, as wide as the room right of the block
        val ps = world.particles
        val left = t + step * COLUMNS + GRAIN_RADIUS + 2f
        val right = (Scenes.COLS - 1) * t - GRAIN_RADIUS
        val gap = 2f * GRAIN_RADIUS + 0.1f
        var row = 0
        var y = floor - GRAIN_RADIUS
        while (ps.count < grains) {
            var x = left + if (row % 2 == 1) gap / 2f else 0f
            while (x <= right && ps.count < grains) {
                ps.add(x, y, radius = GRAIN_RADIUS, collides = true)
                x += gap
            }
            y -= gap * 0.87f
            row++
        }
        // the blobs: rows of balls above the sand, dropped on it
        val across = 10
        val spacing = (right - left) / across
        for (i in 0 until blobs) {
            world.softBody(BLOB, left + spacing * (i % across + 0.5f), BLOB_TOP + (i / across) * (2f * BLOB_RADIUS + 4f))
        }
    }

    fun step(dt: Float) = world.step(dt)

    companion object {
        /** This many of each, at [SUBSTEPS] substeps of [ITERATIONS] passes, 60 steps a second. */
        const val BOXES = 1000
        const val BLOBS = 50
        const val GRAINS = 5000
        const val SUBSTEPS = 8

        /**
         * Two passes a substep: many small substeps rather than a few with many passes, as the Small
         * Steps paper has it, holds a pile as well for less. One holds the boxes, but not a heap of sand
         * fifty grains deep: its weight takes too long to reach the bottom, and the grains pressed into
         * the corners squirt out.
         */
        const val ITERATIONS = 2

        /**
         * The targets, as averages over [Bench]'s 600 steps on an M-series Mac, one thread: each kind
         * alone, never asleep, in under [TARGET_MILLIS]; all of them together in under
         * [WORLD_TARGET_MILLIS], more than a 60 FPS frame. Multithreading is for the rest.
         */
        const val TARGET_MILLIS = 8f
        const val WORLD_TARGET_MILLIS = 20f

        const val BOX = 8f
        const val BOX_GAP = 0.5f
        const val COLUMNS = 40
        const val GRAIN_RADIUS = 1.25f
        const val BLOB_RADIUS = 8f
        const val BLOB_TOP = 30f

        val CONFIG = PhysicsConfig(substeps = SUBSTEPS, iterations = ITERATIONS)
        val BLOB = SoftBodies.circle(radius = BLOB_RADIUS, segments = 12, coreMass = 8f)
    }
}
