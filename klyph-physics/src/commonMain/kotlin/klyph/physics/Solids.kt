package klyph.physics

import kotlin.math.floor
import kotlin.math.sqrt

private const val DIAGONAL = 0.70710678f

/**
 * A surface whose normal points up by more than this (y grows down, so the normal's y is below it)
 * counts as a floor. A 45 degree slope's normal is at -0.707, so a body standing on one is grounded.
 */
private const val MIN_FLOOR_NORMAL_Y = -0.5f

/** How a [Solid] fills its rectangle: all of it, or the half under a diagonal surface. */
enum class SolidShape {
    /** The whole rectangle. */
    BOX,

    /** The triangle under a surface that rises to the right, from the bottom-left corner to the top-right. */
    RISING,

    /** The triangle under a surface that falls to the right, from the top-left corner to the bottom-right. */
    FALLING,
}

/**
 * Something a particle cannot enter: a rectangle with the given edges (y grows down) filled as
 * [shape] says. A tile's solid knows its grid cell, so the grid can tell which faces are buried
 * against a neighbour; a [moving] one has none. Make a tile's solid with [SolidGrid.fill].
 */
class Solid internal constructor(
    val shape: SolidShape,
    val left: Float,
    var top: Float,
    val right: Float,
    var bottom: Float,
    /** Bit flags the game gives this solid, such as "bounce pad", reported to [Contacts] when a body stands on it; 0 for none. */
    val tag: Int = 0,
    val col: Int = -1,
    val row: Int = -1,
) {
    /** Whether this solid moves, such as a crusher, rather than filling a grid cell. It has no neighbours to bury its faces against. */
    val isMoving: Boolean get() = col < 0

    /**
     * Whether the point ([x], [y]) is inside this solid by more than [margin] units, measured straight
     * out of the nearest face (the slope's own surface, for a slope). A point on the surface is outside.
     * This is the one place that decides what "inside" means: collision and [SolidGrid.isSolidAt] both ask it.
     */
    fun contains(x: Float, y: Float, margin: Float = 0f): Boolean = when (shape) {
        SolidShape.BOX -> x > left + margin && x < right - margin && y > top + margin && y < bottom - margin
        SolidShape.RISING -> isWithinEdges(x, y) && ((x - left) - (bottom - y)) * DIAGONAL > margin
        SolidShape.FALLING -> isWithinEdges(x, y) && ((right - left) - (x - left) - (bottom - y)) * DIAGONAL > margin
    }

    private fun isWithinEdges(x: Float, y: Float) = x >= left && x <= right && y >= top && y <= bottom

    companion object {
        /** A solid that is not tied to a grid cell, filling the given edges; its [top] and [bottom] may change. */
        fun moving(left: Float, top: Float, right: Float, bottom: Float, tag: Int = 0): Solid =
            Solid(SolidShape.BOX, left, top, right, bottom, tag)
    }
}

/**
 * A level's solids: at most one per cell of a grid of [cols] by [rows] cells, each [tileSize] wide,
 * plus [dynamic] ones that move. A particle is a point, so it can only be inside the one tile it is in, and [resolve] looks
 * at that tile and at every dynamic solid.
 */
class SolidGrid(val cols: Int, val rows: Int, val tileSize: Float) {
    private val cells = arrayOfNulls<Solid>(cols * rows)

    /** Solids that are not tied to a cell, such as a crusher. Checked every time. */
    val dynamic = ArrayList<Solid>()

    /** The solid in cell ([col], [row]), or `null` for air and for cells outside the grid. */
    fun at(col: Int, row: Int): Solid? = if (col in 0 until cols && row in 0 until rows) cells[row * cols + col] else null

    /** Fills cell ([col], [row]) with a solid of [shape] carrying [tag], and returns it. */
    fun fill(col: Int, row: Int, shape: SolidShape = SolidShape.BOX, tag: Int = 0): Solid {
        val t = tileSize
        return put(Solid(shape, col * t, row * t, (col + 1) * t, (row + 1) * t, tag, col, row))
    }

    /** Fills only the top half of cell ([col], [row]), such as a stub hanging from a ceiling, and returns it. */
    fun fillTopHalf(col: Int, row: Int, tag: Int = 0): Solid {
        val t = tileSize
        return put(Solid(SolidShape.BOX, col * t, row * t, (col + 1) * t, row * t + t / 2f, tag, col, row))
    }

    private fun put(solid: Solid): Solid {
        require(solid.col in 0 until cols && solid.row in 0 until rows) { "Cell (${solid.col}, ${solid.row}) is outside the ${cols}x$rows grid" }
        cells[solid.row * cols + solid.col] = solid
        return solid
    }

