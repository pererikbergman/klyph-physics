package klyph.physics.demo

import klyph.math.Rect
import klyph.physics.BodyKind
import klyph.physics.HitKind
import klyph.physics.Joints
import klyph.physics.Overlaps
import klyph.physics.PhysicsWorld
import klyph.physics.Query
import klyph.physics.RayHit
import klyph.physics.Shape
import klyph.physics.bridge.DebugDraw
import klyph.platform.Color
import klyph.render.Canvas
import kotlin.math.cos
import kotlin.math.sin

/**
 * A laser, traced with raycasts. A beam from the left hits a mirror spinning on a motor, which throws
 * it all round the room, off mirrors fixed on the walls, the ceiling and the floor, and one swinging
 * on a hinge. Each bounce is a new ray from where the last one hit, turned about the hit's normal; the
 * beam ends on the first thing that isn't a mirror, a crate, a blob or a wall, and lights it up. In
 * the bin on the right, a box query finds whatever is in it, and it glows. A right click drops a crate
 * or a blob.
 */
object LaserScene : Scene {
    override val name = "Laser"
    override val blurb = "a beam traced by raycasts, bouncing off mirrors; a box query lights up the bin"

    /** Where the laser is, which way it points at the middle of its sweep, how far either way it sweeps, and how fast. */
    const val LASER_X = 36f
    const val LASER_Y = 90f
    const val AIM = 0.1f
    const val SWEEP = 0.1f
    const val SWEEP_SPEED = 0.7f

    /** How far one ray reaches, and the most bounces drawn: a beam caught between two mirrors stops somewhere. */
    const val RANGE = 2000f
    const val MAX_BOUNCES = 16

    /** How long a mirror is, and how thick. */
    const val MIRROR = 90f
    const val MIRROR_THICK = 3f

    /** The spinning mirror's middle, where the laser points, and how fast its motor turns it. */
    const val SPINNER_X = 330f
    const val SPINNER_Y = 120f
    const val SPIN = 0.6f

    /** Where the swinging mirror hangs from, and how fast its motor swings it to and fro. */
    const val SWING_X = 200f
    const val SWING_Y = 30f
    const val SWING_SPEED = 1.6f

    /** The bin a box query looks in, on the right of the floor. */
    const val BIN_LEFT = 460f
    const val BIN_RIGHT = 608f
    const val BIN_TOP = 280f

    private val MIRROR_COLOR = Color(0xD8F1FFFFu)
    private val BEAM = Color(1f, 0.25f, 0.3f, 0.35f)
    private val BEAM_CORE = Color(0xFFD0D4FFu)
    private val NORMAL = Color(1f, 1f, 1f, 0.6f)
    private val LASER = Color(0x6B7A99FFu)
    private val CRATE = Color(0xC08A55FFu)
    private val BLOB = Color(0x7DDB6EFFu)
    private val LIT = Color(0xFF6B78FFu)
    private val IN_BIN = Color(0xFFD36BFFu)
    private val BIN = Color(1f, 0.83f, 0.42f, 0.5f)

    override fun create(): SceneRun = Run()

    internal class Run : SceneRun {
        override val world = PhysicsWorld().apply {
            solids = Scenes.room().apply {
                for (c in 0 until Scenes.COLS) fill(c, 0)
            }
        }
        private val bodies = world.rigidBodies
        private val floor = (Scenes.ROWS - 2) * Scenes.TILE

        /** The spinner: a mirror on a hinge at its middle, turned by a motor. */
        val spinner = world.rigidBody(Shape.box(MIRROR, MIRROR_THICK), SPINNER_X, SPINNER_Y)

        init {
            world.joints.setMotor(world.joints.hinge(spinner, Joints.WORLD, SPINNER_X, SPINNER_Y), SPIN, 1e7f)
        }

        /** The swinger: a mirror hung from its top, swung to and fro by a motor. */
        val swinger = world.rigidBody(Shape.box(MIRROR_THICK, MIRROR), SWING_X, SWING_Y + MIRROR / 2f)
        private val swingerHinge = world.joints.hinge(swinger, Joints.WORLD, SWING_X, SWING_Y)

        /** Every mirror: the two that move, and fixed ones on the walls, the ceiling and the floor. */
        val mirrors = intArrayOf(
            spinner,
            swinger,
            fixed(580f, 80f, 0.7f),
            fixed(585f, 220f, -0.5f),
            fixed(440f, 26f, 0.15f),
            fixed(50f, 240f, -0.9f),
            world.rigidBody(Shape.box(MIRROR * 1.5f, MIRROR_THICK), 330f, floor - MIRROR_THICK / 2f, kind = BodyKind.STATIC),
        )

        private fun fixed(x: Float, y: Float, angle: Float) = world.rigidBody(Shape.box(MIRROR, MIRROR_THICK), x, y, angle = angle, kind = BodyKind.STATIC)

        /** Crates and blobs: what the beam ends on. Some start in the bin. */
        val crates = ArrayList<Int>().apply {
            for ((x, size) in listOf(500f to 24f, 530f to 20f, 515f to 18f, 130f to 22f)) {
                val stackY = floor - size / 2f - if (x == 515f) 24f else 0f
                add(world.rigidBody(Shape.box(size, size), x, stackY))
            }
        }
        val blobs = ArrayList<Int>().apply {
            add(world.softBody(Scenes.dome(), 90f, floor))
            add(world.softBody(Scenes.dome(), 570f, floor))
        }

        /** The beam: [beamPoints] points, where it starts, bounces and ends, in turn. */
        val beamX = FloatArray(MAX_BOUNCES + 2)
        val beamY = FloatArray(MAX_BOUNCES + 2)
        var beamPoints = 0
            private set

