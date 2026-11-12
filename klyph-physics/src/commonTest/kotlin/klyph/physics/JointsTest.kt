package klyph.physics

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Hinges, sliders, welds and distance joints: pendulums, cars, limits, motors, breaking. */
class JointsTest {
    private val dt = TestBodies.DT
    private val gravity = 1500f

    @Test
    fun aPendulumKeepsItsPeriod() {
        val (period, measured, _) = swing(PhysicsConfig(damping = 0f))
        assertEquals(period, measured, period * 0.01f, "its period")
    }

    @Test
    fun withSmallSubstepsAPendulumKeepsItsSwingToo() {
        // Each substep's projection takes a little off the swing, less the shorter it is: a fifth in
        // five periods at 4 substeps, a twentieth at 16.
        val (_, _, widest) = swing(PhysicsConfig(damping = 0f, substeps = 16, iterations = 1))
        assertEquals(100f * sin(0.1f), widest, 100f * sin(0.1f) * 0.07f, "its swing, after five periods")
    }

    /**
     * A small box swung from a hinge 100 above it, 0.1 radians aside, for six periods: the period it
     * should have, the period it had, and how far it swung in its sixth.
     */
    private fun swing(config: PhysicsConfig): Triple<Float, Float, Float> {
        val world = PhysicsWorld(config)
        val bodies = world.rigidBodies
        // a small box 100 below a hinge in the world, pulled 0.1 radians aside
        val d = 100f
        val swing = 0.1f
        val bob = world.rigidBody(Shape.box(10f, 10f), 320f + d * sin(swing), 100f + d * cos(swing))
        world.joints.hinge(bob, Joints.WORLD, 320f, 100f)
        // a physical pendulum: T = 2π √(I / (m g d)), its inertia about the hinge
        val m = 1f / bodies.inverseMass(bob)
        val inertia = 1f / bodies.inverseInertia(bob) + m * d * d
        val period = 2f * PI.toFloat() * sqrt(inertia / (m * gravity * d))
        // times it crosses the middle, going right
        val crossings = ArrayList<Float>()
        var last = bodies.x(bob) - 320f
        var widest = 0f
        val steps = (period * 6f / dt).toInt()
        for (step in 1..steps) {
            world.step(dt)
            val now = bodies.x(bob) - 320f
            if (last < 0f && now >= 0f) crossings += (step - 1 + -last / (now - last)) * dt
            last = now
            if (step * dt > period * 5f) widest = maxOf(widest, abs(now))
        }
        assertTrue(crossings.size >= 5, "it swung: $crossings")
        return Triple(period, (crossings.last() - crossings.first()) / (crossings.size - 1), widest)
    }

    /** A car: a body on two wheels, each on a hinge, on a ramp rising 0.2 radians to the right; its handles. */
    private class Car(val world: PhysicsWorld, val chassis: Int, val wheels: IntArray, val axles: IntArray)

    private fun car(): Car {
        val world = PhysicsWorld()
        val tilt = -0.2f
        val c = cos(tilt)
        val s = sin(tilt)
        world.rigidBody(Shape.box(700f, 20f), 320f, 300f, angle = tilt, kind = BodyKind.STATIC, material = Material.RUBBER)
        // up from the ramp's surface
        val upX = s
        val upY = -c
        fun on(t: Float, height: Float) = floatArrayOf(320f + t * c + (10f + height) * upX, 300f + t * s + (10f + height) * upY)
        val r = 10f
        val back = on(-200f, r)
        val front = on(-156f, r)
        val middle = on(-178f, r + 12f)
        val chassis = world.rigidBody(Shape.box(60f, 12f), middle[0], middle[1], angle = tilt)
        val wheels = intArrayOf(
            world.rigidBody(Shape.circle(r), back[0], back[1], material = Material.RUBBER),
            world.rigidBody(Shape.circle(r), front[0], front[1], material = Material.RUBBER),
        )
        val axles = IntArray(2) { world.joints.hinge(chassis, wheels[it], world.rigidBodies.x(wheels[it]), world.rigidBodies.y(wheels[it])) }
        return Car(world, chassis, wheels, axles)
    }

