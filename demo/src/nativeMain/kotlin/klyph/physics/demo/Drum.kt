package klyph.physics.demo

import klyph.math.Rect
import klyph.physics.BodyKind
import klyph.physics.Material
import klyph.physics.PhysicsWorld
import klyph.physics.Shape
import klyph.physics.bridge.DebugDraw
import klyph.platform.Color
import klyph.render.Canvas
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Everything in one turning drum: blobs, crates and a heap of sand. The drum is one kinematic body,
 * a ring of boxes with three paddles inside, turned at a steady speed, so nothing pushes it back.
 * It carries what's in it up the side until it slides, tumbles or is dropped off a paddle, and the
 * three kinds of thing push on each other on the way down. A right click drops another crate in.
 */
object DrumScene : Scene {
    override val name = "Drum"
    override val blurb = "blobs, crates and sand tumbling in a turning drum"

    /** The drum: its middle, its inside's radius, how thick its wall is, and how fast it turns, radians a second. */
    const val CENTER_X = 320f
    const val CENTER_Y = 180f
    const val RADIUS = 140f
    const val WALL = 10f
    const val SPEED = 0.8f

    /** How many boxes its ring is made of, and its paddles: how many, and how far in they reach. */
    const val SEGMENTS = 32
    const val PADDLES = 3
    const val PADDLE = 30f

    /** What it holds, to start: blobs, crates and grains of sand. */
    const val BLOBS = 4
    const val CRATES = 5
    const val CRATE = 18f
    const val GRAINS = 240
    const val GRAIN_RADIUS = 2f

    private val DRUM = Color(0x6B7A99FFu)
    private val BLOB = Color(0x6BE07AFFu)
    private val CRATE_COLOR = Color(0xC9A26BFFu)
    private val SAND = Color(0xE8C872FFu)

    override fun create(): SceneRun = Run()

    internal class Run : SceneRun, BlobRun {
        override val world = PhysicsWorld().apply { solids = Scenes.room() }
        private val bodies = world.rigidBodies

        /** The drum: its ring, a little more than closed so its boxes overlap, and its paddles, all one body. */
        val drum: Int = run {
            val shapes = ArrayList<Shape>()
            val step = 2f * PI.toFloat() / SEGMENTS
            val length = (RADIUS + WALL) * step * 1.1f
            for (i in 0 until SEGMENTS) {
                val a = i * step
                val r = RADIUS + WALL / 2f
                shapes += Shape.box(length, WALL, cos(a) * r, sin(a) * r, a + PI.toFloat() / 2f)
            }
            for (i in 0 until PADDLES) {
                val a = i * 2f * PI.toFloat() / PADDLES
                val r = RADIUS - PADDLE / 2f
                shapes += Shape.box(PADDLE, 8f, cos(a) * r, sin(a) * r, a)
            }
            world.rigidBody(shapes, CENTER_X, CENTER_Y, kind = BodyKind.KINEMATIC).also { bodies.setAngularVelocity(it, SPEED) }
        }

        /** Laid out in the drum's bottom half: blobs along the floor, crates above them, the sand on top. */
        override val blobs = IntArray(BLOBS) { world.softBody(Scenes.dome(), CENTER_X + (it - 1.5f) * 50f, CENTER_Y + 90f) }
        val crates = arrayListOf<Int>().apply {
            for (i in 0 until CRATES) add(world.rigidBody(Shape.box(CRATE, CRATE), CENTER_X + (i - 2f) * 34f, CENTER_Y + 40f, angle = i * 0.4f))
        }
        val grains = IntArray(GRAINS) {
            val gap = 2f * GRAIN_RADIUS + 0.5f
            val perRow = 40
            world.particles.add(
                CENTER_X + (it % perRow - perRow / 2f) * gap + (it / perRow % 2) * gap / 2f,
                CENTER_Y - (it / perRow) * gap,
                radius = GRAIN_RADIUS,
                collides = true,
                material = Material(friction = 0.6f, staticFriction = 0.8f),
            )
        }

        override fun step(dt: Float) = world.step(dt)

        override fun spawn(x: Float, y: Float) {
            if (crates.size >= CRATES + Scenes.MAX_SPAWNED || !inside(x, y, CRATE)) return
            crates += world.rigidBody(Shape.box(CRATE, CRATE), x, y)
        }

        /** Whether ([x], [y]) is inside the drum, [margin] from its wall. */
        fun inside(x: Float, y: Float, margin: Float = 0f): Boolean {
            val dx = x - CENTER_X
            val dy = y - CENTER_Y
            return dx * dx + dy * dy < (RADIUS - margin) * (RADIUS - margin)
        }

        override fun draw(canvas: Canvas, debug: DebugDraw, view: Rect) {
            debug.solids(canvas, world.solids!!, view)
            val ps = world.particles
            for (g in grains) debug.square(canvas, ps.x(g), ps.y(g), 2f * GRAIN_RADIUS, SAND)
            for (b in blobs) debug.softBody(canvas, world, b, BLOB)
            for (c in crates) debug.rigidBody(canvas, world, c, CRATE_COLOR)
            debug.rigidBody(canvas, world, drum, DRUM)
        }

        override val particleCount get() = world.particles.count
        override val bodyCount get() = world.softBodies.count + bodies.count
        override val contactCount get() = world.rigidContacts.count
    }
}
