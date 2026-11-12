package klyph.physics

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Sweeps bullets, so they can't pass through anything however fast they go. A bullet, a rigid body or
 * a particle flagged with `setBullet`, isn't held to [PhysicsConfig.maxStep]: in one substep it may
 * jump clean over a thin wall. So after the substep's move, each one is walked from where it was to
 * where it went in steps no longer than half its own thickness, so it can't step over anything,
 * however thin, and at the first step where it overlaps something, it's put back to just where it
 * first touches: a few halvings between that step and the one before. Its velocity is kept, so the
 * contact found there stops it, or bounces it, as hard as it came in. Something it was already
 * touching at the start of the substep only counts once it's reached a margin deeper into it than it
 * was, so a bullet rolls along a floor, but can't pivot on the wall it hit and swing through it.
 *
 * A rigid bullet is swept against the other bodies and the solids' edges; a particle against the
 * solids. The contacts then find what it touches from where it was at the start of the substep, so
 * their normals point back the way it came: see [RigidContacts].
 */
internal class Bullets(private val world: PhysicsWorld) {
    private val bodies = world.rigidBodies
    private val narrow = Narrowphase().apply { margin = RigidContacts.MARGIN }
    private val manifold = Manifold()

    // The bodies and edges a bullet might hit this substep, and for each how deep it may reach into
    // it before that counts: 0 for one it wasn't near at the start, a little deeper than it was for
    // one it was already touching, so it can roll along a floor but not swing through a wall.
    private var candidates = IntArray(16)
    private var allowed = FloatArray(16)
    private var candidateCount = 0
    private var edges = IntArray(16)
    private var edgeAllowed = FloatArray(16)
    private var edgeCount = 0

    // The pose being tried: where, and turned how much.
    private var poseX = 0f
    private var poseY = 0f
    private var poseCos = 1f
    private var poseSin = 0f

    /** Sweeps every awake rigid bullet over the substep just moved. */
    fun sweepBodies() {
        val bs = bodies
        for (b in 0 until bs.end) {
            if (!bs.bullet[b] || !bs.alive[b] || bs.kind[b] != RigidBodies.DYNAMIC || bs.asleep[b]) continue
            sweep(b)
        }
    }

    private fun sweep(b: Int) {
        val bs = bodies
        val dx = bs.x[b] - bs.prevX[b]
        val dy = bs.y[b] - bs.prevY[b]
        val turn = bs.angle[b] - bs.prevAngle[b]
        val distance = sqrt(dx * dx + dy * dy) + abs(turn) * bs.reach[b]
        val step = bs.thickness[b] / 2f
        // moving less than half its thickness, the contacts catch it as they do anything else
        if (distance <= step || step <= 0f) return
        gather(b, dx, dy)
        if (candidateCount == 0 && edgeCount == 0) return
        val samples = (distance / step).toInt() + 1
        var before = 0f
        for (k in 1..samples) {
            val t = k.toFloat() / samples
            if (!hits(b, t, dx, dy)) {
                before = t
                continue
            }
            // it first overlaps between `before` and `t`: halve the gap until the overlap is a sliver
            var hit = t
            repeat(HALVINGS) {
                val mid = (before + hit) / 2f
                if (hits(b, mid, dx, dy)) hit = mid else before = mid
            }
            place(b, hit)
            bs.x[b] = poseX
            bs.y[b] = poseY
            bs.angle[b] = bs.prevAngle[b] + turn * hit
            bs.cos[b] = poseCos
            bs.sin[b] = poseSin
            return
        }
    }

    /**
     * Collects what bullet [b], moving by ([dx], [dy]) this substep, might hit: the bodies the
     * broadphase paired it with and the edges it's moving into, each with how deep it may go.
     */
    private fun gather(b: Int, dx: Float, dy: Float) {
        val bs = bodies
        val contacts = world.rigidContacts
        candidateCount = 0
        edgeCount = 0
        place(b, 0f)
        val bp = contacts.broadphase
        for (i in 0 until bp.pairCount) {
            val other = when (b) {
                bp.pairA(i) -> bp.pairB(i)
                bp.pairB(i) -> bp.pairA(i)
                else -> continue
            }
            if (!bs.alive[other] || bs.bullet[other] || world.joints.connected(b, other)) continue
            if (candidateCount == candidates.size) {
                candidates = candidates.copyOf(candidateCount * 2)
                allowed = allowed.copyOf(candidateCount * 2)
            }
            val depth = depthIn(b, other)
            allowed[candidateCount] = if (depth > -RigidContacts.MARGIN) max(depth, 0f) + RigidContacts.MARGIN else 0f
            candidates[candidateCount++] = other
        }
        val grid = world.solids
        if (grid != null) {
            contacts.updateEdges(grid)
            val ed = contacts.edges
            val r = bs.reach[b] + RigidContacts.MARGIN
            val x0 = bs.prevX[b]
            val y0 = bs.prevY[b]
            ed.near(min(x0, x0 + dx) - r, min(y0, y0 + dy) - r, max(x0, x0 + dx) + r, max(y0, y0 + dy) + r)
            for (k in 0 until ed.foundCount) {
                val e = ed.found[k]
                // only an edge it's moving into
                if (ed.nx[e] * dx + ed.ny[e] * dy >= 0f) continue
                if (edgeCount == edges.size) {
                    edges = edges.copyOf(edgeCount * 2)
                    edgeAllowed = edgeAllowed.copyOf(edgeCount * 2)
                }
                val depth = -nearestTo(b, e)
                edgeAllowed[edgeCount] = if (depth > -RigidContacts.MARGIN) max(depth, 0f) + RigidContacts.MARGIN else 0f
                edges[edgeCount++] = e
            }
        }
    }

