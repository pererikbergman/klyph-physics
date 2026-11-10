package klyph.physics

import kotlin.math.sqrt

/**
 * Where a [SoftBody]'s outline is, as shapes: what lies inside it, the stretches of a row that lie
 * inside it, and the nearest point on it. This is the one place that knows how the outline's edges
 * join up, so whatever fills the body on screen and whatever gets pushed out of it agree about what
 * "inside" means, even when the outline has folded over itself.
 *
 * Inside is decided by winding number, not even-odd: a point the outline winds around at all counts,
 * so a folded part of the body is solid rather than hollow.
 */
object Outline {
    /** Whether the point ([px], [py]) is inside the outline of [body]. */
    fun contains(body: SoftBody, px: Float, py: Float): Boolean {
        var winding = 0
        for (i in 0 until body.ringCount) {
            val j = body.next(i)
            val y0 = body.positionY(i)
            val y1 = body.positionY(j)
            if ((y0 <= py) != (y1 <= py)) {
                val crossingX = body.positionX(i) + (py - y0) / (y1 - y0) * (body.positionX(j) - body.positionX(i))
                if (crossingX > px) winding += if (y1 > y0) 1 else -1
            }
        }
        return winding != 0
    }

    /**
     * Finds the point on the outline of [body] nearest to ([px], [py]), writes its x and y to
     * `out[0]` and `out[1]`, and returns its squared distance from the point.
     */
    fun nearestPoint(body: SoftBody, px: Float, py: Float, out: FloatArray): Float {
        var best = Float.MAX_VALUE
        for (e in 0 until body.ringCount) {
            val f = body.next(e)
            val ex = body.positionX(f) - body.positionX(e)
            val ey = body.positionY(f) - body.positionY(e)
            val lengthSquared = ex * ex + ey * ey
            val t = if (lengthSquared < 1e-9f) 0f else (((px - body.positionX(e)) * ex + (py - body.positionY(e)) * ey) / lengthSquared).coerceIn(0f, 1f)
            val cx = body.positionX(e) + ex * t
            val cy = body.positionY(e) + ey * t
            val d = (px - cx) * (px - cx) + (py - cy) * (py - cy)
            if (d < best) {
                best = d
                out[0] = cx
                out[1] = cy
            }
        }
        return best
    }

    /**
     * Writes into [left] and [right] the stretches of height [y] that are inside the outline, left to
     * right, and returns how many. [left] and [right] must hold at least as many entries as the body
     * has outline particles.
     */
    fun spans(body: SoftBody, y: Float, left: FloatArray, right: FloatArray): Int {
        var crossings = 0
        for (i in 0 until body.ringCount) {
            val j = body.next(i)
            val y0 = body.positionY(i)
            val y1 = body.positionY(j)
            if ((y0 <= y) != (y1 <= y)) {
                // `left` holds each crossing's x and `right` its direction until they are paired up below.
                left[crossings] = body.positionX(i) + (y - y0) / (y1 - y0) * (body.positionX(j) - body.positionX(i))
                right[crossings] = if (y1 > y0) 1f else -1f
                crossings++
            }
        }
        for (i in 1 until crossings) {
            val x = left[i]
            val direction = right[i]
            var k = i - 1
            while (k >= 0 && left[k] > x) {
                left[k + 1] = left[k]
                right[k + 1] = right[k]
                k--
            }
            left[k + 1] = x
            right[k + 1] = direction
        }
        // Walk the crossings left to right keeping the winding number, writing each stretch where it is
        // not zero over the crossings already read: a stretch never ends before its own start, so this is safe in place.
        var winding = 0
        var start = 0f
        var count = 0
        for (i in 0 until crossings) {
            val x = left[i]
            val wasInside = winding != 0
            winding += right[i].toInt()
            val isInside = winding != 0
            if (!wasInside && isInside) {
                start = x
            } else if (wasInside && !isInside) {
                left[count] = start
                right[count] = x
                count++
            }
        }
        return count
    }
}
