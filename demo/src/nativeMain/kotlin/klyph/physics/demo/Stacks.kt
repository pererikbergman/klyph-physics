package klyph.physics.demo

import klyph.math.Rect
import klyph.physics.Joints
import klyph.physics.Material
import klyph.physics.PhysicsWorld
import klyph.physics.Shape
import klyph.physics.bridge.DebugDraw
import klyph.platform.Color
import klyph.render.Canvas
import kotlin.math.cos
import kotlin.math.sin

/**
 * Rigid bodies resting on each other. On the left a tower of ten boxes, in the middle a pyramid of
 * eight rows, on the right a row of dominoes; after a second the first domino is pushed over, and
 * each knocks the next. Nothing here is held up but contacts: two points under each box keep it
 * flat, and friction keeps it from sliding off the one below. Then a wrecking ball, held up and out
 * on its rod, is let go: it swings down into the tower and knocks the top of it off. A right click
 * drops a box on them.
 * With `F1` on to the last level, every contact shows: green where friction holds, orange where it
 * slides.
 */
object StacksScene : Scene {
    override val name = "Stacks"
    override val blurb = "a tower, a pyramid, dominoes, a wrecking ball: held up by contacts alone"

    /** The tower: how many boxes, how big, and where. */
    const val TOWER = 10
    const val TOWER_BOX = 20f
    const val TOWER_X = 70f

    /** The pyramid: how many rows, how big a box, and where its middle is. */
    const val ROWS = 8
    const val PYRAMID_BOX = 18f
    const val PYRAMID_X = 225f

    /** The dominoes: how many, how thick and tall, the first one's x and how far apart. */
    const val DOMINOES = 10
    const val DOMINO_WIDTH = 6f
    const val DOMINO_HEIGHT = 36f
    const val FIRST_DOMINO = 370f
    const val DOMINO_GAP = 24f

    /** When the first domino is pushed, in seconds, and how: a turn about its foot. */
    const val PUSH_AT = 1f
    const val PUSH_SPIN = 2.5f

    /**
     * The wrecking ball: the point its rod hangs from, straight above the tower, how long the rod is,
     * how far out it's held (in radians from straight down, clear of the pyramid), how big and how
     * heavy for its size (five times a box), and when it's let go.
     */
    const val PIVOT_Y = 40f
    const val ROD = 150f
    const val HELD_OUT = 1.3f
    const val BALL = 16f
    const val BALL_DENSITY = 0.25f
    const val RELEASE_AT = 2.5f

    private val TOWER_COLOR = Color(0xF2994AFFu)
    private val PYRAMID_COLOR = Color(0x8FA8C8FFu)
    private val DOMINO_COLOR = Color(0xE8ECF4FFu)
    private val DROPPED = Color(0xB8E05CFFu)
    private val BALL_COLOR = Color(0x3A3F4BFFu)
    private val ROD_COLOR = Color(0x8FA8C8FFu)

    override fun create(): SceneRun = Run()

    internal class Run : SceneRun {
        override val world = PhysicsWorld().apply { solids = Scenes.room() }
        private val bodies = world.rigidBodies
        private val floor = (Scenes.ROWS - 2) * Scenes.TILE
        private var time = 0f
        private var pushed = false

        val tower = IntArray(TOWER) { world.rigidBody(Shape.box(TOWER_BOX, TOWER_BOX), TOWER_X, floor - TOWER_BOX / 2f - it * TOWER_BOX) }
        val pyramid: IntArray = run {
            val all = ArrayList<Int>()
            for (row in 0 until ROWS) for (k in 0 until ROWS - row) {
                val x = PYRAMID_X - (ROWS - row - 1) * PYRAMID_BOX / 2f + k * PYRAMID_BOX
                all += world.rigidBody(Shape.box(PYRAMID_BOX, PYRAMID_BOX), x, floor - PYRAMID_BOX / 2f - row * PYRAMID_BOX)
            }
            all.toIntArray()
        }
        val dominoes = IntArray(DOMINOES) {
            world.rigidBody(Shape.box(DOMINO_WIDTH, DOMINO_HEIGHT), FIRST_DOMINO + it * DOMINO_GAP, floor - DOMINO_HEIGHT / 2f, material = Material.RUBBER)
        }
        private val dropped = ArrayList<Int>()

        /** The wrecking ball, on a rod from straight above the tower, held out by a weld until it's let go. */
        val ball = world.rigidBody(
            Shape.circle(BALL),
            TOWER_X + sin(HELD_OUT) * ROD,
            PIVOT_Y + cos(HELD_OUT) * ROD,
            density = BALL_DENSITY,
        )
        val rod = world.joints.distance(ball, Joints.WORLD, bodies.x(ball), bodies.y(ball), TOWER_X, PIVOT_Y)
        private val hold = world.joints.weld(ball, Joints.WORLD, bodies.x(ball), bodies.y(ball))
        val released get() = !world.joints.isAlive(hold)

        override fun step(dt: Float) {
            time += dt
            if (!pushed && time >= PUSH_AT) {
                pushed = true
                // a turn about its foot: its middle moves with the spin times half its height
                val d = dominoes[0]
                bodies.setAngularVelocity(d, PUSH_SPIN)
                bodies.setVelocity(d, PUSH_SPIN * DOMINO_HEIGHT / 2f, 0f)
            }
            if (!released && time >= RELEASE_AT) world.joints.remove(hold)
            world.step(dt)
        }

        override fun spawn(x: Float, y: Float) {
            if (dropped.size >= Scenes.MAX_SPAWNED || !Scenes.isOpen(world, x, y)) return
            dropped += world.rigidBody(Shape.box(16f, 16f), x, y, angle = dropped.size * 0.7f)
        }

        override fun draw(canvas: Canvas, debug: DebugDraw, view: Rect) {
            debug.solids(canvas, world.solids!!, view)
            for (b in tower) debug.rigidBody(canvas, world, b, TOWER_COLOR)
            for (b in pyramid) debug.rigidBody(canvas, world, b, PYRAMID_COLOR)
            for (b in dominoes) debug.rigidBody(canvas, world, b, DOMINO_COLOR)
            for (b in dropped) debug.rigidBody(canvas, world, b, DROPPED)
            debug.line(canvas, TOWER_X, PIVOT_Y, bodies.x(ball), bodies.y(ball), 2f, ROD_COLOR, debug.layer + 1)
            debug.rigidBody(canvas, world, ball, BALL_COLOR)
        }

        override val bodyCount get() = bodies.count
        override val contactCount get() = world.rigidContacts.count
    }
}
