package klyph.physics

import kotlin.math.sqrt

/**
 * The rules that hold particles together, solved the XPBD way. Each one has a compliance: how far it
 * gives per unit of force, in world units. 0 is rigid. Because each constraint also remembers how hard
 * it has pushed this substep (its lambda), a compliance means the same softness however many substeps
 * and iterations the world runs.
 *
 * Four kinds, each an `Int` handle in its own store:
 * - **distance**: keeps two particles [rest] apart, pushing and pulling
 * - **rope**: the same, but only pulls, so it can go slack
 * - **pin**: holds a particle at a point in the world, which can move (that's how dragging works)
 * - **area**: keeps the area inside a ring of particles, so a squashed body bulges out elsewhere
 *
 * A constraint on a particle that has been removed is skipped. Removed constraints' handles are reused.
 */
class Constraints internal constructor(private val particles: Particles) {
    // Distances and ropes.
    private var linkA = IntArray(16)
    private var linkB = IntArray(16)
    private var linkRest = FloatArray(16)
    private var linkCompliance = FloatArray(16)
    private var linkLambda = FloatArray(16)
    private var linkFlags = IntArray(16)
    private val links = Slots()

    // Pins.
    private var pinParticle = IntArray(8)
    private var pinX = FloatArray(8)
    private var pinY = FloatArray(8)
    private var pinCompliance = FloatArray(8)
    private var pinLambda = FloatArray(8)
    private val pins = Slots()

    // Areas: each one is a run of `areaLength` particles in `ringParticles`, starting at `areaStart`.
    private var areaStart = IntArray(4)
    private var areaLength = IntArray(4)
    private var areaRest = FloatArray(4)
    private var areaCompliance = FloatArray(4)
    private var areaLambda = FloatArray(4)
    private var areaSign = FloatArray(4)
    private val areas = Slots()
    private var ringParticles = IntArray(64)
    private var ringUsed = 0
    private var scratchX = FloatArray(32)
    private var scratchY = FloatArray(32)

    /** How many distance and rope constraints are alive. */
    val linkCount: Int get() = links.count

    val pinCount: Int get() = pins.count

    val areaCount: Int get() = areas.count

    /**
     * Keeps particles [a] and [b] [rest] apart, pushing and pulling, and returns its handle. Without a
     * [rest], it's as far apart as they are now.
     */
    fun distance(a: Int, b: Int, compliance: Float = 0f, rest: Float = Float.NaN): Int =
        addLink(a, b, compliance, rest, 0)

    /** Like [distance], but only pulls: closer than [rest] it goes slack. */
    fun rope(a: Int, b: Int, compliance: Float = 0f, rest: Float = Float.NaN): Int =
        addLink(a, b, compliance, rest, ROPE)

    fun isRope(link: Int): Boolean = linkFlags[link] and ROPE != 0

    fun linkA(link: Int): Int = linkA[link]

    fun linkB(link: Int): Int = linkB[link]

    fun restLength(link: Int): Float = linkRest[link]

    /** How far [link] is from its rest length right now, as a share of it: 0 at rest, 0.1 stretched by a tenth. */
    fun strain(link: Int): Float = currentDistance(linkA[link], linkB[link]) / linkRest[link] - 1f

    fun isLinkAlive(link: Int): Boolean = links.isAlive(link)

    /** One past the highest link handle: loop `0 until linkEnd` and skip what isn't [isLinkAlive]. */
    val linkEnd: Int get() = links.end

    fun removeLink(link: Int) = links.remove(link)

    /** Holds particle [p] at ([x], [y]), by default where it is now, and returns its handle. */
    fun pin(p: Int, x: Float = particles.x(p), y: Float = particles.y(p), compliance: Float = 0f): Int {
        requireParticle(p)
        requireCompliance(compliance)
        val pin = pins.take()
        if (pin == pinParticle.size) growPins()
        pinParticle[pin] = p
        pinX[pin] = x
        pinY[pin] = y
        pinCompliance[pin] = compliance
        pinLambda[pin] = 0f
        return pin
    }

    /** Moves the point [pin] holds its particle at. */
    fun movePin(pin: Int, x: Float, y: Float) {
        pinX[pin] = x
        pinY[pin] = y
    }

    fun pinnedParticle(pin: Int): Int = pinParticle[pin]

    fun isPinAlive(pin: Int): Boolean = pins.isAlive(pin)

    fun removePin(pin: Int) = pins.remove(pin)

    /** One past the highest pin handle. */
    val pinEnd: Int get() = pins.end

