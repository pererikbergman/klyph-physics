package klyph.physics

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The squeezes the slime of slimy-the-blob had to pass, as world tests: a dome with a heavy core, in a
 * world of 16-unit tiles, through shafts and tunnels narrower than itself.
 */
class SqueezeTest {
    private val dt = TestBodies.DT
    private val tile = TestBodies.TILE
    private val world = PhysicsWorld()
    private val bodies = world.softBodies
    private val restWidth = 2 * TestBodies.RADIUS
    private val restArea = PhysicsWorld().run { softBodies.area(softBody(TestBodies.dome(), 0f, 0f)) }

    private fun blob(x: Float, baseY: Float): Int = world.softBody(TestBodies.dome(), x, baseY)

    private fun run(blob: Int, steps: Int, push: Boolean = false) = repeat(steps) {
        if (push) push(blob) else brake(blob)
        world.step(dt)
        assertTrue(bodies.isFinite(blob), "the blob blew up")
    }

    /** What the slime did when you held right: speed up to [MAX_SPEED], harder on the ground than in the air. */
    private fun push(blob: Int) {
        if (bodies.meanVelocityX(blob) >= MAX_SPEED) return
        val acceleration = if (bodies.isOnFloor(blob)) MOVE_ACCEL else AIR_ACCEL
        bodies.addVelocity(blob, acceleration * dt, 0f)
    }

    /** And when you let go on the ground: lose [GROUND_FRICTION] of its sideways speed each step. */
    private fun brake(blob: Int) {
        if (!bodies.isOnFloor(blob)) return
        for (i in 0..bodies.ringCount(blob)) {
            val p = bodies.particle(blob, i)
            world.setVelocity(p, world.velocityX(p) * (1f - GROUND_FRICTION), world.velocityY(p))
        }
    }

    private fun assertBackToRestShape(blob: Int) {
        val width = bodies.maxX(blob) - bodies.minX(blob)
        assertTrue(abs(width - restWidth) < restWidth * 0.15f, "width $width of $restWidth")
        assertTrue(abs(bodies.area(blob) - restArea) < restArea * 0.15f, "area ${bodies.area(blob)} of $restArea")
    }

    private fun assertParticlesOutside(blob: Int, grid: SolidGrid) {
        for (i in 0..bodies.ringCount(blob)) {
            val p = bodies.particle(blob, i)
            val x = world.particles.x(p)
            val y = world.particles.y(p)
            assertTrue(!grid.isSolidAt(x, y, margin = 0.05f), "particle $i is inside a solid at ($x, $y)")
        }
    }

    private fun solidEverywhere(cols: Int, rows: Int) = SolidGrid(cols, rows, tile).apply {
        for (c in 0 until cols) for (r in 0 until rows) fill(c, r)
    }

    private fun SolidGrid.carve(cols: IntRange, rows: IntRange) {
        for (c in cols) for (r in rows) removeAt(c, r)
    }

    private fun flatFloor(cols: Int, rows: Int, firstRow: Int) = SolidGrid(cols, rows, tile).apply {
        for (c in 0 until cols) for (r in firstRow until firstRow + 2) fill(c, r)
    }

    /** A big solid block with a one-tile-wide slot (col 11) from row 5 down to [slotBottom], and a chamber under it. */
    private fun shaftLevel(slotBottom: Int, chamberRows: IntRange?): SolidGrid = solidEverywhere(30, 45).apply {
        carve(1..28, 0..4)
        carve(11..11, 5..slotBottom)
        if (chamberRows != null) carve(1..28, chamberRows)
    }

    @Test
    fun aDeadEndOneTileShaftDoesNotMakeTheBlobBlowUpOrTear() {
        val grid = shaftLevel(slotBottom = 12, chamberRows = null)
        world.solids = grid
        val b = blob(11.5f * tile, 5 * tile - 30f)
        var worstStretch = 0f
        repeat(1200) {
            run(b, 1)
            worstStretch = maxOf(worstStretch, bodies.maxEdgeStretch(b))
        }
        assertTrue(worstStretch < 3f, "an edge stretched to $worstStretch times its length")
        val inShaft = bodies.minX(b) >= 11 * tile - 0.5f && bodies.maxX(b) <= 12 * tile + 0.5f
        assertTrue(inShaft || bodies.minY(b) < 5 * tile, "the blob is neither in the shaft nor on top of it")
    }

    @Test
    fun theBlobFallsThroughAShaftSixtyPercentOfItsWidthAndRecovers() {
        world.solids = shaftLevel(slotBottom = 22, chamberRows = 23..40)
        val b = blob(11.5f * tile, 5 * tile - 30f)
        run(b, 360)
        assertTrue(bodies.centerY(b) > 23 * tile, "still above the chamber at y ${bodies.centerY(b)}")
        run(b, 300)
        assertBackToRestShape(b)
    }

    @Test
    fun theBlobPushesThroughALowTunnelAndRecovers() {
        // A floor at row 16 (top 256), and a tunnel from col 10 to 15 whose ceiling leaves 8 units: 60% of the blob's height.
        world.solids = flatFloor(40, 20, firstRow = 16).apply {
            for (c in 10..15) { fill(c, 14); fillTopHalf(c, 15) }
            for (r in 0..15) { fill(0, r); fill(39, r) }
        }
        val b = blob(6 * tile, 16 * tile)
        run(b, 60)
        var pushed = 0
        while (bodies.centerX(b) < 22 * tile && pushed < 900) {
            run(b, 1, push = true)
            pushed++
        }
        assertTrue(bodies.centerX(b) >= 22 * tile, "stuck at x ${bodies.centerX(b)} after $pushed steps")
        run(b, 200)
        assertBackToRestShape(b)
    }

    @Test
    fun aLongFallOntoAFloorNeverLeavesAParticleInsideIt() {
        val grid = flatFloor(40, 50, firstRow = 40)
        world.solids = grid
        val b = blob(320f, 40 * tile - 430f)
        repeat(240) {
            run(b, 1)
            assertParticlesOutside(b, grid)
        }
    }

    @Test
    fun landingOnTheCornerOfABlockNeverLeavesAParticleInsideIt() {
        val grid = flatFloor(40, 50, firstRow = 40).apply { for (c in 20..25) for (r in 37..39) fill(c, r) }
        world.solids = grid
        // Centred on the block's left edge, so half the blob lands on it and half beside it.
        val b = blob(20 * tile, 30 * tile - 300f)
        repeat(300) {
            run(b, 1)
            assertParticlesOutside(b, grid)
        }
    }

    private companion object {
        const val MOVE_ACCEL = 1800f
        const val AIR_ACCEL = 1000f
        const val MAX_SPEED = 170f
        const val GROUND_FRICTION = 0.12f
    }
}
