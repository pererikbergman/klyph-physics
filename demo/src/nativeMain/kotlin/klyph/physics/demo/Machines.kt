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
import kotlin.math.cos
import kotlin.math.sin

/**
 * Bodies joined the way machines are. On the left, a double pendulum: two planks on hinges, swinging
 * wildly, its elbow bending only so far. Along the floor, a car driving back and forth over a bump:
 * each wheel turns on a motor on a strut, and the strut slides up and down in the car's body on a
 * spring, its suspension. In the middle, a shelf welded to nothing but the air, strong enough for
 * three crates: they're set on it one by one, and the fourth breaks it. Above it a windmill turns on
 * its motor. On the right, a lift on a slider, its motor taking a crate up and down between its
 * limits, and under it a seesaw: a heavy crate dropped on one end throws the ball on the other up.
 * With `F1` on to the second level, the joints show. A right click drops a crate.
 *
 * It runs 8 substeps, not 4, and hardly any damping, so the pendulum keeps on swinging.
 */
object MachinesScene : Scene {
    override val name = "Machines"
    override val blurb = "hinges, sliders, welds, motors: a car, a pendulum, a windmill, a lift, a seesaw"

    /** The double pendulum's hinge, and its two planks' length. */
    const val PIVOT_X = 110f
    const val PIVOT_Y = 40f
    const val ARM = 90f

    /** How far the pendulum's elbow bends either way, in radians. */
    const val ELBOW = 1.5f

    /** The car's wheels' radius and how fast they turn, and where it turns back. */
    const val WHEEL = 10f
    const val DRIVE = 9f
    const val TORQUE = 2e7f
    const val TURN_LEFT_AT = 270f
    const val TURN_RIGHT_AT = 60f

    /**
     * Its suspension: how far each wheel's strut may slide up or down in the body, and how soft the
     * spring holding it is, the inverse of its stiffness: under the car's weight it gives a few units.
     */
    const val TRAVEL = 6f
    const val SPRING = 1e-4f

    /** The bump on the floor it drives over. */
    const val BUMP_X = 170f
    const val BUMP = 5f

    /**
     * The shelf: its welded end, its length, how many crates it bears (its breaking force is its own
     * weight and that many and a half crates'), and how often one is set on it, just touching.
     */
    const val SHELF_X = 330f
    const val SHELF_Y = 170f
    const val SHELF = 90f
    const val SHELF_HOLDS = 3.5f
    const val PLACE_EVERY = 1.5f
    const val PLACED = 4

    /** The lift: where it starts at the top, how far down it goes, how fast, and how long a trip takes. */
    const val LIFT_X = 540f
    const val LIFT_TOP = 120f
    const val LIFT_TRAVEL = 120f
    const val LIFT_SPEED = 60f
    const val LIFT_FORCE = 1e7f

    /** The windmill: its hub, its sails' length from tip to tip, and how fast its motor turns them. */
    const val MILL_X = 455f
    const val MILL_Y = 90f
    const val SAILS = 100f
    const val MILL_SPEED = 1.5f

    /**
     * The seesaw: its pivot, on a post off the floor, its length, how far it starts tipped (its right
     * end down, on the floor), the ball on its right end against a lip, and the heavy crate dropped on
     * its raised left end, when and from how high.
     */
    const val SEESAW_X = 520f
    const val SEESAW = 160f
    const val PIVOT_HEIGHT = 18f
    const val TIPPED = 0.23f
    const val SEESAW_BALL = 8f
    const val DROP_AT = 2f
    const val DROP_Y = 150f
    const val DROP_DENSITY = 0.25f

    private val PLANK = Color(0x8FA8C8FFu)
    private val BODY = Color(0xF2994AFFu)
    private val WHEEL_COLOR = Color(0x3A3F4BFFu)
    private val SHELF_COLOR = Color(0xC9A26BFFu)
    private val CRATE = Color(0xB8E05CFFu)
    private val LIFT = Color(0x9FE3F5FFu)
    private val SAIL = Color(0xE8ECF4FFu)
    private val POST = Color(0x7A5A3AFFu)
    private val BALL = Color(0xF27BB6FFu)
    private val HEAVY = Color(0x6B7A99FFu)

    override fun create(): SceneRun = Run()

    internal class Run : SceneRun {
        override val world = PhysicsWorld(PhysicsConfig(substeps = 8, iterations = 4, damping = 0.02f)).apply { solids = Scenes.room() }
        private val bodies = world.rigidBodies
        private val joints = world.joints
        private val floor = (Scenes.ROWS - 2) * Scenes.TILE
        private var time = 0f
        private var placed = 0

