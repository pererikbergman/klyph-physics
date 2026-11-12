package klyph.physics

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Where two shapes touch: up to two contact points sharing one [normal], pointing from the first
 * shape to the second. Each point has where it is on each shape's surface and how far apart the two
 * are along the normal: its [separation], negative when they overlap. Two points are what keeps a box
 * flat on another, rather than rocking on one corner.
 *
 * Each point also has a feature [id] naming the two edges or corners that made it, so the same
 * contact can be told apart from a new one from one substep to the next.
 */
class Manifold {
    /** How many points: 0 when the shapes don't touch, else 1 or 2. */
    var count: Int = 0
        internal set

    /** The unit normal, from the first shape to the second. */
    var normalX: Float = 0f
        internal set
    var normalY: Float = 0f
        internal set

    internal val ax = FloatArray(2)
    internal val ay = FloatArray(2)
    internal val bx = FloatArray(2)
    internal val by = FloatArray(2)
    internal val sep = FloatArray(2)
    internal val ids = IntArray(2)

    /** Point [i] on the first shape's surface. */
    fun pointAX(i: Int): Float = ax[i]

    fun pointAY(i: Int): Float = ay[i]

    /** Point [i] on the second shape's surface. */
    fun pointBX(i: Int): Float = bx[i]

    fun pointBY(i: Int): Float = by[i]

    /** How far apart point [i]'s two surface points are along the normal: negative when they overlap, by that much. */
    fun separation(i: Int): Float = sep[i]

    /** Which edges or corners made point [i]: the same for the same contact from one substep to the next. */
    fun id(i: Int): Int = ids[i]

    internal fun add(pax: Float, pay: Float, pbx: Float, pby: Float, separation: Float, id: Int) {
        val i = count++
        ax[i] = pax
        ay[i] = pay
        bx[i] = pbx
        by[i] = pby
        sep[i] = separation
        ids[i] = id
    }

    /** Swaps the two shapes: the normal turns round, and each point's two ends trade places. */
    internal fun flip() {
        normalX = -normalX
        normalY = -normalY
        for (i in 0 until count) {
            val x = ax[i]
            val y = ay[i]
            ax[i] = bx[i]
            ay[i] = by[i]
            bx[i] = x
            by[i] = y
        }
    }
}

/**
 * Exact contact points between two [Shape]s: the narrowphase, run on the pairs the broadphase finds.
 *
 * Every shape is a convex core grown by a radius, so there are three cases:
 * - **two points** (circles): along the line between them
 * - **a point and a polygon or segment**: from the core's nearest point, or out through its nearest
 *   face when the point is inside it
 * - **two polygons or segments**: the separating axis test (SAT). Each face of each core is tried as
 *   a wall between them; the one they reach through least is the contact's face. The other shape's
 *   edge most against it is clipped to that face's ends, and what's left that reaches through gives
 *   up to two points. Rounded cores that don't quite touch, such as a capsule near a box's corner, are
 *   measured exactly instead, from their two nearest points.
 *
 * One instance keeps its scratch arrays between calls, so it allocates nothing.
 */
class Narrowphase {
    /**
     * Shapes this far apart, or nearer, already count as touching, with a positive separation: a
     * solver uses that to catch a contact that closes during its substep. 0 by default.
     */
    var margin: Float = 0f
        set(value) {
            require(value >= 0f) { "margin is $value: it must not be negative" }
            field = value
        }

    // Each shape's core in the world: its points and normals, and its radius.
    private val axs = FloatArray(Shape.MAX_VERTICES)
    private val ays = FloatArray(Shape.MAX_VERTICES)
    private val anx = FloatArray(Shape.MAX_VERTICES)
    private val any = FloatArray(Shape.MAX_VERTICES)
    private var an = 0
    private var ar = 0f
    private val bxs = FloatArray(Shape.MAX_VERTICES)
    private val bys = FloatArray(Shape.MAX_VERTICES)
    private val bnx = FloatArray(Shape.MAX_VERTICES)
    private val bny = FloatArray(Shape.MAX_VERTICES)
    private var bn = 0
    private var br = 0f

    // The closest points between two segments, from [closestPoints].
    private var closeAX = 0f
    private var closeAY = 0f
    private var closeBX = 0f
    private var closeBY = 0f

