package klyph.physics.demo

import klyph.math.Rect
import klyph.physics.Material
import klyph.physics.PhysicsWorld
import klyph.physics.Shape
import klyph.physics.bridge.DebugDraw
import klyph.platform.Color
import klyph.render.Canvas

/**
 * Rigid bodies resting on each other. On the left a tower of ten boxes, in the middle a pyramid of
 * eight rows, on the right a row of dominoes; after a second the first domino is pushed over, and
 * each knocks the next. Nothing here is held up but contacts: two points under each box keep it
 * flat, and friction keeps it from sliding off the one below. A right click drops a box on them.
 * With `F1` on to the last level, every contact shows: green where friction holds, orange where it
 * slides.
 */
object StacksScene : Scene {
    override val name = "Stacks"
    override val blurb = "a tower, a pyramid, dominoes: held up by contacts alone"

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

    private val TOWER_COLOR = Color(0xF2994AFFu)
    private val PYRAMID_COLOR = Color(0x8FA8C8FFu)
    private val DOMINO_COLOR = Color(0xE8ECF4FFu)
    private val DROPPED = Color(0xB8E05CFFu)

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

        override fun step(dt: Float) {
            time += dt
            if (!pushed && time >= PUSH_AT) {
                pushed = true
                // a turn about its foot: its middle moves with the spin times half its height
                val d = dominoes[0]
                bodies.setAngularVelocity(d, PUSH_SPIN)
                bodies.setVelocity(d, PUSH_SPIN * DOMINO_HEIGHT / 2f, 0f)
            }
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
        }

        override val bodyCount get() = bodies.count
        override val contactCount get() = world.rigidContacts.count
    }
}
