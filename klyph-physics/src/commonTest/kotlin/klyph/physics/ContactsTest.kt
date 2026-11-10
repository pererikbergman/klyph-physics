package klyph.physics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ContactsTest {
    /** Five particles, of which the first four are the outline and the fifth is the core. */
    private fun contacts() = Contacts(particleCount = 5, outlineCount = 4)

    @Test
    fun aFloorTouchIsSeenThisStepButOnlyRememberedAsLastStepAfterTheNextBegins() {
        val c = contacts()
        c.beginStep()
        c.recordFloor(1)
        assertTrue(c.touchedFloor)
        assertTrue(c.isOnFloor(1))
        assertFalse(c.wasOnFloor(1), "this step's touch is not last step's")
        c.beginStep()
        assertTrue(c.wasOnFloor(1))
        assertFalse(c.touchedFloor, "the new step starts with nothing touched")
        assertFalse(c.isOnFloor(1))
    }

    @Test
    fun aTouchInAnySubstepOfTheStepCounts() {
        val c = contacts()
        c.beginStep()
        c.recordFloor(0)
        c.recordFloor(2)
        assertTrue(c.isOnFloor(0))
        assertTrue(c.isOnFloor(2))
        assertFalse(c.isOnFloor(1))
    }

    @Test
    fun theCoreNeverCountsAsBeingOnTheFloor() {
        val c = contacts()
        c.beginStep()
        c.recordFloor(4)
        c.beginStep()
        assertFalse(c.wasOnFloor(4))
    }

    @Test
    fun theTagsOfWhatWasTouchedAreReportedForTheStepOnly() {
        val c = contacts()
        c.beginStep()
        c.recordFloor(0, tag = 0b01)
        c.recordFloor(1, tag = 0b100)
        assertTrue(c.touched(0b01))
        assertTrue(c.touched(0b100))
        assertFalse(c.touched(0b10))
        assertEquals(0b101, c.touchedTags)
        c.beginStep()
        assertFalse(c.touched(0b01))
    }

    @Test
    fun clearForgetsEverythingIncludingLastStep() {
        val c = contacts()
        c.beginStep()
        c.recordFloor(0, tag = 1)
        c.beginStep()
        c.clear()
        assertFalse(c.wasOnFloor(0))
        assertFalse(c.touchedFloor)
        assertEquals(0, c.touchedTags)
    }
}
