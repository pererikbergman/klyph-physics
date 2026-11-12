package klyph.physics

import kotlin.math.sqrt

/**
 * Particles held to points on rigid bodies: a rope tied to a crate, a blob glued to a wheel. Each one
 * pulls its particle and the point of its body together, the XPBD way, as softly as its compliance
 * says, so the particle is dragged along by the body and the body is tugged by the particle, moving
 * and turning it. 0 is a firm hold. Each is an `Int` handle; a removed one's handle is reused, and
 * one whose particle or body has been removed is skipped.
 */
class Attachments internal constructor(private val particles: Particles, private val bodies: RigidBodies) {
    private val slots = Slots()

    // The particle, the body, the point on the body from its centre of mass in its own frame, how
    // soft, and how hard it has pulled this substep.
    private var particle = IntArray(8)
    private var body = IntArray(8)
    private var localX = FloatArray(8)
    private var localY = FloatArray(8)
    private var compliance = FloatArray(8)
    private var lambda = FloatArray(8)

    /** One past the highest handle in use: loop `0 until end` and skip what isn't [isAlive]. */
    val end: Int get() = slots.end

    val count: Int get() = slots.count

    /**
     * Holds particle [p] to rigid body [b] at the point where [p] is now, as softly as [compliance], and
     * returns the attachment's handle.
     */
    fun attach(p: Int, b: Int, compliance: Float = 0f): Int {
        require(particles.isAlive(p)) { "particle $p is not alive" }
        require(bodies.isAlive(b)) { "body $b is not alive" }
        require(compliance >= 0f) { "compliance is $compliance: it must not be negative" }
        bodies.wake(b)
        val a = slots.take()
        if (a >= particle.size) {
            val size = particle.size * 2
            particle = particle.copyOf(size)
            body = body.copyOf(size)
            localX = localX.copyOf(size)
            localY = localY.copyOf(size)
            this.compliance = this.compliance.copyOf(size)
            lambda = lambda.copyOf(size)
        }
        val dx = particles.x(p) - bodies.x[b]
        val dy = particles.y(p) - bodies.y[b]
        val c = bodies.cos[b]
        val s = bodies.sin[b]
        particle[a] = p
        body[a] = b
        localX[a] = c * dx + s * dy
        localY[a] = -s * dx + c * dy
        this.compliance[a] = compliance
        lambda[a] = 0f
        return a
    }

    /** Lets go: attachment [a] no longer holds its particle. */
    fun remove(a: Int) = slots.remove(a)

    fun isAlive(a: Int): Boolean = slots.isAlive(a)

    /** The particle attachment [a] holds, and the body it holds it to. */
    fun particle(a: Int): Int = particle[a]

    fun body(a: Int): Int = body[a]

    /** Where attachment [a] holds its particle: the point on its body, in the world now. */
    fun pointX(a: Int): Float = bodies.worldX(body[a], localX[a], localY[a])

    fun pointY(a: Int): Float = bodies.worldY(body[a], localX[a], localY[a])

    /** Forgets how hard each has pulled: the start of a substep. */
    internal fun beginSubstep() {
        lambda.fill(0f, 0, slots.end)
    }

    /** One pass over every attachment, for a substep of [h] seconds. */
    internal fun solve(h: Float) {
        val hh = h * h
        for (a in 0 until slots.end) if (slots.isAlive(a)) solve(a, hh)
    }

    private fun solve(a: Int, hh: Float) {
        val p = particle[a]
        val b = body[a]
        if (!particles.isAlive(p) || !bodies.isAlive(b)) return
        val rx = bodies.cos[b] * localX[a] - bodies.sin[b] * localY[a]
        val ry = bodies.sin[b] * localX[a] + bodies.cos[b] * localY[a]
        val dx = particles.x[p] - bodies.x[b] - rx
        val dy = particles.y[p] - bodies.y[b] - ry
        val length = sqrt(dx * dx + dy * dy)
        if (length < 1e-6f) return
        val nx = dx / length
        val ny = dy / length
        val wp = particles.invMass[p]
        val softness = compliance[a] / hh
        val w = wp + bodies.inverseMassAt(b, rx, ry, nx, ny) + softness
        if (w == 0f) return
        val change = (-length - softness * lambda[a]) / w
        lambda[a] += change
        particles.x[p] += wp * change * nx
        particles.y[p] += wp * change * ny
        bodies.shove(b, -change * nx, -change * ny, rx, ry)
    }
}
