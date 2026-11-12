package klyph.physics.demo

import klyph.math.Rect
import klyph.physics.BodyKind
import klyph.physics.Joints
import klyph.physics.Material
import klyph.physics.PhysicsConfig
import klyph.physics.PhysicsWorld
import klyph.physics.Shape
import klyph.physics.bridge.DebugDraw
import klyph.platform.Color
import klyph.render.Canvas
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.sin

/**
 * Bodies hung from each other. On the left, a Newton's cradle: five balls, each on a rod of its own,
 * two units apart; the first is pulled out and let go, and its swing goes through the middle three
 * to the last. On the right, a chain bridge: planks hinged end to end, hung between two posts, and a ragdoll
 * dropped on it, ten bodies hinged at the neck, shoulders, elbows, hips and knees, each bending only
 * so far. A right click drops another ragdoll. With `F1` on to the second level, the joints and
 * their limits show.
 *
 * It runs 16 substeps of 2 passes, not 4 of 4, and hardly any damping, so the cradle keeps on
 * swinging. Each hit leaves the middle balls a little out of place, and after a few swings the cradle
 * goes out of step, as a real one does, only sooner.
 */
object JointsScene : Scene {
    override val name = "Joints"
    override val blurb = "a Newton's cradle, a chain bridge, a ragdoll: bodies hung from each other"

    /**
     * The cradle: how many balls, how big, how far apart their rods hang, where, how long, and how far
     * the first is pulled out. Two units apart, each hit is between two balls alone: touching, all four
     * would be solved at once, and the swing would spread over them.
     */
    const val BALLS = 5
    const val BALL = 12f
    const val SPACING = 26f
    const val CRADLE_X = 150f
    const val CRADLE_Y = 60f
    const val CRADLE_ROD = 120f
    const val PULLED_OUT = 1f

    /** The bridge: its two ends, how many planks, and each one's length: 8% more than the gap, so it sags. */
    const val BRIDGE_LEFT = 290f
    const val BRIDGE_RIGHT = 600f
    const val BRIDGE_Y = 200f
    const val PLANKS = 14
    const val PLANK = 24f

    /** Where the ragdoll starts: its middle, above the middle of the bridge. */
    const val RAGDOLL_X = 445f
    const val RAGDOLL_Y = 80f

    /** The most ragdolls there can be, the first one too. */
    const val MAX_RAGDOLLS = 5

    /** Balls that lose no speed when they hit, and slide on each other without grip. */
    val STEEL = Material(friction = 0f, staticFriction = 0f, restitution = 1f)

    private val BALL_COLOR = Color(0xB8C2D6FFu)
    private val STRING = Color(0x8FA8C8FFu)
    private val FRAME = Color(0x3A4A66FFu)
    private val PLANK_COLOR = Color(0xC9A26BFFu)
    private val POST = Color(0x7A5A3AFFu)
    private val SKIN = Color(0xF2994AFFu)
    private val SHIRT = Color(0x6BE07AFFu)
    private val TROUSERS = Color(0x4D7CFFFFu)

    override fun create(): SceneRun = Run()

    /** A ragdoll's ten bodies, and the nine hinges that hold them together. */
    class Ragdoll(val parts: IntArray, val hinges: IntArray) {
        val torso get() = parts[0]
        val head get() = parts[1]
    }

    internal class Run : SceneRun {
        override val world = PhysicsWorld(PhysicsConfig(substeps = 16, iterations = 2, damping = 0.01f)).apply { solids = Scenes.room() }
        private val bodies = world.rigidBodies
        private val joints = world.joints

        /** The cradle's balls, left to right; the first starts pulled out to the left. */
        val balls = IntArray(BALLS) {
            val pivot = CRADLE_X + it * SPACING
            val swing = if (it == 0) -PULLED_OUT else 0f
            world.rigidBody(Shape.circle(BALL), pivot + sin(swing) * CRADLE_ROD, CRADLE_Y + cos(swing) * CRADLE_ROD, material = STEEL)
        }

        init {
            for ((i, b) in balls.withIndex()) joints.distance(b, Joints.WORLD, bodies.x(b), bodies.y(b), CRADLE_X + i * SPACING, CRADLE_Y)
        }

        /** The posts the bridge hangs between, just outside its ends. */
        val posts = floatArrayOf(BRIDGE_LEFT - 9f, BRIDGE_RIGHT + 9f).map { x ->
            val floor = (Scenes.ROWS - 2) * Scenes.TILE
            world.rigidBody(Shape.box(10f, floor - BRIDGE_Y), x, (BRIDGE_Y + floor) / 2f, kind = BodyKind.STATIC)
        }

        /** The bridge's planks, left to right, and where each one's left end is hinged. */
        val planks: IntArray
        val bridgeHinges: IntArray