    /**
     * Finds where shape [a], at ([ax], [ay]) turned by [aAngle], touches shape [b] at ([bx], [by])
     * turned by [bAngle], into [out]. Returns whether they touch.
     */
    fun collide(a: Shape, ax: Float, ay: Float, aAngle: Float, b: Shape, bx: Float, by: Float, bAngle: Float, out: Manifold): Boolean =
        collide(a, ax, ay, cos(aAngle), sin(aAngle), b, bx, by, cos(bAngle), sin(bAngle), out)

    /** [collide], with each angle given by its cosine and sine. */
    internal fun collide(
        a: Shape, ax: Float, ay: Float, ac: Float, asn: Float,
        b: Shape, bx: Float, by: Float, bc: Float, bsn: Float,
        out: Manifold,
    ): Boolean {
        an = place(a, ax, ay, ac, asn, axs, ays, anx, any)
        ar = a.radius
        bn = place(b, bx, by, bc, bsn, bxs, bys, bnx, bny)
        br = b.radius
        return collidePlaced(out)
    }

    /**
     * Finds where [a] touches the one-sided segment from ([x1], [y1]) to ([x2], [y2]), with its outward
     * normal ([nx], [ny]), into [out], with the segment as the second shape.
     */
    internal fun collideSegment(a: Shape, ax: Float, ay: Float, ac: Float, asn: Float, x1: Float, y1: Float, x2: Float, y2: Float, nx: Float, ny: Float, out: Manifold): Boolean {
        an = place(a, ax, ay, ac, asn, axs, ays, anx, any)
        ar = a.radius
        bn = 2
        br = 0f
        bxs[0] = x1
        bys[0] = y1
        bxs[1] = x2
        bys[1] = y2
        bnx[0] = nx
        bny[0] = ny
        bnx[1] = -nx
        bny[1] = -ny
        return collidePlaced(out)
    }

    private fun collidePlaced(out: Manifold): Boolean {
        out.count = 0
        when {
            an == 1 && bn == 1 -> circles(out)
            an == 1 -> pointAgainstCore(axs[0], ays[0], ar, bxs, bys, bnx, bny, bn, br, out)
            bn == 1 -> {
                pointAgainstCore(bxs[0], bys[0], br, axs, ays, anx, any, an, ar, out)
                out.flip()
            }
            else -> cores(out)
        }
        return out.count > 0
    }

    /** Puts [shape]'s core at ([x], [y]), turned by the angle with cosine [c] and sine [s], into the arrays; returns its point count. */
    private fun place(shape: Shape, x: Float, y: Float, c: Float, s: Float, px: FloatArray, py: FloatArray, nx: FloatArray, ny: FloatArray): Int {
        val n = shape.vx.size
        for (i in 0 until n) {
            val lx = shape.vx[i]
            val ly = shape.vy[i]
            px[i] = x + c * lx - s * ly
            py[i] = y + s * lx + c * ly
        }
        for (i in shape.nx.indices) {
            val lx = shape.nx[i]
            val ly = shape.ny[i]
            nx[i] = c * lx - s * ly
            ny[i] = s * lx + c * ly
        }
        return n
    }

    private fun circles(out: Manifold) {
        var nx = bxs[0] - axs[0]
        var ny = bys[0] - ays[0]
        val d = sqrt(nx * nx + ny * ny)
        val separation = d - ar - br
        if (separation > margin) return
        if (d < TINY) {
            nx = 0f
            ny = 1f
        } else {
            nx /= d
            ny /= d
        }
        out.normalX = nx
        out.normalY = ny
        out.add(axs[0] + nx * ar, ays[0] + ny * ar, bxs[0] - nx * br, bys[0] - ny * br, separation, 0)
    }

