package klyph.physics

/**
 * What a body has been resting on: which outline particles touched a surface facing up, and the
 * [Solid.tag]s of what they touched. It keeps this step's touches and the previous step's apart,
 * because a jump asks what the body was standing on before it left the ground.
 *
 * Call [beginStep] once at the start of every step, then let [SolidGrid.resolve] record touches as
 * many times as it likes; there is no per-substep bookkeeping. [clear] forgets everything, for a
 * body that has been put somewhere new.
 */
class Contacts(particleCount: Int, private val outlineCount: Int) {
    private var current = BooleanArray(particleCount)
    private var previous = BooleanArray(particleCount)

    /** Whether any particle touched a surface facing up so far this step. */
    var touchedFloor = false
        private set

    /** Every [Solid.tag] bit of the floors touched so far this step, or-ed together. */
    var touchedTags = 0
        private set

    /** Starts a step: what was touched during the one before is remembered, and this step starts with nothing touched. */
    fun beginStep() {
        val swap = previous
        previous = current
        current = swap
        current.fill(false)
        touchedFloor = false
        touchedTags = 0
    }

    /** Forgets this step and the last one. */
    fun clear() {
        current.fill(false)
        previous.fill(false)
        touchedFloor = false
        touchedTags = 0
    }

    /** Whether a floor carrying any bit of [tag] was touched so far this step. */
    fun touched(tag: Int): Boolean = touchedTags and tag != 0

    /** Whether particle [i] touched a surface facing up so far this step. */
    fun isOnFloor(i: Int): Boolean = current[i]

    /** Whether outline particle [i] rested on a surface facing up at any time in the previous step. The core never counts. */
    fun wasOnFloor(i: Int): Boolean = i < outlineCount && previous[i]

    /** Records that particle [i] was pushed by a surface facing up that carries [tag]. [SolidGrid] calls this. */
    fun recordFloor(i: Int, tag: Int = 0) {
        current[i] = true
        touchedFloor = true
        touchedTags = touchedTags or tag
    }
}