        // the double pendulum, held out level to start
        val upper = world.rigidBody(Shape.box(ARM, 6f), PIVOT_X + ARM / 2f, PIVOT_Y)
        val lower = world.rigidBody(Shape.box(ARM, 6f), PIVOT_X + ARM * 1.5f, PIVOT_Y)

        // the car: its body on two wheels, at the left, high enough that the springs can give
        val chassis = world.rigidBody(Shape.box(60f, 12f), 120f, floor - WHEEL * 2f - 8f - TRAVEL)
        val wheels = intArrayOf(
            world.rigidBody(Shape.circle(WHEEL), 98f, floor - WHEEL, material = Material.RUBBER),
            world.rigidBody(Shape.circle(WHEEL), 142f, floor - WHEEL, material = Material.RUBBER),
        )

        /** A small body in each wheel's middle: the wheel turns on it, and it slides up and down in the car. */
        val struts = IntArray(2) { world.rigidBody(Shape.box(4f, 4f), bodies.x(wheels[it]), bodies.y(wheels[it]), density = 1f) }
        val axles = IntArray(2) { joints.hinge(struts[it], wheels[it], bodies.x(wheels[it]), bodies.y(wheels[it])) }
        val shocks = IntArray(2) { joints.slider(struts[it], chassis, bodies.x(struts[it]), bodies.y(struts[it]), 0f, 1f) }
        val springs = IntArray(2) {
            val x = bodies.x(struts[it])
            joints.distance(chassis, struts[it], x, bodies.y(chassis), x, bodies.y(struts[it]), compliance = SPRING)
        }

        /** The bump on the floor: half a capsule, lying in it. */
        val bump = world.rigidBody(Shape.capsule(20f, BUMP), BUMP_X, floor, kind = BodyKind.STATIC)
        var direction = 1f
            private set

        // the shelf, welded at its left end, and the crates dropped on it
        val shelf = world.rigidBody(Shape.box(SHELF, 8f), SHELF_X + SHELF / 2f, SHELF_Y)
        val weld: Int
        val crates = ArrayList<Int>()

        // the lift, on a slider down from its top, and a crate riding it
        val lift = world.rigidBody(Shape.box(60f, 8f), LIFT_X, LIFT_TOP)
        val slider = joints.slider(lift, Joints.WORLD, LIFT_X, LIFT_TOP, 0f, 1f)
        val rider = world.rigidBody(Shape.box(20f, 20f), LIFT_X, LIFT_TOP - 14f)

        // the windmill: two sails crossed, on a motor in the air
        val mill = world.rigidBody(listOf(Shape.box(SAILS, 6f), Shape.box(6f, SAILS)), MILL_X, MILL_Y)
        val millHinge = joints.hinge(mill, Joints.WORLD, MILL_X, MILL_Y)

        // the seesaw: a plank on a hinge at the top of a post, tipped onto its right end, where a lip
        // keeps the ball from rolling off
        val post = world.rigidBody(
            Shape.polygon(-12f, PIVOT_HEIGHT, 12f, PIVOT_HEIGHT, 0f, 0f),
            SEESAW_X,
            floor - PIVOT_HEIGHT,
            kind = BodyKind.STATIC,
        )
        private val pivotY = floor - PIVOT_HEIGHT - 4f
        val seesaw = world.rigidBody(
            listOf(Shape.box(SEESAW, 6f), Shape.box(4f, 10f, x = SEESAW / 2f - 2f, y = -8f)),
            SEESAW_X,
            pivotY,
            angle = TIPPED,
        )
        // along the plank from its pivot, not its centre of mass, which the lip moves off the middle
        val ball = run {
            val along = SEESAW / 2f - 5f - SEESAW_BALL
            val up = 4f + SEESAW_BALL
            world.rigidBody(
                Shape.circle(SEESAW_BALL),
                SEESAW_X + cos(TIPPED) * along + sin(TIPPED) * up,
                pivotY + sin(TIPPED) * along - cos(TIPPED) * up,
            )
        }
        private var dropped = -1
        val heavy get() = dropped

