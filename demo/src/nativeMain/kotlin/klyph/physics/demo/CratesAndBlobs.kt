package klyph.physics.demo

import klyph.math.Rect
import klyph.physics.PhysicsWorld
import klyph.physics.Shape
import klyph.physics.SoftBodies
import klyph.physics.Strand
import klyph.physics.bridge.DebugDraw
import klyph.physics.rope
import klyph.platform.Color
import klyph.render.Canvas
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Soft meets rigid. On the left, a crate rests on a big blob and squashes it a little, and the blob
 * holds it up. In the middle, after a second, a blob is thrown at a tower of crates and knocks it
 * down. On the right, a heavy crate tied to a rope swings down through three blobs and scatters them. Each
 * side moves the other: a crate is pushed back by what it hits, and turned when it's hit off its
 * middle. A right click drops another blob.
 */
object CratesAndBlobsScene : Scene {
    override val name = "Crates and Blobs"
    override val blurb = "soft meets rigid: crates squash blobs, blobs knock crates over"

    /** The big blob a crate sits on, and the crate. */
    const val CUSHION_X = 56f
    const val CUSHION_RADIUS = 26f
    const val CRATE = 28f

    /** The tower: how many crates, how big, and where. */
    const val TOWER = 4
    const val TOWER_CRATE = 20f
    const val TOWER_X = 210f

    /** The blob thrown at the tower: from where, when, and how fast. */
    const val THROW_X = 120f
    const val THROW_AT = 1f
    const val THROW_SPEED_X = 650f
    const val THROW_SPEED_Y = -200f

    /** The swinging crate: where its rope hangs from, how long it is, and how far it starts pulled back: 50°, so it clears the tower. */
    const val PIVOT_X = 500f
    const val PIVOT_Y = 20f
    const val ROPE = 280f
    const val ROPE_SEGMENTS = 14
    const val PULLED_BACK = (PI * 5 / 18).toFloat()
    const val SWINGER = 30f

    /** How heavy it is for its size: five times a crate, a wrecking ball of a crate. */
    const val SWINGER_DENSITY = 0.25f

    /** The three blobs it swings through, on the floor below its pivot. */
    val TARGETS = floatArrayOf(490f, 522f, 554f)

    private val CRATE_COLOR = Color(0xC9A26BFFu)
    private val BLOB = Color(0x6BE07AFFu)
    private val THROWN = Color(0xF27BB6FFu)
    private val ROPE_DOT = Color(0xC9A26BFFu)
    private val ANCHOR = Color(0x3A4A66FFu)

    override fun create(): SceneRun = Run()

    internal class Run : SceneRun, BlobRun {
        override val world = PhysicsWorld().apply { solids = Scenes.room() }
        private val bodies = world.rigidBodies
        private val floor = (Scenes.ROWS - 2) * Scenes.TILE
        private var time = 0f
        private var thrown = false

        val cushion = world.softBody(SoftBodies.semiDisc(CUSHION_RADIUS, arcSegments = 22, baseSegments = 12, coreMass = 34f), CUSHION_X, floor)
        val crate = world.rigidBody(Shape.box(CRATE, CRATE), CUSHION_X, floor - CUSHION_RADIUS * 0.9f - CRATE / 2f - 2f)

        val tower = IntArray(TOWER) { world.rigidBody(Shape.box(TOWER_CRATE, TOWER_CRATE), TOWER_X, floor - TOWER_CRATE / 2f - it * TOWER_CRATE) }

        /** Waits on the floor, then is thrown at the tower. */
        val ball = world.softBody(Scenes.dome(), THROW_X, floor)

        /** The rope, its far end tied to the top of [swinger], which starts pulled back and up. */
        val rope: Strand
        val swinger: Int
        val targets = IntArray(TARGETS.size) { world.softBody(Scenes.dome(), TARGETS[it], floor) }

        init {
            // along the rope pulled back by [PULLED_BACK] from straight down
            val dx = -sin(PULLED_BACK)
            val dy = cos(PULLED_BACK)
            val endX = PIVOT_X + dx * ROPE
            val endY = PIVOT_Y + dy * ROPE
            rope = world.rope(PIVOT_X, PIVOT_Y, endX, endY, segments = ROPE_SEGMENTS)
            // the crate hangs on along the rope, turned so its top faces the pivot
            swinger = world.rigidBody(Shape.box(SWINGER, SWINGER), endX + dx * SWINGER / 2f, endY + dy * SWINGER / 2f, angle = PULLED_BACK, density = SWINGER_DENSITY)
            world.attach(rope.last, swinger)
        }

        override val blobs: IntArray get() = intArrayOf(cushion, ball) + targets + spawned

        /** Blobs dropped with a right click. */
        private val spawned = ArrayList<Int>()

        override fun step(dt: Float) {
            time += dt
            if (!thrown && time >= THROW_AT) {
                thrown = true
                val soft = world.softBodies
                for (i in 0..soft.ringCount(ball)) world.setVelocity(soft.particle(ball, i), THROW_SPEED_X, THROW_SPEED_Y)
            }
            world.step(dt)
        }

        override fun spawn(x: Float, y: Float) {
            if (spawned.size >= Scenes.MAX_SPAWNED || !Scenes.isOpen(world, x, y)) return
            spawned += world.softBody(Scenes.dome(), x, y + Scenes.DOME_HALF_HEIGHT)
        }

        override fun draw(canvas: Canvas, debug: DebugDraw, view: Rect) {
            val ps = world.particles
            debug.solids(canvas, world.solids!!, view)
            debug.links(canvas, world.constraints, ps)
            for (p in rope.particles) {
                val pinned = ps.inverseMass(p) == 0f
                debug.square(canvas, ps.x(p), ps.y(p), if (pinned) 6f else 2f, if (pinned) ANCHOR else ROPE_DOT)
            }
            debug.softBody(canvas, world, cushion, BLOB)
            for (t in targets) debug.softBody(canvas, world, t, BLOB)
            for (s in spawned) debug.softBody(canvas, world, s, BLOB)
            debug.softBody(canvas, world, ball, THROWN)
            debug.rigidBody(canvas, world, crate, CRATE_COLOR)
            for (b in tower) debug.rigidBody(canvas, world, b, CRATE_COLOR)
            debug.rigidBody(canvas, world, swinger, CRATE_COLOR)
        }

        override val particleCount get() = world.particles.count
        override val bodyCount get() = world.softBodies.count + bodies.count
        override val contactCount get() = world.rigidContacts.count
    }
}
