package klyph.physics

import kotlin.math.sqrt

/** How a rigid body moves. */
enum class BodyKind {
    /** Never moves: the ground, a wall. Nothing pushes it, and it has no velocity. */
    STATIC,

    /** Moves the way forces, contacts and joints push it, and falls under gravity. */
    DYNAMIC,

    /**
     * Moves only the way it's told, with its velocity or [RigidBodies.moveKinematic], whatever hits it:
     * a lift, a moving platform, a spinning paddle. It pushes dynamic bodies, they never push it.
     */
    KINEMATIC,
}

/**
 * Every rigid body in a [PhysicsWorld], as parallel arrays: bodies that keep their shape and turn. A
 * body is an `Int` handle, an index into them, reused by the next [add] after a [remove].
 *
 * A body has a position, the point it turns about (its centre of mass), and an angle in radians,
 * clockwise on screen since y grows down. Unlike a particle it keeps its velocity explicitly, linear
 * and angular, because turning needs one: each substep the world
 * 1. remembers where the body was, adds gravity to its velocity and moves it by that velocity,
 * 2. lets the solver push it about, by position and angle,
 * 3. and then takes the velocity to be how far it really moved, divided by the substep.
 *
 * The solver moves a body only through [shove], which keeps count, so step 3 adds what it moved the
 * body to the velocity rather than measuring the move from two positions: a body far from the origin
 * keeps every digit of its speed.
 *
 * That's Müller et al. 2020, "Detailed Rigid Body Simulation with Extended Position Based Dynamics":
 * the same XPBD as the particles, with rotation added. A [BodyKind.KINEMATIC] body skips steps 2 and
 * 3, and a [BodyKind.STATIC] one all three.
 *
 * The arrays grow by doubling when a body is added, never during a step.
 */
class RigidBodies internal constructor(initialCapacity: Int = 16) {
    internal var x = FloatArray(initialCapacity)
    internal var y = FloatArray(initialCapacity)
    internal var angle = FloatArray(initialCapacity)
    internal var prevX = FloatArray(initialCapacity)
    internal var prevY = FloatArray(initialCapacity)
    internal var prevAngle = FloatArray(initialCapacity)
    internal var vx = FloatArray(initialCapacity)
    internal var vy = FloatArray(initialCapacity)
    internal var omega = FloatArray(initialCapacity)
    internal var invMass = FloatArray(initialCapacity)

    // How far the solver has moved each body this substep, by position and angle: what its velocity changes by.
    private var movedX = FloatArray(initialCapacity)
    private var movedY = FloatArray(initialCapacity)
    private var turned = FloatArray(initialCapacity)
    internal var invInertia = FloatArray(initialCapacity)
    internal var kind = IntArray(initialCapacity)
    internal var alive = BooleanArray(initialCapacity)

    // Sleeping: whether each body is asleep, how long it has been still, which island it fell asleep
    // with, and its inverse mass and inertia while it sleeps, when the solver sees 0 for both.
    internal var asleep = BooleanArray(initialCapacity)
    internal var stillFor = FloatArray(initialCapacity)
    internal var island = IntArray(initialCapacity)
    private var sleepingInvMass = FloatArray(initialCapacity)
    private var sleepingInvInertia = FloatArray(initialCapacity)

    /** How many bodies are asleep. */
    var sleepingCount: Int = 0
        private set

    /** What each body is made of, around its centre of mass; none for a body made with [add]. */
    internal var shapes = arrayOfNulls<Array<Shape>>(initialCapacity)

    /** How far each body's shapes reach from its centre of mass: a circle round it holds them all. */
    internal var reach = FloatArray(initialCapacity)

    /** Each body's [Material], spread over three arrays. */
    internal var friction = FloatArray(initialCapacity)
    internal var staticFriction = FloatArray(initialCapacity)
    internal var restitution = FloatArray(initialCapacity)