    /**
     * A point core at ([cx], [cy]) with radius [cr], as the first shape, against the core of [n] points
     * [px], [py] with radius [r], as the second.
     */
    private fun pointAgainstCore(cx: Float, cy: Float, cr: Float, px: FloatArray, py: FloatArray, pnx: FloatArray, pny: FloatArray, n: Int, r: Float, out: Manifold) {
        if (n >= 3) {
            // Inside the core: out through the face it is least deep behind.
            var best = 0
            var deepest = -Float.MAX_VALUE
            for (i in 0 until n) {
                val s = (cx - px[i]) * pnx[i] + (cy - py[i]) * pny[i]
                if (s > deepest) {
                    deepest = s
                    best = i
                }
            }
            if (deepest <= 0f) {
                // normal from the point to the polygon: into the face
                val nx = -pnx[best]
                val ny = -pny[best]
                val separation = deepest - cr - r
                out.normalX = nx
                out.normalY = ny
                // on the face, then out by the polygon's radius
                val fx = cx + nx * deepest
                val fy = cy + ny * deepest
                out.add(cx + nx * cr, cy + ny * cr, fx - nx * r, fy - ny * r, separation, best)
                return
            }
        }
        // Outside it: from the nearest point on it.
        var nearest = Float.MAX_VALUE
        var qx = 0f
        var qy = 0f
        var feature = 0
        val edges = if (n == 2) 1 else n
        for (i in 0 until edges) {
            val j = if (i + 1 == n) 0 else i + 1
            val ex = px[j] - px[i]
            val ey = py[j] - py[i]
            val lengthSquared = ex * ex + ey * ey
            val t = if (lengthSquared == 0f) 0f else (((cx - px[i]) * ex + (cy - py[i]) * ey) / lengthSquared).coerceIn(0f, 1f)
            val x = px[i] + ex * t
            val y = py[i] + ey * t
            val d = (cx - x) * (cx - x) + (cy - y) * (cy - y)
            if (d < nearest) {
                nearest = d
                qx = x
                qy = y
                feature = i
            }
        }
        val d = sqrt(nearest)
        val separation = d - cr - r
        if (separation > margin) return
        var nx: Float
        var ny: Float
        if (d < TINY) {
            // right on a segment's line: out along its normal, whichever side the point came from
            nx = -pnx[feature]
            ny = -pny[feature]
        } else {
            nx = (qx - cx) / d
            ny = (qy - cy) / d
        }
        out.normalX = nx
        out.normalY = ny
        out.add(cx + nx * cr, cy + ny * cr, qx - nx * r, qy - ny * r, separation, feature)
    }

    /** Two cores of two or more points each: polygons and segments. */
    private fun cores(out: Manifold) {
        val edgeA = maxSeparation(axs, ays, anx, any, an, bxs, bys, bn)
        val sepA = foundSeparation
        val edgeB = maxSeparation(bxs, bys, bnx, bny, bn, axs, ays, an)
        val sepB = foundSeparation
        val radii = ar + br
        if (sepA > radii + margin || sepB > radii + margin) return
        // Prefer A's face unless B's is clearly better, so a resting stack keeps choosing the same one.
        val flip = sepB > sepA + TOLERANCE
        val sep = if (flip) sepB else sepA
        // Cores apart, near enough for their radii (or the margin) to reach: measure them exactly.
        var measured = false
        var nx = 0f
        var ny = 0f
        var pax = 0f
        var pay = 0f
        var pbx = 0f
        var pby = 0f
        var d = 0f
        var feature = 0
        if (radii > 0f && sep > SLOP || sep > margin) {
            var best = Float.MAX_VALUE
            val edgesA = if (an == 2) 1 else an
            val edgesB = if (bn == 2) 1 else bn
            for (i in 0 until edgesA) {
                val i1 = if (i + 1 == an) 0 else i + 1
                for (j in 0 until edgesB) {
                    val j1 = if (j + 1 == bn) 0 else j + 1
                    val squared = closestPoints(axs[i], ays[i], axs[i1], ays[i1], bxs[j], bys[j], bxs[j1], bys[j1])
                    if (squared < best) {
                        best = squared
                        pax = closeAX
                        pay = closeAY
                        pbx = closeBX
                        pby = closeBY
                        feature = (i shl 8) or j or ROUNDED
                    }
                }
            }
            d = sqrt(best)
            if (d - radii > margin) return
            nx = (pbx - pax) / d
            ny = (pby - pay) / d
            measured = true
            // Leaning away from the face's own normal: a corner or a round end, one point.
            val faceX = if (flip) -bnx[edgeB] else anx[edgeA]
            val faceY = if (flip) -bny[edgeB] else any[edgeA]
            if (nx * faceX + ny * faceY < PARALLEL) {
                nearestPoints(nx, ny, pax, pay, pbx, pby, d - radii, feature, out)
                return
            }
        }
        // Edge on edge: clip for two points, not one.
        if (flip) {
            clip(bxs, bys, bnx, bny, bn, br, edgeB, axs, ays, anx, any, an, ar, out)
            out.flip()
            for (i in 0 until out.count) out.ids[i] = out.ids[i] or FLIPPED
        } else {
            clip(axs, ays, anx, any, an, ar, edgeA, bxs, bys, bnx, bny, bn, br, out)
        }
        // Along the face's normal, but just past its end, so clipping left nothing: the one nearest point.
        if (out.count == 0 && measured) nearestPoints(nx, ny, pax, pay, pbx, pby, d - radii, feature, out)
    }

