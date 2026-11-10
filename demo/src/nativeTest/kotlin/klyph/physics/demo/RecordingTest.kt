package klyph.physics.demo

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RecordingTest {
    @Test
    fun everyNthStepIsSavedInOrderWithNoGaps() {
        val recording = Recording(every = 2, directory = "/tmp/frames")
        assertNull(recording.fileAfter(1L))
        assertEquals("/tmp/frames/0001.png", recording.fileAfter(2L))
        assertNull(recording.fileAfter(3L))
        assertEquals("/tmp/frames/0002.png", recording.fileAfter(4L))
        assertEquals("/tmp/frames/0050.png", recording.fileAfter(100L))
    }

    @Test
    fun recordingEveryStepSavesThemAll() {
        val recording = Recording(every = 1, directory = "out")
        assertEquals("out/0001.png", recording.fileAfter(1L))
        assertEquals("out/0002.png", recording.fileAfter(2L))
        assertNull(recording.fileAfter(0L), "nothing before the first step")
    }

    @Test
    fun aValueIsReadAsEveryThenDirectory() {
        val recording = Recording.parse("3:/tmp/a:b/")!!
        assertEquals(3, recording.every)
        assertEquals("/tmp/a:b", recording.directory, "split at the first colon, trailing slash dropped")
    }

    @Test
    fun aValueWithoutThatFormIsIgnored() {
        assertNull(Recording.parse(null))
        assertNull(Recording.parse("frames"))
        assertNull(Recording.parse("x:frames"))
        assertNull(Recording.parse("0:frames"))
        assertNull(Recording.parse("2:"))
    }
}
