package klyph.physics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EventsTest {
    private val particles = Particles()
    private val events = Events(particles)
    private val a = particles.add(0f, 0f)
    private val b = particles.add(0f, 0f)

    @Test
    fun aFloorTouchIsSeenThisStepButOnlyRememberedAsLastStepAfterTheNextBegins() {
        events.beginStep()
        events.recordFloor(a, tag = 0)
        assertTrue(events.isOnFloor(a))
        assertFalse(events.wasOnFloor(a), "this step's touch is not last step's")
        assertFalse(events.isOnFloor(b))
        events.beginStep()
        assertTrue(events.wasOnFloor(a))
        assertFalse(events.isOnFloor(a), "the new step starts with nothing touched")
        events.beginStep()
        assertFalse(events.wasOnFloor(a), "and is forgotten a step later")
    }

    @Test
    fun theTagsOfWhatWasTouchedAddUpForTheStepOnly() {
        events.beginStep()
        events.recordFloor(a, tag = 0b01)
        events.recordFloor(a, tag = 0b100)
        assertEquals(0b101, events.floorTags(a))
        assertEquals(0, events.floorTags(b))
        events.beginStep()
        assertEquals(0, events.floorTags(a))
    }

    @Test
    fun aNewParticleInAReusedSlotStartsWithNothingTouched() {
        events.beginStep()
        events.recordFloor(a, tag = 1)
        particles.remove(a)
        val c = particles.add(0f, 0f)
        assertEquals(a, c)
        assertFalse(events.isOnFloor(c))
        assertEquals(0, events.floorTags(c))
    }

    @Test
    fun aBodyDroppedOnAFloorLandsStaysOnTopAndSaysSo() {
        val world = PhysicsWorld()
        world.solids = SolidGrid(20, 10, TestBodies.TILE).apply { for (c in 0 until cols) fill(c, 8, tag = TestBodies.PAD) }
        val blob = world.softBody(TestBodies.dome(), centerX = 160f, baseY = 60f)
        assertFalse(world.softBodies.isOnFloor(blob))
        // With its base gripping the floor, it sloshes for a few seconds before it's still.
        repeat(240) { world.step(TestBodies.DT) }
        val floor = 8 * TestBodies.TILE
        assertTrue(world.softBodies.isOnFloor(blob))
        assertTrue(world.softBodies.touched(blob, TestBodies.PAD))
        assertTrue(world.softBodies.floorContactCount(blob) >= TestBodies.BASE_SEGMENTS, "the base rests on it")
        assertEquals(floor, world.softBodies.maxY(blob), 0.5f)
        assertEquals(0f, world.softBodies.meanVelocityY(blob), 1f)
        assertTrue(world.events.wasOnFloor(world.softBodies.particle(blob, 0)) || world.events.wasOnFloor(world.softBodies.particle(blob, TestBodies.ARC_SEGMENTS)))
    }

    @Test
    fun aRoundParticleBouncesOffNothingAndRestsARadiusAboveTheFloor() {
        val world = PhysicsWorld()
        world.solids = SolidGrid(10, 10, TestBodies.TILE).apply { for (c in 0 until cols) fill(c, 8) }
        val p = world.particles.add(80f, 20f, radius = 5f)
        repeat(120) { world.step(TestBodies.DT) }
        assertEquals(8 * TestBodies.TILE - 5f, world.particles.y(p), 0.01f)
        assertTrue(world.events.isOnFloor(p))
    }
}
