package klyph.physics.demo

import klyph.physics.Contacts
import klyph.physics.SoftBody
import klyph.physics.SoftBodyTuning
import klyph.physics.SolidGrid
import klyph.platform.Color

/**
 * A soft body and what it stands on, stepped through a [SolidGrid]: the way every scene of the first
 * phase moves a body, until the world's own step arrives. Each fixed step is cut into [SUBSTEPS].
 */
class Blob(val body: SoftBody, val color: Color) {
    val contacts = Contacts(body.size, body.ringCount)

    /** Advances the blob by [dt] seconds under [gravity], pushed out of [grid] every pass. */
    fun step(grid: SolidGrid, dt: Float, gravity: Float = GRAVITY) {
        contacts.beginStep()
        val h = dt / SUBSTEPS
        repeat(SUBSTEPS) { body.substep(h, gravity) { grid.resolve(body, contacts) } }
    }

    /** How many of its particles stand on a floor right now. */
    fun floorContactCount(): Int {
        var n = 0
        for (i in 0 until body.size) if (contacts.isOnFloor(i)) n++
        return n
    }

    companion object {
        const val SUBSTEPS = 4
        const val GRAVITY = 1500f

        /** A slime-shaped dome: half a disc of [radius], stretched 1.3 times taller, with a core [coreMass] times heavier than its skin. */
        fun dome(
            centerX: Float,
            baseY: Float,
            color: Color,
            radius: Float = 13.3f,
            coreMass: Float = 34f,
            tuning: SoftBodyTuning = SoftBodyTuning(),
        ): Blob {
            val body = SoftBody.semiDisc(radius, arcSegments = 14, baseSegments = 8, coreMass = coreMass, heightScale = 1.3f, tuning = tuning)
            body.placeAt(centerX, baseY)
            return Blob(body, color)
        }
    }
}
