package klyph.physics.demo

import klyph.math.Rect
import klyph.physics.BodyKind
import klyph.physics.PhysicsWorld
import klyph.physics.bridge.DebugDraw
import klyph.platform.Color
import klyph.render.Canvas
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Rigid bodies and rotation. On the left, three bars are tossed up again and again at the same speed,
 * spinning 0, 4 and 10 radians a second: they fly the same arc, because spin doesn't change how a
 * body falls. On the right, a kinematic paddle goes round a circle, turning as it goes, exactly where
 * it's told, and a static bar never moves at all.
 */
object TumbleScene : Scene {
    override val name = "Tumble"
    override val blurb = "bodies that turn. spin doesn't change the fall"

    /** How fast each bar spins, in radians a second, clockwise. */
    val SPINS = floatArrayOf(0f, 4f, 10f)

    /** How fast a bar is tossed up, in units a second: it rises 187 units, and is back in a second. */
    const val TOSS = 750f

    const val BAR_WIDTH = 36f
    const val BAR_HEIGHT = 10f

    /** Where the bars are tossed from, and how far apart. */
    const val TOSS_Y = 300f
    const val FIRST_X = 90f
    const val GAP = 90f

    /** The paddle's circle, and how long a lap takes, in seconds. */
    const val ORBIT_X = 470f
    const val ORBIT_Y = 170f
    const val ORBIT_RADIUS = 90f
    const val LAP = 4f

    private val BAR_COLORS = listOf(Color(0x8FA8C8FFu), Color(0xF2994AFFu), Color(0xF27BB6FFu))
    private val PADDLE = Color(0xB8E05CFFu)
    private val STATIC = Color(0x7A8AA6FFu)
    private val MARK = Color(0xFFFFFFFFu)

    override fun create(): SceneRun = Run()

    internal class Run : SceneRun {
        override val world = PhysicsWorld().apply { solids = Scenes.room() }
        private val bodies = world.rigidBodies

        /** A bar's inertia about its middle: m (w² + h²) / 12, for a mass of 1. */
        private val inertia = (BAR_WIDTH * BAR_WIDTH + BAR_HEIGHT * BAR_HEIGHT) / 12f

        val bars = IntArray(3) { i ->
            bodies.add(FIRST_X + i * GAP, TOSS_Y, inertia = inertia).also { toss(it, i) }
        }

        /** Bars added with a right click, each tossed from where it was clicked, spinning like the next of the three. */
        private val spawned = ArrayList<Int>()
        private val spawnedFrom = ArrayList<Float>()
        val paddle = bodies.add(ORBIT_X + ORBIT_RADIUS, ORBIT_Y, kind = BodyKind.KINEMATIC)
        val wall = bodies.add(ORBIT_X, ORBIT_Y + ORBIT_RADIUS + 50f, angle = 0.3f, kind = BodyKind.STATIC)
        private var time = 0f

        private fun toss(bar: Int, i: Int) {
            bodies.moveTo(bar, FIRST_X + i * GAP, TOSS_Y, 0f)
            bodies.setVelocity(bar, 0f, -TOSS)
            bodies.setAngularVelocity(bar, SPINS[i])
        }

        override fun step(dt: Float) {
            // Nothing collides yet, so each bar is caught where it was tossed from and tossed again.
            for ((i, bar) in bars.withIndex()) if (bodies.velocityY(bar) > 0f && bodies.y(bar) >= TOSS_Y) toss(bar, i)
            for ((i, bar) in spawned.withIndex()) {
                if (bodies.velocityY(bar) <= 0f || bodies.y(bar) < spawnedFrom[i]) continue
                bodies.moveTo(bar, bodies.x(bar), spawnedFrom[i], 0f)
                bodies.setVelocity(bar, 0f, -TOSS / 2f)
            }
            time += dt
            val turn = 2f * PI.toFloat() * time / LAP
            bodies.moveKinematic(paddle, ORBIT_X + ORBIT_RADIUS * cos(turn), ORBIT_Y + ORBIT_RADIUS * sin(turn), angle = 2f * turn)
            world.step(dt)
        }

        override fun spawn(x: Float, y: Float) {
            if (spawned.size >= Scenes.MAX_SPAWNED || !Scenes.isOpen(world, x, y)) return
            val bar = bodies.add(x, y, inertia = inertia)
            bodies.setVelocity(bar, 0f, -TOSS / 2f)
            bodies.setAngularVelocity(bar, SPINS[spawned.size % 3])
            spawned += bar
            spawnedFrom += y
        }

        override fun draw(canvas: Canvas, debug: DebugDraw, view: Rect) {
            debug.solids(canvas, world.solids!!, view)
            for ((i, bar) in bars.withIndex()) body(canvas, debug, bar, BAR_WIDTH, BAR_HEIGHT, BAR_COLORS[i])
            for ((i, bar) in spawned.withIndex()) body(canvas, debug, bar, BAR_WIDTH, BAR_HEIGHT, BAR_COLORS[i % 3])
            body(canvas, debug, paddle, 70f, 12f, PADDLE)
            body(canvas, debug, wall, 120f, 14f, STATIC)
        }

        /** A body as a [width] by [height] bar, with a dot at its centre and a tick on one end so its turning shows. */
        private fun body(canvas: Canvas, debug: DebugDraw, b: Int, width: Float, height: Float, color: Color) {
            val x = bodies.x(b)
            val y = bodies.y(b)
            val angle = bodies.angle(b)
            debug.turnedRect(canvas, x, y, width, height, angle, color)
            val end = width / 2f - 4f
            debug.square(canvas, x + cos(angle) * end, y + sin(angle) * end, 3f, MARK)
            debug.square(canvas, x, y, 2f, MARK)
        }

        override val bodyCount get() = bodies.count
    }
}