    // The cosine and sine of each body's angle, kept up to date whenever it turns: the solver's way to
    // turn a shape's points into the world without a call to cos and sin for every one.
    internal var cos = FloatArray(initialCapacity)
    internal var sin = FloatArray(initialCapacity)

    // Where a kinematic body has been told to be at the end of the step, if [hasTarget].
    private var targetX = FloatArray(initialCapacity)
    private var targetY = FloatArray(initialCapacity)
    private var targetAngle = FloatArray(initialCapacity)
    private var hasTarget = BooleanArray(initialCapacity)

    private var free = IntArray(8)
    private var freeCount = 0

    /** One past the highest handle ever handed out: loop `0 until end` and skip what isn't [isAlive]. */
    var end: Int = 0
        private set

    /** How many bodies are alive. */
    val count: Int get() = end - freeCount

    /**
     * Adds a body of [kind] at ([x], [y]), turned by [angle] radians, at rest, and returns its handle.
     * Its [mass] and its moment of [inertia] (how hard it is to turn, in mass times units squared) only
     * matter to a [BodyKind.DYNAMIC] body. It has no shape, so nothing touches it: most bodies are
     * better made from shapes, which work both out from their size.
     */
    fun add(x: Float, y: Float, angle: Float = 0f, mass: Float = 1f, inertia: Float = 1f, kind: BodyKind = BodyKind.DYNAMIC): Int {
        require(x.isFinite() && y.isFinite() && angle.isFinite()) { "a body can't start at ($x, $y), turned $angle" }
        require(mass > 0f && mass.isFinite()) { "mass is $mass: it must be finite and more than 0" }
        require(inertia > 0f && inertia.isFinite()) { "inertia is $inertia: it must be finite and more than 0" }
        val b = if (freeCount > 0) free[--freeCount] else {
            if (end == this.x.size) grow()
            end++
        }
        this.x[b] = x
        this.y[b] = y
        this.angle[b] = angle
        prevX[b] = x
        prevY[b] = y
        prevAngle[b] = angle
        vx[b] = 0f
        vy[b] = 0f
        omega[b] = 0f
        this.kind[b] = kind.ordinal
        val moves = kind == BodyKind.DYNAMIC
        invMass[b] = if (moves) 1f / mass else 0f
        invInertia[b] = if (moves) 1f / inertia else 0f
        hasTarget[b] = false
        alive[b] = true
        asleep[b] = false
        stillFor[b] = 0f
        shapes[b] = NO_SHAPES
        reach[b] = 0f
        setMaterial(b, Material.DEFAULT)
        cos[b] = kotlin.math.cos(angle)
        sin[b] = kotlin.math.sin(angle)
        return b
    }

    /**
     * Adds a body of [kind] made of [shapes] at [density], and returns its handle. The shapes' origin,
     * the point (0, 0) they were made around, goes at ([x], [y]), turned by [angle]; the body itself is
     * at its centre of mass, where it balances and turns about. Mass and inertia come from the shapes'
     * areas and the [density]. Its [material] says how it grips and bounces.
     */
    fun add(
        shapes: List<Shape>,
        x: Float,
        y: Float,
        angle: Float = 0f,
        density: Float = DENSITY,
        material: Material = Material.DEFAULT,
        kind: BodyKind = BodyKind.DYNAMIC,
    ): Int {
        require(shapes.isNotEmpty()) { "a body needs at least one shape" }
        require(density > 0f && density.isFinite()) { "density is $density: it must be finite and more than 0" }
        val mass = MassProperties(shapes, density)
        val c = kotlin.math.cos(angle)
        val s = kotlin.math.sin(angle)
        val b = add(x + c * mass.centerX - s * mass.centerY, y + s * mass.centerX + c * mass.centerY, angle, mass.mass, mass.inertia, kind)
        val placed = Array(shapes.size) { shapes[it].moved(-mass.centerX, -mass.centerY) }
        this.shapes[b] = placed
        var far = 0f
        for (shape in placed) for (i in 0 until shape.vertexCount) {
            far = maxOf(far, sqrt(shape.vx[i] * shape.vx[i] + shape.vy[i] * shape.vy[i]) + shape.radius)
        }
        reach[b] = far
        setMaterial(b, material)
        return b
    }

