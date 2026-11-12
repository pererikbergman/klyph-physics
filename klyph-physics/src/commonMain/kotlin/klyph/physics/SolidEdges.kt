package klyph.physics

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * The outline of a [SolidGrid] as rigid bodies see it: one-sided edges facing the open air, joined up
 * so a floor of forty tiles is one edge, not forty. A box sliding along it has no seams to catch on.
 *
 * It's built from every tile's faces: a face that lies against a neighbour's face is buried and goes;
 * faces in a line that meet end to end merge into one. Each edge knows its outward normal, and at each
 * end whether the outline turns outwards there, a convex corner such as the lip of a ledge, and the
 * normal of the edge it turns to. A corner belongs to the edge that starts at it.
 *
 * It's rebuilt only when the grid changes. Edges are filed by tile, so the ones near a body are found
 * without looking at the rest.
 */
internal class SolidEdges {
    private var grid: SolidGrid? = null
    private var builtVersion = -1

    var count = 0
        private set
    var x1 = FloatArray(64)
        private set
    var y1 = FloatArray(64)
        private set
    var x2 = FloatArray(64)
        private set
    var y2 = FloatArray(64)
        private set
    var nx = FloatArray(64)
        private set
    var ny = FloatArray(64)
        private set

    /** Whether the outline turns outwards at each edge's start, and the normal of the edge before it. */
    var convexStart = BooleanArray(64)
        private set
    var prevNx = FloatArray(64)
        private set
    var prevNy = FloatArray(64)
        private set

    /**
     * Whether the outline turns inwards, or carries straight on, at each edge's start and at its end:
     * then the solid goes on past that end, and a point just past it, in the corner, is still behind
     * this edge.
     */
    var solidPastStart = BooleanArray(64)
        private set
    var solidPastEnd = BooleanArray(64)
        private set

    /** Each edge's grip and bounce: those of the tile it came from. */
    var friction = FloatArray(64)
        private set
    var staticFriction = FloatArray(64)
        private set
    var restitution = FloatArray(64)
        private set

    // Edges filed by tile: tile `c` holds `cellEdges[cellStart[c] until cellStart[c + 1]]`.
    private var cellStart = IntArray(1)
    private var cellEdges = IntArray(64)

    // A stamp per edge, so an edge filed under several tiles is found once per [near].
    private var stamp = IntArray(64)
    private var stampNow = 0

    /** The edges found by the last [near]. */
    var found = IntArray(32)
        private set
    var foundCount = 0
        private set

    /** Rebuilds the edges if [grid] isn't the one they were built from, or has changed since, and says whether it did. */
    fun update(grid: SolidGrid): Boolean {
        if (grid === this.grid && grid.version == builtVersion) return false
        this.grid = grid
        builtVersion = grid.version
        build(grid)
        return true
    }

    /** Finds every edge filed under a tile that the box from ([left], [top]) to ([right], [bottom]) touches, into [found]. */
    fun near(left: Float, top: Float, right: Float, bottom: Float) {
        foundCount = 0
        val g = grid ?: return
        val t = g.tileSize
        val col0 = max(0, floor(left / t).toInt())
        val col1 = min(g.cols - 1, floor(right / t).toInt())
        val row0 = max(0, floor(top / t).toInt())
        val row1 = min(g.rows - 1, floor(bottom / t).toInt())
        if (col0 > col1 || row0 > row1) return
        stampNow++
        for (row in row0..row1) for (col in col0..col1) {
            val cell = row * g.cols + col
            for (k in cellStart[cell] until cellStart[cell + 1]) {
                val e = cellEdges[k]
                if (stamp[e] == stampNow) continue
                stamp[e] = stampNow
                if (foundCount == found.size) found = found.copyOf(found.size * 2)
                found[foundCount++] = e
            }
        }
    }

