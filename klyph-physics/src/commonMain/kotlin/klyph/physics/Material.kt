package klyph.physics

import kotlin.math.max
import kotlin.math.sqrt

/**
 * What a surface is like to touch: how much it grips and how much it bounces. Solids and particles
 * each have one, and a contact mixes the two: grip by the geometric mean, so ice on anything slides,
 * and bounce by the larger, so a rubber ball bounces on a stone floor.
 */
data class Material(
    /**
     * Grip while sliding, the Coulomb coefficient: a sliding thing loses this times the push holding
     * it on the surface. 0 is ice.
     */
    val friction: Float = 0.4f,
    /**
     * Grip at rest: a thing stays put while the force along the surface is under this times the push
     * holding it on. Something rests on a slope while this is over the slope's tangent: 0.58 for 30°.
     */
    val staticFriction: Float = 0.6f,
    /** How much of its speed into the surface a thing keeps, bouncing back out: 0 stops dead, 1 bounces forever. */
    val restitution: Float = 0f,
) {
    init {
        require(friction >= 0f) { "friction is $friction: it must not be negative" }
        require(staticFriction >= 0f) { "staticFriction is $staticFriction: it must not be negative" }
        require(restitution in 0f..1f) { "restitution is $restitution: it must be from 0 to 1" }
    }

    companion object {
        /** What everything is made of unless told otherwise: some grip, no bounce. */
        val DEFAULT = Material()

        /** No grip at all, no bounce. */
        val ICE = Material(friction = 0f, staticFriction = 0f)

        /** A lot of grip. */
        val RUBBER = Material(friction = 0.9f, staticFriction = 1.2f)

        internal fun mixFriction(a: Float, b: Float): Float = sqrt(a * b)

        internal fun mixRestitution(a: Float, b: Float): Float = max(a, b)
    }
}
