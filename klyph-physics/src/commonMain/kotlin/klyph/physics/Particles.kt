package klyph.physics

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
     * grain of sand; one that doesn't only meets the solids.
     */
    fun add(x: Float, y: Float, mass: Float = 1f, radius: Float = 0f, collides: Boolean = false): Int {
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
     * Moves particle [p] out of a surface: [depth] units along the unit normal ([nx], [ny]) pointing out
     * of it, and drops the speed it had going into the surface while keeping the rest, so it slides.
     */
    internal fun pushOut(p: Int, nx: Float, ny: Float, depth: Float) {
        var vx = x[p] - prevX[p]
        var vy = y[p] - prevY[p]
        x[p] += nx * depth
        y[p] += ny * depth
        val into = vx * nx + vy * ny
        if (into < 0f) {
            vx -= into * nx
            vy -= into * ny
        }
        prevX[p] = x[p] - vx
        prevY[p] = y[p] - vy
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
    }

    companion object {
        /** A mass for a particle nothing can move, such as the top of a hanging rope. */
        const val STATIC = Float.POSITIVE_INFINITY

        internal const val ALIVE = 1
        internal const val COLLIDES = 2
    }
}