    @Test
    fun aMotorisedWheelDrivesACarUpASlope() {
        val car = car()
        val bodies = car.world.rigidBodies
        // the wheels turn clockwise against the body, rolling it to the right
        for (axle in car.axles) car.world.joints.setMotor(axle, 8f, 1e7f)
        val startX = bodies.x(car.chassis)
        val startY = bodies.y(car.chassis)
        repeat(120) { car.world.step(dt) }
        assertTrue(bodies.x(car.chassis) - startX > 60f, "it drove right: ${bodies.x(car.chassis) - startX}")
        assertTrue(bodies.y(car.chassis) < startY - 10f, "and up: ${startY - bodies.y(car.chassis)}")
        // on its wheels, not flipped
        assertEquals(-0.2f, bodies.angle(car.chassis), 0.15f)
        // its wheels stay on their axles
        for (axle in car.axles) {
            val dx = car.world.joints.anchorBX(axle) - car.world.joints.anchorAX(axle)
            val dy = car.world.joints.anchorBY(axle) - car.world.joints.anchorAY(axle)
            assertTrue(sqrt(dx * dx + dy * dy) < 0.5f, "a wheel came off its axle by ${sqrt(dx * dx + dy * dy)}")
        }
    }

    @Test
    fun withoutItsMotorTheCarRollsBackDown() {
        val car = car()
        val bodies = car.world.rigidBodies
        val startX = bodies.x(car.chassis)
        repeat(120) { car.world.step(dt) }
        assertTrue(bodies.x(car.chassis) < startX - 20f, "it rolled back: ${bodies.x(car.chassis) - startX}")
    }

    @Test
    fun aMotorTooWeakForItsLoadStalls() {
        for ((torque, lifts) in listOf(1e4f to false, 1e7f to true)) {
            val world = PhysicsWorld()
            // an arm sticking out level from a hinge at its left end, its motor turning it up
            val arm = world.rigidBody(Shape.box(100f, 10f), 370f, 200f)
            val hinge = world.joints.hinge(arm, Joints.WORLD, 320f, 200f)
            // the arm is the joint's first end, so the joint's angle is the world's against the arm:
            // the arm turned anticlockwise, up, is a positive angle
            world.joints.setMotor(hinge, 2f, torque)
            repeat(30) { world.step(dt) }
            val angle = world.joints.angle(hinge)
            if (lifts) assertTrue(angle > 0.5f, "a strong motor lifts it: $angle") else assertTrue(angle < 0f, "a weak one can't hold it up: $angle")
        }
    }

    @Test
    fun aHingesLimitHoldsUnderALoad() {
        val world = PhysicsWorld()
        val bodies = world.rigidBodies
        // a plank sticking out level from a hinge at its left end, a heavy box welded to its far end
        val plank = world.rigidBody(Shape.box(100f, 10f), 370f, 200f)
        val hinge = world.joints.hinge(plank, Joints.WORLD, 320f, 200f)
        world.joints.setLimits(hinge, -0.3f, 0.3f)
        val load = world.rigidBody(Shape.box(20f, 20f), 410f, 185f, density = 0.5f)
        world.joints.weld(plank, load, 410f, 195f)
        repeat(240) {
            world.step(dt)
            // the plank, the first end, turning clockwise, down, is a negative angle
            assertTrue(world.joints.angle(hinge) > -0.3f - 0.02f, "past its limit: ${world.joints.angle(hinge)} at step $it")
        }
        assertEquals(-0.3f, world.joints.angle(hinge), 0.02f, "resting on its limit")
        assertTrue(abs(bodies.angularVelocity(plank)) < 0.05f, "and still")
    }

