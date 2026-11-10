package klyph.physics

import kotlin.math.exp
import kotlin.math.sqrt

/**
 * Everything that moves, and the one way to move time: [step]. The world owns its stores; bodies,
 * particles and constraints are `Int` handles into them, so a step touches plain arrays and allocates
 * nothing. The same calls in the same order give the same results, to the bit.
 */
class PhysicsWorld(val config: PhysicsConfig = PhysicsConfig()) {
    val particles = Particles()

    /** The length of the last substep, in seconds: what velocities in units per second are measured against. */
    var substepLength: Float = 1f / 60f / config.substeps
        private set

    /** How many times [step] has run. */
    var stepCount: Long = 0
        private set

    /**
     * Advances the world by [dt] seconds, in [PhysicsConfig.substeps] substeps. Each one moves every
     * particle, then [PhysicsConfig.iterations] times solves the constraints and collides.
     */
    fun step(dt: Float) {
        require(dt > 0f && dt.isFinite()) { "dt is $dt: it must be a finite time more than 0" }
        val h = dt / config.substeps
        substepLength = h
        val keep = exp(-config.damping * h)
        repeat(config.substeps) {
            integrate(h, keep)
        }
        stepCount++
    }

    /** Velocity of particle [p] in units per second, over the last substep. */
    fun velocityX(p: Int): Float = particles.movedX(p) / substepLength

    fun velocityY(p: Int): Float = particles.movedY(p) / substepLength

    /** Gives particle [p] a velocity of ([vx], [vy]) units per second. */
    fun setVelocity(p: Int, vx: Float, vy: Float) = particles.setVelocity(p, vx, vy, substepLength)

    /**
     * Moves every particle that can move: it repeats its last move, a little weaker for [keep], plus
     * gravity, capped at [PhysicsConfig.maxStep].
     */
    private fun integrate(h: Float, keep: Float) {
        val ps = particles
        val x = ps.x
        val y = ps.y
        val prevX = ps.prevX
        val prevY = ps.prevY
        val invMass = ps.invMass
        val gx = config.gravity.x * h * h
        val gy = config.gravity.y * h * h
        val maxStep = config.maxStep
        for (p in 0 until ps.end) {
            if (invMass[p] == 0f) continue
            var dx = (x[p] - prevX[p]) * keep + gx
            var dy = (y[p] - prevY[p]) * keep + gy
            val length = sqrt(dx * dx + dy * dy)
            if (length > maxStep) {
                dx *= maxStep / length
                dy *= maxStep / length
            }
            prevX[p] = x[p]
            prevY[p] = y[p]
            x[p] += dx
            y[p] += dy
        }
    }
}
