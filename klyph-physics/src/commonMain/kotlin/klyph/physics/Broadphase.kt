package klyph.physics

import kotlin.math.max
import kotlin.math.min

/**
 * Finds which boxes might touch, fast and without allocating: a spatial hash grid.
 *
 * Every step, [clear] it, [add] a box for everything that can collide, [build] it once, then read the
 * candidate pairs with [pairCount], [pairA] and [pairB]. A pair is only a candidate: its boxes overlap,
 * so the narrowphase has to look closer.
 *
 * Space is cut into square cells [cellSize] wide, with no edge: a cell is hashed into a bucket of a
 * table, so the world can be any size. Each box is filed under every cell it covers, the filings are
 * sorted by bucket into one array by counting, and only boxes filed under the same cell are compared.
 * Two boxes that share several cells are only compared in the top-left one, so no pair comes twice.
 */
class Broadphase(val cellSize: Float = 16f, initialCapacity: Int = 64) {
    init {
        require(cellSize > 0f) { "cellSize is $cellSize: it must be more than 0" }
    }

    private val inverseCell = 1f / cellSize

    private var ids = IntArray(initialCapacity)
    private var minX = FloatArray(initialCapacity)
    private var minY = FloatArray(initialCapacity)
    private var maxX = FloatArray(initialCapacity)
    private var maxY = FloatArray(initialCapacity)
    private var col0 = IntArray(initialCapacity)
    private var row0 = IntArray(initialCapacity)
    private var col1 = IntArray(initialCapacity)
    private var row1 = IntArray(initialCapacity)

    /** Where each bucket's filings start; bucket `b` holds filings `start[b] until start[b + 1]`. */
    private var start = IntArray(17)
    private var mask = 15

    /**
     * The filings, in bucket order, copied next to each other so a bucket is read in one sweep: per
     * filing [FILED_INTS] ints (the box, the cell it's filed under, its top-left cell) and its edges.
     */
    private var filed = IntArray(FILED_INTS * initialCapacity)
    private var filedBox = FloatArray(4 * initialCapacity)

    private var pairs = IntArray(2 * initialCapacity)

    /** How many boxes were added since the last [clear]. */
    var count: Int = 0
        private set

    /** How many candidate pairs the last [build] found. */
    var pairCount: Int = 0
        private set

    /** How many times boxes were filed under a cell in the last [build]: one per cell each box covers. */
    var filedCount: Int = 0
        private set

    /** Forgets every box and pair, keeping the memory for the next step. */
    fun clear() {
        count = 0
        pairCount = 0
        filedCount = 0
    }

    /** Adds a box from ([left], [top]) to ([right], [bottom]) for the thing called [id]. */
    fun add(id: Int, left: Float, top: Float, right: Float, bottom: Float) {
        if (count == ids.size) grow()
        val e = count++
        ids[e] = id
        minX[e] = min(left, right)
        minY[e] = min(top, bottom)
        maxX[e] = max(left, right)
        maxY[e] = max(top, bottom)
    }

    /**
     * Adds the box a round thing sweeps through this step: a circle of [radius] at ([x], [y]) that will
     * move by ([dx], [dy]). Growing the box by the motion means a fast thing finds what it will hit
     * before it gets there, with one build per step.
     */
    fun addMoving(id: Int, x: Float, y: Float, dx: Float, dy: Float, radius: Float) {
        add(id, min(x, x + dx) - radius, min(y, y + dy) - radius, max(x, x + dx) + radius, max(y, y + dy) + radius)
    }