    private fun build(grid: SolidGrid) {
        // Every tile's faces, each from its start to its end with the solid on its right-hand side
        // as y grows down, so the normal (dy, -dx) points out.
        val faces = ArrayList<Face>()
        for (row in 0 until grid.rows) for (col in 0 until grid.cols) {
            val s = grid.at(col, row) ?: continue
            val l = s.left
            val r = s.right
            val t = s.top
            val b = s.bottom
            when (s.shape) {
                SolidShape.BOX -> {
                    faces += Face(l, t, r, t, s)
                    faces += Face(r, t, r, b, s)
                    faces += Face(r, b, l, b, s)
                    faces += Face(l, b, l, t, s)
                }
                SolidShape.RISING -> {
                    faces += Face(l, b, r, t, s)
                    faces += Face(r, t, r, b, s)
                    faces += Face(r, b, l, b, s)
                }
                SolidShape.FALLING -> {
                    faces += Face(l, t, r, b, s)
                    faces += Face(r, b, l, b, s)
                    faces += Face(l, b, l, t, s)
                }
            }
        }
        // A face lying against its neighbour's, the same two points the other way round, is buried.
        val byEnds = HashSet<Pair<Long, Long>>()
        for (f in faces) byEnds += point(f.x1, f.y1) to point(f.x2, f.y2)
        val width = grid.cols * grid.tileSize
        val height = grid.rows * grid.tileSize
        for (f in faces) {
            if (point(f.x2, f.y2) to point(f.x1, f.y1) in byEnds) f.buried = true
            // and one on the grid's own edge faces nothing: there's no way in from outside
            if (f.x1 == f.x2 && (f.x1 == 0f || f.x1 == width) || f.y1 == f.y2 && (f.y1 == 0f || f.y1 == height)) f.buried = true
        }
        val open = faces.filter { !it.buried }
        // Join faces that carry on in the same direction from where another ends.
        val startingAt = HashMap<Long, Int>()
        for ((i, f) in open.withIndex()) startingAt[point(f.x1, f.y1)] = i
        val next = IntArray(open.size) { startingAt[point(open[it].x2, open[it].y2)] ?: -1 }
        val hasSameBefore = BooleanArray(open.size)
        for (i in open.indices) {
            val j = next[i]
            if (j >= 0 && sameWay(open[i], open[j]) && open[j].solid.material == open[i].solid.material) hasSameBefore[j] = true
        }
        val merged = ArrayList<Face>()
        val mergedFrom = ArrayList<Int>()
        val mergedTo = ArrayList<Int>()
        for (i in open.indices) {
            if (hasSameBefore[i]) continue
            var last = i
            var steps = 0
            while (true) {
                val j = next[last]
                if (j < 0 || j == i || !sameWay(open[last], open[j]) || open[j].solid.material != open[i].solid.material || steps > open.size) break
                last = j
                steps++
            }
            merged += Face(open[i].x1, open[i].y1, open[last].x2, open[last].y2, open[i].solid)
            mergedFrom += i
            mergedTo += last
        }
        // Each merged edge, and at its start the edge before it, which ends where it starts.
        val endingAt = HashMap<Long, Int>()
        for ((m, f) in merged.withIndex()) endingAt[point(f.x2, f.y2)] = m
        ensure(merged.size)
        count = merged.size
        for ((m, f) in merged.withIndex()) {
            x1[m] = f.x1
            y1[m] = f.y1
            x2[m] = f.x2
            y2[m] = f.y2
            val dx = f.x2 - f.x1
            val dy = f.y2 - f.y1
            val length = sqrt(dx * dx + dy * dy)
            nx[m] = dy / length
            ny[m] = -dx / length
            val material = f.solid.material
            friction[m] = material.friction
            staticFriction[m] = material.staticFriction
            restitution[m] = material.restitution
        }
        solidPastStart.fill(false, 0, count)
        solidPastEnd.fill(false, 0, count)
        for ((m, f) in merged.withIndex()) {
            val before = endingAt[point(f.x1, f.y1)]
            if (before == null) {
                // a loose end, at the edge of the grid where the faces beyond were dropped: nothing gets there
                convexStart[m] = false
                prevNx[m] = nx[m]
                prevNy[m] = ny[m]
                continue
            }
            val pdx = merged[before].x2 - merged[before].x1
            val pdy = merged[before].y2 - merged[before].y1
            val dx = f.x2 - f.x1
            val dy = f.y2 - f.y1
            // turning right, with y down, is turning outwards round the solid
            convexStart[m] = pdx * dy - pdy * dx > 0f
            solidPastStart[m] = !convexStart[m]
            solidPastEnd[before] = !convexStart[m]
            prevNx[m] = nx[before]
            prevNy[m] = ny[before]
        }
        fileByTile(grid)
    }

    /** Files every edge under each tile its box, a unit bigger all round, touches. */
    private fun fileByTile(grid: SolidGrid) {
        val cells = grid.cols * grid.rows
        val t = grid.tileSize
        val counts = IntArray(cells + 1)
        fun forEachCell(e: Int, action: (Int) -> Unit) {
            val col0 = max(0, floor((min(x1[e], x2[e]) - 1f) / t).toInt())
            val col1 = min(grid.cols - 1, floor((max(x1[e], x2[e]) + 1f) / t).toInt())
            val row0 = max(0, floor((min(y1[e], y2[e]) - 1f) / t).toInt())
            val row1 = min(grid.rows - 1, floor((max(y1[e], y2[e]) + 1f) / t).toInt())
            for (row in row0..row1) for (col in col0..col1) action(row * grid.cols + col)
        }
        for (e in 0 until count) forEachCell(e) { counts[it]++ }
        cellStart = IntArray(cells + 1)
        for (c in 0 until cells) cellStart[c + 1] = cellStart[c] + counts[c]
        cellEdges = IntArray(maxOf(1, cellStart[cells]))
        val fill = cellStart.copyOf()
        for (e in 0 until count) forEachCell(e) { cellEdges[fill[it]++] = e }
        if (stamp.size < count) stamp = IntArray(count)
    }

    private fun ensure(size: Int) {
        if (x1.size >= size) return
        x1 = FloatArray(size)
        y1 = FloatArray(size)
        x2 = FloatArray(size)
        y2 = FloatArray(size)
        nx = FloatArray(size)
        ny = FloatArray(size)
        convexStart = BooleanArray(size)
        prevNx = FloatArray(size)
        prevNy = FloatArray(size)
        solidPastStart = BooleanArray(size)
        solidPastEnd = BooleanArray(size)
        friction = FloatArray(size)
        staticFriction = FloatArray(size)
        restitution = FloatArray(size)
        stamp = IntArray(size)
    }

    private class Face(val x1: Float, val y1: Float, val x2: Float, val y2: Float, val solid: Solid) {
        var buried = false
    }

    private fun sameWay(a: Face, b: Face): Boolean {
        val ax = a.x2 - a.x1
        val ay = a.y2 - a.y1
        val bx = b.x2 - b.x1
        val by = b.y2 - b.y1
        return ax * by - ay * bx == 0f && ax * bx + ay * by > 0f
    }

    /** A point as a key: tile corners and half-tile heights are exact in a float, so they compare exactly. */
    private fun point(x: Float, y: Float): Long = (x.toRawBits().toLong() shl 32) or (y.toRawBits().toLong() and 0xFFFFFFFFL)
}