    /** Makes body [b] grip and bounce like [material]. */
    fun setMaterial(b: Int, material: Material) {
        friction[b] = material.friction
        staticFriction[b] = material.staticFriction
        restitution[b] = material.restitution
    }

    /** How many shapes body [b] is made of. */
    fun shapeCount(b: Int): Int = shapes[b]!!.size

    /**
     * Shape [i] of body [b], around the body's centre of mass: a point (sx, sy) of it is in the world
     * at [worldX], [worldY].
     */
    fun shape(b: Int, i: Int): Shape = shapes[b]!![i]

    /** Where the point ([localX], [localY]) of body [b], measured from its centre of mass, is in the world. */
    fun worldX(b: Int, localX: Float, localY: Float): Float = x[b] + cos[b] * localX - sin[b] * localY

    fun worldY(b: Int, localX: Float, localY: Float): Float = y[b] + sin[b] * localX + cos[b] * localY

    /** Where the world point ([wx], [wy]) is on body [b], measured from its centre of mass. */
    fun localX(b: Int, wx: Float, wy: Float): Float = cos[b] * (wx - x[b]) + sin[b] * (wy - y[b])

    fun localY(b: Int, wx: Float, wy: Float): Float = -sin[b] * (wx - x[b]) + cos[b] * (wy - y[b])

    /** Whether the world point ([wx], [wy]) is inside one of body [b]'s shapes. */
    fun contains(b: Int, wx: Float, wy: Float): Boolean {
        val lx = localX(b, wx, wy)
        val ly = localY(b, wx, wy)
        for (shape in shapes[b]!!) if (shape.contains(lx, ly)) return true
        return false
    }

    /** Removes body [b]; its handle may be handed out again. Removing a dead body does nothing. */
    fun remove(b: Int) {
        if (!isAlive(b)) return
        // what rested on it wakes and falls: a body that sleeps did so with everything it touched, and
        // a static or kinematic one may hold up any sleeping body
        if (kind[b] == DYNAMIC) wake(b) else wakeAll()
        alive[b] = false
        invMass[b] = 0f
        invInertia[b] = 0f
        if (freeCount == free.size) free = free.copyOf(free.size * 2)
        free[freeCount++] = b
    }

    /** Removes every body. Handles start from 0 again. */
    fun clear() {
        alive.fill(false, 0, end)
        asleep.fill(false, 0, end)
        sleepingCount = 0
        end = 0
        freeCount = 0
    }

    fun isAlive(b: Int): Boolean = b in 0 until end && alive[b]

    fun kind(b: Int): BodyKind = BodyKind.entries[kind[b]]

    /** Where body [b]'s centre of mass is. */
    fun x(b: Int): Float = x[b]

    fun y(b: Int): Float = y[b]

    /** How far body [b] is turned, in radians, clockwise on screen. It isn't wrapped: two turns is 4π. */
    fun angle(b: Int): Float = angle[b]

    /** Body [b]'s velocity, in units per second. */
    fun velocityX(b: Int): Float = vx[b]

    fun velocityY(b: Int): Float = vy[b]

    /** How fast body [b] turns, in radians per second, clockwise on screen. */
    fun angularVelocity(b: Int): Float = omega[b]

    /**
     * Whether body [b] is asleep: it and everything it touched were still for
     * [PhysicsConfig.timeToSleep], so it isn't moved or solved until something wakes it. See [wake].
     */
    fun isAsleep(b: Int): Boolean = asleep[b]