    /** One contact along ([nx], [ny]) from the cores' nearest points, out to each shape's surface by its radius. */
    private fun nearestPoints(nx: Float, ny: Float, pax: Float, pay: Float, pbx: Float, pby: Float, separation: Float, feature: Int, out: Manifold) {
        out.normalX = nx
        out.normalY = ny
        out.add(pax + nx * ar, pay + ny * ar, pbx - nx * br, pby - ny * br, separation, feature)
    }

    private var foundSeparation = 0f

    /**
     * The face of core 1 that core 2 reaches through least, and in [foundSeparation] how far core 2 is
     * outside it: negative when every face is reached through.
     */
    private fun maxSeparation(x1: FloatArray, y1: FloatArray, nx1: FloatArray, ny1: FloatArray, n1: Int, x2: FloatArray, y2: FloatArray, n2: Int): Int {
        var best = 0
        var bestSeparation = -Float.MAX_VALUE
        for (i in 0 until n1) {
            var least = Float.MAX_VALUE
            for (j in 0 until n2) {
                val s = (x2[j] - x1[i]) * nx1[i] + (y2[j] - y1[i]) * ny1[i]
                if (s < least) least = s
            }
            if (least > bestSeparation) {
                bestSeparation = least
                best = i
            }
        }
        foundSeparation = bestSeparation
        return best
    }

    /**
     * Contact points with face [edge] of the reference core (points [rx], [ry], radius [rr]) against
     * the incident core's edge most against it, clipped to the face's ends. The reference core is the
     * first shape of [out].
     */
    private fun clip(
        rx: FloatArray, ry: FloatArray, rnx: FloatArray, rny: FloatArray, rn: Int, rr: Float, edge: Int,
        ix: FloatArray, iy: FloatArray, inx: FloatArray, iny: FloatArray, inn: Int, ir: Float,
        out: Manifold,
    ) {
        val e1 = if (edge + 1 == rn) 0 else edge + 1
        val v1x = rx[edge]
        val v1y = ry[edge]
        val v2x = rx[e1]
        val v2y = ry[e1]
        val nx = rnx[edge]
        val ny = rny[edge]
        // The incident edge: the one whose normal points most against the face's.
        var incident = 0
        var most = Float.MAX_VALUE
        for (k in 0 until inn) {
            val d = inx[k] * nx + iny[k] * ny
            if (d < most) {
                most = d
                incident = k
            }
        }
        val i1 = if (incident + 1 == inn) 0 else incident + 1
        var p1x = ix[incident]
        var p1y = iy[incident]
        var p2x = ix[i1]
        var p2y = iy[i1]
        var id1 = (edge shl 8) or incident
        var id2 = (edge shl 8) or i1
        // Clip to between the face's two ends, along its tangent.
        val tx = v2x - v1x
        val ty = v2y - v1y
        val length = sqrt(tx * tx + ty * ty)
        val ux = tx / length
        val uy = ty / length
        val lo = v1x * ux + v1y * uy
        val hi = v2x * ux + v2y * uy
        var d1 = p1x * ux + p1y * uy
        var d2 = p2x * ux + p2y * uy
        if (d1 < lo && d2 < lo || d1 > hi && d2 > hi) return
        if (d1 < lo) {
            val t = (lo - d1) / (d2 - d1)
            p1x += (p2x - p1x) * t
            p1y += (p2y - p1y) * t
            d1 = lo
            id1 = id1 or CLIPPED
        } else if (d2 < lo) {
            val t = (lo - d2) / (d1 - d2)
            p2x += (p1x - p2x) * t
            p2y += (p1y - p2y) * t
            d2 = lo
            id2 = id2 or CLIPPED
        }
        if (d1 > hi) {
            val t = (d1 - hi) / (d1 - d2)
            p1x += (p2x - p1x) * t
            p1y += (p2y - p1y) * t
            id1 = id1 or CLIPPED_HIGH
        } else if (d2 > hi) {
            val t = (d2 - hi) / (d2 - d1)
            p2x += (p1x - p2x) * t
            p2y += (p1y - p2y) * t
            id2 = id2 or CLIPPED_HIGH
        }
        out.normalX = nx
        out.normalY = ny
        val radii = rr + ir
        val s1 = (p1x - v1x) * nx + (p1y - v1y) * ny
        val s2 = (p2x - v1x) * nx + (p2y - v1y) * ny
        // A rounded edge that leans to the face touches it with one end only: its radius reaches out
        // along the normal from its ends, but not from the middle of a leaning edge.
        var keep1 = s1 - radii <= margin
        var keep2 = s2 - radii <= margin
        if (ir > 0f) {
            val ex = p2x - p1x
            val ey = p2y - p1y
            val length = sqrt(ex * ex + ey * ey)
            if (length > TINY && abs(ex * nx + ey * ny) > LEANING * length) {
                if (s1 <= s2) keep2 = false else keep1 = false
            }
        }
        if (keep1) out.add(p1x - nx * (s1 - rr), p1y - ny * (s1 - rr), p1x - nx * ir, p1y - ny * ir, s1 - radii, id1)
        if (keep2) out.add(p2x - nx * (s2 - rr), p2y - ny * (s2 - rr), p2x - nx * ir, p2y - ny * ir, s2 - radii, id2)
    }

