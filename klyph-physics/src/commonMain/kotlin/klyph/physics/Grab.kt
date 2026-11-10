package klyph.physics

import kotlin.math.sqrt

/**
 * A hand in the world, like a mouse's: it takes hold of one particle at a time and pulls it towards a
 * point with a soft pin. Use it through [PhysicsWorld.grab], [PhysicsWorld.moveGrab] and
 * [PhysicsWorld.release].
 *
 * However far the point jumps, the pin never pulls from further than [maxPull] away: at the start of
 * every step its target is put back within [maxPull] of the particle. So a mouse flung across the
 * screen in one frame drags a body along quickly, but never yanks one particle out of it.
 */
class Grab internal constructor(private val world: PhysicsWorld) {
    /** The particle held, or -1 when nothing is. */
    var particle: Int = -1
        private set

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

    val isHolding: Boolean get() = particle >= 0

    /**
     * Takes hold of the movable particle nearest ([x], [y]) and no further than [reach] from it, with a
     * pin as soft as [compliance]. Lets go of whatever it held first. Returns the particle, or -1 when
     * there is none in reach.
     */
    fun grab(x: Float, y: Float, reach: Float = REACH, compliance: Float = COMPLIANCE): Int {
        release()
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
    }

    /** Lets go if the particle or its pin has gone, and otherwise aims the pin at the hand, no further than [maxPull]. */
    internal fun beginStep() {
        if (particle < 0) return
        if (!stillHeld()) {
            particle = -1
            pin = -1
            return
        }
        val ps = world.particles
        val x = ps.x(particle)
        val y = ps.y(particle)
        var dx = targetX - x
        var dy = targetY - y
        val length = sqrt(dx * dx + dy * dy)
        if (length > maxPull) {
            dx *= maxPull / length
            dy *= maxPull / length
        }
        world.constraints.movePin(pin, x + dx, y + dy)
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