    /**
     * Wakes body [b] and every body that fell asleep with it: its island, everything it touched or was
     * joined to. A body wakes by itself when an awake one hits it, when it's grabbed, given a velocity,
     * moved, or joined, and when what holds it up is removed; call this when you change the world some
     * other way, such as removing a particle it rests on. Waking an awake body does nothing.
     */
    fun wake(b: Int) {
        if (!isAlive(b) || !asleep[b]) return
        val group = island[b]
        for (other in 0 until end) if (asleep[other] && island[other] == group) wakeOne(other)
    }

    /** Wakes every sleeping body. */
    fun wakeAll() {
        if (sleepingCount == 0) return
        for (b in 0 until end) if (asleep[b]) wakeOne(b)
    }

    private fun wakeOne(b: Int) {
        asleep[b] = false
        sleepingCount--
        stillFor[b] = 0f
        invMass[b] = sleepingInvMass[b]
        invInertia[b] = sleepingInvInertia[b]
        prevX[b] = x[b]
        prevY[b] = y[b]
        prevAngle[b] = angle[b]
        movedX[b] = 0f
        movedY[b] = 0f
        turned[b] = 0f
        // Woken after this substep's bodies moved, it moves as they did: gravity, from rest. Without
        // that, it would sit out the substep's gravity while the solver still pushed it up against it,
        // and come out moving up: a box woken under a load jolts it.
        if (inSubstep) {
            vx[b] = substepGravityX * substepLength
            vy[b] = substepGravityY * substepLength
            x[b] += vx[b] * substepLength
            y[b] += vy[b] * substepLength
        }
    }

    // While a substep is being solved: how long it is, and its gravity, for a body woken in it.
    internal var inSubstep = false
    private var substepLength = 0f
    private var substepGravityX = 0f
    private var substepGravityY = 0f

    /**
     * Puts body [b] to sleep with the rest of [group], its island: it stops where it is, and the solver
     * sees nothing that can move it, so its contacts with other sleeping bodies and the solids aren't
     * even looked for.
     */
    internal fun sleep(b: Int, group: Int) {
        if (asleep[b]) return
        asleep[b] = true
        sleepingCount++
        island[b] = group
        vx[b] = 0f
        vy[b] = 0f
        omega[b] = 0f
        sleepingInvMass[b] = invMass[b]
        sleepingInvInertia[b] = invInertia[b]
        invMass[b] = 0f
        invInertia[b] = 0f
    }

    /** Whether body [b] can push a sleeping body awake: an awake dynamic body, or a kinematic one that moves. */
    internal fun isMoving(b: Int): Boolean = when (kind[b]) {
        DYNAMIC -> !asleep[b]
        KINEMATIC -> vx[b] != 0f || vy[b] != 0f || omega[b] != 0f
        else -> false
    }

    /** 0 for a body that nothing moves: a static or kinematic one. A sleeping body keeps its own. */
    fun inverseMass(b: Int): Float = if (asleep[b]) sleepingInvMass[b] else invMass[b]

    fun inverseInertia(b: Int): Float = if (asleep[b]) sleepingInvInertia[b] else invInertia[b]

    /** Gives body [b] a velocity of ([vx], [vy]) units per second. A static body stays still. */
    fun setVelocity(b: Int, vx: Float, vy: Float) {
        if (kind[b] == STATIC) return
        wake(b)
        this.vx[b] = vx
        this.vy[b] = vy
    }

    /** Sets how fast body [b] turns, in radians per second. A static body stays still. */
    fun setAngularVelocity(b: Int, omega: Float) {
        if (kind[b] == STATIC) return
        wake(b)
        this.omega[b] = omega
    }

    /** Puts body [b] at ([x], [y]), turned by [angle], keeping its velocity: a teleport, not a move. */
    fun moveTo(b: Int, x: Float, y: Float, angle: Float = this.angle[b]) {
        // what rested on a moved static or kinematic body has nothing under it now
        if (kind[b] == DYNAMIC) wake(b) else wakeAll()
        this.x[b] = x
        this.y[b] = y
        this.angle[b] = angle
        cos[b] = kotlin.math.cos(angle)
        sin[b] = kotlin.math.sin(angle)
        prevX[b] = x
        prevY[b] = y
        prevAngle[b] = angle
    }