    /**
     * Keeps the area inside the closed ring of particles [ring], in order, at [rest], and returns its
     * handle. Without a [rest], it's the area the ring has now.
     */
    fun area(ring: IntArray, compliance: Float = 0f, rest: Float = Float.NaN): Int {
        require(ring.size >= 3) { "an area needs a ring of at least 3 particles, not ${ring.size}" }
        for (p in ring) requireParticle(p)
        requireCompliance(compliance)
        val area = areas.take()
        if (area == areaStart.size) growAreas()
        if (ringUsed + ring.size > ringParticles.size) ringParticles = ringParticles.copyOf(maxOf(ringParticles.size * 2, ringUsed + ring.size))
        ring.copyInto(ringParticles, ringUsed)
        areaStart[area] = ringUsed
        areaLength[area] = ring.size
        ringUsed += ring.size
        if (ring.size > scratchX.size) {
            scratchX = FloatArray(ring.size)
            scratchY = FloatArray(ring.size)
        }
        val signed = signedArea(area)
        areaSign[area] = if (signed < 0f) -1f else 1f
        areaRest[area] = if (rest.isNaN()) signed * areaSign[area] else rest
        areaCompliance[area] = compliance
        areaLambda[area] = 0f
        return area
    }

    /** The area inside [area]'s ring right now. */
    fun currentArea(area: Int): Float = signedArea(area) * areaSign[area]

    fun restArea(area: Int): Float = areaRest[area]

    fun isAreaAlive(area: Int): Boolean = areas.isAlive(area)

    /** One past the highest area handle. */
    val areaEnd: Int get() = areas.end

    /** Whether particle [p] is part of [area]'s ring. */
    fun ringContains(area: Int, p: Int): Boolean {
        val start = areaStart[area]
        for (k in 0 until areaLength[area]) if (ringParticles[start + k] == p) return true
        return false
    }

    /** Removes [area]. Its handle is reused, but the room its ring took is not. */
    fun removeArea(area: Int) = areas.remove(area)

    /** Removes every constraint. */
    fun clear() {
        links.clear()
        pins.clear()
        areas.clear()
        ringUsed = 0
    }

    /** Forgets how hard every constraint has pushed: the start of a substep. */
    internal fun beginSubstep() {
        linkLambda.fill(0f, 0, links.end)
        pinLambda.fill(0f, 0, pins.end)
        areaLambda.fill(0f, 0, areas.end)
    }

    /** One pass over every constraint, for a substep of [h] seconds. */
    internal fun solve(h: Float) {
        val hh = h * h
        for (c in 0 until links.end) if (links.isAlive(c)) solveLink(c, hh)
        for (c in 0 until pins.end) if (pins.isAlive(c)) solvePin(c, hh)
        for (c in 0 until areas.end) if (areas.isAlive(c)) solveArea(c, hh)
    }

    private fun solveLink(c: Int, hh: Float) {
        val a = linkA[c]
        val b = linkB[c]
        val ps = particles
        if (ps.flags[a] and Particles.ALIVE == 0 || ps.flags[b] and Particles.ALIVE == 0) return
        val wa = ps.invMass[a]
        val wb = ps.invMass[b]
        if (wa + wb == 0f) return
        val dx = ps.x[a] - ps.x[b]
        val dy = ps.y[a] - ps.y[b]
        val length = sqrt(dx * dx + dy * dy)
        if (length < 1e-6f) return
        val error = length - linkRest[c]
        if (error < 0f && linkFlags[c] and ROPE != 0) return
        val softness = linkCompliance[c] / hh
        val change = (-error - softness * linkLambda[c]) / (wa + wb + softness)
        linkLambda[c] += change
        val nx = dx / length
        val ny = dy / length
        ps.x[a] += wa * change * nx
        ps.y[a] += wa * change * ny
        ps.x[b] -= wb * change * nx
        ps.y[b] -= wb * change * ny
    }

    private fun solvePin(c: Int, hh: Float) {
        val p = pinParticle[c]
        val ps = particles
        if (ps.flags[p] and Particles.ALIVE == 0) return
        val w = ps.invMass[p]
        if (w == 0f) return
        val dx = ps.x[p] - pinX[c]
        val dy = ps.y[p] - pinY[c]
        val length = sqrt(dx * dx + dy * dy)
        if (length < 1e-6f) return
        val softness = pinCompliance[c] / hh
        val change = (-length - softness * pinLambda[c]) / (w + softness)
        pinLambda[c] += change
        ps.x[p] += w * change * dx / length
        ps.y[p] += w * change * dy / length
    }