    /** Empties cell ([col], [row]). */
    fun removeAt(col: Int, row: Int) {
        if (col in 0 until cols && row in 0 until rows) cells[row * cols + col] = null
    }

    /** Whether the point ([x], [y]) is inside any solid by more than [margin] units; see [Solid.contains]. */
    fun isSolidAt(x: Float, y: Float, margin: Float = 0f): Boolean {
        val solid = at(floor(x / tileSize).toInt(), floor(y / tileSize).toInt())
        if (solid != null && solid.contains(x, y, margin)) return true
        for (d in dynamic) if (d.contains(x, y, margin)) return true
        return false
    }

    /**
     * Pushes every particle of [body] out of the solids, along the way of least penetration. The
     * speed going into the surface is removed and the rest kept, so particles slide along walls and
     * floors. Faces buried against a neighbouring solid are never exits, so a floor made of many
     * tiles has no seams.
     */
    fun resolve(body: SoftBody, contacts: Contacts) {
        val tile = tileSize
        for (i in 0 until body.size) {
            val col = floor(body.positionX(i) / tile).toInt()
            val row = floor(body.positionY(i) / tile).toInt()
            val solid = at(col, row)
            if (solid != null) push(body, i, solid, contacts)
            for (d in dynamic) push(body, i, d, contacts)
        }
    }

    private fun push(body: SoftBody, i: Int, solid: Solid, contacts: Contacts) {
        val px = body.positionX(i)
        val py = body.positionY(i)
        if (!solid.contains(px, py)) return
        val exit = exitScratch
        when (solid.shape) {
            SolidShape.BOX -> exitFromBox(solid, px, py, exit)
            SolidShape.RISING -> exitFromRising(solid, px, py, exit)
            SolidShape.FALLING -> exitFromFalling(solid, px, py, exit)
        }
        body.pushOut(i, exit.nx, exit.ny, exit.depth)
        if (exit.ny < MIN_FLOOR_NORMAL_Y) contacts.recordFloor(i, solid.tag)
    }

    /** Writes to [exit] the cheapest way out of the box for a point inside it. */
    private fun exitFromBox(solid: Solid, px: Float, py: Float, exit: Exit) {
        nearestExit(solid, px, py, respectBuried = true)
        // Where a step meets a wall, both faces at the corner are buried, yet just past the corner is air.
        // A point just inside leaves through that corner, not through the far side of the wall.
        if (!solid.isMoving) {
            tryCorner(solid, -1, -1, px - solid.left, py - solid.top, exit)
            tryCorner(solid, 1, -1, solid.right - px, py - solid.top, exit)
            tryCorner(solid, -1, 1, px - solid.left, solid.bottom - py, exit)
            tryCorner(solid, 1, 1, solid.right - px, solid.bottom - py, exit)
        }
        // Buried on every side, such as under the foot of a wall: step toward the nearest face
        // anyway, just past it, and the tile there pushes the particle the rest of the way out.
        if (exit.depth == Float.MAX_VALUE) {
            nearestExit(solid, px, py, respectBuried = false)
            exit.depth += 0.01f
        }
        // A tile with nowhere to go at all, such as the only cell of a one-cell grid: leave it be.
        if (exit.depth == Float.MAX_VALUE) exit.depth = 0f
    }

    /** Writes to [exit] the way out, along the surface's normal, for a point under a surface that rises to the right. */
    private fun exitFromRising(solid: Solid, px: Float, py: Float, exit: Exit) {
        val u = px - solid.left
        val v = solid.bottom - py
        exit.depth = (u - v) * DIAGONAL
        exit.nx = -DIAGONAL
        exit.ny = -DIAGONAL
        // A slope in mid-air can be hit from below or from its tall side, and the way back out is
        // shorter there than up through the surface: on top of it is the wrong side.
        tryFace(solid, Side.BOTTOM, solid.bottom - py, 0f, 1f, exit)
        tryFace(solid, Side.RIGHT, solid.right - px, 1f, 0f, exit)
    }

    /**
     * Takes the corner of a tile's [solid] toward ([dx], [dy]), each -1 or 1, as the way out if the cell
     * diagonally across it is air inside the grid and the corner, [toX] and [toY] away along each axis,
     * is closer than [exit]'s. A corner is never closer than an open face beside it, so it only wins
     * where both of those faces are buried.
     */
    private fun tryCorner(solid: Solid, dx: Int, dy: Int, toX: Float, toY: Float, exit: Exit) {
        val col = solid.col + dx
        val row = solid.row + dy
        if (col !in 0 until cols || row !in 0 until rows || at(col, row) != null) return
        val depth = sqrt(toX * toX + toY * toY)
        if (depth >= exit.depth || depth < 1e-6f) return
        exit.depth = depth
        exit.nx = dx * toX / depth
        exit.ny = dy * toY / depth
    }

