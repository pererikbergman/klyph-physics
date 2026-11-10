package klyph.physics.demo

import klyph.math.Rect
import klyph.physics.Solid
import klyph.physics.SolidGrid
import klyph.physics.SolidShape
import klyph.physics.bridge.DebugDraw
import klyph.platform.Color
import klyph.render.Canvas
import kotlin.math.PI
import kotlin.math.sin

/** Every scene of the sandbox, in the order the number keys choose them. */
object Scenes {
    val all: List<Scene> = listOf(DebugCheckScene)

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

/** Every kind of solid and a falling blob, to check that the debug drawing shows all of it. */
object DebugCheckScene : Scene {
    override val name = "Debug Check"
    override val blurb = "every kind of solid. F1 shows particles, tile edges and feet"

    override fun create(): SceneRun = object : SceneRun, BlobRun {
        val grid = Scenes.room().apply {
            // A ramp up to a ledge: rising slopes over solid tiles.
            fill(12, 20, SolidShape.RISING)
            fill(13, 19, SolidShape.RISING); fill(13, 20)
            for (c in 14..17) for (r in 19..20) fill(c, r)
            fill(18, 19, SolidShape.FALLING); fill(18, 20)
            // A ceiling with a stub hanging from it.
            for (c in 22..27) fill(c, 12)
            fillTopHalf(24, 13)
        }
        val crusher = Solid.moving(30 * Scenes.TILE, 0f, 32 * Scenes.TILE, 2 * Scenes.TILE).also { grid.dynamic.add(it) }
        override val blobs = listOf(
            Blob.dome(6 * Scenes.TILE, 6 * Scenes.TILE, Color(0x62C45CFFu)),
            Blob.dome(14 * Scenes.TILE, 4 * Scenes.TILE, Color(0x5CA8E0FFu)),
        )
        var seconds = 0f

        override fun step(dt: Float) {
            seconds += dt
            // The crusher swings between 6 and 16 tiles down, three tiles above the floor at its lowest, every four seconds.
            val top = 11 * Scenes.TILE + 5 * Scenes.TILE * sin(seconds * PI.toFloat() / 2f)
            crusher.top = top
            crusher.bottom = top + 2 * Scenes.TILE
            for (blob in blobs) blob.step(grid, dt)
        }

        override fun draw(canvas: Canvas, debug: DebugDraw, view: Rect) {
            debug.solids(canvas, grid, view)
            for (blob in blobs) {
                debug.softBody(canvas, blob.body, blob.color)
                debug.contacts(canvas, blob.body, blob.contacts)
            }
        }

        override val particleCount get() = blobs.sumOf { it.body.size }
        override val bodyCount get() = blobs.size
        override val contactCount get() = blobs.sumOf { it.floorContactCount() }
    }
}

/** A run made of blobs, so tests can look at them. */
interface BlobRun {
    val blobs: List<Blob>
}
