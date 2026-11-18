package klyph.physics.demo

import klyph.math.Rect
import klyph.physics.PhysicsWorld
import klyph.physics.Shape
import klyph.physics.bridge.DebugDraw
import klyph.platform.Color
import klyph.render.Canvas
import kotlin.time.TimeSource

/**
 * The [StressWorld], the one the performance targets are measured on: a thousand boxes in a block on
 * the left, fifty blobs dropped on five thousand grains of sand on the right, at eight substeps. The
 * graph in the corner is how long each of the last [HISTORY] steps took, green where it fits in a
 * 60 FPS frame, against that and the target, [StressWorld.WORLD_TARGET_MILLIS]. A right click drops
 * more: [SPAWN_BOXES] boxes, a blob or [SPAWN_GRAINS] grains, in turn.
 */
object StressScene : Scene {
    override val name = "Stress"
    override val blurb = "1 000 crates, 5 000 grains, 50 blobs. a bar a step"

    /** How many steps the graph shows, and its corner, size and scale in world units: [UNITS_PER_MS] high a millisecond. */
    const val HISTORY = 120
    const val GRAPH_LEFT = 40f
    const val GRAPH_BOTTOM = 130f
    const val GRAPH_WIDTH = 240f
    const val UNITS_PER_MS = 3f
    private const val LABEL_GAP = 30f

    /** What a right click drops, in turn. */
    const val SPAWN_BOXES = 25
    const val SPAWN_GRAINS = 250

    /** A whole frame at 60 FPS, in milliseconds. */
    const val FRAME_MS = 16.7f

    private val BOX_COLORS = listOf(Color(0xF2994AFFu), Color(0xE8C887FFu), Color(0xD9A75FFFu))
    private val SAND = Color(0xE8C887FFu)
    private val SPAWNED_SAND = Color(0xF27BB6FFu)
    private val BLOB = Color(0x8FA8C8FFu)
    private val BAR = Color(0xB8E05CFFu)
    private val SLOW_BAR = Color(0xF2994AFFu)
    private val TARGET = Color(0xE8ECF4FFu)
    private val FRAME = Color(0xF25C5CFFu)
    private val TEXT = Color(0xE8ECF4FFu)

    override fun create(): SceneRun = Run()

    internal class Run : SceneRun {
        val stress = StressWorld()
        override val world: PhysicsWorld = stress.world

        /** The last [HISTORY] steps' times in milliseconds, oldest first from [next]. */
        val millis = FloatArray(HISTORY)
        private var next = 0
        private var spawns = 0
        private val grains = world.particles.count
        private val average = StringBuilder()

        override fun step(dt: Float) {
            val start = TimeSource.Monotonic.markNow()
            world.step(dt)
            millis[next] = start.elapsedNow().inWholeNanoseconds / 1e6f
            next = (next + 1) % HISTORY
        }

        override fun spawn(x: Float, y: Float) {
            if (spawns >= Scenes.MAX_SPAWNED || !Scenes.isOpen(world, x, y)) return
            when (spawns++ % 3) {
                0 -> repeat(SPAWN_BOXES) { i ->
                    val step = StressWorld.BOX + StressWorld.BOX_GAP
                    world.rigidBody(Shape.box(StressWorld.BOX, StressWorld.BOX), x + (i % 5 - 2) * step, y - (i / 5) * step)
                }
                1 -> world.softBody(StressWorld.BLOB, x, y + StressWorld.BLOB_RADIUS)
                else -> repeat(SPAWN_GRAINS) { i ->
                    val gap = 2f * StressWorld.GRAIN_RADIUS + 0.1f
                    val row = i / 25
                    world.particles.add(x + (i % 25 - 12) * gap + (row % 2) * gap / 2f, y - row * gap * 0.87f, radius = StressWorld.GRAIN_RADIUS, collides = true)
                }
            }
        }

        override fun draw(canvas: Canvas, debug: DebugDraw, view: Rect) {
            debug.solids(canvas, world.solids!!, view)
            val bodies = world.rigidBodies
            for (b in 0 until bodies.end) if (bodies.isAlive(b)) debug.rigidBody(canvas, world, b, BOX_COLORS[b % BOX_COLORS.size])
            debug.particles(canvas, world.particles) { p -> if (p < grains) SAND else SPAWNED_SAND }
            for (s in 0 until world.softBodies.end) if (world.softBodies.isAlive(s)) debug.softBody(canvas, world, s, BLOB)
            drawGraph(canvas, debug)
        }

        /** The last [HISTORY] steps' times as bars, with lines at the target and at a whole frame. */
        private fun drawGraph(canvas: Canvas, debug: DebugDraw) {
            val layer = debug.layer + 4
            val barWidth = GRAPH_WIDTH / HISTORY
            for (i in 0 until HISTORY) {
                val ms = millis[(next + i) % HISTORY]
                if (ms == 0f) continue
                val x = GRAPH_LEFT + (i + 0.5f) * barWidth
                val height = minOf(ms, StressWorld.WORLD_TARGET_MILLIS * 1.2f) * UNITS_PER_MS
                debug.line(canvas, x, GRAPH_BOTTOM, x, GRAPH_BOTTOM - height, barWidth * 0.8f, if (ms > FRAME_MS) SLOW_BAR else BAR, layer)
            }
            val right = GRAPH_LEFT + GRAPH_WIDTH
            val target = GRAPH_BOTTOM - StressWorld.WORLD_TARGET_MILLIS * UNITS_PER_MS
            val frame = GRAPH_BOTTOM - FRAME_MS * UNITS_PER_MS
            debug.line(canvas, GRAPH_LEFT, GRAPH_BOTTOM, right, GRAPH_BOTTOM, 0.5f, TARGET, layer + 1)
            debug.line(canvas, GRAPH_LEFT, target, right, target, 0.5f, TARGET, layer + 1)
            debug.line(canvas, GRAPH_LEFT, frame, right, frame, 0.5f, FRAME, layer + 1)
            debug.label(canvas, "target", right + LABEL_GAP, target, TEXT)
            debug.label(canvas, "60 fps", right + LABEL_GAP, frame, FRAME)
            var sum = 0f
            var timed = 0
            for (ms in millis) if (ms > 0f) {
                sum += ms
                timed++
            }
            if (timed == 0) return
            average.clear()
            average.append("average ").appendFixed(sum / timed, 1).append(" ms a step")
            debug.label(canvas, average, GRAPH_LEFT + GRAPH_WIDTH / 2f, target - 10f, TEXT)
        }

        override val particleCount get() = world.particles.count
        override val bodyCount get() = world.rigidBodies.count + world.softBodies.count
        override val contactCount get() = world.rigidContacts.count
    }
}
