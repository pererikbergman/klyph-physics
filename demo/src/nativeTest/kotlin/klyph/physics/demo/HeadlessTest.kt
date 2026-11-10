package klyph.physics.demo

import klyph.physics.PhysicsWorld
import klyph.physics.SolidGrid
import klyph.physics.SolidShape
import kotlin.math.floor
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/** Every scene, run for ten seconds with no window: nothing goes NaN, nothing ends up inside a wall. */
class HeadlessTest {
    private val dt = 16_666_666 / 1e9f
    private val roomWidth = Scenes.COLS * Scenes.TILE
    private val roomHeight = Scenes.ROWS * Scenes.TILE

    /** How far into a solid a particle's centre may be before it counts as through it: a contact being solved, not a tunnel. */
    private val slack = 2f

    /**
     * How deep ([x], [y]) is inside the tile at ([col], [row]) of [grid]: its distance to the nearest
     * face it could be pushed out of, a slope's surface or a side with no tile beyond it. 0 or less
     * when it's outside.
     */
    private fun depth(grid: SolidGrid, col: Int, row: Int, x: Float, y: Float): Float {
        val solid = grid.at(col, row) ?: return 0f
        fun open(dc: Int, dr: Int) = grid.at(col + dc, row + dr) == null
        val diagonal = sqrt(0.5f)
        var nearest = Float.MAX_VALUE
        when (solid.shape) {
            SolidShape.BOX -> {
                if (open(0, -1)) nearest = minOf(nearest, y - solid.top)
                if (open(-1, 0)) nearest = minOf(nearest, x - solid.left)
                if (open(1, 0)) nearest = minOf(nearest, solid.right - x)
                if (open(0, 1)) nearest = minOf(nearest, solid.bottom - y)
            }
            // under a slope from the top-left down to the bottom-right: the solid is below-left of it
            SolidShape.FALLING -> {
                nearest = (y - solid.top - (x - solid.left)) * diagonal
                if (open(-1, 0)) nearest = minOf(nearest, x - solid.left)
                if (open(0, 1)) nearest = minOf(nearest, solid.bottom - y)
            }
            SolidShape.RISING -> {
                nearest = (y - solid.top - (solid.right - x)) * diagonal
                if (open(1, 0)) nearest = minOf(nearest, solid.right - x)
                if (open(0, 1)) nearest = minOf(nearest, solid.bottom - y)
            }
        }
        return if (nearest == Float.MAX_VALUE) grid.tileSize else nearest
    }

    private fun checkWorld(name: String, world: PhysicsWorld) {
        val ps = world.particles
        val grid = world.solids
        for (p in 0 until ps.end) {
            if (!ps.isAlive(p)) continue
            val x = ps.x(p)
            val y = ps.y(p)
            assertTrue(x.isFinite() && y.isFinite(), "$name: particle $p is at ($x, $y)")
            if (grid == null || ps.inverseMass(p) == 0f) continue
            assertTrue(x in 0f..roomWidth && y in 0f..roomHeight, "$name: particle $p left the room, to ($x, $y)")
            // in every tile it touches, since one on a tile's edge or corner is in all of them, and only
            // as deep as the shallowest
            var deep = Float.MAX_VALUE
            for (dx in -1..1 step 2) for (dy in -1..1 step 2) {
                val col = floor((x + dx * 0.01f) / grid.tileSize).toInt()
                val row = floor((y + dy * 0.01f) / grid.tileSize).toInt()
                deep = minOf(deep, depth(grid, col, row, x, y))
            }
            assertTrue(deep < slack, "$name: particle $p at ($x, $y) is $deep deep in a solid")
        }
        val bodies = world.softBodies
        for (body in 0 until bodies.end) if (bodies.isAlive(body)) assertTrue(bodies.isFinite(body), "$name: body $body went NaN")
    }

    @Test
    fun everySceneRunsSixHundredStepsWithNothingNaNOrThroughAWall() {
        for (scene in Scenes.all) {
            val run = scene.create()
            repeat(600) {
                run.step(dt)
                if (it % 60 == 59) checkWorld("${scene.name} at step ${it + 1}", run.world ?: fail("${scene.name} has no world"))
            }
        }
    }

    @Test
    fun everySandGrainStaysInTheHourglassAndMostOfThemPourThrough() {
        val run = SandScene.create()
        val world = run.world!!
        repeat(900) { run.step(dt) }
        val ps = world.particles
        val left = (SandScene.LEFT_WALL + 1) * Scenes.TILE
        val right = SandScene.RIGHT_WALL * Scenes.TILE
        val neck = (SandScene.NECK_ROW + 1) * Scenes.TILE
        var below = 0
        for (p in 0 until ps.end) {
            assertTrue(ps.x(p) in left..right, "grain $p got out of the hourglass, to (${ps.x(p)}, ${ps.y(p)})")
            if (ps.y(p) > neck) below++
        }
        assertEquals(SandScene.GRAINS, ps.count)
        assertTrue(below > SandScene.GRAINS * 9 / 10, "after 15 seconds most have poured through: $below")
    }

    @Test
    fun allThirtyBodiesInTheBlobPileGetThroughTheFunnelIntoTheBin() {
        val run = PileScene.create() as PileScene.Run
        repeat(PileScene.COUNT * PileScene.EVERY + 600) { run.step(dt) }
        val bodies = run.world.softBodies
        assertEquals(PileScene.COUNT, bodies.count)
        val left = PileScene.BIN_LEFT * Scenes.TILE
        val right = (PileScene.BIN_RIGHT + 1) * Scenes.TILE
        for (body in run.blobs) {
            val x = bodies.centerX(body)
            val y = bodies.centerY(body)
            assertTrue(x in left..right && y > (PileScene.FUNNEL_TOP + PileScene.FUNNEL_ROWS) * Scenes.TILE, "body $body is stuck at ($x, $y)")
        }
    }
}
