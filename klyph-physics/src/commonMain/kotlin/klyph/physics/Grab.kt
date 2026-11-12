package klyph.physics

import kotlin.math.sqrt

/**
 * A hand in the world, like a mouse's: it takes hold of one thing at a time and pulls it towards a
 * point with a soft pin. That's a particle, or a rigid body by the point of it that was grabbed, so a
 * crate picked up by its corner hangs from that corner. Use it through [PhysicsWorld.grab],
 * [PhysicsWorld.moveGrab] and [PhysicsWorld.release].
 *
 * However far the point jumps, the pin never pulls from further than [maxPull] away: at the start of
 * every step its target is put back within [maxPull] of what it holds. So a mouse flung across the
 * screen in one frame drags a body along quickly, but never yanks one particle out of it.
 */
class Grab internal constructor(private val world: PhysicsWorld) {
    /** The particle held, or -1 when none is. */
    var particle: Int = -1
        private set

    /** The rigid body held, or -1 when none is. */
    var body: Int = -1
        private set

    // Where on [body] it's held, from its centre of mass in its own frame, and how soft the hold is.
    private var localX = 0f
    private var localY = 0f
    private var bodyCompliance = 0f
    private var lambda = 0f

    // Where the pin pulls the held point towards this step: the hand, no further than [maxPull] away.
    private var pullX = 0f
    private var pullY = 0f

    /** Where the hand is: the point the particle is pulled towards. */
    var targetX: Float = 0f
        private set
    var targetY: Float = 0f
        private set

    /** The furthest from the particle that the pin pulls from, in world units. */
    var maxPull: Float = MAX_PULL
        set(value) {
            require(value > 0f) { "maxPull is $value: it must be more than 0" }
            field = value
        }

    private var pin = -1

    val isHolding: Boolean get() = particle >= 0 || body >= 0

    /** Where the point held is in the world: the particle, or the grabbed point of the body. */
    val heldX: Float get() = if (body >= 0) world.rigidBodies.worldX(body, localX, localY) else world.particles.x(particle)

    val heldY: Float get() = if (body >= 0) world.rigidBodies.worldY(body, localX, localY) else world.particles.y(particle)

    /**
     * Takes hold of what is at ([x], [y]), letting go of whatever it held first: a dynamic rigid body
     * whose shape it is inside, by that point, or else the movable particle nearest it, no further than
     * [reach] away. Holds it with a pin as soft as [compliance], for a particle; a body's pin is that
     * soft per unit of its mass, so a heavy crate follows as well as a light one. Returns the particle,
     * or -1 when it took a body (see [body]) or nothing.
     */
    fun grab(x: Float, y: Float, reach: Float = REACH, compliance: Float = COMPLIANCE): Int {
        release()
        val bodies = world.rigidBodies
        for (b in bodies.end - 1 downTo 0) {
            if (!bodies.isAlive(b) || bodies.kind(b) != BodyKind.DYNAMIC || !bodies.contains(b, x, y)) continue
            bodies.wake(b)
            body = b
            localX = bodies.localX(b, x, y)
            localY = bodies.localY(b, x, y)
            bodyCompliance = compliance * bodies.inverseMass(b)
            targetX = x
            targetY = y
            pullX = x
            pullY = y
            return -1
        }
        val ps = world.particles
        var best = -1
        var bestSquared = reach * reach
        for (p in 0 until ps.end) {
            if (!ps.isAlive(p) || ps.inverseMass(p) == 0f) continue
            val dx = ps.x(p) - x
            val dy = ps.y(p) - y
            val squared = dx * dx + dy * dy
            if (squared <= bestSquared) {
                best = p
                bestSquared = squared
            }
        }
        if (best < 0) return -1
        particle = best
        targetX = x
        targetY = y
        pin = world.constraints.pin(best, ps.x(best), ps.y(best), compliance)
        return best
    }

    /** Moves the hand to ([x], [y]); the held particle follows from the next step. */
    fun moveTo(x: Float, y: Float) {
        targetX = x
        targetY = y
    }

    /** Lets go. Does nothing when nothing is held. */
    fun release() {
        if (pin >= 0 && stillHeld()) world.constraints.removePin(pin)
        particle = -1
        pin = -1
        body = -1
    }

    /**
     * Lets go if what it held has gone, and otherwise aims the pin at the hand, no further than
     * [maxPull] from the point held.
     */
    internal fun beginStep() {
        if (body >= 0) {
            if (!world.rigidBodies.isAlive(body) || world.rigidBodies.inverseMass(body) == 0f) {
                body = -1
                return
            }
            aim(heldX, heldY)
            return
        }
        if (particle < 0) return
        if (!stillHeld()) {
            particle = -1
            pin = -1
            return
        }
        aim(world.particles.x(particle), world.particles.y(particle))
        world.constraints.movePin(pin, pullX, pullY)
    }

    /** Sets the point pulled towards: the hand, or [maxPull] from ([x], [y]) towards it if it's further. */
    private fun aim(x: Float, y: Float) {
        var dx = targetX - x
        var dy = targetY - y
        val length = sqrt(dx * dx + dy * dy)
        if (length > maxPull) {
            dx *= maxPull / length
            dy *= maxPull / length
        }
        pullX = x + dx
        pullY = y + dy
    }

    /** Forgets how hard the body's pin has pulled: the start of a substep. */
    internal fun beginSubstep() {
        lambda = 0f
    }

    /**
     * One pass of a held body's pin, for a substep of [h] seconds: the held point pulled towards
     * where it's aimed, the XPBD way, moving and turning the body. A particle's pin is solved with the
     * other constraints.
     */
    internal fun solve(h: Float) {
        val b = body
        if (b < 0) return
        val bodies = world.rigidBodies
        val rx = bodies.cos[b] * localX - bodies.sin[b] * localY
        val ry = bodies.sin[b] * localX + bodies.cos[b] * localY
        val dx = bodies.x[b] + rx - pullX
        val dy = bodies.y[b] + ry - pullY
        val length = sqrt(dx * dx + dy * dy)
        if (length < 1e-6f) return
        val nx = dx / length
        val ny = dy / length
        val softness = bodyCompliance / (h * h)
        val change = (-length - softness * lambda) / (bodies.inverseMassAt(b, rx, ry, nx, ny) + softness)
        lambda += change
        bodies.shove(b, change * nx, change * ny, rx, ry)
    }

    /** Whether the pin is still the one made in [grab], on the particle it was made for. */
    private fun stillHeld(): Boolean {
        val cs = world.constraints
        return world.particles.isAlive(particle) && cs.isPinAlive(pin) && cs.pinnedParticle(pin) == particle
    }

    companion object {
        /** How far from the point a particle can be and still be taken hold of. */
        const val REACH = 24f

        /** How soft the hand's pin is: firm enough to lift a blob, soft enough to give a little. */
        const val COMPLIANCE = 0.00005f

        /**
         * The furthest the pin pulls from, by default: less than a 16-unit tile. Pulled from much
         * further, a particle can be dragged more than halfway into a floor in one step, and pushed out
         * of its far side, tearing its body in two.
         */
        const val MAX_PULL = 12f
    }
}
