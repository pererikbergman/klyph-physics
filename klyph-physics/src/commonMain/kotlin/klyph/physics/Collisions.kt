package klyph.physics

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Keeps bodies out of each other: a soft body's particles out of every other soft body, and particles
 * that [Particles.collides] out of soft bodies and out of each other.
 *
 * Once per step, [beginStep] files a box for every body and every colliding particle in a
 * [Broadphase], each grown by how far it will move this step. Then every pass of every substep,
 * [solve] looks only at the pairs it found:
 * - a particle inside another body's outline, or closer to it than its radius (at least [SKIN]), is
 *   pushed out to the nearest edge, and the edge is pushed back; who moves how far is split by
 *   inverse mass
 * - two colliding particles closer than their radii are pushed apart along the line between them
 * - a body with [SoftBodyStore.hasSelfCollision] keeps each ring particle off its own other edges
 * - every body keeps its core inside its own skin: the core is the goo inside, and when another body
 *   presses a very soft skin past it, the two are pulled back together
 *
 * Every push is also remembered with the two sides' grip, their [Material]s mixed, for
 * [Particles.applyFriction] at the end of the substep. A push that lifts a particle counts as standing
 * on a floor, so a blob resting on another can jump.
 */
internal class Collisions(private val world: PhysicsWorld) {
    /** Every body and colliding particle of this step, by box: ids are `2·particle` and `2·body + 1`. */
    val broadphase = Broadphase(world.config.cellSize)
    private val ps = world.particles
    private val bodies = world.softBodies

    // Each body's box this pass, for a quick "can't be inside" check.
    private var left = FloatArray(8)
    private var top = FloatArray(8)
    private var right = FloatArray(8)
    private var bottom = FloatArray(8)

    // The nearest edge found by [nearestEdge]: which one, how far along it, the point, and the distance squared.
    private var nearEdge = 0
    private var nearT = 0f
    private var nearX = 0f
    private var nearY = 0f
    private var nearDistanceSquared = 0f
    private var nearInside = false

    /** Files every body and colliding particle in the broadphase, grown by the [dt] seconds ahead. */
    fun beginStep(dt: Float) {
        val bp = broadphase
        bp.clear()
        val ahead = dt / world.substepLength
        for (body in 0 until bodies.end) {
            if (!bodies.isAlive(body)) continue
            var l = Float.MAX_VALUE
            var t = Float.MAX_VALUE
            var r = -Float.MAX_VALUE
            var b = -Float.MAX_VALUE
            for (i in 0..bodies.ringCount(body)) {
                val p = bodies.particle(body, i)
                val x = ps.x[p]
                val y = ps.y[p]
                val toX = x + (x - ps.prevX[p]) * ahead
                val toY = y + (y - ps.prevY[p]) * ahead
                val grow = ps.radius[p]
                l = min(l, min(x, toX) - grow)
                t = min(t, min(y, toY) - grow)
                r = max(r, max(x, toX) + grow)
                b = max(b, max(y, toY) + grow)
            }
            bp.add(bodyId(body), l - MARGIN, t - MARGIN, r + MARGIN, b + MARGIN)
        }
        for (p in 0 until ps.end) {
            if (!ps.isAlive(p) || !ps.collides(p) || ps.owner[p] >= 0) continue
            bp.addMoving(particleId(p), ps.x[p], ps.y[p], (ps.x[p] - ps.prevX[p]) * ahead, (ps.y[p] - ps.prevY[p]) * ahead, ps.radius[p] + MARGIN)
        }
        grainCount = 0
        otherCount = 0
        if (bp.count > 1) {
            bp.build()
            sortPairs(ahead)
        }
    }

    // This step's pairs, sorted once: two loose particles that can meet this step, most of a heap of
    // sand, solved in a loop of their own; and the rest, with a body. Filtered pairs are left out.
    private var grainA = IntArray(64)
    private var grainB = IntArray(64)
    private var grainCount = 0
    private var otherA = IntArray(64)
    private var otherB = IntArray(64)
    private var otherCount = 0

