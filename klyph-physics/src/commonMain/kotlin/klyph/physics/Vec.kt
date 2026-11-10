package klyph.physics

/** A 2D vector, for settings and arguments. y grows down, as in klyph. The step itself never makes one. */
data class Vec(val x: Float, val y: Float) {
    companion object {
        val ZERO = Vec(0f, 0f)
    }
}
