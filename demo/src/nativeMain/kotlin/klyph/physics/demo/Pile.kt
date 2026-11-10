package klyph.physics.demo

import klyph.math.Rect
import klyph.physics.Material
import klyph.physics.PhysicsWorld
import klyph.physics.SoftBodies
import klyph.physics.SoftBodyShape
import klyph.physics.SoftBodyTuning
import klyph.physics.SolidShape
import klyph.physics.bridge.DebugDraw
import klyph.platform.Color
import klyph.render.Canvas
import kotlin.random.Random

/**
 * Thirty soft bodies, domes, balls and boxes, poured one after another into a funnel that drops them
 * into a bin, where they pile up without passing through each other. With the view on everything (`F1` three times), the yellow marks
 * are particles standing on something, a floor or another body, and the cyan ones are pushed sideways.
 */
object PileScene : Scene {
    override val name = "Blob Pile"
    override val blurb = "thirty bodies through a funnel. none of them goes through another"

    /** How many bodies fall, and how many steps apart. */
    const val COUNT = 30
    const val EVERY = 10

    /** The funnel: its walls slope in from [FUNNEL_LEFT] and [FUNNEL_RIGHT] on row [FUNNEL_TOP], over [FUNNEL_ROWS] rows, to a neck 10 tiles wide. */
    const val FUNNEL_TOP = 2
    const val FUNNEL_LEFT = 8
    const val FUNNEL_RIGHT = 31
    const val FUNNEL_ROWS = 7

    /** The bin's walls, and the row they start on. */
    const val BIN_LEFT = 12
    const val BIN_RIGHT = 27
    const val BIN_TOP = 11

    private val COLORS = listOf(
        Color(0xF27BB6FFu), Color(0xF2994AFFu), Color(0x8FA8C8FFu), Color(0xA678E0FFu), Color(0xB8E05CFFu), Color(0x5BC0EBFFu),
    )

    private val SHAPES: List<SoftBodyShape> = listOf(
        Scenes.dome(),
        SoftBodies.circle(radius = 12f, segments = 18, coreMass = 10f),
        SoftBodies.box(width = 26f, height = 18f, across = 7, down = 5, coreMass = 20f),
    )

    override fun create(): SceneRun = Run()

    internal class Run : SceneRun, BlobRun {
        private val t = Scenes.TILE
        override val world = PhysicsWorld().apply {
            solids = Scenes.room().apply {
                // A funnel over a bin 14 tiles wide in the middle of the room. Icy, or soft bodies sit on its
                // slopes; and two tiles thick, or they hook over its edge.
                for (k in 0 until FUNNEL_ROWS) {
                    fill(FUNNEL_LEFT + k, FUNNEL_TOP + k, SolidShape.FALLING, material = Material.ICE)
                    fill(FUNNEL_RIGHT - k, FUNNEL_TOP + k, SolidShape.RISING, material = Material.ICE)
                    fill(FUNNEL_LEFT + k, FUNNEL_TOP + k + 1, material = Material.ICE)
                    fill(FUNNEL_RIGHT - k, FUNNEL_TOP + k + 1, material = Material.ICE)
                }
                for (r in BIN_TOP..Scenes.ROWS - 3) {
                    fill(BIN_LEFT, r)
                    fill(BIN_RIGHT, r)
                }
            }
        }
        private val bodies = world.softBodies
        private val random = Random(11)
        private val colors = ArrayList<Color>()
        private var steps = 0
        private var dropped = 0

        override val blobs: IntArray get() = IntArray(bodies.count) { it }

        override fun step(dt: Float) {
            if (steps % EVERY == 0 && dropped < COUNT) {
                val i = dropped++
                // poured into the funnel's mouth, from anywhere along it
                val x = (FUNNEL_LEFT + 2 + random.nextFloat() * (FUNNEL_RIGHT - FUNNEL_LEFT - 3)) * t
                bodies.add(SHAPES[i % SHAPES.size], x, FUNNEL_TOP * t, SoftBodyTuning(spokeCompliance = 0.001f, areaCompliance = 0.0006f))
                colors += COLORS[i % COLORS.size]
            }
            steps++
            world.step(dt)
        }

        /** One more body, the next shape in turn, dropped from ([x], [y]). */
        override fun spawn(x: Float, y: Float) {
            if (bodies.count >= COUNT + Scenes.MAX_SPAWNED || !Scenes.isOpen(world, x, y)) return
            val i = bodies.count
            bodies.add(SHAPES[i % SHAPES.size], x, y + 9f, SoftBodyTuning(spokeCompliance = 0.001f, areaCompliance = 0.0006f))
            colors += COLORS[i % COLORS.size]
        }

        override fun draw(canvas: Canvas, debug: DebugDraw, view: Rect) {
            debug.solids(canvas, world.solids!!, view)
            for (body in 0 until bodies.end) debug.softBody(canvas, world, body, colors[body])
        }

        override val particleCount get() = world.particles.count
        override val bodyCount get() = bodies.count
        override val contactCount get() = (0 until bodies.end).sumOf { bodies.floorContactCount(it) }
    }
}