    /** Takes the [side] face as the way out, along ([nx], [ny]), if it is open and closer than [exit]'s. */
    private fun tryFace(solid: Solid, side: Side, depth: Float, nx: Float, ny: Float, exit: Exit) {
        if (depth < exit.depth && isExit(solid, side, respectBuried = true)) {
            exit.depth = depth
            exit.nx = nx
            exit.ny = ny
        }
    }

    /** Writes to [exit] the way out, along the surface's normal, for a point under a surface that falls to the right. */
    private fun exitFromFalling(solid: Solid, px: Float, py: Float, exit: Exit) {
        val u = px - solid.left
        val v = solid.bottom - py
        val side = solid.right - solid.left
        exit.depth = (side - u - v) * DIAGONAL
        exit.nx = DIAGONAL
        exit.ny = -DIAGONAL
        tryFace(solid, Side.BOTTOM, solid.bottom - py, 0f, 1f, exit)
        tryFace(solid, Side.LEFT, px - solid.left, -1f, 0f, exit)
    }

    /** The way out of a box that costs the least movement, reused between calls so nothing allocates. */
    private class Exit {
        var depth = Float.MAX_VALUE
        var nx = 0f
        var ny = 0f
    }

    private val exitScratch = Exit()

    /** Writes to the scratch [Exit] the face of a box that is cheapest to leave through, skipping buried faces when asked. */
    private fun nearestExit(solid: Solid, px: Float, py: Float, respectBuried: Boolean) {
        val exit = exitScratch
        exit.depth = Float.MAX_VALUE
        exit.nx = 0f
        exit.ny = 0f
        val toLeft = px - solid.left
        if (toLeft < exit.depth && isExit(solid, Side.LEFT, respectBuried)) { exit.depth = toLeft; exit.nx = -1f; exit.ny = 0f }
        val toRight = solid.right - px
        if (toRight < exit.depth && isExit(solid, Side.RIGHT, respectBuried)) { exit.depth = toRight; exit.nx = 1f; exit.ny = 0f }
        val toTop = py - solid.top
        if (toTop < exit.depth && isExit(solid, Side.TOP, respectBuried)) { exit.depth = toTop; exit.nx = 0f; exit.ny = -1f }
        val toBottom = solid.bottom - py
        if (toBottom < exit.depth && isExit(solid, Side.BOTTOM, respectBuried)) { exit.depth = toBottom; exit.nx = 0f; exit.ny = 1f }
    }

    /**
     * Whether a particle may leave [solid] through its [side]: never through the edge of the grid,
     * where there is nothing to land on and nothing to push it back, and, when [respectBuried], not
     * through a face buried against a neighbour.
     */
    private fun isExit(solid: Solid, side: Side, respectBuried: Boolean): Boolean =
        !isOnGridEdge(solid, side) && !(respectBuried && isBuried(solid, side))

    /** Whether the [side] face of a tile's [solid] lies on the edge of the grid. A moving solid's never does. */
    private fun isOnGridEdge(solid: Solid, side: Side): Boolean {
        if (solid.isMoving) return false
        return when (side) {
            Side.LEFT -> solid.col == 0
            Side.RIGHT -> solid.col == cols - 1
            Side.TOP -> solid.row == 0
            Side.BOTTOM -> solid.row == rows - 1
        }
    }

    private enum class Side { LEFT, RIGHT, TOP, BOTTOM }

    /** Whether the [side] face of a tile's [solid] lies against a neighbour that fills the whole face. */
    private fun isBuried(solid: Solid, side: Side): Boolean {
        if (solid.isMoving) return false
        val neighbour = when (side) {
            Side.LEFT -> at(solid.col - 1, solid.row)
            Side.RIGHT -> at(solid.col + 1, solid.row)
            Side.TOP -> at(solid.col, solid.row - 1)
            Side.BOTTOM -> at(solid.col, solid.row + 1)
        } ?: return false
        return when (side) {
            Side.LEFT -> neighbour.shape != SolidShape.FALLING && spansHeight(neighbour, solid) && neighbour.right == solid.left
            Side.RIGHT -> neighbour.shape != SolidShape.RISING && spansHeight(neighbour, solid) && neighbour.left == solid.right
            Side.TOP -> neighbour.bottom == solid.top && neighbour.left <= solid.left && neighbour.right >= solid.right
            Side.BOTTOM -> neighbour.shape == SolidShape.BOX && neighbour.top == solid.bottom &&
                neighbour.left <= solid.left && neighbour.right >= solid.right
        }
    }

    private fun spansHeight(neighbour: Solid, solid: Solid): Boolean = neighbour.top <= solid.top && neighbour.bottom >= solid.bottom
}