        init {
            joints.hinge(upper, Joints.WORLD, PIVOT_X, PIVOT_Y)
            // its elbow bends only so far either way: F1 shows the wedge it may swing in
            joints.setLimits(joints.hinge(lower, upper, PIVOT_X + ARM, PIVOT_Y), -ELBOW, ELBOW)
            for (axle in axles) joints.setMotor(axle, DRIVE, TORQUE)
            for (shock in shocks) joints.setLimits(shock, -TRAVEL, TRAVEL)
            joints.setMotor(millHinge, MILL_SPEED, TORQUE)
            joints.hinge(seesaw, Joints.WORLD, SEESAW_X, pivotY)
            // a crate's weight times SHELF_HOLDS, at the shelf's weld: the crates' leverage adds no force
            val crateWeight = world.config.gravity.y / bodies.inverseMass(rider)
            weld = joints.weld(shelf, Joints.WORLD, SHELF_X, SHELF_Y, breakForce = crateWeight * SHELF_HOLDS + world.config.gravity.y / bodies.inverseMass(shelf))
            // the crate on the lift is one of these, and so are those set on the shelf
            crates += rider
            // the slider's position is how far the world's anchor is along the lift's axis: down is negative
            joints.setLimits(slider, -LIFT_TRAVEL, 0f)
            joints.setMotor(slider, -LIFT_SPEED, LIFT_FORCE)
        }

        override fun step(dt: Float) {
            time += dt
            // the car turns back at either wall
            val x = bodies.x(chassis)
            if (direction > 0f && x > TURN_LEFT_AT || direction < 0f && x < TURN_RIGHT_AT) {
                direction = -direction
                for (axle in axles) joints.setMotor(axle, DRIVE * direction, TORQUE)
            }
            // a crate set on the shelf, just touching it, every so often: from its far end in
            if (placed < PLACED && time >= (placed + 1) * PLACE_EVERY && joints.isAlive(weld)) {
                val x = bodies.worldX(shelf, SHELF / 2f - 12f - placed * 22f, 0f)
                val y = bodies.worldY(shelf, SHELF / 2f - 12f - placed * 22f, -14f)
                crates += world.rigidBody(Shape.box(20f, 20f), x, y, angle = bodies.angle(shelf))
                placed++
            }
            // the heavy crate, dropped on the seesaw's left end
            if (dropped < 0 && time >= DROP_AT) {
                dropped = world.rigidBody(Shape.box(20f, 20f), SEESAW_X - SEESAW / 2f + 14f, DROP_Y, density = DROP_DENSITY)
            }
            // the lift goes down and up again, its whole travel each way
            val trip = LIFT_TRAVEL / LIFT_SPEED + 0.5f
            val down = (time / trip).toInt() % 2 == 0
            joints.setMotor(slider, if (down) -LIFT_SPEED else LIFT_SPEED, LIFT_FORCE)
            world.step(dt)
        }

        override fun spawn(x: Float, y: Float) {
            if (crates.size >= Scenes.MAX_SPAWNED + PLACED + 1 || !Scenes.isOpen(world, x, y)) return
            crates += world.rigidBody(Shape.box(20f, 20f), x, y)
        }

        override fun draw(canvas: Canvas, debug: DebugDraw, view: Rect) {
            debug.solids(canvas, world.solids!!, view)
            debug.rigidBody(canvas, world, upper, PLANK)
            debug.rigidBody(canvas, world, lower, PLANK)
            debug.rigidBody(canvas, world, chassis, BODY)
            debug.rigidBody(canvas, world, bump, POST)
            for (w in wheels) {
                debug.rigidBody(canvas, world, w, WHEEL_COLOR)
                // a spoke, so the wheel is seen to turn
                debug.line(canvas, bodies.x(w), bodies.y(w), bodies.worldX(w, WHEEL - 2f, 0f), bodies.worldY(w, WHEEL - 2f, 0f), 2f, BODY, debug.layer + 4)
            }
            debug.rigidBody(canvas, world, shelf, SHELF_COLOR)
            for (c in crates) debug.rigidBody(canvas, world, c, CRATE)
            debug.rigidBody(canvas, world, lift, LIFT)
            debug.rigidBody(canvas, world, mill, SAIL)
            debug.rigidBody(canvas, world, post, POST)
            debug.rigidBody(canvas, world, seesaw, SHELF_COLOR)
            debug.rigidBody(canvas, world, ball, BALL)
            if (dropped >= 0) debug.rigidBody(canvas, world, dropped, HEAVY)
        }

        override val bodyCount get() = bodies.count
        override val contactCount get() = world.rigidContacts.count
    }
}