    /**
     * Sorts the broadphase's pairs into [grainA] and the rest, leaving out those filtered apart, and
     * two loose particles that can't meet this step: further apart than their radii, the [MARGIN] each
     * and how far each will move. Their boxes overlap at the corners, where their circles don't: in a
     * heap of sand, most of the pairs.
     */
    private fun sortPairs(ahead: Float) {
        val bp = broadphase
        val pairs = bp.pairs
        val filtered = ps.anyFiltered
        val x = ps.x
        val y = ps.y
        val prevX = ps.prevX
        val prevY = ps.prevY
        val radius = ps.radius
        if (grainA.size < bp.pairCount) {
            grainA = IntArray(bp.pairCount)
            grainB = IntArray(bp.pairCount)
        }
        for (i in 0 until bp.pairCount) {
            val a = pairs[2 * i]
            val b = pairs[2 * i + 1]
            val aIsBody = a and 1 == 1
            val bIsBody = b and 1 == 1
            if (filtered) {
                // a body's filter is its particles', all the same: its core's
                val pa = if (aIsBody) bodies.core(a shr 1) else a shr 1
                val pb = if (bIsBody) bodies.core(b shr 1) else b shr 1
                if (!Filter.collides(ps.category[pa], ps.mask[pa], ps.category[pb], ps.mask[pb])) continue
            }
            if (aIsBody || bIsBody) {
                if (otherCount == otherA.size) {
                    otherA = otherA.copyOf(otherCount * 2)
                    otherB = otherB.copyOf(otherCount * 2)
                }
                otherA[otherCount] = a
                otherB[otherCount] = b
                otherCount++
                continue
            }
            val pa = a shr 1
            val pb = b shr 1
            val moveA = (abs(x[pa] - prevX[pa]) + abs(y[pa] - prevY[pa])) * ahead
            val moveB = (abs(x[pb] - prevX[pb]) + abs(y[pb] - prevY[pb])) * ahead
            val reach = radius[pa] + radius[pb] + 2f * MARGIN + moveA + moveB
            val dx = x[pb] - x[pa]
            val dy = y[pb] - y[pa]
            if (dx * dx + dy * dy > reach * reach) continue
            grainA[grainCount] = pa
            grainB[grainCount] = pb
            grainCount++
        }
    }

    /** One pass over every pair the broadphase found this step, and every body that collides with itself. */
    fun solve() {
        if (grainCount > 0) solveGrains()
        if (otherCount > 0) {
            measureBodies()
            for (i in 0 until otherCount) {
                val a = otherA[i]
                val b = otherB[i]
                val aIsBody = a and 1 == 1
                val bIsBody = b and 1 == 1
                when {
                    aIsBody && bIsBody -> {
                        bodyAgainstBody(a shr 1, b shr 1)
                        bodyAgainstBody(b shr 1, a shr 1)
                    }
                    aIsBody -> particleAgainstBody(b shr 1, a shr 1)
                    else -> particleAgainstBody(a shr 1, b shr 1)
                }
            }
        }
        for (body in 0 until bodies.end) {
            if (!bodies.isAlive(body)) continue
            if (bodies.hasSelfCollision(body)) selfCollide(body)
            keepCoreInside(body)
        }
    }

    /**
     * Pulls [body]'s core back inside its skin if it has got out, and the nearest edge toward it, each
     * by its share of the inverse mass, until the core is [SKIN] inside.
     */
    private fun keepCoreInside(body: Int) {
        val core = bodies.core(body)
        val x = ps.x[core]
        val y = ps.y[core]
        nearestEdge(body, x, y)
        if (nearInside) return
        val distance = sqrt(nearDistanceSquared)
        if (distance < TOUCHING) return
        separate(core, body, (nearX - x) / distance, (nearY - y) / distance, distance + SKIN)
    }

