package klyph.physics.demo

import klyph.physics.bridge.DebugDraw
import klyph.physics.bridge.DebugView
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The debug levels `F1` steps through, and the colours stress is drawn in. */
class DebugViewTest {
    @Test
    fun f1StepsOffBodiesConstraintsEverythingAndBackToOff() {
        var view = DebugView.OFF
        val seen = mutableListOf(view)
        repeat(4) {
            view = view.next()
            seen += view
        }
        assertEquals(listOf(DebugView.OFF, DebugView.BODIES, DebugView.CONSTRAINTS, DebugView.EVERYTHING, DebugView.OFF), seen)
    }

    @Test
    fun eachLevelShowsWhatTheOnesBelowItShow() {
        assertTrue(DebugView.EVERYTHING.shows(DebugView.CONSTRAINTS))
        assertTrue(DebugView.CONSTRAINTS.shows(DebugView.BODIES))
        assertTrue(DebugView.BODIES.shows(DebugView.BODIES))
        assertFalse(DebugView.BODIES.shows(DebugView.CONSTRAINTS))
        assertFalse(DebugView.OFF.shows(DebugView.BODIES))
    }

    @Test
    fun stressGoesFromBlueSqueezedThroughGreyToRedStretched() {
        val squeezed = DebugDraw.stressColor(-1f)
        val resting = DebugDraw.stressColor(0f)
        val stretched = DebugDraw.stressColor(1f)
        assertTrue(squeezed.blue > squeezed.red, "squeezed is blue")
        assertTrue(stretched.red > stretched.blue, "stretched is red")
        assertTrue(resting.red < stretched.red && resting.blue < squeezed.blue, "rest is in between")
        assertEquals(stretched, DebugDraw.stressColor(7f), "past full is full")
        assertEquals(squeezed, DebugDraw.stressColor(-7f))
        assertEquals(resting, DebugDraw.stressColor(Float.NaN), "a broken number draws as rest, not a crash")
        val half = DebugDraw.stressColor(0.5f)
        assertTrue(half.red > resting.red && half.red <= stretched.red && half.blue < resting.blue, "half way is in between")
    }
}
