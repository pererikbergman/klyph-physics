package klyph.physics.demo

import klyph.math.Rect
import klyph.physics.BodyKind
import klyph.physics.Manifold
import klyph.physics.Narrowphase
import klyph.physics.PhysicsWorld
import klyph.physics.Shape
import klyph.physics.bridge.DebugDraw
import klyph.platform.Color
import klyph.render.Canvas
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Rigid bodies, their shapes and how they turn. Five shapes, a circle, a box, a triangle, a capsule and
 * a hammer made of two boxes, are tossed up again and again with the same speed and the same twist.
 * They fly the same arc, because spin doesn't change a fall, but they spin at different speeds: the
 * same twist turns a shape that's hard to turn, one with its mass far from its middle, more slowly.
 * The hammer turns about its centre of mass, the dot near its head. On the right, a kinematic paddle
 * goes round a circle, exactly where it's told, through a static bar that never moves. With `F1` on,
 * the points where the two overlap show, as the narrowphase finds them: two where they lie flat on
 * each other, one where a corner pokes in.
 */
object TumbleScene : Scene {
    override val name = "Tumble"
    override val blurb = "the same twist, five shapes: the hard to turn spin slowest"

    /** How fast a shape is tossed up, in units a second: it rises 187 units, and is back in a second. */
    const val TOSS = 750f

    /** The twist every shape gets each toss, an angular impulse: its spin is this over its inertia. */
    const val TWIST = 16_000f

    /** Where the shapes are tossed from, and how far apart. */
    const val TOSS_Y = 290f
    const val FIRST_X = 70f
    const val GAP = 80f

    /** The paddle's circle, and how long a lap takes, in seconds. */
    const val ORBIT_X = 540f
    const val ORBIT_Y = 220f
    const val ORBIT_RADIUS = 60f
    const val LAP = 4f

    /** What's tossed, in order: a circle, a box, a triangle, a capsule, and a hammer of two boxes. */
    val SHAPES: List<List<Shape>> = listOf(
        listOf(Shape.circle(12f)),
        listOf(Shape.box(24f, 24f)),
        listOf(Shape.polygon(-14f, 10f, 0f, -14f, 14f, 10f)),
        listOf(Shape.capsule(26f, 7f)),
        listOf(Shape.box(6f, 40f, y = 8f), Shape.box(28f, 10f, y = -14f)),
    )

    private val COLORS = listOf(Color(0x8FA8C8FFu), Color(0xF2994AFFu), Color(0xF27BB6FFu), Color(0xB8E05CFFu), Color(0xC9A26BFFu))
    private val PADDLE = Color(0x9FE3F5FFu)
    private val STATIC = Color(0x7A8AA6FFu)
    private val MARK = Color(0xFFFFFFFFu)

    override fun create(): SceneRun = Run()

    internal class Run : SceneRun {
        override val world = PhysicsWorld().apply { solids = Scenes.room() }
        private val bodies = world.rigidBodies

        /** Each tossed body, its spot to toss it from again when it comes back down, and its colour; right clicks add more. */
        private val all = ArrayList<Int>()
        private val spotX = ArrayList<Float>()
        private val spotY = ArrayList<Float>()
        private val colors = ArrayList<Color>()
        private var time = 0f

        val tossed = IntArray(SHAPES.size) { i -> add(i, FIRST_X + i * GAP, TOSS_Y) }
        val paddle = world.rigidBody(Shape.box(60f, 10f), ORBIT_X + ORBIT_RADIUS, ORBIT_Y, kind = BodyKind.KINEMATIC)
        val bar = world.rigidBody(Shape.box(100f, 12f), ORBIT_X, ORBIT_Y + ORBIT_RADIUS + 8f, kind = BodyKind.STATIC)
        private val narrow = Narrowphase()
        private val touch = Manifold()

        /** Adds shape [i] of [SHAPES] at ([x], [y]) and tosses it. */
        private fun add(i: Int, x: Float, y: Float): Int {
            val b = world.rigidBody(SHAPES[i], x, y)
            all += b
            spotX += x
            spotY += y
            colors += COLORS[i]
            toss(b, x, y)
            return b
        }

        /** Puts [b] at ([x], [y]), level, and tosses it up with the same speed and twist as every other. */
        private fun toss(b: Int, x: Float, y: Float) {
            bodies.moveTo(b, x, y, 0f)
            bodies.setVelocity(b, 0f, -TOSS)
            bodies.setAngularVelocity(b, TWIST * bodies.inverseInertia(b))
        }

        override fun step(dt: Float) {
            // Nothing collides yet, so each shape is caught where it was tossed from and tossed again.
            for ((k, b) in all.withIndex()) {
                if (world.grabbedBody != b && bodies.velocityY(b) > 0f && bodies.y(b) >= spotY[k]) toss(b, spotX[k], spotY[k])
            }
            time += dt
            val turn = 2f * PI.toFloat() * time / LAP
            bodies.moveKinematic(paddle, ORBIT_X + ORBIT_RADIUS * cos(turn), ORBIT_Y + ORBIT_RADIUS * sin(turn), angle = 2f * turn)
            world.step(dt)
        }

        override fun spawn(x: Float, y: Float) {
            val spawned = all.size - SHAPES.size
            if (spawned >= Scenes.MAX_SPAWNED || !Scenes.isOpen(world, x, y)) return
            add(spawned % SHAPES.size, x, y)
        }

        override fun draw(canvas: Canvas, debug: DebugDraw, view: Rect) {
            debug.solids(canvas, world.solids!!, view)
            for ((k, b) in all.withIndex()) {
                debug.rigidBody(canvas, world, b, colors[k])
                debug.square(canvas, bodies.x(b), bodies.y(b), 2f, MARK)
            }
            debug.rigidBody(canvas, world, paddle, PADDLE)
            debug.rigidBody(canvas, world, bar, STATIC)
            if (debug.showDetails) {
                // nothing pushes them apart yet: this is only where they overlap
                val p = paddle
                val q = bar
                val found = narrow.collide(
                    bodies.shape(p, 0), bodies.x(p), bodies.y(p), bodies.angle(p),
                    bodies.shape(q, 0), bodies.x(q), bodies.y(q), bodies.angle(q), touch,
                )
                if (found) debug.manifold(canvas, touch)
            }
        }

        override val bodyCount get() = bodies.count
    }
}
