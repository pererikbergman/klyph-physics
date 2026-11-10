package klyph.physics.demo

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SandboxTest {
    private val dt = 1f / 60f

    /** A scene whose runs count their steps, and which counts how many runs it made. */
    private class Counting(override val name: String) : Scene {
        override val blurb = ""
        var created = 0
        val runs = ArrayList<Run>()

        class Run : SceneRun {
            var steps = 0
            override fun step(dt: Float) {
                steps++
            }
        }

        override fun create(): SceneRun = Run().also { created++; runs.add(it) }
    }

    private val a = Counting("A")
    private val b = Counting("B")
    private val c = Counting("C")
    private val sandbox = Sandbox(listOf(a, b, c))

    private val steps: Int get() = (sandbox.run as Counting.Run).steps

    @Test
    fun itStartsOnTheFirstSceneRunningAtFullSpeed() {
        assertEquals(0, sandbox.index)
        assertSame(a, sandbox.scene)
        assertFalse(sandbox.isPaused)
        assertFalse(sandbox.isSlowMotion)
        assertTrue(sandbox.fixedStep(dt))
        assertEquals(1, steps)
        assertEquals(1L, sandbox.stepCount)
    }

    @Test
    fun selectingASceneStartsAFreshRunOfIt() {
        sandbox.select(2)
        assertSame(c, sandbox.scene)
        assertEquals(1, c.created)
        sandbox.fixedStep(dt)
        sandbox.select(2)
        assertEquals(2, c.created, "choosing the scene that is on starts it again")
        assertEquals(0L, sandbox.stepCount)
    }

    @Test
    fun selectingASceneThatDoesNotExistChangesNothing() {
        val before = sandbox.run
        sandbox.select(3)
        sandbox.select(-1)
        assertSame(a, sandbox.scene)
        assertSame(before, sandbox.run)
    }

    @Test
    fun nextAndPreviousWrapAround() {
        sandbox.previous()
        assertSame(c, sandbox.scene)
        sandbox.next()
        assertSame(a, sandbox.scene)
        sandbox.next()
        assertSame(b, sandbox.scene)
    }

    @Test
    fun resetReplacesTheRunAndKeepsPauseAndSlowMotion() {
        sandbox.fixedStep(dt)
        sandbox.togglePause()
        sandbox.toggleSlowMotion()
        val before = sandbox.run
        sandbox.reset()
        assertNotSame(before, sandbox.run)
        assertEquals(0L, sandbox.stepCount)
        assertTrue(sandbox.isPaused)
        assertTrue(sandbox.isSlowMotion)
    }

    @Test
    fun aPausedSandboxDoesNotStep() {
        sandbox.togglePause()
        repeat(10) { assertFalse(sandbox.fixedStep(dt)) }
        assertEquals(0, steps)
        sandbox.togglePause()
        assertTrue(sandbox.fixedStep(dt))
    }

    @Test
    fun steppingOncePausesAndMovesExactlyOneStep() {
        sandbox.stepOnce(dt)
        assertTrue(sandbox.isPaused)
        assertEquals(1, steps)
        sandbox.fixedStep(dt)
        assertEquals(1, steps, "still paused")
        sandbox.stepOnce(dt)
        assertEquals(2, steps)
    }

    @Test
    fun slowMotionStepsOnOneFixedStepInFour() {
        sandbox.toggleSlowMotion()
        var moved = 0
        repeat(40) { if (sandbox.fixedStep(dt)) moved++ }
        assertEquals(40 / Sandbox.SLOW_MOTION_FACTOR, moved)
        assertEquals(moved, steps)
        sandbox.toggleSlowMotion()
        repeat(4) { assertTrue(sandbox.fixedStep(dt)) }
    }

    @Test
    fun numbersAreWrittenWithTheAskedDecimalsAndRounded() {
        assertEquals("59", StringBuilder().appendFixed(59.4f, 0).toString())
        assertEquals("0.05", StringBuilder().appendFixed(0.049f, 2).toString())
        assertEquals("1.50", StringBuilder().appendFixed(1.5f, 2).toString())
        assertEquals("12.3", StringBuilder().appendFixed(12.34f, 1).toString())
        assertEquals("-2.5", StringBuilder().appendFixed(-2.5f, 1).toString())
        assertEquals("0.0", StringBuilder().appendFixed(-0.01f, 1).toString())
    }
}
