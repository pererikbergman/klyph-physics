package klyph.physics.demo

import klyph.math.Rect
import klyph.physics.PhysicsConfig
import klyph.physics.PhysicsWorld
import klyph.physics.SolidShape
import klyph.physics.bridge.DebugDraw
import klyph.platform.Color
import klyph.render.Canvas

/**
 * Sand: [GRAINS] round particles in a closed hourglass. They start packed into the top bulb's funnel
 * from the neck up, pour through the neck, one tile wide, and heap up in the bottom bulb at the slope their friction holds. Each grain
 * is a colliding particle on its own: no bodies, no constraints, just contacts and grip, thousands of
 * them a step. Bands of colour show how the layers mix as they pour.
 */
object SandScene : Scene {
    override val name = "Sand"
    override val blurb = "3 000 grains through an hourglass. contacts and friction, nothing else"

    const val GRAINS = 3000
    const val GRAIN_RADIUS = 1.25f

    /** The hourglass's columns: its side walls, and the neck in the middle, one tile wide. */
    const val LEFT_WALL = 11
    const val RIGHT_WALL = 29
    const val NECK = 20

    /** The rows: the top bulb's walls end at [SLOPE_TOP], where the funnel starts, and it narrows to the neck at [NECK_ROW]. */
    const val SLOPE_TOP = 6
    const val NECK_ROW = 13

    /** Grains have no constraints to settle, only contacts: fewer passes than the default 6 hold a heap. */
    const val ITERATIONS = 3

    private const val GAP = 2f * GRAIN_RADIUS + 0.1f

    private val BANDS = listOf(Color(0xE8C887FFu), Color(0xD9A75FFFu), Color(0xF2DDAAFFu), Color(0xC98F4AFFu))
    private val GLASS = Color(0x5F7699FFu)
    private val SCOOP_COLOR = Color(0xF27BB6FFu)

    /** Grains in a right click's scoop: a little heap, in a colour of its own. */
    const val SCOOP = 30

    override fun create(): SceneRun = Run()

    internal class Run : SceneRun {
        private val t = Scenes.TILE
        override val world = PhysicsWorld(PhysicsConfig(iterations = ITERATIONS)).apply {
            solids = Scenes.room().apply {
                // straight sides from top to bottom, and a lid
                for (r in 0 until Scenes.ROWS - 2) {
                    fill(LEFT_WALL, r)
                    fill(RIGHT_WALL, r)
                }
                for (c in LEFT_WALL + 1 until RIGHT_WALL) fill(c, 0)
                // the top bulb's funnel down to the neck, solid glass under its slopes, so the bottom
                // bulb is closed but for the neck
                for (k in 0..NECK_ROW - SLOPE_TOP) {
                    val r = SLOPE_TOP + k
                    fill(LEFT_WALL + 1 + k, r, SolidShape.FALLING)
                    fill(RIGHT_WALL - 1 - k, r, SolidShape.RISING)
                    for (c in LEFT_WALL + 1 until LEFT_WALL + 1 + k) fill(c, r)
                    for (c in RIGHT_WALL - k until RIGHT_WALL) fill(c, r)
                }
            }
        }

        /** Each grain's band of colour: which dozen rows of the starting heap it was in. */
        private val band = IntArray(GRAINS)

        init {
            // Packed into the funnel from the neck up, a row at a time, every other row half a grain over.
            // Dropped in from above instead, the heap slams into the funnel and the squeezed grains fly.
            val ps = world.particles
            val left = (LEFT_WALL + 1) * t + GRAIN_RADIUS
            val right = RIGHT_WALL * t - GRAIN_RADIUS
            // the funnel's slopes are the lines y = x + leftSlope and y = rightSlope - x; inside is above both
            val leftSlope = (SLOPE_TOP - LEFT_WALL - 1) * t
            val rightSlope = (RIGHT_WALL + SLOPE_TOP) * t
            val clear = GRAIN_RADIUS * 1.5f
            var row = 0
            var y = (NECK_ROW + 1) * t - GRAIN_RADIUS
            while (ps.count < GRAINS && y > t + GRAIN_RADIUS) {
                var x = left + if (row % 2 == 1) GAP / 2f else 0f
                while (x <= right && ps.count < GRAINS) {
                    if (y < x + leftSlope - clear && y < rightSlope - x - clear) {
                        band[ps.count] = row / 12 % BANDS.size
                        ps.add(x, y, radius = GRAIN_RADIUS, collides = true)
                    }
                    x += GAP
                }
                y -= GAP * 0.87f
                row++
            }
        }

        override fun step(dt: Float) = world.step(dt)

        private var scoops = 0

        override fun spawn(x: Float, y: Float) {
            if (scoops >= Scenes.MAX_SPAWNED || !Scenes.isOpen(world, x, y)) return
            scoops++
            repeat(SCOOP) { i ->
                // a little hex heap, five grains a row, around the click
                val row = i / 5
                world.particles.add(x + (i % 5 - 2) * GAP + (row % 2) * GAP / 2f, y - row * GAP * 0.87f, radius = GRAIN_RADIUS, collides = true)
            }
        }

        override fun draw(canvas: Canvas, debug: DebugDraw, view: Rect) {
            debug.solids(canvas, world.solids!!, view, GLASS)
            debug.particles(canvas, world.particles) { p -> if (p < GRAINS) BANDS[band[p]] else SCOOP_COLOR }
        }

        override val particleCount get() = world.particles.count
        override val contactCount get() = (0 until world.particles.end).count { world.events.isTouching(it) }
    }
}
