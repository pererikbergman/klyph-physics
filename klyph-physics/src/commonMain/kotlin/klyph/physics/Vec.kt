package klyph.physics

/** A 2D vector, for settings and arguments. y grows down, as in klyph. The step itself never makes one. */
public data class Vec(val x: Float, val y: Float) {
    public companion object {
        /** No length and no direction: (0, 0). */
        public val ZERO: Vec = Vec(0f, 0f)
    }
}
