package klyph.physics

import kotlin.math.min
import kotlin.math.sqrt

/**
 * Every particle in a [PhysicsWorld], as parallel arrays. A particle is an `Int` handle: an index into
 * them. A removed particle's handle is handed out again by the next [add], so hold on to handles only
 * while their particles are alive.
 *
 * Velocity is implicit, as in Verlet integration: the difference between where a particle is and where
 * it was a substep ago. That's why the velocity setters move the previous position.
 *
 * The arrays grow by doubling when a particle is added, never during a step.
 */
class Particles internal constructor(initialCapacity: Int = 64) {
    internal var x = FloatArray(initialCapacity)
    internal var y = FloatArray(initialCapacity)
    internal var prevX = FloatArray(initialCapacity)
    internal var prevY = FloatArray(initialCapacity)
    internal var invMass = FloatArray(initialCapacity)
    internal var radius = FloatArray(initialCapacity)
    internal var flags = IntArray(initialCapacity)

    /** Whether each particle stood on a floor this step ([Events.NOW]) and the step before ([Events.BEFORE]). */
    internal var contact = IntArray(initialCapacity)

    /** The [Solid.tag]s of the floors each particle stood on this step, or-ed together. */
    internal var floorTag = IntArray(initialCapacity)

    /** The soft body each particle belongs to, or -1 for a loose one. */
    internal var owner = IntArray(initialCapacity)

    /** Each particle's [Material], spread over three arrays. */
    internal var friction = FloatArray(initialCapacity)
    internal var staticFriction = FloatArray(initialCapacity)
    internal var restitution = FloatArray(initialCapacity)

    // Each particle's contact this substep, for [applyFriction]: how far it was pushed out in all, along
    // which normal, with what grip, and against what: nothing that moves (-1), a particle (contactA), or
    // the point `(1 - contactT)·contactA + contactT·contactB` of an edge.
    internal var contactDepth = FloatArray(initialCapacity)
    internal var contactNx = FloatArray(initialCapacity)
    internal var contactNy = FloatArray(initialCapacity)
    internal var contactGrip = FloatArray(initialCapacity)
    internal var contactHold = FloatArray(initialCapacity)
    internal var contactA = IntArray(initialCapacity)
    internal var contactB = IntArray(initialCapacity)
    internal var contactT = FloatArray(initialCapacity)
    private var free = IntArray(16)
    private var freeCount = 0

    /** One past the highest handle ever handed out: loop `0 until end` and skip what isn't [isAlive]. */
    var end: Int = 0
        private set

    /** How many particles are alive. */
    val count: Int get() = end - freeCount

    /**
     * Adds a particle at ([x], [y]) at rest and returns its handle. [mass] may be [STATIC] for one that
     * nothing moves. [radius] is how far it keeps out of things; 0 is a point. A particle that [collides]
     * is kept out of soft bodies and out of every other particle that collides, by their radii, like a
     * grain of sand; one that doesn't only meets the solids. Its [material] says how it grips and bounces.
     */
    fun add(x: Float, y: Float, mass: Float = 1f, radius: Float = 0f, collides: Boolean = false, material: Material = Material.DEFAULT): Int {
        require(mass > 0f) { "mass is $mass: it must be more than 0, or STATIC" }
        require(radius >= 0f) { "radius is $radius: it must not be negative" }
        val p = if (freeCount > 0) free[--freeCount] else {
            if (end == this.x.size) grow()
            end++
        }
        this.x[p] = x
        this.y[p] = y
        prevX[p] = x
        prevY[p] = y
        invMass[p] = if (mass == STATIC) 0f else 1f / mass
        this.radius[p] = radius
        flags[p] = if (collides) ALIVE or COLLIDES else ALIVE
        owner[p] = -1
        contactDepth[p] = 0f
        setMaterial(p, material)
        contact[p] = 0
        floorTag[p] = 0
        return p
    }

    /** Removes particle [p]; its handle may be handed out again. Removing a dead particle does nothing. */
    fun remove(p: Int) {
        if (!isAlive(p)) return
        flags[p] = 0
        invMass[p] = 0f
        if (freeCount == free.size) free = free.copyOf(free.size * 2)
        free[freeCount++] = p
    }

    /** Removes every particle. Handles start from 0 again. */
    fun clear() {
        flags.fill(0, 0, end)
        end = 0
        freeCount = 0
    }

    fun isAlive(p: Int): Boolean = p in 0 until end && flags[p] and ALIVE != 0

    /** Makes particle [p] grip and bounce like [material]. */
    fun setMaterial(p: Int, material: Material) {
        friction[p] = material.friction
        staticFriction[p] = material.staticFriction
        restitution[p] = material.restitution
    }

    /** Whether particle [p] is kept out of bodies and other particles that collide. See [add]. */
    fun collides(p: Int): Boolean = flags[p] and COLLIDES != 0

    fun x(p: Int): Float = x[p]

    fun y(p: Int): Float = y[p]

    /** How far particle [p] moved in the last substep, in units: its velocity per substep. */
    fun movedX(p: Int): Float = x[p] - prevX[p]

    fun movedY(p: Int): Float = y[p] - prevY[p]

    /** 0 for a [STATIC] particle. */
    fun inverseMass(p: Int): Float = invMass[p]

    fun radius(p: Int): Float = radius[p]

    /** Moves particle [p] to ([x], [y]), keeping its velocity. */
    fun moveTo(p: Int, x: Float, y: Float) {
        prevX[p] += x - this.x[p]
        prevY[p] += y - this.y[p]
        this.x[p] = x
        this.y[p] = y
    }

    /** Gives particle [p] a velocity of ([vx], [vy]) units per second, for substeps of [h] seconds. */
    fun setVelocity(p: Int, vx: Float, vy: Float, h: Float) {
        prevX[p] = x[p] - vx * h
        prevY[p] = y[p] - vy * h
    }