    /** Whether bullet [b], [t] of the way through its substep's move, overlaps a candidate or crosses a candidate edge. */
    private fun hits(b: Int, t: Float, dx: Float, dy: Float): Boolean {
        place(b, t)
        for (k in 0 until candidateCount) if (depthIn(b, candidates[k]) > allowed[k]) return true
        if (edgeCount == 0) return false
        // a point further behind an edge than this has come through the solid from elsewhere, not this way
        val deepest = bodies.thickness[b] + sqrt(dx * dx + dy * dy) / 2f
        for (k in 0 until edgeCount) {
            val depth = -nearestTo(b, edges[k])
            if (depth > edgeAllowed[k] && depth < deepest + edgeAllowed[k]) return true
        }
        return false
    }

    /**
     * How deep bullet [b], at the pose being tried, reaches into body [other]: how far its deepest
     * point is through, or minus how far apart they are when they're within a contact's reach.
     * -[Float.MAX_VALUE] when they're further apart.
     */
    private fun depthIn(b: Int, other: Int): Float {
        val bs = bodies
        val mine = bs.shapes[b]!!
        val theirs = bs.shapes[other]!!
        var deepest = -Float.MAX_VALUE
        for (sa in mine) for (sb in theirs) {
            if (!narrow.collide(sa, poseX, poseY, poseCos, poseSin, sb, bs.x[other], bs.y[other], bs.cos[other], bs.sin[other], manifold)) continue
            for (k in 0 until manifold.count) deepest = max(deepest, -manifold.separation(k))
        }
        return deepest
    }

    /**
     * How far bullet [b], at the pose being tried, is in front of edge [e]: the least, over every point
     * of its shapes' cores that lies along the edge, of how far it is out of the solid less its radius.
     * Negative where it reaches through. [Float.MAX_VALUE] if no point lies along it.
     */
    private fun nearestTo(b: Int, e: Int): Float {
        val ed = world.rigidContacts.edges
        val ex = ed.x1[e]
        val ey = ed.y1[e]
        val nx = ed.nx[e]
        val ny = ed.ny[e]
        val tx = -ny
        val ty = nx
        val length = (ed.x2[e] - ex) * tx + (ed.y2[e] - ey) * ty
        var nearest = Float.MAX_VALUE
        for (shape in bodies.shapes[b]!!) {
            for (i in 0 until shape.vertexCount) {
                val vx = poseX + poseCos * shape.vx[i] - poseSin * shape.vy[i]
                val vy = poseY + poseSin * shape.vx[i] + poseCos * shape.vy[i]
                val along = (vx - ex) * tx + (vy - ey) * ty
                if (along < -shape.radius || along > length + shape.radius) continue
                nearest = min(nearest, (vx - ex) * nx + (vy - ey) * ny - shape.radius)
            }
        }
        return nearest
    }

    /** Sets the pose being tried to bullet [b]'s, [t] of the way from where it was to where it is. */
    private fun place(b: Int, t: Float) {
        val bs = bodies
        poseX = bs.prevX[b] + (bs.x[b] - bs.prevX[b]) * t
        poseY = bs.prevY[b] + (bs.y[b] - bs.prevY[b]) * t
        val angle = bs.prevAngle[b] + (bs.angle[b] - bs.prevAngle[b]) * t
        poseCos = cos(angle)
        poseSin = sin(angle)
    }

    /**
     * Sweeps every bullet particle over the substep just moved, against the solids: walked in steps
     * no longer than a quarter of a tile, the thinnest a solid is half of, and put back to where it
     * first reaches into one, keeping its velocity.
     */
    fun sweepParticles(particles: Particles) {
        val grid = world.solids ?: return
        val step = grid.tileSize / 4f
        for (p in 0 until particles.end) {
            if (particles.flags[p] and Particles.BULLET == 0 || particles.invMass[p] == 0f) continue
            val x0 = particles.prevX[p]
            val y0 = particles.prevY[p]
            val dx = particles.x[p] - x0
            val dy = particles.y[p] - y0
            val distance = sqrt(dx * dx + dy * dy)
            if (distance <= step) continue
            val r = particles.radius[p]
            // already in one at the start, the solids push it out as they would anything
            if (inSolid(grid, x0, y0, r)) continue
            val samples = (distance / step).toInt() + 1
            var before = 0f
            for (k in 1..samples) {
                val t = k.toFloat() / samples
                if (!inSolid(grid, x0 + dx * t, y0 + dy * t, r)) {
                    before = t
                    continue
                }
                var hit = t
                repeat(HALVINGS) {
                    val mid = (before + hit) / 2f
                    if (inSolid(grid, x0 + dx * mid, y0 + dy * mid, r)) hit = mid else before = mid
                }
                // there, with the whole move behind it, so it hits as fast as it came
                val x = x0 + dx * hit
                val y = y0 + dy * hit
                particles.x[p] = x
                particles.y[p] = y
                particles.prevX[p] = x - dx
                particles.prevY[p] = y - dy
                break
            }
        }
    }

    private fun inSolid(grid: SolidGrid, x: Float, y: Float, r: Float): Boolean {
        val tile = grid.tileSize
        val col0 = floor((x - r) / tile).toInt()
        val col1 = floor((x + r) / tile).toInt()
        val row0 = floor((y - r) / tile).toInt()
        val row1 = floor((y + r) / tile).toInt()
        for (row in row0..row1) for (col in col0..col1) {
            val solid = grid.at(col, row) ?: continue
            if (solid.overlaps(x, y, r)) return true
        }
        for (k in 0 until grid.dynamic.size) if (grid.dynamic[k].overlaps(x, y, r)) return true
        return false
    }

    private companion object {
        /** How many times the step it hits in is halved: to a 256th of it. */
        const val HALVINGS = 8
    }
}