    /**
     * Tells kinematic body [b] to be at ([x], [y]), turned by [angle], at the end of the next step. It
     * gets there at a steady velocity, which it pushes things with, and keeps that velocity after
     * unless told again. Call it every step to have it follow a path exactly.
     */
    fun moveKinematic(b: Int, x: Float, y: Float, angle: Float = this.angle[b]) {
        require(kind[b] == KINEMATIC) { "body $b is ${kind(b)}: only a kinematic body moves where it's told" }
        targetX[b] = x
        targetY[b] = y
        targetAngle[b] = angle
        hasTarget[b] = true
    }

    /** Body [b]'s velocity at the point ([px], [py]) of it, along x: its own, plus what its turning adds there. */
    fun pointVelocityX(b: Int, px: Float, py: Float): Float = vx[b] - omega[b] * (py - y[b])

    fun pointVelocityY(b: Int, px: Float, py: Float): Float = vy[b] + omega[b] * (px - x[b])

    /**
     * Starts a step of [dt] seconds: every kinematic body told where to go is given the velocity that
     * gets it there.
     */
    internal fun beginStep(dt: Float) {
        for (b in 0 until end) {
            if (!hasTarget[b] || !alive[b]) continue
            hasTarget[b] = false
            vx[b] = (targetX[b] - x[b]) / dt
            vy[b] = (targetY[b] - y[b]) / dt
            omega[b] = (targetAngle[b] - angle[b]) / dt
        }
    }

    /**
     * Moves every body that moves on by a substep of [h] seconds: a dynamic one gains gravity ([gx],
     * [gy]) and loses a little to damping, [keep], and none moves further than [maxStep].
     */
    internal fun integrate(h: Float, keep: Float, gx: Float, gy: Float, maxStep: Float) {
        inSubstep = true
        substepLength = h
        substepGravityX = gx
        substepGravityY = gy
        for (b in 0 until end) {
            if (!alive[b] || asleep[b]) continue
            val k = kind[b]
            if (k == STATIC) continue
            prevX[b] = x[b]
            prevY[b] = y[b]
            prevAngle[b] = angle[b]
            if (k == DYNAMIC) {
                vx[b] = (vx[b] + gx * h) * keep
                vy[b] = (vy[b] + gy * h) * keep
                omega[b] *= keep
                val move = sqrt(vx[b] * vx[b] + vy[b] * vy[b]) * h
                if (move > maxStep) {
                    vx[b] *= maxStep / move
                    vy[b] *= maxStep / move
                }
            }
            x[b] += vx[b] * h
            y[b] += vy[b] * h
            angle[b] += omega[b] * h
            cos[b] = kotlin.math.cos(angle[b])
            sin[b] = kotlin.math.sin(angle[b])
            movedX[b] = 0f
            movedY[b] = 0f
            turned[b] = 0f
        }
    }

    /**
     * Ends a substep of [h] seconds: each dynamic body's velocity becomes how far it really moved over
     * [h]. That's the velocity it moved by plus what the solver added to the move, which is the same
     * as `(x - prevX) / h` but doesn't lose the velocity's low digits to a far-off position.
     */
    internal fun updateVelocities(h: Float) {
        for (b in 0 until end) {
            if (!alive[b] || kind[b] != DYNAMIC || asleep[b]) continue
            vx[b] += movedX[b] / h
            vy[b] += movedY[b] / h
            omega[b] += turned[b] / h
        }
    }

