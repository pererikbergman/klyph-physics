package klyph.physics.demo

import klyph.math.Rect
import klyph.physics.Material
import klyph.physics.PhysicsWorld
import klyph.physics.SoftBodies
import klyph.physics.SoftBodyTuning
import klyph.physics.SolidShape
import klyph.physics.bridge.DebugDraw
import klyph.platform.Color
import klyph.render.Canvas
import klyph.render.DrawSpace
import kotlin.math.PI
import kotlin.math.sqrt

/**
 * Friction and bounce, side by side. On the left, three boxes are set down at rest on three 45° ramps:
 * ice on ice slides off at once, the default material on itself slides slowly, and rubbery grip holds.
 * On the right, three balls fall from the same height and bounce back as high as their restitution says:
 * 0, 0.5 and 0.9. Every few seconds everything goes back to where it started.
 */
object GripAndBounceScene : Scene {
    override val name = "Grip and Bounce"
    override val blurb = "ice slides, grip holds. balls bounce with 0, 0.5 and 0.9"

    /** Steps between putting everything back. */
    const val EVERY = 300

    /** Each ramp and its box share a material: how it grips is down to the pair. */
    val GRIP = Material(friction = 1.5f, staticFriction = 2f)
    val MATERIALS = listOf(Material.ICE, Material.DEFAULT, GRIP)
    val RESTITUTIONS = floatArrayOf(0f, 0.5f, 0.9f)

    /** The ramps' first column, and the row their tops are on: five tiles down to the floor at row 21. */
    private const val FIRST_COL = 2
    private const val RAMP_GAP = 7
    private const val TOP_ROW = 16
    private const val RAMP_TILES = 5
    private const val BALL_RADIUS = 7f
    private const val DROP_ROW = 3

    private val BOX_COLORS = listOf(Color(0x9FE3F5FFu), Color(0xF2994AFFu), Color(0xF27BB6FFu))
    private val BALL_COLORS = listOf(Color(0x8FA8C8FFu), Color(0xB8E05CFFu), Color(0xF2D45CFFu))

    override fun create(): SceneRun = Run()

    internal class Run : SceneRun, BlobRun {
        private val t = Scenes.TILE
        override val world = PhysicsWorld().apply {
            solids = Scenes.room().apply {
                for (w in 0 until 3) {
                    val c0 = FIRST_COL + w * RAMP_GAP
                    for (k in 0 until RAMP_TILES) {
                        fill(c0 + k, TOP_ROW + k, SolidShape.FALLING, material = MATERIALS[w])
                        for (r in TOP_ROW + k + 1 until Scenes.ROWS - 2) fill(c0 + k, r, material = MATERIALS[w])
                    }
                }
            }
        }
        private val bodies = world.softBodies
        private val ps = world.particles

        /** Stiff: a soft body with a heavy core sags downhill until it's round, and rolls. */
        private val stiff = SoftBodyTuning(edgeCompliance = 0f, spokeCompliance = 0f, areaCompliance = 0f)
        private val shape = SoftBodies.box(28f, 16f, across = 7, down = 4, coreMass = 20f)

        override val blobs = IntArray(3) { w -> setDown(bodies.add(shape, 0f, 0f, stiff, material = MATERIALS[w]), w) }
        val balls = IntArray(3) { i ->
            ps.add((26 + i * 5) * t, DROP_ROW * t, radius = BALL_RADIUS, collides = true, material = Material(restitution = RESTITUTIONS[i]))
        }

        /** Balls added with a right click, each bouncing like the next of the three in turn. */
        private val spawned = ArrayList<Int>()

        /** Where every particle starts, to go back to. */
        private val homeX = FloatArray(ps.end) { ps.x(it) }
        private val homeY = FloatArray(ps.end) { ps.y(it) }
        private var steps = 0

        /** Turns [box] to lie along ramp [w] and sets it down at rest, half a unit off it, a third of the way down. */
        private fun setDown(box: Int, w: Int): Int {
            val c0 = FIRST_COL + w * RAMP_GAP
            bodies.placeAt(box, (c0 + 1.7f) * t, (TOP_ROW + 0.5f) * t)
            bodies.rotate(box, PI.toFloat() / 4f)
            // The ramp is the line y = x + (TOP_ROW - c0) * t; its normal, out of it, is (s, -s).
            val s = sqrt(0.5f)
            val offset = (TOP_ROW - c0) * t
            var lowest = Float.MAX_VALUE
            for (i in 0 until bodies.ringCount(box)) {
                val p = bodies.particle(box, i)
                lowest = minOf(lowest, (ps.x(p) - ps.y(p) + offset) * s)
            }
            val by = 0.5f - lowest
            for (i in 0..bodies.ringCount(box)) {
                val p = bodies.particle(box, i)
                ps.moveTo(p, ps.x(p) + by * s, ps.y(p) - by * s)
            }
            return box
        }

        override fun step(dt: Float) {
            if (steps > 0 && steps % EVERY == 0) {
                for (p in homeX.indices) {
                    ps.moveTo(p, homeX[p], homeY[p])
                    world.setVelocity(p, 0f, 0f)
                }
            }
            steps++
            world.step(dt)
        }

        override fun spawn(x: Float, y: Float) {
            if (spawned.size >= Scenes.MAX_SPAWNED || !Scenes.isOpen(world, x, y)) return
            val i = spawned.size % 3
            spawned += ps.add(x, y, radius = BALL_RADIUS, collides = true, material = Material(restitution = RESTITUTIONS[i]))
        }

        override fun draw(canvas: Canvas, debug: DebugDraw, view: Rect) {
            debug.solids(canvas, world.solids!!, view)
            for (w in 0 until 3) debug.softBody(canvas, world, blobs[w], BOX_COLORS[w])
            for (i in 0 until 3) disc(canvas, ps.x(balls[i]), ps.y(balls[i]), BALL_RADIUS, BALL_COLORS[i], debug.layer)
            for ((i, ball) in spawned.withIndex()) disc(canvas, ps.x(ball), ps.y(ball), BALL_RADIUS, BALL_COLORS[i % 3], debug.layer)
            debug.contacts(canvas, world)
        }

        /** A filled circle, one unit-tall strip at a time. */
        private fun disc(canvas: Canvas, x: Float, y: Float, r: Float, color: Color, layer: Int) {
            var dy = -r + 0.5f
            while (dy < r) {
                val half = sqrt(r * r - dy * dy)
                canvas.drawRect(x - half, y + dy - 0.5f, 2f * half, 1f, color, layer, DrawSpace.WORLD)
                dy += 1f
            }
        }

        override val particleCount get() = ps.count
        override val bodyCount get() = bodies.count
        override val contactCount get() = (0 until ps.end).count { world.events.isOnFloor(it) }
    }
}
