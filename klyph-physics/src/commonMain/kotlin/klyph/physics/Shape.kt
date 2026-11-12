package klyph.physics

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** What a [Shape] is. Every one is a convex core, a point, a segment or a polygon, grown by a radius. */
enum class ShapeKind {
    /** A point grown by its radius. */
    CIRCLE,

    /** A segment grown by its radius: a pill, a rounded rod. */
    CAPSULE,

    /** A convex polygon, with sharp corners. */
    POLYGON,
}

/**
 * What a rigid body is made of: a convex shape, in the body's own units, around the body's origin.
 * Make one with [circle], [box], [polygon] or [capsule]; a body made of several is a compound body,
 * such as a hammer of two boxes.
 *
 * Inside, every shape is a convex **core**, one point for a circle, two for a capsule, three or more
 * for a polygon, grown by a [radius]: a circle is a point grown by its radius, a capsule a segment, a
 * polygon has a radius of 0. One way of describing all of them means one set of collision code.
 *
 * A polygon's points go clockwise on screen (y grows down), and each edge `i`, from point `i` to the
 * next, has a unit normal pointing out. A shape never changes once made, so any number of bodies can
 * share one.
 */
class Shape private constructor(
    val kind: ShapeKind,
    internal val vx: FloatArray,
    internal val vy: FloatArray,
    /** How far the shape reaches out from its core: a circle's or capsule's radius, 0 for a polygon. */
    val radius: Float,
) {
    /** How many points the core has: 1 for a circle, 2 for a capsule, 3 or more for a polygon. */
    val vertexCount: Int get() = vx.size

    // The unit normal of each edge, pointing out: a capsule's two edges are its segment, both ways.
    internal val nx = FloatArray(if (vx.size > 1) vx.size else 0)
    internal val ny = FloatArray(nx.size)

    /** How much space the shape covers, in units squared. */
    val area: Float

    /** The middle of the shape, by area: where a body made of it alone balances. */
    val centroidX: Float
    val centroidY: Float

    /** How hard the shape is to turn about its [centroidX], [centroidY], for a density of 1: its moment of inertia. */
    val inertia: Float

    init {
        for (i in nx.indices) {
            val j = if (i + 1 == vx.size) 0 else i + 1
            val ex = vx[j] - vx[i]
            val ey = vy[j] - vy[i]
            val length = sqrt(ex * ex + ey * ey)
            nx[i] = ey / length
            ny[i] = -ex / length
        }
        when (kind) {
            ShapeKind.CIRCLE -> {
                area = PI_F * radius * radius
                centroidX = vx[0]
                centroidY = vy[0]
                inertia = area * radius * radius / 2f
            }
            ShapeKind.CAPSULE -> {
                // A box between the two caps' centres, and a disc split in half over its two ends.
                val ex = vx[1] - vx[0]
                val ey = vy[1] - vy[0]
                val length = sqrt(ex * ex + ey * ey)
                val rr = radius * radius
                val disc = PI_F * rr
                val box = 2f * radius * length
                area = disc + box
                centroidX = (vx[0] + vx[1]) / 2f
                centroidY = (vy[0] + vy[1]) / 2f
                // Each half disc's own centroid is 4r / 3π from its flat side; the parallel axis
                // theorem moves it there from the half's centre, then out to its end of the box.
                val lc = 4f * radius / (3f * PI_F)
                val h = length / 2f
                inertia = disc * (rr / 2f + h * h + 2f * h * lc) + box * (4f * rr + length * length) / 12f
            }
            ShapeKind.POLYGON -> {
                // A fan of triangles from the first point: each adds its area, its centroid weighted
                // by that, and its inertia about the first point.
                var a = 0f
                var cx = 0f
                var cy = 0f
                var i0 = 0f
                val ox = vx[0]
                val oy = vy[0]
                for (k in 1 until vx.size - 1) {
                    val e1x = vx[k] - ox
                    val e1y = vy[k] - oy
                    val e2x = vx[k + 1] - ox
                    val e2y = vy[k + 1] - oy
                    val cross = e1x * e2y - e1y * e2x
                    val triangle = cross / 2f
                    a += triangle
                    cx += triangle * (e1x + e2x) / 3f
                    cy += triangle * (e1y + e2y) / 3f
                    val intX = e1x * e1x + e2x * e1x + e2x * e2x
                    val intY = e1y * e1y + e2y * e1y + e2y * e2y
                    i0 += cross * (intX + intY) / 12f
                }
                area = a
                cx /= a
                cy /= a
                centroidX = ox + cx
                centroidY = oy + cy
                // from the first point to the centroid
                inertia = i0 - a * (cx * cx + cy * cy)
            }
        }
    }

    /** Point [i] of the core, in the body's units. */
    fun vertexX(i: Int): Float = vx[i]

    fun vertexY(i: Int): Float = vy[i]

    /** The unit normal of edge [i], from point [i] to the next, pointing out. A circle has none. */
    fun normalX(i: Int): Float = nx[i]

    fun normalY(i: Int): Float = ny[i]

    /**
     * How far the point ([x], [y]), in the body's units, is outside the shape: negative inside, by
     * how deep, at least where the core is near; 0 on its edge.
     */
    fun distance(x: Float, y: Float): Float {
        val n = vx.size
        if (n >= 3) {
            // Inside the core: as deep as the nearest edge.
            var deepest = -Float.MAX_VALUE
            for (i in 0 until n) deepest = max(deepest, (x - vx[i]) * nx[i] + (y - vy[i]) * ny[i])
            if (deepest <= 0f) return deepest - radius
        }
        var nearest = Float.MAX_VALUE
        for (i in 0 until n) {
            val j = if (i + 1 == n) 0 else i + 1
            nearest = min(nearest, segmentDistanceSquared(x, y, vx[i], vy[i], vx[j], vy[j]))
            if (n <= 2) break
        }
        return sqrt(nearest) - radius
    }

    /** Whether the point ([x], [y]), in the body's units, is inside the shape. */
    fun contains(x: Float, y: Float): Boolean = distance(x, y) <= 0f

    /** This shape, moved by ([dx], [dy]). */
    internal fun moved(dx: Float, dy: Float): Shape =
        Shape(kind, FloatArray(vx.size) { vx[it] + dx }, FloatArray(vy.size) { vy[it] + dy }, radius)

    companion object {
        /** The most points a polygon may have. */
        const val MAX_VERTICES = 16

        private const val PI_F = PI.toFloat()

        /** A circle of [radius] centred on ([x], [y]). */
        fun circle(radius: Float, x: Float = 0f, y: Float = 0f): Shape {
            require(radius > 0f && radius.isFinite()) { "a circle's radius is $radius: it must be finite and more than 0" }
            return Shape(ShapeKind.CIRCLE, floatArrayOf(x), floatArrayOf(y), radius)
        }

        /** A box [width] by [height] centred on ([x], [y]), turned by [angle] radians. */
        fun box(width: Float, height: Float, x: Float = 0f, y: Float = 0f, angle: Float = 0f): Shape {
            require(width > 0f && height > 0f && width.isFinite() && height.isFinite()) { "a box is $width by $height: both must be finite and more than 0" }
            val hx = width / 2f
            val hy = height / 2f
            val c = cos(angle)
            val s = sin(angle)
            val cornerX = floatArrayOf(-hx, hx, hx, -hx)
            val cornerY = floatArrayOf(-hy, -hy, hy, hy)
            return Shape(
                ShapeKind.POLYGON,
                FloatArray(4) { x + c * cornerX[it] - s * cornerY[it] },
                FloatArray(4) { y + s * cornerX[it] + c * cornerY[it] },
                0f,
            )
        }

        /**
         * A pill [length] long between its two caps' centres, and [radius] round, centred on ([x],
         * [y]) and lying along x, turned by [angle] radians. End to end it is `length + 2 · radius`.
         */
        fun capsule(length: Float, radius: Float, x: Float = 0f, y: Float = 0f, angle: Float = 0f): Shape {
            require(length > 0f && length.isFinite()) { "a capsule's length is $length: it must be finite and more than 0; for none, make a circle" }
            require(radius > 0f && radius.isFinite()) { "a capsule's radius is $radius: it must be finite and more than 0; for none, make a box" }
            val hx = cos(angle) * length / 2f
            val hy = sin(angle) * length / 2f
            return Shape(ShapeKind.CAPSULE, floatArrayOf(x - hx, x + hx), floatArrayOf(y - hy, y + hy), radius)
        }

        /**
         * A convex polygon through [points], given as x and y in turn: `polygon(0f, 0f, 20f, 0f, 10f, 15f)`
         * is a triangle. They may go round either way. A dent, a crossing, or points in a line are
         * refused: a concave shape is made of several convex ones, as a compound body.
         */
        fun polygon(vararg points: Float): Shape {
            require(points.size % 2 == 0) { "a polygon's points come as x and y in turn, but ${points.size} numbers were given" }
            val n = points.size / 2
            require(n in 3..MAX_VERTICES) { "a polygon needs 3 to $MAX_VERTICES points, not $n" }
            for (v in points) require(v.isFinite()) { "a polygon's points must be finite: ${points.joinToString()}" }
            val xs = FloatArray(n) { points[2 * it] }
            val ys = FloatArray(n) { points[2 * it + 1] }
            var twiceArea = 0f
            for (i in 0 until n) {
                val j = (i + 1) % n
                twiceArea += xs[i] * ys[j] - xs[j] * ys[i]
            }
            // clockwise on screen is a positive area with y down; turn the other way round
            if (twiceArea < 0f) {
                xs.reverse()
                ys.reverse()
            }
            // Convex: every corner turns the same way, and all of them together turn once, not twice.
            var turning = 0f
            for (i in 0 until n) {
                val j = (i + 1) % n
                val k = (i + 2) % n
                val ax = xs[j] - xs[i]
                val ay = ys[j] - ys[i]
                val bx = xs[k] - xs[j]
                val by = ys[k] - ys[j]
                val cross = ax * by - ay * bx
                val scale = sqrt((ax * ax + ay * ay) * (bx * bx + by * by))
                require(scale > 0f) { "points ${j} and ${if (j == 0) n - 1 else j - 1} of the polygon are the same point (${xs[j]}, ${ys[j]})" }
                require(cross > 1e-5f * scale) {
                    val corner = "(${xs[j]}, ${ys[j]})"
                    if (abs(cross) <= 1e-5f * scale) {
                        "the polygon's corner at $corner is in a line with its neighbours: leave that point out"
                    } else {
                        "the polygon has a dent at $corner: a shape must be convex. Make it from convex pieces instead, as a compound body"
                    }
                }
                turning += atan2(cross, ax * bx + ay * by)
            }
            require(turning < 3f * PI_F) { "the polygon crosses itself: list its points in order round the outside" }
            return Shape(ShapeKind.POLYGON, xs, ys, 0f)
        }

        /** How far ([px], [py]) is from the segment from ([ax], [ay]) to ([bx], [by]), squared. */
        internal fun segmentDistanceSquared(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float): Float {
            val ex = bx - ax
            val ey = by - ay
            val lengthSquared = ex * ex + ey * ey
            val t = if (lengthSquared == 0f) 0f else (((px - ax) * ex + (py - ay) * ey) / lengthSquared).coerceIn(0f, 1f)
            val dx = px - (ax + ex * t)
            val dy = py - (ay + ey * t)
            return dx * dx + dy * dy
        }
    }
}

/**
 * What a body made of several shapes weighs: the mass of [shapes] at [density], where it balances,
 * and how hard it is to turn about there. A body's shapes are moved so that point is its origin.
 */
internal class MassProperties(shapes: List<Shape>, density: Float) {
    val mass: Float
    val centerX: Float
    val centerY: Float
    val inertia: Float

    init {
        var area = 0f
        var cx = 0f
        var cy = 0f
        for (s in shapes) {
            area += s.area
            cx += s.area * s.centroidX
            cy += s.area * s.centroidY
        }
        cx /= area
        cy /= area
        var i = 0f
        for (s in shapes) {
            val dx = s.centroidX - cx
            val dy = s.centroidY - cy
            i += s.inertia + s.area * (dx * dx + dy * dy)
        }
        mass = area * density
        centerX = cx
        centerY = cy
        inertia = i * density
    }
}
