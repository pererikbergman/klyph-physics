package klyph.physics

import kotlin.math.sqrt

/**
 * A rope or a chain made by [PhysicsWorld.rope] or [PhysicsWorld.chain]: its particles in order from
 * one end to the other, the links between neighbours, and the bends at every particle but the ends
 * (empty when it has none). They're ordinary world particles and constraints; this just remembers
 * which ones.
 */
class Strand internal constructor(
    /** The particles, from the start to the end. */
    val particles: IntArray,
    /** The link from `particles[i]` to `particles[i + 1]` is `links[i]`. */
    val links: IntArray,
    /** The bend at `particles[i + 1]` is `bends[i]`. */
    val bends: IntArray,
) {
    val first: Int get() = particles[0]
    val last: Int get() = particles[particles.size - 1]

    /** How long it is now, along its particles. */
    fun length(world: PhysicsWorld): Float {
        val ps = world.particles
        var sum = 0f
        for (i in 1 until particles.size) {
            val dx = ps.x(particles[i]) - ps.x(particles[i - 1])
            val dy = ps.y(particles[i]) - ps.y(particles[i - 1])
            sum += sqrt(dx * dx + dy * dy)
        }
        return sum
    }

    /** How long it is at rest: the sum of its links' rest lengths. */
    fun restLength(world: PhysicsWorld): Float {
        var sum = 0f
        for (link in links) sum += world.constraints.restLength(link)
        return sum
    }
}

/**
 * A sheet made by [PhysicsWorld.cloth]: a grid of [cols] by [rows] particles, held by structural links
 * along its rows and columns, shear links across each square, and bends along its rows and columns.
 */
class Cloth internal constructor(
    val cols: Int,
    val rows: Int,
    /** Row by row from the top-left: the particle at ([col], [row]) is `particles[row * cols + col]`. */
    val particles: IntArray,
    /** Every link: structural ones first, then shear. */
    val links: IntArray,
    val bends: IntArray,
) {
    fun particle(col: Int, row: Int): Int = particles[row * cols + col]
}

/**
 * A rope from ([fromX], [fromY]) to ([toX], [toY]), cut into [segments] links: `segments + 1`
 * particles of [mass], held by rope constraints that pull but go slack when pushed. With a
 * [bendCompliance] it also resists folding (see [Constraints.bend]); without, it folds anywhere.
 * [pinStart] and [pinEnd] make an end particle static, so it hangs from there. A link stretched past
 * [tearAt] times its rest length breaks (0, the default, never). Its particles are as round as
 * [radius], and [collides] with bodies and grains when asked.
 */
fun PhysicsWorld.rope(
    fromX: Float,
    fromY: Float,
    toX: Float,
    toY: Float,
    segments: Int,
    mass: Float = 1f,
    compliance: Float = 0f,
    bendCompliance: Float = Float.NaN,
    pinStart: Boolean = true,
    pinEnd: Boolean = false,
    tearAt: Float = 0f,
    radius: Float = 0f,
    collides: Boolean = false,
    material: Material = Material.DEFAULT,
): Strand = strand(fromX, fromY, toX, toY, segments, mass, compliance, bendCompliance, pinStart, pinEnd, tearAt, radius, collides, material, slack = true)

/**
 * Like [rope], but its links are rigid rods that push as well as pull, its particles are heavier and
 * round, and it bends a little: a chain hangs in a smoother curve and doesn't bunch up.
 */
fun PhysicsWorld.chain(
    fromX: Float,
    fromY: Float,
    toX: Float,
    toY: Float,
    segments: Int,
    mass: Float = 2f,
    compliance: Float = 0f,
    bendCompliance: Float = 0.00001f,
    pinStart: Boolean = true,
    pinEnd: Boolean = false,
    tearAt: Float = 0f,
    radius: Float = 2f,
    collides: Boolean = false,
    material: Material = Material.DEFAULT,
): Strand = strand(fromX, fromY, toX, toY, segments, mass, compliance, bendCompliance, pinStart, pinEnd, tearAt, radius, collides, material, slack = false)