        /** At each bounce, the mirror's normal there. */
        private val normalX = FloatArray(MAX_BOUNCES + 1)
        private val normalY = FloatArray(MAX_BOUNCES + 1)

        /** Which mirror each bounce was off. */
        val bouncedOff = IntArray(MAX_BOUNCES + 1)

        /** What the beam ended on. */
        var endedOn = HitKind.NOTHING
            private set
        var endedOnBody = -1
            private set

        /** What the box query found in the bin. */
        val inBin = Overlaps()

        private val hit = RayHit()
        private var time = 0f

        override fun step(dt: Float) {
            time += dt
            world.joints.setMotor(swingerHinge, cos(time * 1.4f) * SWING_SPEED, 1e7f)
            world.step(dt)
            trace(AIM + sin(time * SWEEP_SPEED) * SWEEP)
            world.queryBox(BIN_LEFT, BIN_TOP, BIN_RIGHT, floor, inBin, Query.RIGID_BODIES or Query.SOFT_BODIES)
        }

        /** Traces the beam from the laser at [angle]: a ray, and a new one from each mirror it hits, turned about its normal. */
        private fun trace(angle: Float) {
            var x = LASER_X + cos(angle) * 14f
            var y = LASER_Y + sin(angle) * 14f
            var dx = cos(angle)
            var dy = sin(angle)
            beamX[0] = x
            beamY[0] = y
            beamPoints = 1
            endedOn = HitKind.NOTHING
            endedOnBody = -1
            while (true) {
                if (!world.raycast(x, y, x + dx * RANGE, y + dy * RANGE, hit)) {
                    beamX[beamPoints] = x + dx * RANGE
                    beamY[beamPoints] = y + dy * RANGE
                    beamPoints++
                    return
                }
                beamX[beamPoints] = hit.x
                beamY[beamPoints] = hit.y
                beamPoints++
                val mirror = hit.what == HitKind.RIGID_BODY && hit.body in mirrors
                if (!mirror || beamPoints == beamX.size) {
                    endedOn = hit.what
                    endedOnBody = hit.body
                    return
                }
                // off the mirror: the part of the way along the normal turns round
                val bounce = beamPoints - 2
                normalX[bounce] = hit.normalX
                normalY[bounce] = hit.normalY
                bouncedOff[bounce] = hit.body
                val along = dx * hit.normalX + dy * hit.normalY
                dx -= 2f * along * hit.normalX
                dy -= 2f * along * hit.normalY
                x = hit.x
                y = hit.y
            }
        }

        /** How many times the beam bounced. */
        val bounces: Int get() = (beamPoints - 2).coerceAtLeast(0)

        override fun spawn(x: Float, y: Float) {
            if (crates.size + blobs.size >= Scenes.MAX_SPAWNED || !Scenes.isOpen(world, x, y)) return
            if ((crates.size + blobs.size) % 2 == 0) {
                crates += world.rigidBody(Shape.box(20f, 20f), x, y, angle = x * 0.1f)
            } else {
                blobs += world.softBody(Scenes.dome(), x, y + Scenes.DOME_HALF_HEIGHT)
            }
        }

        override fun draw(canvas: Canvas, debug: DebugDraw, view: Rect) {
            debug.solids(canvas, world.solids!!, view)
            // the bin's outline
            debug.line(canvas, BIN_LEFT, BIN_TOP, BIN_RIGHT, BIN_TOP, 1f, BIN, debug.layer)
            debug.line(canvas, BIN_LEFT, BIN_TOP, BIN_LEFT, floor, 1f, BIN, debug.layer)
            debug.line(canvas, BIN_RIGHT, BIN_TOP, BIN_RIGHT, floor, 1f, BIN, debug.layer)
            for (b in crates) {
                val color = when {
                    endedOn == HitKind.RIGID_BODY && endedOnBody == b -> LIT
                    inBin.hasRigidBody(b) -> IN_BIN
                    else -> CRATE
                }
                debug.rigidBody(canvas, world, b, color)
            }
            for (b in blobs) {
                val color = when {
                    endedOn == HitKind.SOFT_BODY && endedOnBody == b -> LIT
                    inBin.hasSoftBody(b) -> IN_BIN
                    else -> BLOB
                }
                debug.softBody(canvas, world, b, color)
            }
            for (m in mirrors) debug.rigidBody(canvas, world, m, MIRROR_COLOR)
            val angle = AIM + sin(time * SWEEP_SPEED) * SWEEP
            debug.turnedRect(canvas, LASER_X, LASER_Y, 28f, 10f, angle, LASER)
            // the beam: a glow, and a bright core over it
            for (i in 0 until beamPoints - 1) debug.line(canvas, beamX[i], beamY[i], beamX[i + 1], beamY[i + 1], 6f, BEAM, debug.layer)
            for (i in 0 until beamPoints - 1) debug.line(canvas, beamX[i], beamY[i], beamX[i + 1], beamY[i + 1], 1.5f, BEAM_CORE, debug.layer)
            for (k in 0 until bounces) {
                val x = beamX[k + 1]
                val y = beamY[k + 1]
                debug.line(canvas, x, y, x + normalX[k] * 10f, y + normalY[k] * 10f, 1f, NORMAL, debug.layer)
            }
            if (beamPoints >= 2) debug.square(canvas, beamX[beamPoints - 1], beamY[beamPoints - 1], 5f, BEAM_CORE)
        }

        override val particleCount get() = world.particles.count
        override val bodyCount get() = bodies.count
        override val contactCount get() = world.rigidContacts.count
    }
}