    /**
     * The closest points between the segments p1-q1 and p2-q2, into the close- fields, and the
     * distance between them squared (Ericson, Real-Time Collision Detection, 5.1.9).
     */
    private fun closestPoints(p1x: Float, p1y: Float, q1x: Float, q1y: Float, p2x: Float, p2y: Float, q2x: Float, q2y: Float): Float {
        val d1x = q1x - p1x
        val d1y = q1y - p1y
        val d2x = q2x - p2x
        val d2y = q2y - p2y
        val rx = p1x - p2x
        val ry = p1y - p2y
        val a = d1x * d1x + d1y * d1y
        val e = d2x * d2x + d2y * d2y
        val f = d2x * rx + d2y * ry
        var s: Float
        var t: Float
        if (a <= TINY && e <= TINY) {
            s = 0f
            t = 0f
        } else if (a <= TINY) {
            s = 0f
            t = (f / e).coerceIn(0f, 1f)
        } else {
            val c = d1x * rx + d1y * ry
            if (e <= TINY) {
                t = 0f
                s = (-c / a).coerceIn(0f, 1f)
            } else {
                val b = d1x * d2x + d1y * d2y
                val denominator = a * e - b * b
                s = if (abs(denominator) > TINY) ((b * f - c * e) / denominator).coerceIn(0f, 1f) else 0f
                t = (b * s + f) / e
                if (t < 0f) {
                    t = 0f
                    s = (-c / a).coerceIn(0f, 1f)
                } else if (t > 1f) {
                    t = 1f
                    s = ((b - c) / a).coerceIn(0f, 1f)
                }
            }
        }
        closeAX = p1x + d1x * s
        closeAY = p1y + d1y * s
        closeBX = p2x + d2x * t
        closeBY = p2y + d2y * t
        val dx = closeBX - closeAX
        val dy = closeBY - closeAY
        return dx * dx + dy * dy
    }

    internal companion object {
        const val TINY = 1e-9f

        /** Cores this far apart, or less, count as touching, and are clipped rather than measured. */
        const val SLOP = 0.05f

        /** How much more one face must be reached through less than the other's to be chosen over it. */
        const val TOLERANCE = 0.01f

        /** A rounded edge leaning more than this, as the sine of its angle to a face, touches it at one end only. */
        const val LEANING = 0.01f

        /** The cosine of the most a rounded contact's normal may lean from its face to still be clipped for two points. */
        const val PARALLEL = 0.999f

        // Feature id flags: whose face it was, and which end of the face a point was clipped to.
        const val FLIPPED = 1 shl 16
        const val CLIPPED = 1 shl 17
        const val CLIPPED_HIGH = 1 shl 18
        const val ROUNDED = 1 shl 19
    }
}
