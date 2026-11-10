package klyph.physics.bridge

/** How much a [DebugDraw] shows, each level adding to the one before. `F1` in the sandbox steps through them. */
enum class DebugView {
    /** Just the bodies and the solids, filled. */
    OFF,

    /** And every particle, the heavy cores, and the edge of every tile. */
    BODIES,

    /** And every constraint, coloured by how hard it is working: blue squeezed, red stretched. */
    CONSTRAINTS,

    /** And every contact: where each particle touched something, and which way it was pushed. */
    EVERYTHING,
    ;

    /** The next level, back to [OFF] after [EVERYTHING]. */
    fun next(): DebugView = entries[(ordinal + 1) % entries.size]

    /** Whether this shows at least as much as [level]. */
    fun shows(level: DebugView): Boolean = ordinal >= level.ordinal
}
