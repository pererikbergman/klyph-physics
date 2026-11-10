package klyph.physics.demo

import klyph.math.Rect
import klyph.physics.PhysicsWorld
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
        Kind("rubber", Color(0xF2994AFFu), 34f, SoftBodyTuning(spokeCompliance = 0.0001f, areaCompliance = 0.0001f)),
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
        override val world = PhysicsWorld().also { it.solids = grid }

        override val blobs = IntArray(kinds.size) { i ->
            val kind = kinds[i]
            world.softBody(Scenes.dome(kind.coreMass), centerX = (6 + i * 6) * t, baseY = 6 * t, tuning = kind.tuning)
        }
        val bodies = world.softBodies

        /** Blobs added with a right click, each the next kind in turn: they don't hop, they just sit. */
        val spawned = ArrayList<Int>()
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
                val x = bodies.centerX(blob)
                if (x < 3 * t) direction[i] = 1
                if (x > (Scenes.COLS - 3) * t) direction[i] = -1
                hopTimer[i] -= dt
                if (hopTimer[i] <= 0f && bodies.isOnFloor(blob)) {
                    launch(blob, direction[i] * HOP_DRIFT, -HOP_SPEED)
                    hopTimer[i] = HOP_SECONDS
                }
            }
            world.step(dt)
            for (blob in blobs) if (bodies.touched(blob, PAD)) launch(blob, 0f, -BOUNCE_SPEED)
        }

        /**
         * Sends every particle of [blob] off at [vx] units per second sideways, on top of what it had,
         * and [vy] up or down instead of what it had. y grows down, so a jump is a negative [vy].
         */
        fun launch(blob: Int, vx: Float, vy: Float) {
            for (i in 0..bodies.ringCount(blob)) {
                val p = bodies.particle(blob, i)
                world.setVelocity(p, world.velocityX(p) + vx, vy)
            }
        }

        override fun spawn(x: Float, y: Float) {
            if (spawned.size >= Scenes.MAX_SPAWNED || !Scenes.isOpen(world, x, y)) return
            val kind = kinds[spawned.size % kinds.size]
            spawned += world.softBody(Scenes.dome(kind.coreMass), x, y + Scenes.DOME_HALF_HEIGHT, tuning = kind.tuning)
        }

        override fun draw(canvas: Canvas, debug: DebugDraw, view: Rect) {
            debug.solids(canvas, grid, view)
            for ((i, blob) in spawned.withIndex()) debug.softBody(canvas, world, blob, kinds[i % kinds.size].color)
            for (x in 28..30) debug.solid(canvas, grid.at(x, 20) ?: continue, PAD_COLOR)
            for ((i, blob) in blobs.withIndex()) {
                debug.softBody(canvas, world, blob, kinds[i].color)
                debug.label(canvas, kinds[i].label, bodies.centerX(blob), bodies.minY(blob) - 12f)
            }
        }

        override val particleCount get() = world.particles.count
        override val bodyCount get() = bodies.count
        override val contactCount get() = blobs.sumOf { bodies.floorContactCount(it) }
    }

    private val PAD_COLOR = Color(0xE0B040FFu)
}