    @Test
    fun aSliderFallsToItsLimitStraightAndAMotorLiftsItBack() {
        val world = PhysicsWorld()
        val bodies = world.rigidBodies
        val box = world.rigidBody(Shape.box(20f, 20f), 320f, 100f)
        // B is the box, so its position is how far it has gone down the axis
        val slider = world.joints.slider(box, Joints.WORLD, 320f, 100f, 0f, 1f)
        world.joints.setLimits(slider, -80f, 0f)
        bodies.setAngularVelocity(box, 3f)
        bodies.setVelocity(box, 200f, 0f)
        repeat(120) { world.step(dt) }
        // the world's anchor goes up the box's axis as the box falls: -80 is 80 down
        assertEquals(180f, bodies.y(box), 1f, "down to its limit")
        assertEquals(320f, bodies.x(box), 0.5f, "straight down its axis")
        assertEquals(0f, bodies.angle(box), 0.01f, "without turning")
        world.joints.setMotor(slider, 60f, 1e6f)
        repeat(60) { world.step(dt) }
        assertTrue(bodies.y(box) < 160f, "the motor lifts it: ${bodies.y(box)}")
    }

    @Test
    fun aWeldHoldsUntilItsLoadIsMoreThanItCanBear() {
        for ((strength, holds) in listOf(1.5f to true, 0.5f to false)) {
            val world = PhysicsWorld()
            val bodies = world.rigidBodies
            val box = world.rigidBody(Shape.box(20f, 20f), 320f, 110f)
            val weight = gravity / bodies.inverseMass(box)
            val weld = world.joints.weld(box, Joints.WORLD, 320f, 100f, breakForce = strength * weight)
            repeat(60) { world.step(dt) }
            if (holds) {
                assertTrue(world.joints.isAlive(weld), "it holds")
                assertEquals(110f, bodies.y(box), 0.5f)
                assertEquals(weight, world.joints.force(weld), weight * 0.05f, "with the box's weight")
            } else {
                assertFalse(world.joints.isAlive(weld), "it broke")
                assertTrue(bodies.y(box) > 200f, "and the box fell: ${bodies.y(box)}")
            }
        }
    }

    @Test
    fun weldedBodiesTumbleAsOne() {
        val world = PhysicsWorld(PhysicsConfig(gravity = Vec(0f, 0f), damping = 0f))
        val bodies = world.rigidBodies
        val a = world.rigidBody(Shape.box(20f, 20f), 300f, 200f)
        val b = world.rigidBody(Shape.box(20f, 20f), 320f, 200f)
        world.joints.weld(a, b, 310f, 200f)
        bodies.setVelocity(a, 50f, 0f)
        bodies.setAngularVelocity(b, 4f)
        repeat(120) {
            world.step(dt)
            val dx = bodies.x(b) - bodies.x(a)
            val dy = bodies.y(b) - bodies.y(a)
            assertEquals(20f, sqrt(dx * dx + dy * dy), 0.1f, "their middles stay 20 apart")
            assertEquals(0f, bodies.angle(b) - bodies.angle(a), 0.01f, "and turned alike")
        }
        assertTrue(abs(bodies.angularVelocity(a)) > 0.5f, "turning together")
    }

    @Test
    fun aDistanceJointKeepsTwoBodiesApartAndABodyAndAParticle() {
        val world = PhysicsWorld()
        val bodies = world.rigidBodies
        val anchor = world.rigidBody(Shape.box(10f, 10f), 320f, 100f, kind = BodyKind.STATIC)
        val box = world.rigidBody(Shape.box(16f, 16f), 380f, 100f)
        val stick = world.joints.distance(anchor, box, 320f, 100f, 372f, 100f)
        val bead = world.particles.add(320f, 160f)
        val string = world.joints.distance(anchor, Joints.particle(bead), 320f, 105f, 320f, 160f)
        repeat(180) {
            world.step(dt)
            for ((j, length) in listOf(stick to 52f, string to 55f)) {
                val dx = world.joints.anchorBX(j) - world.joints.anchorAX(j)
                val dy = world.joints.anchorBY(j) - world.joints.anchorAY(j)
                assertEquals(length, sqrt(dx * dx + dy * dy), 0.2f, "joint $j at step $it")
            }
        }
        assertTrue(bodies.y(box) > 120f, "the box swung down")
    }

