package klyph.physics

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
        if (bp.count > 1) bp.build()
    }

    /** One pass over every pair the broadphase found this step, and every body that collides with itself. */
    fun solve() {
        val bp = broadphase
        if (bp.pairCount > 0) {
            measureBodies()
            for (i in 0 until bp.pairCount) {
                val a = bp.pairA(i)
                val b = bp.pairB(i)
                val aIsBody = a and 1 == 1
                val bIsBody = b and 1 == 1
                when {
                    aIsBody && bIsBody -> {
                        bodyAgainstBody(a shr 1, b shr 1)
                        bodyAgainstBody(b shr 1, a shr 1)
                    }
                    aIsBody -> particleAgainstBody(b shr 1, a shr 1)
                    bIsBody -> particleAgainstBody(a shr 1, b shr 1)
                    else -> particleAgainstParticle(a shr 1, b shr 1)
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
        if (Outline.contains(bodies.ring(body), x, y)) return
        nearestEdge(body, x, y)
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
        if (x < left[body] - radius || x > right[body] + radius || y < top[body] - radius || y > bottom[body] + radius) return
        nearestEdge(body, x, y)
        val inside = Outline.contains(bodies.ring(body), x, y)
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
    private fun touch(p: Int, a: Int, b: Int, t: Float, nx: Float, ny: Float, depth: Float) {
        val grip = Material.mixFriction(ps.friction[p], ps.friction[a])
        val hold = Material.mixFriction(ps.staticFriction[p], ps.staticFriction[a])
        ps.touch(p, nx, ny, depth, grip, hold, a, b, t)
    }

    /**
     * Moves particle [p] and the point `s·a + t·b` apart by [amount] along the unit ([ux], [uy]): [p]
     * one way, [a] and [b] the other, each by its share of the inverse mass. [b] may be -1.
     */
    private fun shift(p: Int, a: Int, b: Int, s: Float, t: Float, ux: Float, uy: Float, amount: Float): Boolean {
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
    private fun separate(p: Int, body: Int, nx: Float, ny: Float, depth: Float): Boolean {
        val n = bodies.ringCount(body)
        val a = bodies.particle(body, nearEdge)
        val b = bodies.particle(body, if (nearEdge + 1 == n) 0 else nearEdge + 1)
        return shift(p, a, b, 1f - nearT, nearT, nx, ny, depth)
    }

    /** Two colliding particles closer than their radii, pushed apart along the line between them. */
    private fun particleAgainstParticle(a: Int, b: Int) {
        val dx = ps.x[b] - ps.x[a]
        val dy = ps.y[b] - ps.y[a]
        val reach = ps.radius[a] + ps.radius[b]
        val distanceSquared = dx * dx + dy * dy
        if (distanceSquared >= reach * reach || distanceSquared < TOUCHING * TOUCHING) return
        val wa = ps.invMass[a]
        val wb = ps.invMass[b]
        val w = wa + wb
        if (w == 0f) return
        val distance = sqrt(distanceSquared)
        val nx = dx / distance
        val ny = dy / distance
        val push = (reach - distance) / w
        ps.x[a] -= wa * push * nx
        ps.y[a] -= wa * push * ny
        ps.x[b] += wb * push * nx
        ps.y[b] += wb * push * ny
        if (wb > 0f && ny < MIN_FLOOR_NORMAL_Y) world.events.recordFloor(b, 0)
        if (wa > 0f && -ny < MIN_FLOOR_NORMAL_Y) world.events.recordFloor(a, 0)
        touch(b, a, -1, 0f, nx, ny, reach - distance)
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
        val n = bodies.ringCount(body)
        nearDistanceSquared = Float.MAX_VALUE
        for (e in 0 until n) {
            val a = bodies.particle(body, e)
            val b = bodies.particle(body, if (e + 1 == n) 0 else e + 1)
            val t = closest(x, y, a, b)
            val qx = ps.x[a] + (ps.x[b] - ps.x[a]) * t
            val qy = ps.y[a] + (ps.y[b] - ps.y[a]) * t
            val dx = x - qx
            val dy = y - qy
            val d = dx * dx + dy * dy
            if (d < nearDistanceSquared) {
                nearDistanceSquared = d
                nearEdge = e
                nearT = t
                nearX = qx
                nearY = qy
            }
        }
    }

    /** How far along the edge from particle [a] to [b] the point nearest ([x], [y]) is, from 0 to 1. */
    private fun closest(x: Float, y: Float, a: Int, b: Int): Float {
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
