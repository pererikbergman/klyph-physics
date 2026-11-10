package klyph.physics

/**
 * How a [PhysicsWorld] moves time. Every value is in world units and seconds, so a world behaves the
 * same however finely it is stepped, except where a value says otherwise.
 */
data class PhysicsConfig(
    /** Acceleration on every particle, in units per second squared. y grows down, so down is positive. */
    val gravity: Vec = Vec(0f, 1500f),
    /** How many substeps each [PhysicsWorld.step] is cut into. More is stiffer and steadier, and costs more. */
    val substeps: Int = 4,
    /** Constraint passes per substep, each followed by collision, so walls have the last word. */
    val iterations: Int = 6,
    /**
     * How fast motion dies away, per second: velocity is multiplied by `exp(-damping * h)` every
     * substep of `h` seconds, so the same damping loses the same share of speed per second however
     * many substeps there are. 0 keeps all of it; 0.24 loses about a fifth per second.
     */
    val damping: Float = 0.24f,
    /**
     * The farthest a particle may move in one substep, in units. Keep it under half the thinnest wall,
     * or a fast particle can pop out the far side. This one is per substep on purpose: walls don't
     * get thicker when you add substeps.
     */
    val maxStep: Float = 4f,
    /**
     * The side of a cell of the broadphase that finds which bodies might touch, in units. About the
     * size of a body works best; see the broadphase page.
     */
    val cellSize: Float = 32f,
    /**
     * Slower than this into a surface, in units per second, nothing bounces, whatever its restitution:
     * resting things settle instead of buzzing. Gravity alone reaches it in a fall of under a unit.
     */
    val bounceSpeed: Float = 40f,
) {
    init {
        require(substeps >= 1) { "substeps is $substeps: there must be at least one" }
        require(iterations >= 1) { "iterations is $iterations: there must be at least one" }
        require(damping >= 0f && damping.isFinite()) { "damping is $damping: it must be finite and not negative" }
        require(maxStep > 0f) { "maxStep is $maxStep: it must be more than 0" }
        require(cellSize > 0f) { "cellSize is $cellSize: it must be more than 0" }
        require(bounceSpeed >= 0f) { "bounceSpeed is $bounceSpeed: it must not be negative" }
    }
}