    private fun measureBodies() {
        if (left.size < bodies.end) {
            val size = max(bodies.end, left.size * 2)
            left = left.copyOf(size)
            top = top.copyOf(size)
            right = right.copyOf(size)
            bottom = bottom.copyOf(size)
        }
        for (body in 0 until bodies.end) {
            if (!bodies.isAlive(body)) continue
            var l = Float.MAX_VALUE
            var t = Float.MAX_VALUE
            var r = -Float.MAX_VALUE
            var b = -Float.MAX_VALUE
            for (i in 0 until bodies.ringCount(body)) {
                val p = bodies.particle(body, i)
                l = min(l, ps.x[p])
                t = min(t, ps.y[p])
                r = max(r, ps.x[p])
                b = max(b, ps.y[p])
            }
            left[body] = l
            top[body] = t
            right[body] = r
            bottom[body] = b
        }
    }

    /** Every particle of [from], its core too, pushed out of body [into]. */
    private fun bodyAgainstBody(from: Int, into: Int) {
        for (i in 0..bodies.ringCount(from)) particleAgainstBody(bodies.particle(from, i), into)
    }

    /**
     * Pushes particle [p] out of [body]'s outline if it is inside, or nearer the outline than its radius
     * if it is outside, to the nearest edge, and pushes that edge back, each by its share of the
     * inverse mass.
     */
    private fun particleAgainstBody(p: Int, body: Int) {
        val x = ps.x[p]
        val y = ps.y[p]
        val radius = max(ps.radius[p], SKIN)
        // a body's own particle near another's skin touches it, for events: see [Events.TOUCH_SLOP]
        val owner = ps.owner[p]
        val reach = if (owner >= 0 && world.events.recording) radius + Events.TOUCH_SLOP else radius
        if (x < left[body] - reach || x > right[body] + reach || y < top[body] - reach || y > bottom[body] + reach) return
        nearestEdge(body, x, y)
        val inside = nearInside
        if (!inside && nearDistanceSquared >= reach * reach) return
        if (owner >= 0 && world.events.recording) world.events.touch(TouchKind.SOFT_BODY, owner, TouchKind.SOFT_BODY, body)
        if (!inside && nearDistanceSquared >= radius * radius) return
        val distance = sqrt(nearDistanceSquared)
        val n = bodies.ringCount(body)
        val a = bodies.particle(body, nearEdge)
        val b = bodies.particle(body, if (nearEdge + 1 == n) 0 else nearEdge + 1)
        // The way out, from the particle toward the edge when it's inside, away from it when outside.
        var nx: Float
        var ny: Float
        val depth: Float
        if (distance < TOUCHING) {
            // Right on the edge, as when two things lie on the same floor: out along the edge's
            // normal, on the side away from the body's core.
            val ex = ps.x[b] - ps.x[a]
            val ey = ps.y[b] - ps.y[a]
            val length = sqrt(ex * ex + ey * ey)
            if (length < TOUCHING) return
            nx = ey / length
            ny = -ex / length
            val core = bodies.core(body)
            if (nx * (nearX - ps.x[core]) + ny * (nearY - ps.y[core]) < 0f) {
                nx = -nx
                ny = -ny
            }
            depth = radius
        } else if (inside) {
            nx = (nearX - x) / distance
            ny = (nearY - y) / distance
            depth = distance + radius
        } else {
            nx = (x - nearX) / distance
            ny = (y - nearY) / distance
            depth = radius - distance
        }
        if (!separate(p, body, nx, ny, depth)) return
        recordFloors(p, a, b, ny)
        touch(p, a, b, nearT, nx, ny, depth)
    }

    /** Remembers particle [p]'s push against the point at [t] of the edge from [a] to [b] ([b] -1: particle [a]), for its grip. */
    @Suppress("NOTHING_TO_INLINE")
    private inline fun touch(p: Int, a: Int, b: Int, t: Float, nx: Float, ny: Float, depth: Float) {
        val grip = Material.mixFriction(ps.friction[p], ps.friction[a])
        val hold = Material.mixFriction(ps.staticFriction[p], ps.staticFriction[a])
        ps.touch(p, nx, ny, depth, grip, hold, a, b, t)
    }

