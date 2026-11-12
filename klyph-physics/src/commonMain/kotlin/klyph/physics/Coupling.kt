package klyph.physics

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Where soft meets rigid: keeps particles and soft bodies out of rigid bodies, and rigid bodies out of
 * soft bodies, both sides moving.
 *
 * Once per step, [beginStep] files every rigid body, every soft body and every particle that
 * [Particles.collides] in a [Broadphase] of its own, each grown by how far it will move. Then every
 * pass of every substep, [solve] looks only at the pairs of a rigid body and something soft:
 * - a particle inside a rigid shape, or nearer it than its radius, is pushed out along the shape's
 *   surface, and the body is pushed back at that point, so it moves and turns. Who moves how far is
 *   split by the particle's inverse mass and how hard the body is to move there.
 * - a sharp corner of a rigid shape inside a soft body's outline is pushed out to the nearest edge,
 *   and the edge's two particles back, so a crate's corner can't slip between them.
 *
 * Every push on a particle is also remembered with its grip, against the point of the body it touched,
 * for [Particles.applyFriction]. A push that lifts a particle counts as standing on a floor.
 *
 * A particle [Attachments] holds to a body doesn't bump into that body.
 */
internal class Coupling(private val world: PhysicsWorld) {
    /** Every rigid body, soft body and colliding particle of this step, by box: see [id]. */
    val broadphase = Broadphase(world.config.cellSize)
    private val ps = world.particles
    private val soft = world.softBodies
    private val bodies = world.rigidBodies
    private val narrow = Narrowphase()
    private val manifold = Manifold()
    private var active = false

    // The body each particle is attached to, for this step, or -1: a particle doesn't bump into the
    // body it's tied to, or it would fight its own attachment.
    private var tiedTo = IntArray(0)

    // The nearest edge of a soft body's outline, from [nearestEdge].
    private var nearEdge = 0
    private var nearT = 0f
    private var nearX = 0f
    private var nearY = 0f
    private var nearDistanceSquared = 0f

    /** Files every rigid body and everything soft in the broadphase, grown by the [dt] seconds ahead. */
    fun beginStep(dt: Float) {
        val bp = broadphase
        bp.clear()
        active = false
        if (bodies.count == 0) return
        var rigid = 0
        for (b in 0 until bodies.end) {
            if (!bodies.isAlive(b) || bodies.reach[b] == 0f) continue
            bp.addMoving(id(b, RIGID), bodies.x[b], bodies.y[b], bodies.vx[b] * dt, bodies.vy[b] * dt, bodies.reach[b] + MARGIN)
            rigid++
        }
        if (rigid == 0) return
        val ahead = dt / world.substepLength
        for (s in 0 until soft.end) {
            if (!soft.isAlive(s)) continue
            var l = Float.MAX_VALUE
            var t = Float.MAX_VALUE
            var r = -Float.MAX_VALUE
            var b = -Float.MAX_VALUE
            for (i in 0..soft.ringCount(s)) {
                val p = soft.particle(s, i)
                val x = ps.x[p]
                val y = ps.y[p]
                val toX = x + (x - ps.prevX[p]) * ahead
                val toY = y + (y - ps.prevY[p]) * ahead
                val grow = max(ps.radius[p], SKIN)
                l = min(l, min(x, toX) - grow)
                t = min(t, min(y, toY) - grow)
                r = max(r, max(x, toX) + grow)
                b = max(b, max(y, toY) + grow)
            }
            bp.add(id(s, SOFT), l - MARGIN, t - MARGIN, r + MARGIN, b + MARGIN)
        }
        for (p in 0 until ps.end) {
            if (!ps.isAlive(p) || !ps.collides(p) || ps.owner[p] >= 0) continue
            bp.addMoving(id(p, PARTICLE), ps.x[p], ps.y[p], (ps.x[p] - ps.prevX[p]) * ahead, (ps.y[p] - ps.prevY[p]) * ahead, max(ps.radius[p], SKIN) + MARGIN)
        }
        if (bp.count > 1) bp.build()
        active = bp.pairCount > 0
        if (tiedTo.size < ps.end) tiedTo = IntArray(max(ps.end, 2 * tiedTo.size))
        tiedTo.fill(-1, 0, ps.end)
        val tied = world.attachments
        for (a in 0 until tied.end) if (tied.isAlive(a) && tied.particle(a) < ps.end) tiedTo[tied.particle(a)] = tied.body(a)
    }