    /**
     * Moves body [b] as if pushed by ([px], [py]) at the point ([rx], [ry]) from its centre: along by
     * the push over its mass, and turned by the push's torque over its inertia. A positional impulse,
     * the solver's one way of moving a body.
     */
    internal fun shove(b: Int, px: Float, py: Float, rx: Float, ry: Float) {
        val w = invMass[b]
        if (w == 0f) return
        val turn = (rx * py - ry * px) * invInertia[b]
        x[b] += px * w
        y[b] += py * w
        if (turn != 0f) {
            angle[b] += turn
            cos[b] = kotlin.math.cos(angle[b])
            sin[b] = kotlin.math.sin(angle[b])
        }
        movedX[b] += px * w
        movedY[b] += py * w
        turned[b] += turn
    }

    /**
     * Turns body [b] as if twisted by the angular push [amount]: by the amount over its inertia. The
     * solver's way of turning a body without moving it, for a joint's angle.
     */
    internal fun twist(b: Int, amount: Float) {
        val turn = amount * invInertia[b]
        if (turn == 0f) return
        angle[b] += turn
        cos[b] = kotlin.math.cos(angle[b])
        sin[b] = kotlin.math.sin(angle[b])
        turned[b] += turn
    }

    /**
     * Changes body [b]'s velocity as if struck by the impulse ([ix], [iy]) at the point ([rx], [ry]) from
     * its centre: along by the impulse over its mass, and its spin by the impulse's torque over its
     * inertia. The velocity pass's one way of changing a body's speed.
     */
    internal fun kick(b: Int, ix: Float, iy: Float, rx: Float, ry: Float) {
        val w = invMass[b]
        if (w == 0f) return
        vx[b] += ix * w
        vy[b] += iy * w
        omega[b] += (rx * iy - ry * ix) * invInertia[b]
    }

    /**
     * How hard body [b] is to move at ([rx], [ry]) from its centre along the unit ([nx], [ny]): its
     * inverse mass plus what turning adds. A push there moves the point by the push times this.
     */
    internal fun inverseMassAt(b: Int, rx: Float, ry: Float, nx: Float, ny: Float): Float {
        val turn = rx * ny - ry * nx
        return invMass[b] + turn * turn * invInertia[b]
    }

    private fun grow() {
        val size = x.size * 2
        x = x.copyOf(size)
        y = y.copyOf(size)
        angle = angle.copyOf(size)
        prevX = prevX.copyOf(size)
        prevY = prevY.copyOf(size)
        prevAngle = prevAngle.copyOf(size)
        vx = vx.copyOf(size)
        vy = vy.copyOf(size)
        omega = omega.copyOf(size)
        invMass = invMass.copyOf(size)
        movedX = movedX.copyOf(size)
        movedY = movedY.copyOf(size)
        turned = turned.copyOf(size)
        invInertia = invInertia.copyOf(size)
        kind = kind.copyOf(size)
        alive = alive.copyOf(size)
        asleep = asleep.copyOf(size)
        stillFor = stillFor.copyOf(size)
        island = island.copyOf(size)
        sleepingInvMass = sleepingInvMass.copyOf(size)
        sleepingInvInertia = sleepingInvInertia.copyOf(size)
        targetX = targetX.copyOf(size)
        targetY = targetY.copyOf(size)
        targetAngle = targetAngle.copyOf(size)
        hasTarget = hasTarget.copyOf(size)
        shapes = shapes.copyOf(size)
        reach = reach.copyOf(size)
        friction = friction.copyOf(size)
        staticFriction = staticFriction.copyOf(size)
        restitution = restitution.copyOf(size)
        cos = cos.copyOf(size)
        sin = sin.copyOf(size)
    }

    companion object {
        /**
         * How heavy a body's shapes are per unit squared, unless told otherwise: a 32-unit crate weighs
         * about 50, as much as a blob.
         */
        const val DENSITY = 0.05f

        private val NO_SHAPES = emptyArray<Shape>()

        // BodyKind's ordinals, as constants for the hot loops.
        internal const val STATIC = 0
        internal const val DYNAMIC = 1
        internal const val KINEMATIC = 2
    }
}
