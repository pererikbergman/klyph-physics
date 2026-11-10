package klyph.physics

/**
 * The body and world the tests here are built from: a dome with the shape and weight of the slime
 * this module grew out of, in a world of 16-unit tiles stepped 240 times a second.
 */
object TestBodies {
    const val RADIUS = 13.3f
    const val HEIGHT_SCALE = 1.3f
    const val ARC_SEGMENTS = 14
    const val BASE_SEGMENTS = 8
    const val CORE_MASS = 34f

    /** One substep: four of them to a 60 Hz step. */
    const val H = 1f / 240f
    const val GRAVITY = 1500f
    const val TILE = 16f

    /** A [Solid.tag] for the tests that need one. */
    const val PAD = 1

    fun dome(): SoftBody = SoftBody.semiDisc(RADIUS, ARC_SEGMENTS, BASE_SEGMENTS, CORE_MASS, HEIGHT_SCALE)

    /** An empty 10 x 10 grid of tiles. */
    fun grid(): SolidGrid = SolidGrid(10, 10, TILE)
}
