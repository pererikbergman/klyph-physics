package klyph.physics.demo

import klyph.math.Rect
import klyph.physics.BodyKind
import klyph.physics.Joints
import klyph.physics.Material
import klyph.physics.PhysicsWorld
import klyph.physics.Shape
import klyph.physics.bridge.DebugDraw
import klyph.platform.Color
import klyph.render.Canvas
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Things too fast to see, stopped by things too thin to see. On the left a gun fires a bullet every
 * so often at 5 000 units a second, a ball or a little box, at three panes one unit thick hung on
 * hinges, and behind them a plate as thin. A bullet crosses the whole room in four frames, and moves
 * twenty units a substep, twenty times the panes' thickness, yet none goes through: each is swept from
 * where it was to where it went, and stopped where it first touched. On the right, in a bin, a
 * fountain of sparks, bullet particles, shoots up at a ledge half a tile thick, and none comes out on
 * top. A right click fires a bullet from the gun at the mouse.
 */
object BulletsScene : Scene {
    override val name = "Bullets"
    override val blurb = "5 000 units a second at walls one unit thick: nothing goes through"

    /** How fast a bullet is fired, in units a second, how often, and how many there are at most. */
    const val SPEED = 5000f
    const val FIRE_EVERY = 0.35f
    const val MAX_BULLETS = 16

    /** Where the gun is. */
    const val GUN_X = 40f
    const val GUN_Y = 150f

    /** The panes: how many, where the first hangs from, how far apart, how long, and how thick. */
    const val PANES = 3
    const val PANE_X = 200f
    const val PANE_Y = 70f
    const val PANE_GAP = 60f
    const val PANE = 150f
    const val THIN = 1f

    /** How heavy the panes are for their size: a hundred times a bullet, so a hit nudges one, not flings it. */
    const val PANE_DENSITY = 1f

    /** The plate behind the panes, that stops what they don't, and its top. */
    const val PLATE_X = 400f
    const val PLATE_TOP = 30f

    /** The sparks: where they're shot from, how many a step, at most, and how high the ledge over them is. */
    const val SPARKS_X = 530f
    const val SPARKS_Y = 330f
    const val SPARKS_PER_STEP = 2
    const val MAX_SPARKS = 160
    const val LEDGE_ROW = 4
    const val LEDGE_FROM = 27
    const val LEDGE_TO = 39

    /** The column of the fence that closes the sparks' bin on the left, the room's wall on the right. */
    const val FENCE = 26

    private val BULLET = Color(0xFFD36BFFu)
    private val TRACER = Color(1f, 0.83f, 0.42f, 0.45f)
    private val PANE_COLOR = Color(0x9FE3F5FFu)
    private val PLATE = Color(0x7A8AA6FFu)
    private val GUN = Color(0x6B7A99FFu)
    private val SPARK = Color(0xFF9A3DFFu)

    /** Sparks don't bounce: they stop where they hit, and drop. */
    private val SPARK_MATERIAL = Material(friction = 1f, staticFriction = 1f, restitution = 0f)

    override fun create(): SceneRun = Run()

    internal class Run : SceneRun {
        override val world = PhysicsWorld().apply {
            solids = Scenes.room().apply {
                // a ceiling, as a bullet that glances off a pane can go up a long way
                for (c in 0 until Scenes.COLS) fill(c, 0)
                for (c in LEDGE_FROM until LEDGE_TO) fillTopHalf(c, LEDGE_ROW)
                for (r in LEDGE_ROW until Scenes.ROWS - 2) fill(FENCE, r)
            }
        }
        private val bodies = world.rigidBodies

        /** The panes, each on a hinge at its top, so a bullet knocks it swinging. */
        val panes = IntArray(PANES) {
            val x = PANE_X + it * PANE_GAP
            world.rigidBody(Shape.box(THIN, PANE), x, PANE_Y + PANE / 2f, density = PANE_DENSITY).also { b -> world.joints.hinge(b, Joints.WORLD, x, PANE_Y) }
        }
        /** The plate, from high up down to the floor, so nothing gets round it. */
        val plate: Int = run {
            val floor = (Scenes.ROWS - 2) * Scenes.TILE
            world.rigidBody(Shape.box(THIN, floor - PLATE_TOP), PLATE_X, (PLATE_TOP + floor) / 2f, kind = BodyKind.STATIC)
        }

        /** The bullets in flight or fallen, oldest first. */
        val bullets = ArrayList<Int>()

        /** The sparks, oldest first. */
        val sparks = ArrayList<Int>()

        private var time = 0f
        private var fired = 0

        override fun step(dt: Float) {
            time += dt
            if (time >= (fired + 1) * FIRE_EVERY) {
                // up and down a little, shot to shot, so they hit the panes all along
                val aim = sin(fired * 1.7f) * 0.12f
                fire(cos(aim), sin(aim))
            }
            repeat(SPARKS_PER_STEP) { spark() }
            world.step(dt)
        }

        /** Fires a bullet from the gun along ([dx], [dy]), a unit long: a ball, then a box, by turns. */
        private fun fire(dx: Float, dy: Float) {
            val shape = if (fired % 2 == 0) Shape.circle(3f) else Shape.box(6f, 3f)
            val b = world.rigidBody(shape, GUN_X + 16f, GUN_Y, angle = kotlin.math.atan2(dy, dx), material = Material(restitution = 0.3f))
            bodies.setBullet(b, true)
            bodies.setVelocity(b, dx * SPEED, dy * SPEED)
            bullets += b
            fired++
            if (bullets.size > MAX_BULLETS) bodies.remove(bullets.removeAt(0))
        }

        /** Shoots a spark up at the ledge, a little to either side. */
        private fun spark() {
            val ps = world.particles
            val k = sparks.size + world.stepCount.toInt() * SPARKS_PER_STEP
            val spread = sin(k * 2.3f) * 0.1f
            val p = ps.add(SPARKS_X + sin(k * 0.7f) * 15f, SPARKS_Y, radius = 1f, material = SPARK_MATERIAL)
            ps.setBullet(p, true)
            world.setVelocity(p, sin(spread) * SPEED, -cos(spread) * SPEED)
            sparks += p
            if (sparks.size > MAX_SPARKS) world.removeParticle(sparks.removeAt(0))
        }

        override fun spawn(x: Float, y: Float) {
            val dx = x - GUN_X
            val dy = y - GUN_Y
            val length = sqrt(dx * dx + dy * dy)
            if (length < 1f) return
            fire(dx / length, dy / length)
        }

        override fun draw(canvas: Canvas, debug: DebugDraw, view: Rect) {
            debug.solids(canvas, world.solids!!, view)
            debug.turnedRect(canvas, GUN_X, GUN_Y, 32f, 12f, 0f, GUN)
            for (p in panes) debug.rigidBody(canvas, world, p, PANE_COLOR)
            debug.rigidBody(canvas, world, plate, PLATE)
            for (b in bullets) {
                // a tracer: where it was a sixtieth of a second ago
                val x = bodies.x(b)
                val y = bodies.y(b)
                debug.line(canvas, x - bodies.velocityX(b) / 60f, y - bodies.velocityY(b) / 60f, x, y, 2f, TRACER, debug.layer)
                debug.rigidBody(canvas, world, b, BULLET)
            }
            val ps = world.particles
            for (p in sparks) debug.square(canvas, ps.x(p), ps.y(p), 2f, SPARK)
        }

        override val particleCount get() = world.particles.count
        override val bodyCount get() = bodies.count
        override val contactCount get() = world.rigidContacts.count
    }
}