    /**
     * Moves particle [p] and the point `s·a + t·b` apart by [amount] along the unit ([ux], [uy]): [p]
     * one way, [a] and [b] the other, each by its share of the inverse mass. [b] may be -1.
     */
    @Suppress("NOTHING_TO_INLINE")
    private inline fun shift(p: Int, a: Int, b: Int, s: Float, t: Float, ux: Float, uy: Float, amount: Float): Boolean {
        val wp = ps.invMass[p]
        val wa = ps.invMass[a] * s
        val wb = if (b < 0) 0f else ps.invMass[b] * t
        val w = wp + wa * s + wb * t
        if (w == 0f) return false
        val push = amount / w
        ps.x[p] += wp * push * ux
        ps.y[p] += wp * push * uy
        ps.x[a] -= wa * push * ux
        ps.y[a] -= wa * push * uy
        if (b >= 0) {
            ps.x[b] -= wb * push * ux
            ps.y[b] -= wb * push * uy
        }
        return true
    }

    /**
     * Moves particle [p] by [depth] along ([nx], [ny]) away from the near edge of [body], found by
     * [nearestEdge], sharing the move between the particle and the edge's two ends by inverse mass, the
     * ends by how near the point is to each. Returns whether anything could move.
     */
    @Suppress("NOTHING_TO_INLINE")
    private inline fun separate(p: Int, body: Int, nx: Float, ny: Float, depth: Float): Boolean {
        val n = bodies.ringCount(body)
        val a = bodies.particle(body, nearEdge)
        val b = bodies.particle(body, if (nearEdge + 1 == n) 0 else nearEdge + 1)
        return shift(p, a, b, 1f - nearT, nearT, nx, ny, depth)
    }

    /**
     * Pushes apart every two loose particles of [grainA] closer than their radii, along the line
     * between them. A loop of its own, with the arrays it reads at hand: there are thousands of them.
     */
    private fun solveGrains() {
        val x = ps.x
        val y = ps.y
        val radius = ps.radius
        val invMass = ps.invMass
        val friction = ps.friction
        val staticFriction = ps.staticFriction
        val contact = ps.contact
        val ga = grainA
        val gb = grainB
        for (i in 0 until grainCount) {
            val a = ga[i]
            val b = gb[i]
            val dx = x[b] - x[a]
            val dy = y[b] - y[a]
            val reach = radius[a] + radius[b]
            val distanceSquared = dx * dx + dy * dy
            if (distanceSquared >= reach * reach || distanceSquared < TOUCHING * TOUCHING) continue
            val wa = invMass[a]
            val wb = invMass[b]
            val w = wa + wb
            if (w == 0f) continue
            val distance = sqrt(distanceSquared)
            val nx = dx / distance
            val ny = dy / distance
            val push = (reach - distance) / w
            x[a] -= wa * push * nx
            y[a] -= wa * push * ny
            x[b] += wb * push * nx
            y[b] += wb * push * ny
            // a push that lifts stands it on a floor: what Events.recordFloor does, without the call
            if (wb > 0f && ny < MIN_FLOOR_NORMAL_Y) contact[b] = contact[b] or Events.NOW
            if (wa > 0f && -ny < MIN_FLOOR_NORMAL_Y) contact[a] = contact[a] or Events.NOW
            ps.touch(b, nx, ny, reach - distance, Material.mixFriction(friction[b], friction[a]), Material.mixFriction(staticFriction[b], staticFriction[a]), a, -1, 0f)
        }
    }

    /** Keeps each ring particle of [body] at least its self-thickness away from the edges it isn't on. */
    private fun selfCollide(body: Int) {
        val n = bodies.ringCount(body)
        val reach = bodies.selfThickness(body)
        for (i in 0 until n) {
            val p = bodies.particle(body, i)
            for (e in 0 until n) {
                val e1 = if (e + 1 == n) 0 else e + 1
                if (e == i || e1 == i) continue
                val a = bodies.particle(body, e)
                val b = bodies.particle(body, e1)
                val t = closest(ps.x[p], ps.y[p], a, b)
                val qx = ps.x[a] + (ps.x[b] - ps.x[a]) * t
                val qy = ps.y[a] + (ps.y[b] - ps.y[a]) * t
                val dx = ps.x[p] - qx
                val dy = ps.y[p] - qy
                val distanceSquared = dx * dx + dy * dy
                if (distanceSquared >= reach * reach || distanceSquared < TOUCHING * TOUCHING) continue
                val distance = sqrt(distanceSquared)
                val nx = dx / distance
                val ny = dy / distance
                val s = 1f - t
                val wp = ps.invMass[p]
                val wa = ps.invMass[a] * s
                val wb = ps.invMass[b] * t
                val w = wp + wa * s + wb * t
                if (w == 0f) continue
                val push = (reach - distance) / w
                ps.x[p] += wp * push * nx
                ps.y[p] += wp * push * ny
                ps.x[a] -= wa * push * nx
                ps.y[a] -= wa * push * ny
                ps.x[b] -= wb * push * nx
                ps.y[b] -= wb * push * ny
            }
        }
    }