    /** Sets where particle [p] is and where it was a substep ago, which gives it its velocity. */
    internal fun set(p: Int, x: Float, y: Float, previousX: Float = x, previousY: Float = y) {
        this.x[p] = x
        this.y[p] = y
        prevX[p] = previousX
        prevY[p] = previousY
    }

    /**
     * Moves particle [p] out of a surface that doesn't move: [depth] units along the unit normal ([nx],
     * [ny]) pointing out of it. Its speed into the surface is dropped, or bounced back times
     * [restitution] when it came in faster than [restingMove] units a substep. The push is remembered
     * with the grip, [friction] and [staticFriction], for [applyFriction] at the end of the substep.
     */
    internal fun pushOut(
        p: Int,
        nx: Float,
        ny: Float,
        depth: Float,
        friction: Float = 0f,
        staticFriction: Float = 0f,
        restitution: Float = 0f,
        restingMove: Float = 0f,
    ) {
        var vx = x[p] - prevX[p]
        var vy = y[p] - prevY[p]
        x[p] += nx * depth
        y[p] += ny * depth
        val into = vx * nx + vy * ny
        touch(p, nx, ny, depth, friction, staticFriction, -1, -1, 0f)
        if (into < 0f) {
            val bounce = if (-into > restingMove) restitution else 0f
            vx -= (1f + bounce) * into * nx
            vy -= (1f + bounce) * into * ny
        }
        prevX[p] = x[p] - vx
        prevY[p] = y[p] - vy
    }

    /**
     * Remembers that particle [p] was pushed [depth] along ([nx], [ny]) by a contact with [grip] and
     * [hold] (its friction and static friction), against particle [a], the edge from [a] to [b] at [t],
     * or nothing that moves when [a] is -1. Pushes add up over the substep; the rest is the last one's.
     */
    internal fun touch(p: Int, nx: Float, ny: Float, depth: Float, grip: Float, hold: Float, a: Int, b: Int, t: Float) {
        contactDepth[p] += depth
        contactNx[p] = nx
        contactNy[p] = ny
        contactGrip[p] = grip
        contactHold[p] = hold
        contactA[p] = a
        contactB[p] = b
        contactT[p] = t
    }

    /**
     * Ends a substep's contacts with their grip. For each particle that was pushed, how far it slid this
     * substep along the surface, past whatever it touched, is undone while that's under the static
     * friction times all it was pushed (the push is what holds it on), and cut by the friction times
     * that otherwise. Coulomb's law, one substep at a time.
     *
     * It runs once a substep, just before the last pass, rather than with each push: a soft body's
     * own constraints tug its particles to and fro between passes, and only what's left near the end
     * is really sliding. The pushes it weighs are the ones since it last ran: the last pass of the
     * substep before, and every pass but the last of this one.
     */
    internal fun applyFriction() {
        for (p in 0 until end) {
            val depth = contactDepth[p]
            if (depth == 0f) continue
            contactDepth[p] = 0f
            val a = contactA[p]
            val b = contactB[p]
            val t = if (b < 0) 0f else contactT[p]
            val s = 1f - t
            var dx = x[p] - prevX[p]
            var dy = y[p] - prevY[p]
            if (a >= 0) {
                dx -= s * (x[a] - prevX[a])
                dy -= s * (y[a] - prevY[a])
            }
            if (b >= 0) {
                dx -= t * (x[b] - prevX[b])
                dy -= t * (y[b] - prevY[b])
            }
            val nx = contactNx[p]
            val ny = contactNy[p]
            val along = dx * nx + dy * ny
            val tx = dx - along * nx
            val ty = dy - along * ny
            val slide = sqrt(tx * tx + ty * ty)
            if (slide < 1e-7f) continue
            val held = if (slide < contactHold[p] * depth) slide else min(slide, contactGrip[p] * depth)
            if (held == 0f) continue
            val ux = -tx / slide
            val uy = -ty / slide
            val wp = invMass[p]
            val wa = if (a >= 0) invMass[a] * s else 0f
            val wb = if (b >= 0) invMass[b] * t else 0f
            val w = if (a < 0) wp else wp + wa * s + wb * t
            if (w == 0f) continue
            val push = held / w
            x[p] += wp * push * ux
            y[p] += wp * push * uy
            if (a >= 0) {
                x[a] -= wa * push * ux
                y[a] -= wa * push * uy
            }
            if (b >= 0) {
                x[b] -= wb * push * ux
                y[b] -= wb * push * uy
            }
        }
    }

    private fun grow() {
        val size = x.size * 2
        x = x.copyOf(size)
        y = y.copyOf(size)
        prevX = prevX.copyOf(size)
        prevY = prevY.copyOf(size)
        invMass = invMass.copyOf(size)
        radius = radius.copyOf(size)
        flags = flags.copyOf(size)
        contact = contact.copyOf(size)
        floorTag = floorTag.copyOf(size)
        owner = owner.copyOf(size)
        friction = friction.copyOf(size)
        staticFriction = staticFriction.copyOf(size)
        restitution = restitution.copyOf(size)
        contactDepth = contactDepth.copyOf(size)
        contactNx = contactNx.copyOf(size)
        contactNy = contactNy.copyOf(size)
        contactGrip = contactGrip.copyOf(size)
        contactHold = contactHold.copyOf(size)
        contactA = contactA.copyOf(size)
        contactB = contactB.copyOf(size)
        contactT = contactT.copyOf(size)
    }

    companion object {
        /** A mass for a particle nothing can move, such as the top of a hanging rope. */
        const val STATIC = Float.POSITIVE_INFINITY

        internal const val ALIVE = 1
        internal const val COLLIDES = 2
    }
}
