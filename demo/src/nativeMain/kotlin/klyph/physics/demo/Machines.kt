package klyph.physics.demo

import klyph.math.Rect
import klyph.physics.Joints
import klyph.physics.Material
import klyph.physics.PhysicsConfig
import klyph.physics.PhysicsWorld
import klyph.physics.Shape
import klyph.physics.bridge.DebugDraw
import klyph.platform.Color
import klyph.render.Canvas

/**
 * Bodies joined the way machines are. On the left, a double pendulum: two planks on hinges, swinging
 * wildly. Along the floor, a car: a body on two wheels, each on a hinge with a motor, driving back
 * and forth. In the middle, a shelf welded to nothing but the air, strong enough for three crates:
 * they're set on it one by one, and the fourth breaks it. On the right, a lift on a slider, its motor
 * taking a crate up and down between its limits. With `F1` on to the second level, the joints show.
 * A right click drops a crate.
 *
 * It runs 8 substeps, not 4, and hardly any damping, so the pendulum keeps on swinging.
 */
object MachinesScene : Scene {
    override val name = "Machines"
    override val blurb = "hinges, sliders, welds and motors: a car, a pendulum, a lift"

    /** The double pendulum's hinge, and its two planks' length. */
    const val PIVOT_X = 110f
    const val PIVOT_Y = 40f
    const val ARM = 90f

    /** The car's wheels' radius and how fast they turn, and where it turns back. */
    const val WHEEL = 10f
    const val DRIVE = 9f
    const val TORQUE = 2e7f
    const val TURN_LEFT_AT = 270f
    const val TURN_RIGHT_AT = 60f

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

    private val PLANK = Color(0x8FA8C8FFu)
    private val BODY = Color(0xF2994AFFu)
    private val WHEEL_COLOR = Color(0x3A3F4BFFu)
    private val SHELF_COLOR = Color(0xC9A26BFFu)
    private val CRATE = Color(0xB8E05CFFu)
    private val LIFT = Color(0x9FE3F5FFu)

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

        // the car: its body on two wheels, at the left
        val chassis = world.rigidBody(Shape.box(60f, 12f), 120f, floor - WHEEL * 2f - 8f)
        val wheels = intArrayOf(
            world.rigidBody(Shape.circle(WHEEL), 98f, floor - WHEEL, material = Material.RUBBER),
            world.rigidBody(Shape.circle(WHEEL), 142f, floor - WHEEL, material = Material.RUBBER),
        )
        val axles = IntArray(2) { joints.hinge(chassis, wheels[it], bodies.x(wheels[it]), bodies.y(wheels[it])) }
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

        init {
            joints.hinge(upper, Joints.WORLD, PIVOT_X, PIVOT_Y)
            joints.hinge(lower, upper, PIVOT_X + ARM, PIVOT_Y)
            for (axle in axles) joints.setMotor(axle, DRIVE, TORQUE)
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
            for (w in wheels) {
                debug.rigidBody(canvas, world, w, WHEEL_COLOR)
                // a spoke, so the wheel is seen to turn
                debug.line(canvas, bodies.x(w), bodies.y(w), bodies.worldX(w, WHEEL - 2f, 0f), bodies.worldY(w, WHEEL - 2f, 0f), 2f, BODY, debug.layer + 4)
            }
            debug.rigidBody(canvas, world, shelf, SHELF_COLOR)
            for (c in crates) debug.rigidBody(canvas, world, c, CRATE)
            debug.rigidBody(canvas, world, lift, LIFT)
        }

        override val bodyCount get() = bodies.count
        override val contactCount get() = world.rigidContacts.count
    }
}