    /** Files every box under its cells and finds every pair of boxes that overlap. */
    fun build() {
        val n = count
        var total = 0
        for (e in 0 until n) {
            col0[e] = cell(minX[e])
            row0[e] = cell(minY[e])
            col1[e] = cell(maxX[e])
            row1[e] = cell(maxY[e])
            total += (col1[e] - col0[e] + 1) * (row1[e] - row0[e] + 1)
        }
        filedCount = total
        if (FILED_INTS * total > filed.size) {
            val size = max(total, 2 * filed.size / FILED_INTS)
            filed = IntArray(FILED_INTS * size)
            filedBox = FloatArray(4 * size)
        }
        // At least a bucket per filing keeps the buckets short.
        var buckets = mask + 1
        while (buckets < total) buckets *= 2
        if (buckets + 1 > start.size) start = IntArray(buckets + 1)
        mask = buckets - 1

        // A counting sort: count each bucket's filings, add the counts up so start[b] is where bucket b
        // ends, then file each box just before its bucket's end, last box first. That leaves start[b]
        // where bucket b begins, and start[b + 1] where it ends, with its boxes in the order they came.
        start.fill(0, 0, buckets)
        for (e in 0 until n) {
            for (row in row0[e]..row1[e]) for (col in col0[e]..col1[e]) start[bucket(col, row)]++
        }
        for (b in 1 until buckets) start[b] += start[b - 1]
        start[buckets] = total
        for (e in n - 1 downTo 0) {
            for (row in row1[e] downTo row0[e]) for (col in col1[e] downTo col0[e]) {
                val k = --start[bucket(col, row)]
                val i = FILED_INTS * k
                filed[i] = e
                filed[i + 1] = col
                filed[i + 2] = row
                filed[i + 3] = col0[e]
                filed[i + 4] = row0[e]
                filedBox[4 * k] = minX[e]
                filedBox[4 * k + 1] = minY[e]
                filedBox[4 * k + 2] = maxX[e]
                filedBox[4 * k + 3] = maxY[e]
            }
        }
        findPairs(buckets)
    }

    /**
     * Compares every two boxes filed under the same cell, but only in the top-left cell the two share,
     * so a pair is never found twice.
     */
    private fun findPairs(buckets: Int) {
        pairCount = 0
        val f = filed
        val box = filedBox
        for (bucket in 0 until buckets) {
            val end = start[bucket + 1]
            for (k in start[bucket] until end - 1) {
                val i = FILED_INTS * k
                val col = f[i + 1]
                val row = f[i + 2]
                val left = box[4 * k]
                val top = box[4 * k + 1]
                val right = box[4 * k + 2]
                val bottom = box[4 * k + 3]
                for (m in k + 1 until end) {
                    val j = FILED_INTS * m
                    // another cell that landed in the same bucket
                    if (f[j + 1] != col || f[j + 2] != row) continue
                    // not the top-left cell the two share: they meet there too, and are compared there
                    if (max(f[i + 3], f[j + 3]) != col || max(f[i + 4], f[j + 4]) != row) continue
                    if (left > box[4 * m + 2] || box[4 * m] > right || top > box[4 * m + 3] || box[4 * m + 1] > bottom) continue
                    val a = f[i]
                    val b = f[j]
                    if (a < b) addPair(ids[a], ids[b]) else addPair(ids[b], ids[a])
                }
            }
        }
    }

    /** The id of the first of candidate pair [i]: the one added earlier. */
    fun pairA(i: Int): Int = pairs[2 * i]

    /** The id of the second of candidate pair [i]. */
    fun pairB(i: Int): Int = pairs[2 * i + 1]

    private fun addPair(a: Int, b: Int) {
        if (2 * pairCount + 2 > pairs.size) pairs = pairs.copyOf(pairs.size * 2)
        pairs[2 * pairCount] = a
        pairs[2 * pairCount + 1] = b
        pairCount++
    }

    /** The column or row of the cell [v] is in: `floor(v / cellSize)`, without a call to floor. */
    private fun cell(v: Float): Int {
        val scaled = v * inverseCell
        val i = scaled.toInt()
        return if (scaled < i) i - 1 else i
    }

    /** Which bucket cell ([col], [row]) is filed in. Different cells may share one; [overlaps] sorts them out. */
    private fun bucket(col: Int, row: Int): Int = ((col * 73_856_093) xor (row * 19_349_663)) and mask

    /** The box of entry [e] in the order boxes were added, for drawing: its id, then its edges. */
    fun id(e: Int): Int = ids[e]

    fun left(e: Int): Float = minX[e]

    fun top(e: Int): Float = minY[e]

    fun right(e: Int): Float = maxX[e]

    fun bottom(e: Int): Float = maxY[e]

    private companion object {
        const val FILED_INTS = 5
    }

    private fun grow() {
        val size = ids.size * 2
        ids = ids.copyOf(size)
        minX = minX.copyOf(size)
        minY = minY.copyOf(size)
        maxX = maxX.copyOf(size)
        maxY = maxY.copyOf(size)
        col0 = col0.copyOf(size)
        row0 = row0.copyOf(size)
        col1 = col1.copyOf(size)
        row1 = row1.copyOf(size)
    }
}