private fun PhysicsWorld.strand(
    fromX: Float,
    fromY: Float,
    toX: Float,
    toY: Float,
    segments: Int,
    mass: Float,
    compliance: Float,
    bendCompliance: Float,
    pinStart: Boolean,
    pinEnd: Boolean,
    tearAt: Float,
    radius: Float,
    collides: Boolean,
    material: Material,
    slack: Boolean,
): Strand {
    require(segments >= 1) { "a strand needs at least 1 segment, not $segments" }
    require(fromX != toX || fromY != toY) { "a strand needs two different ends" }
    val ps = particles
    val cs = constraints
    val points = IntArray(segments + 1) { i ->
        val t = i.toFloat() / segments
        val pinned = (i == 0 && pinStart) || (i == segments && pinEnd)
        ps.add(
            fromX + (toX - fromX) * t, fromY + (toY - fromY) * t,
            mass = if (pinned) Particles.STATIC else mass, radius = radius, collides = collides, material = material,
        )
    }
    val links = IntArray(segments) { i ->
        val link = if (slack) cs.rope(points[i], points[i + 1], compliance) else cs.distance(points[i], points[i + 1], compliance)
        if (tearAt > 0f) cs.setTear(link, tearAt)
        link
    }
    val bends = if (bendCompliance.isNaN() || segments < 2) IntArray(0)
    else IntArray(segments - 1) { i -> cs.bend(points[i], points[i + 1], points[i + 2], bendCompliance) }
    return Strand(points, links, bends)
}

/**
 * A sheet of [cols] by [rows] particles [spacing] apart, its top-left particle at ([left], [top]).
 * Structural links along the rows and columns keep it from stretching ([compliance]); shear links
 * across every square keep the squares square ([shearCompliance]); bends along the rows and columns
 * keep it from creasing ([bendCompliance], or none at all for `NaN`). The particles [pinned] says are
 * static, by default the whole top row, so it hangs like a curtain. Links stretched past [tearAt]
 * times their rest length break, and a bend across a broken link goes with it, so it tears cleanly.
 */
fun PhysicsWorld.cloth(
    left: Float,
    top: Float,
    cols: Int,
    rows: Int,
    spacing: Float,
    mass: Float = 1f,
    compliance: Float = 0f,
    shearCompliance: Float = 0.001f,
    bendCompliance: Float = 0.0001f,
    tearAt: Float = 0f,
    radius: Float = 0f,
    collides: Boolean = false,
    material: Material = Material.DEFAULT,
    pinned: (col: Int, row: Int) -> Boolean = { _, row -> row == 0 },
): Cloth {
    require(cols >= 2 && rows >= 2) { "a cloth needs at least 2 by 2 particles, not $cols by $rows" }
    require(spacing > 0f) { "spacing is $spacing: it must be more than 0" }
    val ps = particles
    val cs = constraints
    val grid = IntArray(cols * rows) { k ->
        val col = k % cols
        val row = k / cols
        ps.add(
            left + col * spacing, top + row * spacing,
            mass = if (pinned(col, row)) Particles.STATIC else mass, radius = radius, collides = collides, material = material,
        )
    }
    fun at(col: Int, row: Int) = grid[row * cols + col]
    val links = ArrayList<Int>()
    fun link(a: Int, b: Int, c: Float) {
        val link = cs.distance(a, b, c)
        if (tearAt > 0f) cs.setTear(link, tearAt)
        links += link
    }
    for (row in 0 until rows) for (col in 0 until cols) {
        if (col + 1 < cols) link(at(col, row), at(col + 1, row), compliance)
        if (row + 1 < rows) link(at(col, row), at(col, row + 1), compliance)
    }
    for (row in 0 until rows - 1) for (col in 0 until cols - 1) {
        link(at(col, row), at(col + 1, row + 1), shearCompliance)
        link(at(col + 1, row), at(col, row + 1), shearCompliance)
    }
    val bends = ArrayList<Int>()
    if (!bendCompliance.isNaN()) {
        for (row in 0 until rows) for (col in 1 until cols - 1) bends += cs.bend(at(col - 1, row), at(col, row), at(col + 1, row), bendCompliance)
        for (col in 0 until cols) for (row in 1 until rows - 1) bends += cs.bend(at(col, row - 1), at(col, row), at(col, row + 1), bendCompliance)
    }
    return Cloth(cols, rows, grid, links.toIntArray(), bends.toIntArray())
}
