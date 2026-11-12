package klyph.physics.demo

import klyph.physics.PhysicsWorld
import klyph.physics.SolidGrid
import klyph.physics.SolidShape
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
        val rigid = world.rigidBodies
        for (b in 0 until rigid.end) {
            if (!rigid.isAlive(b)) continue
            val x = rigid.x(b)
            val y = rigid.y(b)
            assertTrue(x.isFinite() && y.isFinite() && rigid.angle(b).isFinite(), "$name: rigid body $b is at ($x, $y)")
            if (grid == null || rigid.inverseMass(b) == 0f) continue
            assertTrue(x in 0f..roomWidth && y in 0f..roomHeight, "$name: rigid body $b left the room, to ($x, $y)")
            // its middle in a tile, deeper than a contact being solved: through a wall
            val deep = depth(grid, floor(x / grid.tileSize).toInt(), floor(y / grid.tileSize).toInt(), x, y)
            assertTrue(deep < slack, "$name: rigid body $b at ($x, $y) is $deep deep in a solid")
        }
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
    fun inStacksTheTowerAndPyramidStandEveryDominoFallsAndTheBallKnocksTheTowerDown() {
        val run = StacksScene.create() as StacksScene.Run
        val bodies = run.world.rigidBodies
        val startX = (run.tower + run.pyramid).map { bodies.x(it) }
        val startY = (run.tower + run.pyramid).map { bodies.y(it) }
        val held = (StacksScene.RELEASE_AT / dt).toInt() - 1
        repeat(held) { run.step(dt) }
        assertFalse(run.released, "the ball is still held out")
        for ((k, b) in (run.tower + run.pyramid).withIndex()) {
            assertEquals(startX[k], bodies.x(b), 1f, "box $b stays where it was stacked")
            assertEquals(startY[k], bodies.y(b), 1f, "box $b keeps its height")
        }
        repeat(900 - held) { run.step(dt) }
        val pyramidFrom = run.tower.size
        for ((k, b) in run.pyramid.withIndex()) {
            assertEquals(startX[pyramidFrom + k], bodies.x(b), 1f, "pyramid box $b stands: the ball misses it")
            assertEquals(startY[pyramidFrom + k], bodies.y(b), 1f)
        }
        val top = run.tower.last()
        assertTrue(bodies.y(top) > startY[run.tower.size - 1] + 60f, "the ball knocked the tower's top down: it's at ${bodies.y(top)}")
        for (d in run.dominoes) assertTrue(bodies.angle(d) > 0.6f, "domino $d fell: it's at ${bodies.angle(d)}")
    }

    @Test
    fun inCratesAndBlobsTheCushionHoldsItsCrateTheBlobFellsTheTowerTheSwingScattersTheBlobsAndTheTrampolineBounces() {
        val run = CratesAndBlobsScene.create() as CratesAndBlobsScene.Run
        val bodies = run.world.rigidBodies
        val soft = run.world.softBodies
        val floor = (Scenes.ROWS - 2) * Scenes.TILE
        val crateHalf = CratesAndBlobsScene.CRATE / 2f
        val targetsAt = run.targets.map { soft.centerX(it) }
        // the crate dropped on the trampoline: how low it went, and how far back up after
        var lowest = 0f
        var bounced = 0f
        repeat(600) {
            run.step(dt)
            if (run.jumper < 0) return@repeat
            val y = bodies.y(run.jumper)
            if (y > lowest) lowest = y
            bounced = maxOf(bounced, lowest - y)
        }
        val bottom = bodies.y(run.crate) + crateHalf
        assertTrue(bottom < floor - 10f, "the cushion holds its crate up: its bottom at $bottom")
        assertEquals(CratesAndBlobsScene.CUSHION_X, bodies.x(run.crate), 6f, "on top of it")
        val top = run.tower.last()
        assertTrue(bodies.y(top) > floor - CratesAndBlobsScene.TOWER * CratesAndBlobsScene.TOWER_CRATE + 30f, "the tower was knocked down: its top crate at ${bodies.y(top)}")
        var scattered = 0
        for ((k, t) in run.targets.withIndex()) if (kotlin.math.abs(soft.centerX(t) - targetsAt[k]) > 20f) scattered++
        assertTrue(scattered >= 2, "the swinging crate scattered the blobs: $scattered of ${run.targets.size}")
        val ps = run.world.particles
        val tie = 0
        val dx = run.world.attachments.pointX(tie) - ps.x(run.rope.last)
        val dy = run.world.attachments.pointY(tie) - ps.y(run.rope.last)
        assertTrue(dx * dx + dy * dy < 1f, "the rope stays tied to its crate")
        val trampoline = CratesAndBlobsScene.TRAMPOLINE_Y
        assertTrue(lowest > trampoline, "the trampoline stretched under the crate: it went down to $lowest")
        assertTrue(bounced > 15f, "and threw it back up $bounced")
        val y = bodies.y(run.jumper)
        assertTrue(y in trampoline - 20f..trampoline + 60f, "it rests on the trampoline at last, at $y")
        assertTrue(bodies.x(run.jumper) in CratesAndBlobsScene.TRAMPOLINE_LEFT..CratesAndBlobsScene.TRAMPOLINE_RIGHT)
    }

    @Test
    fun inMachinesTheCarDrivesBothWaysTheShelfBreaksTheLiftGoesDownAndUpTheMillTurnsAndTheSeesawThrows() {
        val run = MachinesScene.create() as MachinesScene.Run
        val bodies = run.world.rigidBodies
        val joints = run.world.joints
        var furthest = 0f
        var turnedBack = false
        var lowest = 0f
        var rose = false
        var swinging = 0
        var springGave = 0f
        val ballFrom = bodies.y(run.ball)
        var ballHighest = ballFrom
        val placeEvery = (MachinesScene.PLACE_EVERY / dt).toInt()
        repeat(MachinesScene.PLACED * placeEvery + 120) { step ->
            run.step(dt)
            furthest = maxOf(furthest, bodies.x(run.chassis))
            if (run.direction < 0f) turnedBack = true
            val down = -joints.position(run.slider)
            if (down > lowest) lowest = down
            if (lowest > MachinesScene.LIFT_TRAVEL - 2f && down < lowest - 30f) rose = true
            if (kotlin.math.abs(bodies.angularVelocity(run.lower)) > 1f) swinging++
            for (shock in run.shocks) springGave = maxOf(springGave, kotlin.math.abs(joints.position(shock)))
            ballHighest = minOf(ballHighest, bodies.y(run.ball))
            // three crates on the shelf and it holds; the fourth breaks it
            if (step == (MachinesScene.PLACED) * placeEvery - 10) assertTrue(joints.isAlive(run.weld), "it bears three crates")
        }
        assertFalse(joints.isAlive(run.weld), "the fourth crate broke the shelf")
        assertTrue(furthest > MachinesScene.TURN_LEFT_AT && turnedBack, "the car drove right, and turned back: as far as $furthest")
        assertTrue(lowest > MachinesScene.LIFT_TRAVEL - 2f && rose, "the lift went all the way down, $lowest, and back up")
        assertTrue(swinging > 300, "the pendulum keeps swinging: fast in $swinging steps")
        // the car is still on its wheels
        assertTrue(kotlin.math.abs(bodies.angle(run.chassis)) < 0.5f, "the car is upright: ${bodies.angle(run.chassis)}")
        assertTrue(springGave > 1f, "its springs gave, on the bump and under its weight: $springGave")
        for (shock in run.shocks) {
            assertTrue(kotlin.math.abs(joints.position(shock)) <= MachinesScene.TRAVEL + 0.5f, "a strut stays in its travel: ${joints.position(shock)}")
        }
        // the motor turns the mill at its speed: the other way to its joint's angle, as its second end is the world
        assertEquals(MachinesScene.MILL_SPEED, kotlin.math.abs(bodies.angularVelocity(run.mill)), 0.1f, "the mill turns")
        assertTrue(run.heavy >= 0 && ballFrom - ballHighest > 60f, "the crate on the seesaw threw the ball ${ballFrom - ballHighest} up")
    }

    @Test
    fun inJointsTheCradlePassesItsSwingToTheLastBallAndTheBridgeHoldsTheRagdoll() {
        val run = JointsScene.create() as JointsScene.Run
        val bodies = run.world.rigidBodies
        val joints = run.world.joints
        val rest = FloatArray(JointsScene.BALLS) { JointsScene.CRADLE_X + it * JointsScene.SPACING }
        var lastOut = 0f
        var middleMoved = 0f
        repeat(48) {
            run.step(dt)
            lastOut = maxOf(lastOut, bodies.x(run.balls.last()) - rest.last())
            for (i in 1 until JointsScene.BALLS - 1) middleMoved = maxOf(middleMoved, abs(bodies.x(run.balls[i]) - rest[i]))
        }
        // pulled out a radian on a rod of 120, it swings 101 out
        assertTrue(lastOut > 80f, "the last ball swung out: $lastOut")
        assertTrue(middleMoved < 10f, "the middle three stayed put: they moved $middleMoved")

        repeat(240) { run.step(dt) }
        val torso = run.ragdolls[0].torso
        val floor = (Scenes.ROWS - 2) * Scenes.TILE
        assertTrue(bodies.x(torso) in JointsScene.BRIDGE_LEFT..JointsScene.BRIDGE_RIGHT, "the ragdoll is on the bridge: ${bodies.x(torso)}")
        assertTrue(bodies.y(torso) in JointsScene.BRIDGE_Y..floor - 40f, "held up by it: ${bodies.y(torso)}")
        for (j in run.ragdolls[0].hinges + run.bridgeHinges) {
            val gap = sqrt(sq(joints.anchorAX(j) - joints.anchorBX(j)) + sq(joints.anchorAY(j) - joints.anchorBY(j)))
            assertTrue(gap < 1f, "hinge $j holds together: its anchors are $gap apart")
        }
    }

    private fun sq(x: Float) = x * x

    @Test
    fun inTheDrumEverythingTumblesAndNothingGetsOut() {
        val run = DrumScene.create() as DrumScene.Run
        val bodies = run.world.rigidBodies
        val ps = run.world.particles
        var highest = Float.MAX_VALUE
        // its ring is flat boxes, so where two meet the wall is 0.7 further out than its radius
        val wall = -1f
        repeat(600) { step ->
            run.step(dt)
            for (c in run.crates) highest = minOf(highest, bodies.y(c))
            if (step % 60 == 59) {
                for (c in run.crates) assertTrue(run.inside(bodies.x(c), bodies.y(c), wall), "crate $c is in the drum, at (${bodies.x(c)}, ${bodies.y(c)})")
                for (p in 0 until ps.end) {
                    if (ps.isAlive(p)) assertTrue(run.inside(ps.x(p), ps.y(p), wall), "particle $p is in the drum, at (${ps.x(p)}, ${ps.y(p)})")
                }
            }
        }
        assertEquals(DrumScene.SPEED * 600 * dt, bodies.angle(run.drum), 0.01f, "the drum turned at its speed")
        assertTrue(highest < DrumScene.CENTER_Y, "a crate was carried up past the drum's middle: as high as $highest")
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