    /** Finds the edge of [body]'s outline nearest ([x], [y]), into the near- fields. */
    private fun nearestEdge(body: Int, x: Float, y: Float) {
        val n = bodies.ringCounts[body]
        val pool = bodies.particlePool
        val first = bodies.start[body]
        val px = ps.x
        val py = ps.y
        var best = Float.MAX_VALUE
        // and, in the same sweep, whether the point is inside, as Outline.contains finds it
        var winding = 0
        var a = pool[first]
        for (e in 0 until n) {
            val b = pool[first + if (e + 1 == n) 0 else e + 1]
            val ax = px[a]
            val ay = py[a]
            val ex = px[b] - ax
            val ey = py[b] - ay
            val lengthSquared = ex * ex + ey * ey
            val t = if (lengthSquared == 0f) 0f else (((x - ax) * ex + (y - ay) * ey) / lengthSquared).coerceIn(0f, 1f)
            val qx = ax + ex * t
            val qy = ay + ey * t
            val dx = x - qx
            val dy = y - qy
            val d = dx * dx + dy * dy
            if (d < best) {
                best = d
                nearEdge = e
                nearT = t
                nearX = qx
                nearY = qy
            }
            val by = py[b]
            if ((ay <= y) != (by <= y)) {
                val crossingX = ax + (y - ay) / (by - ay) * ex
                if (crossingX > x) winding += if (by > ay) 1 else -1
            }
            a = b
        }
        nearDistanceSquared = best
        nearInside = winding != 0
    }

    /** How far along the edge from particle [a] to [b] the point nearest ([x], [y]) is, from 0 to 1. */
    @Suppress("NOTHING_TO_INLINE")
    private inline fun closest(x: Float, y: Float, a: Int, b: Int): Float {
        val ex = ps.x[b] - ps.x[a]
        val ey = ps.y[b] - ps.y[a]
        val lengthSquared = ex * ex + ey * ey
        if (lengthSquared == 0f) return 0f
        return ((x - ps.x[a]) * ex + (y - ps.y[a]) * ey).div(lengthSquared).coerceIn(0f, 1f)
    }

    /** Whoever the last push lifted, particle [p] or the near edge's ends, stood on something. */
    private fun recordFloors(p: Int, a: Int, b: Int, ny: Float) {
        if (ny < MIN_FLOOR_NORMAL_Y) world.events.recordFloor(p, 0)
        if (-ny < MIN_FLOOR_NORMAL_Y) {
            world.events.recordFloor(a, 0)
            world.events.recordFloor(b, 0)
        }
    }

    private fun bodyId(body: Int) = 2 * body + 1

    private fun particleId(p: Int) = 2 * p

    private companion object {
        /** Slack around every box, so what drifts a little further than its velocity said is still found. */
        const val MARGIN = 1f

        /**
         * How far a particle of no radius keeps from another body's skin: just enough that two bodies
         * pressed onto the same floor can't slip their particles exactly onto each other's edges.
         */
        const val SKIN = 0.5f

        /** Closer than this is touching, with no direction to push in. */
        const val TOUCHING = 0.0001f

        /** A push this far upward or more lifts what it pushes: the same 60 degrees as the solids' floors. */
        const val MIN_FLOOR_NORMAL_Y = -0.5f
    }
}