    @Test
    fun aParticleHingedToABodyGoesWhereverItGoes() {
        val world = PhysicsWorld()
        val bodies = world.rigidBodies
        val box = world.rigidBody(Shape.box(20f, 20f), 320f, 100f)
        val p = world.particles.add(330f, 90f)
        val hinge = world.joints.hinge(box, Joints.particle(p), 330f, 90f)
        bodies.setAngularVelocity(box, 5f)
        repeat(60) { world.step(dt) }
        assertEquals(world.joints.anchorAX(hinge), world.particles.x(p), 0.2f, "on its corner still")
        assertEquals(world.joints.anchorAY(hinge), world.particles.y(p), 0.2f)
        assertTrue(bodies.y(box) > 150f, "falling with it")
    }

    @Test
    fun hingedBodiesThatOverlapDontPushEachOtherApart() {
        val world = PhysicsWorld(PhysicsConfig(gravity = Vec(0f, 0f)))
        val bodies = world.rigidBodies
        val a = world.rigidBody(Shape.box(40f, 40f), 320f, 200f)
        val b = world.rigidBody(Shape.circle(15f), 330f, 200f)
        world.joints.hinge(a, b, 330f, 200f)
        repeat(60) { world.step(dt) }
        assertEquals(320f, bodies.x(a), 0.01f, "they don't collide with each other")
        assertEquals(330f, bodies.x(b), 0.01f)
        assertEquals(0f, bodies.velocityX(a), 0.01f)
    }

    @Test
    fun aRemovedJointLetsGo() {
        val world = PhysicsWorld()
        val bodies = world.rigidBodies
        val box = world.rigidBody(Shape.box(20f, 20f), 320f, 110f)
        val hinge = world.joints.hinge(box, Joints.WORLD, 320f, 100f)
        repeat(30) { world.step(dt) }
        assertEquals(110f, bodies.y(box), 0.5f)
        world.joints.remove(hinge)
        assertFalse(world.joints.isAlive(hinge))
        repeat(30) { world.step(dt) }
        assertTrue(bodies.y(box) > 150f, "it falls: ${bodies.y(box)}")
    }

    @Test
    fun aJointTellsWhatItWasSetTo() {
        val world = PhysicsWorld(PhysicsConfig(gravity = Vec(0f, 0f)))
        val joints = world.joints
        val box = world.rigidBody(Shape.box(20f, 20f), 320f, 100f, angle = 0.5f)
        val wheel = world.rigidBody(Shape.circle(10f), 340f, 100f)
        val axle = joints.hinge(box, wheel, 340f, 100f)
        assertFalse(joints.hasLimits(axle))
        assertFalse(joints.hasMotor(axle))
        assertEquals(-0.5f, joints.restAngle(axle), 1e-6f, "the wheel's angle less the box's")
        joints.setLimits(axle, -0.2f, 0.3f)
        joints.setMotor(axle, 4f, 100f)
        assertTrue(joints.hasLimits(axle))
        assertEquals(-0.2f, joints.lowerLimit(axle))
        assertEquals(0.3f, joints.upperLimit(axle))
        assertTrue(joints.hasMotor(axle))
        assertEquals(4f, joints.motorSpeed(axle))
        joints.setMotor(axle, 4f, 0f)
        assertFalse(joints.hasMotor(axle), "no torque is off")
        joints.clearLimits(axle)
        assertFalse(joints.hasLimits(axle))
    }

    @Test
    fun aSlidersAxisTurnsWithItsFirstBody() {
        val world = PhysicsWorld(PhysicsConfig(gravity = Vec(0f, 0f)))
        val box = world.rigidBody(Shape.box(20f, 20f), 320f, 100f, angle = 0.5f)
        val rail = world.joints.slider(box, Joints.WORLD, 320f, 100f, 0f, 1f)
        assertEquals(0f, world.joints.axisX(rail), 1e-5f, "as it was given, in the world")
        assertEquals(1f, world.joints.axisY(rail), 1e-5f)
        // turned half a radian more, it turns with the box
        world.rigidBodies.twist(box, 0.5f / world.rigidBodies.inverseInertia(box))
        assertEquals(-sin(0.5f), world.joints.axisX(rail), 1e-4f)
        assertEquals(cos(0.5f), world.joints.axisY(rail), 1e-4f)
    }
}
