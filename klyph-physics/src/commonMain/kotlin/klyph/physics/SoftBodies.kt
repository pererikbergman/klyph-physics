package klyph.physics

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * The rest shape of a soft body: a ring of points in order, and a core inside it [coreMass] times
 * heavier than a ring point. The coordinates are the shape's own; [PhysicsWorld.softBody] places it.
 * Make one with [SoftBodies].
 */
class SoftBodyShape internal constructor(
    internal val ringX: FloatArray,
    internal val ringY: FloatArray,
    internal val coreX: Float,
    internal val coreY: Float,
    val coreMass: Float,
) {
    init {
        require(ringX.size >= 3 && ringX.size == ringY.size) { "a soft body needs a ring of at least 3 points" }
        require(coreMass > 0f) { "coreMass is $coreMass: it must be more than 0" }
    }

    /** How many points the ring has. */
    val ringCount: Int get() = ringX.size
}

/** Builders for the shapes soft bodies come in. y grows down. */
object SoftBodies {
    /**
     * A slime: a half-disc of [radius], [arcSegments] segments over the dome from the left end to the
     * right, then [baseSegments] along the flat base back, with the dome stretched [heightScale] times
     * taller and the core where the half-disc's centre of mass is.
     */
    fun semiDisc(radius: Float, arcSegments: Int, baseSegments: Int, coreMass: Float, heightScale: Float = 1f): SoftBodyShape {
        require(radius > 0f && arcSegments >= 2 && baseSegments >= 1) { "a semi-disc needs a radius and at least 2 arc and 1 base segments" }
        val ring = arcSegments + baseSegments
        val xs = FloatArray(ring)
        val ys = FloatArray(ring)
        for (i in 0..arcSegments) {
            val angle = PI.toFloat() + PI.toFloat() * i / arcSegments
            xs[i] = cos(angle) * radius
            ys[i] = sin(angle) * radius * heightScale
        }
        for (j in 1 until baseSegments) {
            xs[arcSegments + j] = radius - 2f * radius * j / baseSegments
            ys[arcSegments + j] = 0f
        }
        return SoftBodyShape(xs, ys, 0f, -4f * radius / (3f * PI.toFloat()) * heightScale, coreMass)
    }

    /** A ball: [segments] points around a circle of [radius], with the core in the middle. */
    fun circle(radius: Float, segments: Int, coreMass: Float): SoftBodyShape {
        require(radius > 0f && segments >= 3) { "a circle needs a radius and at least 3 segments" }
        val xs = FloatArray(segments) { cos(2f * PI.toFloat() * it / segments) * radius }
        val ys = FloatArray(segments) { sin(2f * PI.toFloat() * it / segments) * radius }
        return SoftBodyShape(xs, ys, 0f, 0f, coreMass)
    }

    /**
     * A box [width] by [height], its ring [across] segments along the top and bottom and [down] along
     * each side, with the core in the middle.
     */
    fun box(width: Float, height: Float, across: Int, down: Int, coreMass: Float): SoftBodyShape {
        require(width > 0f && height > 0f && across >= 1 && down >= 1) { "a box needs a size and at least 1 segment a side" }
        val ring = 2 * (across + down)
        val xs = FloatArray(ring)
        val ys = FloatArray(ring)
        var k = 0
        val left = -width / 2f
        val top = -height / 2f
        for (i in 0 until across) { xs[k] = left + width * i / across; ys[k] = top; k++ }
        for (i in 0 until down) { xs[k] = -left; ys[k] = top + height * i / down; k++ }
        for (i in 0 until across) { xs[k] = -left - width * i / across; ys[k] = -top; k++ }
        for (i in 0 until down) { xs[k] = left; ys[k] = -top - height * i / down; k++ }
        return SoftBodyShape(xs, ys, 0f, 0f, coreMass)
    }
}

/**
 * How soft a body is. The compliances say how far each kind of constraint gives per unit of force
 * (0 is rigid): the ring's edges, the spokes from the ring to the core, and the area. Soft spokes let a
 * body stretch and squash, a soft area lets it sag and be squeezed, and stiff edges keep the skin from
 * tearing. How fast it moves, how damped and how often it's solved is the world's [PhysicsConfig].
 */
data class SoftBodyTuning(
    val edgeCompliance: Float = 0.00002f,
    val spokeCompliance: Float = 0.0005f,
    val areaCompliance: Float = 0.0004f,
)