        init {
            // Plank i's slope is a straight line in i, so the hinges lie on a parabola: as a chain hangs,
            // near enough. How steep is found by halving, until the planks just span the gap.
            val middle = (PLANKS - 1) / 2f
            fun span(k: Float): Float = (0 until PLANKS).sumOf { cos(atan(-k * (it - middle))).toDouble() }.toFloat() * PLANK
            var low = 0f
            var high = 1f
            repeat(40) {
                val k = (low + high) / 2f
                if (span(k) > BRIDGE_RIGHT - BRIDGE_LEFT) low = k else high = k
            }
            val k = (low + high) / 2f
            var x = BRIDGE_LEFT
            var y = BRIDGE_Y
            planks = IntArray(PLANKS)
            bridgeHinges = IntArray(PLANKS + 1)
            for (i in 0 until PLANKS) {
                val angle = atan(-k * (i - middle))
                val nx = x + cos(angle) * PLANK
                val ny = y + sin(angle) * PLANK
                planks[i] = world.rigidBody(Shape.box(PLANK, 4f), (x + nx) / 2f, (y + ny) / 2f, angle = angle)
                bridgeHinges[i] = if (i == 0) joints.hinge(planks[0], Joints.WORLD, x, y) else joints.hinge(planks[i], planks[i - 1], x, y)
                x = nx
                y = ny
            }
            // the last plank's right end, where the halving put it: the right post's top
            bridgeHinges[PLANKS] = joints.hinge(planks[PLANKS - 1], Joints.WORLD, BRIDGE_RIGHT, BRIDGE_Y)
        }

        val ragdolls = arrayListOf(ragdoll(RAGDOLL_X, RAGDOLL_Y))

        /**
         * A ragdoll standing with its middle at ([x], [y]): a torso, a head, upper and lower arms and legs,
         * each hinged to the one it hangs from, the elbows and knees bending only one way.
         */
        private fun ragdoll(x: Float, y: Float): Ragdoll {
            fun part(shape: Shape, dx: Float, dy: Float) = world.rigidBody(shape, x + dx, y + dy)
            val torso = part(Shape.box(14f, 28f), 0f, 0f)
            val head = part(Shape.circle(7f), 0f, -21f)
            val parts = arrayListOf(torso, head)
            val hinges = arrayListOf(limited(head, torso, x, y - 14f, -0.5f, 0.5f))
            for (side in floatArrayOf(-1f, 1f)) {
                val upperArm = part(Shape.box(5f, 14f), side * 10f, -6f)
                val lowerArm = part(Shape.box(5f, 14f), side * 10f, 8f)
                val thigh = part(Shape.box(6f, 16f), side * 4f, 22f)
                val shin = part(Shape.box(6f, 16f), side * 4f, 38f)
                parts += listOf(upperArm, lowerArm, thigh, shin)
                hinges += limited(upperArm, torso, x + side * 10f, y - 12f, -2.6f, 2.6f)
                hinges += limited(lowerArm, upperArm, x + side * 10f, y + 1f, -2.2f, 0f)
                hinges += limited(thigh, torso, x + side * 4f, y + 14f, -1.6f, 0.4f)
                hinges += limited(shin, thigh, x + side * 4f, y + 30f, 0f, 2.2f)
            }
            return Ragdoll(parts.toIntArray(), hinges.toIntArray())
        }

        private fun limited(a: Int, b: Int, x: Float, y: Float, lower: Float, upper: Float): Int =
            joints.hinge(a, b, x, y).also { joints.setLimits(it, lower, upper) }

        override fun step(dt: Float) = world.step(dt)

        override fun spawn(x: Float, y: Float) {
            if (ragdolls.size >= MAX_RAGDOLLS || !Scenes.isOpen(world, x, y - 30f) || !Scenes.isOpen(world, x, y + 50f)) return
            ragdolls += ragdoll(x, y)
        }

        override fun draw(canvas: Canvas, debug: DebugDraw, view: Rect) {
            debug.solids(canvas, world.solids!!, view)
            // the cradle's frame, and a rod from it to each ball
            debug.line(canvas, CRADLE_X - 20f, CRADLE_Y, CRADLE_X + (BALLS - 1) * SPACING + 20f, CRADLE_Y, 4f, FRAME, debug.layer + 1)
            for ((i, b) in balls.withIndex()) {
                debug.line(canvas, CRADLE_X + i * SPACING, CRADLE_Y, bodies.x(b), bodies.y(b), 1f, STRING, debug.layer + 1)
                debug.rigidBody(canvas, world, b, BALL_COLOR)
            }
            for (p in posts) debug.rigidBody(canvas, world, p, POST)
            for (p in planks) debug.rigidBody(canvas, world, p, PLANK_COLOR)
            for (r in ragdolls) {
                for ((k, p) in r.parts.withIndex()) {
                    // torso the shirt, head and arms skin, legs trousers: parts come torso, head, then
                    // upper arm, lower arm, thigh, shin for each side
                    val color = when {
                        k == 0 -> SHIRT
                        k == 1 -> SKIN
                        (k - 2) % 4 < 2 -> SKIN
                        else -> TROUSERS
                    }
                    debug.rigidBody(canvas, world, p, color)
                }
            }
        }

        override val bodyCount get() = bodies.count
        override val contactCount get() = world.rigidContacts.count
    }
}