    /** One pass over every pair of a rigid body and something soft that the broadphase found this step. */
    fun solve() {
        if (!active) return
        val bp = broadphase
        for (i in 0 until bp.pairCount) {
            val a = bp.pairA(i)
            val b = bp.pairB(i)
            val kindA = a % KINDS
            val kindB = b % KINDS
            if ((kindA == RIGID) == (kindB == RIGID)) continue
            val body = if (kindA == RIGID) a / KINDS else b / KINDS
            val other = if (kindA == RIGID) b else a
            if (other % KINDS == PARTICLE) {
                particleAgainstBody(other / KINDS, body)
            } else {
                val s = other / KINDS
                for (k in 0..soft.ringCount(s)) particleAgainstBody(soft.particle(s, k), body)
                cornersAgainstSoftBody(body, s)
            }
        }
    }

    /**
     * Pushes particle [p] out of every shape of rigid [body] it is inside, or nearer than its radius,
     * and the body back at the point it touched.
     */
    private fun particleAgainstBody(p: Int, body: Int) {
        val wp = ps.invMass[p]
        if (wp == 0f && bodies.invMass[body] == 0f || tiedTo[p] == body) return
        val x = ps.x[p]
        val y = ps.y[p]
        val radius = max(ps.radius[p], SKIN)
        val bx = bodies.x[body]
        val by = bodies.y[body]
        val reach = bodies.reach[body] + radius
        if (x < bx - reach || x > bx + reach || y < by - reach || y > by + reach) return
        val shapes = bodies.shapes[body]!!
        val m = manifold
        for (si in shapes.indices) {
            // a body of many shapes, such as a drum, has most of them far from any one particle
            val shape = shapes[si]
            val cx = bx + bodies.cos[body] * shape.boundX - bodies.sin[body] * shape.boundY
            val cy = by + bodies.sin[body] * shape.boundX + bodies.cos[body] * shape.boundY
            val near = shape.boundRadius + radius
            if ((ps.x[p] - cx) * (ps.x[p] - cx) + (ps.y[p] - cy) * (ps.y[p] - cy) > near * near) continue
            if (!narrow.collidePoint(ps.x[p], ps.y[p], radius, shape, bx, by, bodies.cos[body], bodies.sin[body], m)) continue
            val depth = -m.separation(0)
            if (depth <= 0f) continue
            // the normal points from the particle into the body: the particle goes back along it
            val nx = m.normalX
            val ny = m.normalY
            val rx = m.pointBX(0) - bodies.x[body]
            val ry = m.pointBY(0) - bodies.y[body]
            val w = wp + bodies.inverseMassAt(body, rx, ry, nx, ny)
            if (w == 0f) continue
            val push = depth / w
            ps.x[p] -= wp * push * nx
            ps.y[p] -= wp * push * ny
            bodies.shove(body, push * nx, push * ny, rx, ry)
            if (-ny < MIN_FLOOR_NORMAL_Y) world.events.recordFloor(p, 0)
            // where on the body it touched, in the body's own frame, for its grip
            val c = bodies.cos[body]
            val s = bodies.sin[body]
            val grip = Material.mixFriction(ps.friction[p], bodies.friction[body])
            val hold = Material.mixFriction(ps.staticFriction[p], bodies.staticFriction[body])
            ps.touchBody(p, -nx, -ny, depth, grip, hold, body, c * rx + s * ry, -s * rx + c * ry)
        }
    }

