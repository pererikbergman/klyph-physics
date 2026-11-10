package klyph.physics

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * The soft bodies in a [PhysicsWorld]. A body is an `Int` handle for a group of the world's particles,
 * a ring and a core, held together by the world's constraints:
 * - an **edge** (distance) between each ring particle and the next: stiff, so the skin doesn't tear
 * - a **spoke** (distance) from each ring particle to the core: soft, so it squashes and springs back
 * - an **area** over the ring, so a squashed body bulges out elsewhere
 *
 * Its particles and constraints are ordinary ones: reach them with [particle] and [ringCount].
 */
class SoftBodyStore internal constructor(private val world: PhysicsWorld) {
    private val slots = Slots()
    private var start = IntArray(8)
    private var ringCounts = IntArray(8)
    private var linkStart = IntArray(8)
    private var areaOf = IntArray(8)
    private var tunings = arrayOfNulls<SoftBodyTuning>(8)
    private var rings = arrayOfNulls<BodyRing>(8)
    private var selfCollision = BooleanArray(8)
    private var thickness = FloatArray(8)
    private var particlePool = IntArray(64)
    private var particleUsed = 0
    private var linkPool = IntArray(64)
    private var linkUsed = 0

    /** How many bodies are alive. */
    val count: Int get() = slots.count

    /** One past the highest body handle: loop `0 until end` and skip what isn't [isAlive]. */
    val end: Int get() = slots.end

    fun isAlive(body: Int): Boolean = slots.isAlive(body)

    /**
     * Adds a body of [shape] with its middle at [centerX] and its bottom on [baseY], at rest, and
     * returns its handle. Each ring particle weighs [particleMass]; the core is [SoftBodyShape.coreMass]
     * times that.
     */
    fun add(shape: SoftBodyShape, centerX: Float, baseY: Float, tuning: SoftBodyTuning = SoftBodyTuning(), particleMass: Float = 1f): Int {
        val n = shape.ringCount
        var minX = shape.ringX[0]
        var maxX = minX
        var maxY = shape.ringY[0]
        for (i in 1 until n) {
            minX = min(minX, shape.ringX[i])
            maxX = max(maxX, shape.ringX[i])
            maxY = max(maxY, shape.ringY[i])
        }
        val dx = centerX - (minX + maxX) / 2f
        val dy = baseY - maxY
        val body = slots.take()
        if (body == start.size) grow()
        ensurePools(n + 1, 2 * n)
        start[body] = particleUsed
        ringCounts[body] = n
        linkStart[body] = linkUsed
        tunings[body] = tuning
        if (rings[body] == null) rings[body] = BodyRing(body)
        val ps = world.particles
        for (i in 0 until n) particlePool[particleUsed + i] = ps.add(shape.ringX[i] + dx, shape.ringY[i] + dy, particleMass)
        val core = ps.add(shape.coreX + dx, shape.coreY + dy, particleMass * shape.coreMass)
        particlePool[particleUsed + n] = core
        for (i in 0..n) ps.owner[particlePool[particleUsed + i]] = body
        selfCollision[body] = false
        var shortest = Float.MAX_VALUE
        for (i in 0 until n) {
            val j = (i + 1) % n
            val ex = shape.ringX[j] - shape.ringX[i]
            val ey = shape.ringY[j] - shape.ringY[i]
            shortest = min(shortest, sqrt(ex * ex + ey * ey))
        }
        thickness[body] = shortest / 2f
        val cs = world.constraints
        for (i in 0 until n) {
            val a = particlePool[particleUsed + i]
            val b = particlePool[particleUsed + (i + 1) % n]
            linkPool[linkUsed + i] = cs.distance(a, b, tuning.edgeCompliance)
        }
        for (i in 0 until n) linkPool[linkUsed + n + i] = cs.distance(particlePool[particleUsed + i], core, tuning.spokeCompliance)
        areaOf[body] = cs.area(IntArray(n) { particlePool[particleUsed + it] }, tuning.areaCompliance)
        particleUsed += n + 1
        linkUsed += 2 * n
        return body
    }

    /** Removes [body], its particles and its constraints. Its handle is reused; the room it took in the pools is not. */
    fun remove(body: Int) {
        if (!isAlive(body)) return
        for (i in 0..ringCounts[body]) world.removeParticle(particle(body, i))
        slots.remove(body)
    }

    /**
     * Whether [body]'s skin is kept from passing through itself, as when it folds over. Off by default:
     * a body that doesn't fold doesn't need it, and it costs a check of every ring particle against
     * every edge.
     */
    fun hasSelfCollision(body: Int): Boolean = selfCollision[body]

    fun setSelfCollision(body: Int, on: Boolean) {
        selfCollision[body] = on
    }

    /** How close [body]'s skin may come to itself with [hasSelfCollision] on: half its shortest edge at rest. */
    internal fun selfThickness(body: Int): Float = thickness[body]

    /** How many ring particles [body] has. Particle `ringCount` is its core. */
    fun ringCount(body: Int): Int = ringCounts[body]

    /** The world particle of [body]'s ring point [i], or its core when [i] is [ringCount]. */
    fun particle(body: Int, i: Int): Int = particlePool[start[body] + i]

    fun core(body: Int): Int = particle(body, ringCounts[body])

    fun tuning(body: Int): SoftBodyTuning = tunings[body]!!

