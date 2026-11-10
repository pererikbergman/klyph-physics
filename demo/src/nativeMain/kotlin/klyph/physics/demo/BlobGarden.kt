package klyph.physics.demo

import klyph.math.Rect
import klyph.physics.Solid
import klyph.physics.SoftBodyTuning
import klyph.physics.SolidShape
import klyph.physics.bridge.DebugDraw
import klyph.platform.Color
import klyph.render.Canvas
import kotlin.math.PI
import kotlin.math.sin

/**
 * Five blobs that differ only in how they are tuned, hopping about a garden of every kind of solid:
 * a ramp, a staircase, a deck with a shaft one tile wide, a crusher and bounce pads under a ledge.
 * Each hops every [HOP_SECONDS] and turns round at the walls, so they keep finding things to do.
 */
object BlobGardenScene : Scene {
    override val name = "Blob Garden"
    override val blurb = "same shape, five tunings. watch them squash, squeeze and bounce"

    /** One kind of blob: what it is called, what colour it is, how heavy its core is and how soft it is. */
    class Kind(val label: String, val color: Color, val coreMass: Float, val tuning: SoftBodyTuning)

    val kinds = listOf(
        Kind("jelly", Color(0xF27BB6FFu), 34f, SoftBodyTuning(spokeCompliance = 0.004f, areaCompliance = 0.003f)),
        Kind("rubber", Color(0xF2994AFFu), 34f, SoftBodyTuning(spokeCompliance = 0.0001f, areaCompliance = 0.0001f, damping = 0.9995f)),
        Kind("stiff", Color(0x8FA8C8FFu), 34f, SoftBodyTuning(edgeCompliance = 0f, spokeCompliance = 0.00001f, areaCompliance = 0.00001f)),
        Kind("heavy core", Color(0xA678E0FFu), 80f, SoftBodyTuning()),
        Kind("light core", Color(0xB8E05CFFu), 5f, SoftBodyTuning()),
    )

    /** The [klyph.physics.Solid.tag] of a bounce pad. */
    const val PAD = 1

    const val HOP_SECONDS = 1.6f
    const val HOP_SPEED = 360f
    const val HOP_DRIFT = 140f
    const val BOUNCE_SPEED = 680f

    override fun create(): SceneRun = Run()

    private class Run : SceneRun, BlobRun {
        val t = Scenes.TILE
        val grid = Scenes.room().apply {
            // A ramp up to a plateau, then a staircase up to row 16.
            fill(3, 20, SolidShape.RISING)
            fill(4, 19, SolidShape.RISING); fill(4, 20)
            for (c in 5..7) for (r in 19..20) fill(c, r)
            for (r in 18..20) fill(8, r)
            for (r in 17..20) fill(9, r)
            for (r in 16..20) fill(10, r)
            // A deck level with the top of the stairs, with a shaft one tile wide at column 15.
            for (c in 11..18) if (c != 15) fill(c, 16)
            fill(19, 16, SolidShape.FALLING)
            // A ceiling stub over the open floor, and bounce pads under a high ledge.
            for (c in 20..23) fill(c, 9)
            fillTopHalf(22, 10)
            for (c in 28..30) fill(c, 20, tag = PAD)
            for (c in 32..38) fill(c, 12)
            fill(31, 12, SolidShape.RISING)
        }
        val crusher = Solid.moving(25 * t, 0f, 27 * t, 2 * t).also { grid.dynamic.add(it) }

        override val blobs = kinds.mapIndexed { i, kind ->
            Blob.dome(centerX = (6 + i * 6) * t, baseY = 6 * t, color = kind.color, coreMass = kind.coreMass, tuning = kind.tuning)
        }
        val direction = IntArray(blobs.size) { if (it % 2 == 0) 1 else -1 }
        val hopTimer = FloatArray(blobs.size) { 0.6f + it * 0.3f }
        var seconds = 0f

        override fun step(dt: Float) {
            seconds += dt
            // The crusher swings between 10 and 18 tiles down: at its lowest a tile above the floor, low enough to squash a blob flat.
            val top = 14 * t + 4 * t * sin(seconds * PI.toFloat() / 2f)
            crusher.top = top
            crusher.bottom = top + 2 * t
            for ((i, blob) in blobs.withIndex()) {
                val x = blob.body.centerX()
                if (x < 3 * t) direction[i] = 1
                if (x > (Scenes.COLS - 3) * t) direction[i] = -1
                hopTimer[i] -= dt
                if (hopTimer[i] <= 0f && blob.contacts.touchedFloor) {
                    blob.launch(direction[i] * HOP_DRIFT, -HOP_SPEED, dt)
                    hopTimer[i] = HOP_SECONDS
                }
                blob.step(grid, dt)
                if (blob.contacts.touched(PAD)) blob.launch(0f, -BOUNCE_SPEED, dt)
            }
        }

        override fun draw(canvas: Canvas, debug: DebugDraw, view: Rect) {
            debug.solids(canvas, grid, view)
            for (x in 28..30) debug.solid(canvas, grid.at(x, 20) ?: continue, PAD_COLOR)
            for ((i, blob) in blobs.withIndex()) {
                debug.softBody(canvas, blob.body, blob.color)
                debug.contacts(canvas, blob.body, blob.contacts)
                debug.label(canvas, kinds[i].label, blob.body.centerX(), blob.body.minY() - 12f)
            }
        }

        override val particleCount get() = blobs.sumOf { it.body.size }
        override val bodyCount get() = blobs.size
        override val contactCount get() = blobs.sumOf { it.floorContactCount() }
    }

    private val PAD_COLOR = Color(0xE0B040FFu)
}
