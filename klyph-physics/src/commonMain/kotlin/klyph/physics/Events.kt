package klyph.physics

/**
 * What happened to particles in the last [PhysicsWorld.step]: which ones stood on a floor, and the
 * [Solid.tag]s of those floors. It keeps this step and the one before apart, because "can I jump?" asks
 * about before you left the ground.
 *
 * A floor is any solid that pushed the particle up: a surface flatter than 60 degrees. Walls and
 * ceilings push too, but aren't floors and report no tags.
 */
class Events internal constructor(private val particles: Particles) {
    /** Whether particle [p] stood on a floor at any time during the last step. */
    fun isOnFloor(p: Int): Boolean = particles.contact[p] and NOW != 0

    /** Whether particle [p] stood on a floor during the step before the last. */
    fun wasOnFloor(p: Int): Boolean = particles.contact[p] and BEFORE != 0

    /** The tags of every floor particle [p] stood on during the last step, or-ed together; 0 for none. */
    fun floorTags(p: Int): Int = particles.floorTag[p]

    /**
     * Whether particle [p] was pushed out of anything during the last step: a solid, a body or another
     * particle, floor or not.
     */
    fun isTouching(p: Int): Boolean = particles.contact[p] and TOUCHING != 0

    /**
     * The unit normal of particle [p]'s last contact, pointing away from what it touched: (0, -1) on a
     * flat floor. Only meaningful while [isTouching].
     */
    fun contactNormalX(p: Int): Float = particles.contactNx[p]

    fun contactNormalY(p: Int): Float = particles.contactNy[p]

    /** What particle [p] last touched: another particle, an edge's first particle, or -1 for a solid. */
    fun touchedParticle(p: Int): Int = particles.contactA[p]

    /** Starts a step: this step's touches become the last step's, and nothing has been touched yet. */
    internal fun beginStep() {
        val contact = particles.contact
        val floorTag = particles.floorTag
        for (p in 0 until particles.end) {
            contact[p] = if (contact[p] and NOW != 0) BEFORE else 0
            floorTag[p] = 0
        }
    }

    /** Records that a floor carrying [tag] pushed particle [p]. */
    internal fun recordFloor(p: Int, tag: Int) {
        particles.contact[p] = particles.contact[p] or NOW
        particles.floorTag[p] = particles.floorTag[p] or tag
    }

    internal companion object {
        const val NOW = 1
        const val BEFORE = 2
        const val TOUCHING = 4
    }
}
