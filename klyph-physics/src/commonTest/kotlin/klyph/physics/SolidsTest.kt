package klyph.physics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SolidsTest {
    private val body = TestBodies.dome()
    private val contacts = Contacts(body.size, body.ringCount)

    /** Puts particle 0 at ([x], [y]) having come from ([fromX], [fromY]), and every other particle far away. */
    private fun particle(x: Float, y: Float, fromX: Float = x, fromY: Float = y) {
        for (i in 0 until body.size) body.setParticle(i, -1000f, -1000f)
        body.setParticle(0, x, y, previousX = fromX, previousY = fromY)
    }

    private fun resolve(grid: SolidGrid) {
        contacts.beginStep()
        grid.resolve(body, contacts)
    }

    /** A 10 x 10 grid with a floor of full tiles on row 5, whose top is y = 80. */
    private fun floorGrid(): SolidGrid = TestBodies.grid().apply { for (c in 0 until cols) fill(c, 5) }

    @Test
    fun aParticleFallingIntoTheFloorLandsOnTopKeepingItsSidewaysSpeed() {
        val grid = floorGrid()
        particle(40f, 84f, fromX = 35f, fromY = 70f)
        resolve(grid)
        assertEquals(80f, body.positionY(0), 0.001f)
        assertEquals(40f, body.positionX(0), 0.001f)
        assertEquals(0f, body.movedY(0), 0.001f)
        assertEquals(5f, body.movedX(0), 0.001f)
        assertTrue(contacts.isOnFloor(0))
        assertTrue(contacts.touchedFloor)
    }

    @Test
    fun aParticleHittingTheSideOfABlockIsPushedSidewaysAndIsNotOnTheFloor() {
        val grid = TestBodies.grid().apply { fill(5, 3) }
        particle(82f, 56f, fromX = 78f, fromY = 56f)
        resolve(grid)
        assertEquals(80f, body.positionX(0), 0.001f)
        assertEquals(56f, body.positionY(0), 0.001f)
        assertFalse(contacts.isOnFloor(0))
    }

    @Test
    fun aParticleThatStartedInsideLeavesThroughTheNearestFace() {
        val grid = TestBodies.grid().apply { fill(5, 3) }
        particle(94f, 56f)
        resolve(grid)
        assertEquals(96f, body.positionX(0), 0.001f)
    }

    @Test
    fun aParticleOutsideEverythingIsLeftAlone() {
        val grid = floorGrid()
        particle(40f, 60f, fromX = 41f, fromY = 59f)
        resolve(grid)
        assertEquals(40f, body.positionX(0))
        assertEquals(60f, body.positionY(0))
        assertFalse(contacts.touchedFloor)
    }

    @Test
    fun theSeamBetweenTwoFloorTilesDoesNotPushSideways() {
        val grid = floorGrid()
        particle(95.5f, 82f)
        resolve(grid)
        assertEquals(95.5f, body.positionX(0), 0.001f)
        assertEquals(80f, body.positionY(0), 0.001f)
    }

    @Test
    fun aTaggedTileReportsItsTagWhenLandedOn() {
        val grid = TestBodies.grid().apply { fill(4, 5, tag = TestBodies.PAD) }
        particle(72f, 82f, fromY = 70f)
        resolve(grid)
        assertTrue(contacts.touched(TestBodies.PAD))
        assertTrue(contacts.touchedFloor)
    }

    @Test
    fun aRisingSlopePushesAlongItsSurfaceNormal() {
        // a ramp in the ground: a tile below it and one beside its tall (right) side
        val grid = TestBodies.grid().apply { fill(2, 2, SolidShape.RISING); fill(2, 3); fill(3, 2) }
        particle(44f, 44f)
        resolve(grid)
        assertEquals(40f, body.positionX(0), 0.001f)
        assertEquals(40f, body.positionY(0), 0.001f)
        assertTrue(contacts.isOnFloor(0))
    }

    @Test
    fun aFallingSlopeMirrorsIt() {
        val grid = TestBodies.grid().apply { fill(2, 2, SolidShape.FALLING); fill(2, 3); fill(1, 2) }
        particle(36f, 44f)
        resolve(grid)
        assertEquals(40f, body.positionX(0), 0.001f)
        assertEquals(40f, body.positionY(0), 0.001f)
    }

    @Test
    fun aParticleThatHitsAFloatingSlopeFromBelowGoesBackDownNotOnTop() {
        // a rising slope in mid-air, cell (2, 2): x 32..48, y 32..48; hit from below, near its bottom
        val grid = TestBodies.grid().apply { fill(2, 2, SolidShape.RISING) }
        particle(44f, 46f, fromY = 50f)
        resolve(grid)
        assertEquals(44f, body.positionX(0), 0.001f)
        assertEquals(48f, body.positionY(0), 0.001f, "out through the open bottom, two units away, not onto the surface")
        assertFalse(contacts.isOnFloor(0), "a ceiling is not a floor")
    }

    @Test
    fun aParticleNearTheTallSideOfAFloatingSlopeLeavesThroughThatSide() {
        val grid = TestBodies.grid().apply { fill(2, 2, SolidShape.FALLING) }
        // a falling slope's tall side is its left one, at x = 32
        particle(33f, 44f, fromX = 30f)
        resolve(grid)
        assertEquals(32f, body.positionX(0), 0.001f)
        assertEquals(44f, body.positionY(0), 0.001f)
    }

    @Test
    fun aSlopeBuriedBelowAndBesideStillPushesUpItsSurfaceFromDeepInside() {
        // a ramp in the ground: tiles below it and beside its tall side, so its surface is the only way out
        val grid = TestBodies.grid().apply { fill(2, 2, SolidShape.RISING); fill(2, 3); fill(3, 2) }
        // one unit above the bottom, where the bottom face would be the nearest if it were open
        particle(44f, 47f)
        resolve(grid)
        assertTrue(body.positionY(0) < 47f, "pushed up, y = ${body.positionY(0)}")
        assertEquals(44f - body.positionX(0), 47f - body.positionY(0), 0.001f, "up and left alike: along the surface's normal")
    }

    @Test
    fun aHalfTileStubOnlyFillsTheTopHalfOfItsTile() {
        val grid = TestBodies.grid().apply { fillTopHalf(4, 4) }
        particle(70f, 74f)
        resolve(grid)
        // y 64..72 is solid; 74 is below the stub, in the open half of the tile.
        assertEquals(74f, body.positionY(0))
        particle(70f, 70f, fromY = 75f)
        resolve(grid)
        assertEquals(72f, body.positionY(0), 0.001f)
    }

    @Test
    fun aMovingSolidPushesToo() {
        val grid = TestBodies.grid()
        grid.dynamic.add(Solid.moving(left = 20f, top = 20f, right = 52f, bottom = 52f))
        particle(36f, 50f)
        resolve(grid)
        assertEquals(52f, body.positionY(0), 0.001f)
    }

    @Test
    fun aBoxBesideTheTallEdgeOfARisingSlopeCannotBeLeftThroughThatFace() {
        val grid = TestBodies.grid().apply { fill(4, 5, SolidShape.RISING); fill(5, 5) }
        // inside the box, half a unit from the face it shares with the slope's full-height edge
        particle(80.5f, 82f)
        resolve(grid)
        assertEquals(80.5f, body.positionX(0), 0.001f)
        assertEquals(80f, body.positionY(0), 0.001f)
    }

    @Test
    fun aTileIsNeverLeftThroughTheEdgeOfTheGridEvenWhenThatIsTheNearestFace() {
        // a wall tile at the grid's left edge with the floor beside it; outside the grid there is nothing to land on
        val grid = TestBodies.grid().apply { fill(0, 5); fill(1, 5) }
        particle(2f, 84f)
        resolve(grid)
        assertEquals(2f, body.positionX(0), 0.001f, "not pushed out of the world, two units to the left")
        assertEquals(80f, body.positionY(0), 0.001f, "out through the open top instead")
    }

    @Test
    fun aTileBuriedOnEveryOtherSideStillDoesNotPushOutOfTheGrid() {
        // the corner where a wall meets the floor: the top and right faces are buried, the left one is the world's edge
        val grid = TestBodies.grid().apply { fill(0, 4); fill(0, 5); fill(1, 5); fill(0, 6) }
        particle(2f, 88f)
        resolve(grid)
        assertTrue(body.positionX(0) >= 2f, "never toward the world's edge, even as a last resort: x = ${body.positionX(0)}")
        assertEquals(16f, body.positionX(0), 0.001f, "out to the corner where the floor meets the wall, the nearest air")
        assertEquals(80f, body.positionY(0), 0.001f)
    }

    @Test
    fun aParticleJustInsideAnInnerCornerLeavesThroughTheCornerNotThroughTheWall() {
        // a step, (4, 5), against the foot of a wall, (5, 4) and (5, 5): tile (5, 5)'s top and left faces
        // are both buried, but the cell diagonally across its top-left corner, (4, 4), is air
        val grid = TestBodies.grid().apply { fill(5, 4); fill(5, 5); fill(4, 5) }
        // half a unit into the wall, right where the step's top meets it, having come from the step
        particle(80.5f, 80.01f, fromX = 79.5f)
        resolve(grid)
        assertEquals(80f, body.positionX(0), 0.001f, "back out to the corner, not shoved 15.5 units through the wall")
        assertEquals(80f, body.positionY(0), 0.001f)
        assertFalse(grid.isSolidAt(body.positionX(0), body.positionY(0)))
    }

    @Test
    fun aCornerIsNeverAWayOutWhenAnOpenFaceIsCloser() {
        val grid = floorGrid()
        // near a seam between floor tiles, the open top is closer than the corner above the seam
        particle(95.9f, 80.5f)
        resolve(grid)
        assertEquals(95.9f, body.positionX(0), 0.001f)
        assertEquals(80f, body.positionY(0), 0.001f)
    }

    @Test
    fun aBoxBesideTheLowEdgeOfAFallingSlopeCanBeLeftThroughThatFace() {
        val grid = TestBodies.grid().apply { fill(4, 5, SolidShape.FALLING); fill(5, 5) }
        // a falling slope meets the box only at its bottom corner, so that face is open air above it
        particle(80.5f, 82f)
        resolve(grid)
        assertEquals(80f, body.positionX(0), 0.001f)
        assertEquals(82f, body.positionY(0), 0.001f)
    }

    @Test
    fun aMovingSolidIsNotATileAndATileIsNotMoving() {
        assertTrue(Solid.moving(0f, 0f, 10f, 10f).isMoving)
        assertFalse(TestBodies.grid().fill(2, 3).isMoving)
        assertFalse(TestBodies.grid().fillTopHalf(2, 3).isMoving)
    }

    @Test
    fun aPointIsInsideASolidOnlyPastItsSurfaceByMoreThanTheMargin() {
        val box = TestBodies.grid().apply { fill(2, 2) }
        assertTrue(box.isSolidAt(40f, 40f))
        assertFalse(box.isSolidAt(32f, 40f), "a point on the surface is outside")
        assertFalse(box.isSolidAt(10f, 40f))
        assertFalse(box.isSolidAt(33f, 40f, margin = 2f), "inside by 1 is not inside by more than 2")
        assertTrue(box.isSolidAt(36f, 40f, margin = 2f))
    }

    @Test
    fun aStubIsOnlySolidInItsTopHalfAndASlopeOnlyBelowItsSurface() {
        val stub = TestBodies.grid().apply { fillTopHalf(4, 4) }
        assertTrue(stub.isSolidAt(70f, 68f))
        assertFalse(stub.isSolidAt(70f, 76f))
        val rising = TestBodies.grid().apply { fill(2, 2, SolidShape.RISING) }
        assertTrue(rising.isSolidAt(44f, 44f), "below the rising surface")
        assertFalse(rising.isSolidAt(36f, 36f), "above it")
        val falling = TestBodies.grid().apply { fill(2, 2, SolidShape.FALLING) }
        assertTrue(falling.isSolidAt(36f, 44f), "below the falling surface")
        assertFalse(falling.isSolidAt(44f, 36f), "above it")
    }

    @Test
    fun aMovingSolidIsFoundToo() {
        val grid = TestBodies.grid()
        grid.dynamic.add(Solid.moving(20f, 20f, 52f, 52f))
        assertTrue(grid.isSolidAt(36f, 36f))
        assertFalse(grid.isSolidAt(60f, 36f))
    }
}