    /** [body]'s outline, for [Outline] and for drawing. The same object every time. */
    fun ring(body: Int): Ring = rings[body]!!

    /** Moves [body], at rest, so its middle is at [centerX] and its bottom on [baseY]. */
    fun placeAt(body: Int, centerX: Float, baseY: Float) {
        val dx = centerX - (minX(body) + maxX(body)) / 2f
        val dy = baseY - maxY(body)
        val ps = world.particles
        for (i in 0..ringCounts[body]) {
            val p = particle(body, i)
            ps.moveTo(p, ps.x(p) + dx, ps.y(p) + dy)
            world.setVelocity(p, 0f, 0f)
        }
    }

    /** Adds ([vx], [vy]) units per second to every particle of [body]. */
    fun addVelocity(body: Int, vx: Float, vy: Float) {
        for (i in 0..ringCounts[body]) {
            val p = particle(body, i)
            world.setVelocity(p, world.velocityX(p) + vx, world.velocityY(p) + vy)
        }
    }

    /** Mean position of [body]'s ring particles. */
    fun centerX(body: Int): Float = ringMean(body) { world.particles.x(it) }

    fun centerY(body: Int): Float = ringMean(body) { world.particles.y(it) }

    /** Mean velocity of [body]'s ring particles, in units per second. */
    fun meanVelocityX(body: Int): Float = ringMean(body) { world.velocityX(it) }

    fun meanVelocityY(body: Int): Float = ringMean(body) { world.velocityY(it) }

    fun minX(body: Int): Float = ringExtreme(body, -1f) { world.particles.x(it) }

    fun maxX(body: Int): Float = ringExtreme(body, 1f) { world.particles.x(it) }

    fun minY(body: Int): Float = ringExtreme(body, -1f) { world.particles.y(it) }

    fun maxY(body: Int): Float = ringExtreme(body, 1f) { world.particles.y(it) }

    /** The area inside [body]'s ring. */
    fun area(body: Int): Float = world.constraints.currentArea(areaOf[body])

    /** The longest edge of [body]'s ring as a multiple of its rest length: 1 at rest, large when it's tearing. */
    fun maxEdgeStretch(body: Int): Float {
        var worst = 0f
        for (i in 0 until ringCounts[body]) worst = max(worst, 1f + world.constraints.strain(linkPool[linkStart[body] + i]))
        return worst
    }

    /** Whether every coordinate of [body] is a real number. */
    fun isFinite(body: Int): Boolean {
        val ps = world.particles
        for (i in 0..ringCounts[body]) {
            val p = particle(body, i)
            if (!ps.x(p).isFinite() || !ps.y(p).isFinite()) return false
        }
        return true
    }

    /** Whether any particle of [body] stood on a floor during the last step. */
    fun isOnFloor(body: Int): Boolean {
        for (i in 0..ringCounts[body]) if (world.events.isOnFloor(particle(body, i))) return true
        return false
    }

    /** How many particles of [body] stood on a floor during the last step. */
    fun floorContactCount(body: Int): Int {
        var n = 0
        for (i in 0..ringCounts[body]) if (world.events.isOnFloor(particle(body, i))) n++
        return n
    }

    /** Whether [body] stood on a floor carrying any bit of [tag] during the last step. */
    fun touched(body: Int, tag: Int): Boolean {
        for (i in 0..ringCounts[body]) if (world.events.floorTags(particle(body, i)) and tag != 0) return true
        return false
    }

    /** The edge link from ring point [i] to the next. */
    fun edge(body: Int, i: Int): Int = linkPool[linkStart[body] + i]

    /** The spoke link from ring point [i] to the core. */
    fun spoke(body: Int, i: Int): Int = linkPool[linkStart[body] + ringCounts[body] + i]

    private inline fun ringMean(body: Int, value: (Int) -> Float): Float {
        var sum = 0f
        val n = ringCounts[body]
        for (i in 0 until n) sum += value(particle(body, i))
        return sum / n
    }

    private inline fun ringExtreme(body: Int, direction: Float, value: (Int) -> Float): Float {
        var best = value(particle(body, 0))
        for (i in 1 until ringCounts[body]) {
            val v = value(particle(body, i))
            if (v * direction > best * direction) best = v
        }
        return best
    }

    private fun ensurePools(particles: Int, links: Int) {
        if (particleUsed + particles > particlePool.size) particlePool = particlePool.copyOf(max(particlePool.size * 2, particleUsed + particles))
        if (linkUsed + links > linkPool.size) linkPool = linkPool.copyOf(max(linkPool.size * 2, linkUsed + links))
    }

    private fun grow() {
        val size = start.size * 2
        start = start.copyOf(size)
        ringCounts = ringCounts.copyOf(size)
        linkStart = linkStart.copyOf(size)
        areaOf = areaOf.copyOf(size)
        tunings = tunings.copyOf(size)
        rings = rings.copyOf(size)
        selfCollision = selfCollision.copyOf(size)
        thickness = thickness.copyOf(size)
    }

    private inner class BodyRing(private val body: Int) : Ring {
        override val ringCount: Int get() = ringCounts[body]

        override fun ringX(i: Int): Float = world.particles.x(particle(body, i))

        override fun ringY(i: Int): Float = world.particles.y(particle(body, i))
    }
}