    /**
     * Pushes every sharp corner of rigid [body] that is inside soft body [s]'s outline out to the
     * outline's nearest edge, and that edge's two particles back.
     */
    private fun cornersAgainstSoftBody(body: Int, s: Int) {
        val shapes = bodies.shapes[body]!!
        val ring = soft.ring(s)
        val c = bodies.cos[body]
        val sn = bodies.sin[body]
        // the outline's box: a corner outside it isn't inside the outline, and needn't be looked for
        var left = Float.MAX_VALUE
        var top = Float.MAX_VALUE
        var right = -Float.MAX_VALUE
        var bottom = -Float.MAX_VALUE
        for (k in 0 until soft.ringCount(s)) {
            val p = soft.particle(s, k)
            left = min(left, ps.x[p])
            top = min(top, ps.y[p])
            right = max(right, ps.x[p])
            bottom = max(bottom, ps.y[p])
        }
        for (si in shapes.indices) {
            val shape = shapes[si]
            if (shape.radius > 0f || shape.vertexCount < 3) continue
            for (k in 0 until shape.vertexCount) {
                val rx = c * shape.vx[k] - sn * shape.vy[k]
                val ry = sn * shape.vx[k] + c * shape.vy[k]
                val x = bodies.x[body] + rx
                val y = bodies.y[body] + ry
                if (x < left || x > right || y < top || y > bottom || !Outline.contains(ring, x, y)) continue
                nearestEdge(s, x, y)
                val distance = sqrt(nearDistanceSquared)
                if (distance < TOUCHING) continue
                val nx = (nearX - x) / distance
                val ny = (nearY - y) / distance
                val n = soft.ringCount(s)
                val a = soft.particle(s, nearEdge)
                val b = soft.particle(s, if (nearEdge + 1 == n) 0 else nearEdge + 1)
                val t = nearT
                val wa = ps.invMass[a] * (1f - t)
                val wb = ps.invMass[b] * t
                val w = bodies.inverseMassAt(body, rx, ry, nx, ny) + wa * (1f - t) + wb * t
                if (w == 0f) continue
                val push = (distance + SKIN) / w
                bodies.shove(body, push * nx, push * ny, rx, ry)
                ps.x[a] -= wa * push * nx
                ps.y[a] -= wa * push * ny
                ps.x[b] -= wb * push * nx
                ps.y[b] -= wb * push * ny
            }
        }
    }

    /** Finds the edge of soft body [s]'s outline nearest ([x], [y]), into the near- fields. */
    private fun nearestEdge(s: Int, x: Float, y: Float) {
        val n = soft.ringCount(s)
        nearDistanceSquared = Float.MAX_VALUE
        for (e in 0 until n) {
            val a = soft.particle(s, e)
            val b = soft.particle(s, if (e + 1 == n) 0 else e + 1)
            val ex = ps.x[b] - ps.x[a]
            val ey = ps.y[b] - ps.y[a]
            val lengthSquared = ex * ex + ey * ey
            val t = if (lengthSquared == 0f) 0f else (((x - ps.x[a]) * ex + (y - ps.y[a]) * ey) / lengthSquared).coerceIn(0f, 1f)
            val qx = ps.x[a] + ex * t
            val qy = ps.y[a] + ey * t
            val d = (x - qx) * (x - qx) + (y - qy) * (y - qy)
            if (d < nearDistanceSquared) {
                nearDistanceSquared = d
                nearEdge = e
                nearT = t
                nearX = qx
                nearY = qy
            }
        }
    }

    private fun id(index: Int, kind: Int) = KINDS * index + kind

    private companion object {
        const val PARTICLE = 0
        const val SOFT = 1
        const val RIGID = 2
        const val KINDS = 3

        /** Slack around every box, so what drifts a little further than its velocity said is still found. */
        const val MARGIN = 1f

        /** How far a particle of no radius keeps from a rigid body's surface, as from a soft body's skin. */
        const val SKIN = 0.5f

        /** Closer than this is touching, with no direction to push in. */
        const val TOUCHING = 0.0001f

        /** A push this far upward or more lifts what it pushes: the same 60 degrees as the solids' floors. */
        const val MIN_FLOOR_NORMAL_Y = -0.5f
    }
}