    private fun solveArea(c: Int, hh: Float) {
        val start = areaStart[c]
        val n = areaLength[c]
        val ps = particles
        for (k in 0 until n) if (ps.flags[ringParticles[start + k]] and Particles.ALIVE == 0) return
        val sign = areaSign[c]
        val error = signedArea(c) * sign - areaRest[c]
        // Every gradient is worked out before any particle moves, so the order around the ring doesn't matter.
        var weighted = 0f
        for (k in 0 until n) {
            val prev = ringParticles[start + (k + n - 1) % n]
            val next = ringParticles[start + (k + 1) % n]
            val gx = sign * 0.5f * (ps.y[next] - ps.y[prev])
            val gy = sign * 0.5f * (ps.x[prev] - ps.x[next])
            scratchX[k] = gx
            scratchY[k] = gy
            weighted += ps.invMass[ringParticles[start + k]] * (gx * gx + gy * gy)
        }
        val softness = areaCompliance[c] / hh
        if (weighted + softness == 0f) return
        val change = (-error - softness * areaLambda[c]) / (weighted + softness)
        areaLambda[c] += change
        for (k in 0 until n) {
            val p = ringParticles[start + k]
            val w = ps.invMass[p]
            ps.x[p] += w * change * scratchX[k]
            ps.y[p] += w * change * scratchY[k]
        }
    }

    private fun signedArea(area: Int): Float {
        val start = areaStart[area]
        val n = areaLength[area]
        val ps = particles
        var sum = 0f
        for (k in 0 until n) {
            val i = ringParticles[start + k]
            val j = ringParticles[start + (k + 1) % n]
            sum += ps.x[i] * ps.y[j] - ps.x[j] * ps.y[i]
        }
        return sum / 2f
    }

    private fun currentDistance(a: Int, b: Int): Float {
        val dx = particles.x(a) - particles.x(b)
        val dy = particles.y(a) - particles.y(b)
        return sqrt(dx * dx + dy * dy)
    }

    private fun addLink(a: Int, b: Int, compliance: Float, rest: Float, flags: Int): Int {
        requireParticle(a)
        requireParticle(b)
        require(a != b) { "a link needs two different particles, not $a twice" }
        val length = if (rest.isNaN()) currentDistance(a, b) else rest
        require(length > 0f && length.isFinite()) { "rest length is $length: it must be more than 0" }
        requireCompliance(compliance)
        val link = links.take()
        if (link == linkA.size) growLinks()
        linkA[link] = a
        linkB[link] = b
        linkRest[link] = length
        linkCompliance[link] = compliance
        linkLambda[link] = 0f
        linkFlags[link] = flags
        return link
    }

    private fun requireParticle(p: Int) = require(particles.isAlive(p)) { "particle $p isn't alive" }

    private fun requireCompliance(compliance: Float) =
        require(compliance >= 0f && compliance.isFinite()) { "compliance is $compliance: it must be finite and not negative" }

    private fun growLinks() {
        val size = linkA.size * 2
        linkA = linkA.copyOf(size)
        linkB = linkB.copyOf(size)
        linkRest = linkRest.copyOf(size)
        linkCompliance = linkCompliance.copyOf(size)
        linkLambda = linkLambda.copyOf(size)
        linkFlags = linkFlags.copyOf(size)
    }

    private fun growPins() {
        val size = pinParticle.size * 2
        pinParticle = pinParticle.copyOf(size)
        pinX = pinX.copyOf(size)
        pinY = pinY.copyOf(size)
        pinCompliance = pinCompliance.copyOf(size)
        pinLambda = pinLambda.copyOf(size)
    }

    private fun growAreas() {
        val size = areaStart.size * 2
        areaStart = areaStart.copyOf(size)
        areaLength = areaLength.copyOf(size)
        areaRest = areaRest.copyOf(size)
        areaCompliance = areaCompliance.copyOf(size)
        areaLambda = areaLambda.copyOf(size)
        areaSign = areaSign.copyOf(size)
    }

    private companion object {
        const val ROPE = 1
    }
}

/**
 * Which handles of a store are alive, with a free list so removed ones come back. [take] returns the
 * next handle, which is [end] - 1 when the store had to grow, so the caller grows its arrays then.
 */
internal class Slots {
    private var alive = BooleanArray(16)
    private var free = IntArray(16)
    private var freeCount = 0

    var end = 0
        private set

    val count: Int get() = end - freeCount

    fun take(): Int {
        val slot = if (freeCount > 0) free[--freeCount] else end++
        if (slot >= alive.size) alive = alive.copyOf(alive.size * 2)
        alive[slot] = true
        return slot
    }

    fun isAlive(slot: Int): Boolean = slot in 0 until end && alive[slot]

    fun remove(slot: Int) {
        if (!isAlive(slot)) return
        alive[slot] = false
        if (freeCount == free.size) free = free.copyOf(free.size * 2)
        free[freeCount++] = slot
    }

    fun clear() {
        alive.fill(false, 0, end)
        end = 0
        freeCount = 0
    }
}
