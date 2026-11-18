package klyph.physics.bridge

import klyph.app.App
import klyph.app.Phase
import klyph.entity.ComponentType
import klyph.entity.Transform
import klyph.entity.World
import klyph.physics.PhysicsWorld
import kotlin.math.PI
import kotlin.math.atan2

/**
 * A [PhysicsWorld] run by a klyph app: [App.addPhysics] makes one and steps it every fixed step. After
 * each step, every entity with a [PhysicsBody] and a [Transform] is moved to where its body is.
 *
 * Give the entity [klyph.entity.Interpolated] too and klyph draws it between the last two steps, so it
 * moves smoothly at any frame rate.
 */
public class Physics(world: PhysicsWorld) {
    /**
     * The world that's stepped. Swap it to change levels: entities still linked to bodies of the old
     * world follow whatever has the same handle in the new one, so remove or relink them.
     */
    public var world: PhysicsWorld = world

    /** While paused, the world isn't stepped and entities don't move. [step] still works, one at a time. */
    public var isPaused: Boolean = false

    /** Steps taken since this was made. */
    public var stepCount: Long = 0L
        private set

    /** Steps [world] by [dt] seconds, then moves every linked entity of [entities] to its body. */
    public fun step(entities: World, dt: Float) {
        world.step(dt)
        stepCount++
        follow(entities)
    }

    /** Moves every entity of [entities] with a [PhysicsBody] and a [Transform] to where its body is now. */
    public fun follow(entities: World) {
        val world = world
        entities.forEach(PhysicsBody, Transform) { _, body, transform -> body.moveTo(world, transform) }
    }
}

/**
 * Links an entity to a body in the [Physics]' world: after each step its [Transform] is put where
 * the body is, and turned as the body is. Make one with [rigid], [soft] or [particle], and add it as
 * `world.add(entity, PhysicsBody, PhysicsBody.rigid(box))`.
 *
 * A rigid body's place is its centre of mass, a soft body's the middle of its ring. It has no one angle, so it turns as its first ring
 * point goes round the middle, from where that was when the link was first followed. A particle
 * doesn't turn. A body that has been removed leaves its entity where it was.
 */
public class PhysicsBody private constructor(
    /** What [handle] is: one of [RIGID], [SOFT] and [PARTICLE]. */
    public val kind: Int,
    /** The handle the world gave the body when it was made. */
    public val handle: Int,
) {
    /** For a soft body: the angle its first ring point was at, seen from its middle, when first followed. */
    private var startAngle = Float.NaN

    internal fun moveTo(world: PhysicsWorld, transform: Transform) {
        when (kind) {
            RIGID -> {
                val bodies = world.rigidBodies
                if (!bodies.isAlive(handle)) return
                transform.x = bodies.x(handle)
                transform.y = bodies.y(handle)
                transform.rotation = bodies.angle(handle)
            }
            SOFT -> {
                val bodies = world.softBodies
                if (!bodies.isAlive(handle)) return
                val x = bodies.centerX(handle)
                val y = bodies.centerY(handle)
                val first = bodies.particle(handle, 0)
                val angle = atan2(world.particles.y(first) - y, world.particles.x(first) - x)
                if (startAngle.isNaN()) startAngle = angle
                transform.x = x
                transform.y = y
                transform.rotation = wrap(angle - startAngle)
            }
            else -> {
                val particles = world.particles
                if (!particles.isAlive(handle)) return
                transform.x = particles.x(handle)
                transform.y = particles.y(handle)
            }
        }
    }

    override fun toString(): String = "PhysicsBody(${NAMES[kind]} $handle)"

    public companion object : ComponentType<PhysicsBody>("PhysicsBody") {
        /** A [PhysicsBody] that follows a rigid body. */
        public const val RIGID: Int = 0
        /** A [PhysicsBody] that follows a soft body. */
        public const val SOFT: Int = 1
        /** A [PhysicsBody] that follows a particle. */
        public const val PARTICLE: Int = 2

        private val NAMES = arrayOf("rigid", "soft", "particle")

        /** Follows the rigid body [handle]: its centre of mass and its angle. */
        public fun rigid(handle: Int): PhysicsBody = PhysicsBody(RIGID, handle)

        /** Follows the soft body [handle]: the middle of its ring, and how far it has turned. */
        public fun soft(handle: Int): PhysicsBody = PhysicsBody(SOFT, handle)

        /** Follows the particle [handle]: its place. */
        public fun particle(handle: Int): PhysicsBody = PhysicsBody(PARTICLE, handle)

        private const val TWO_PI = (2.0 * PI).toFloat()

        /** [angle] brought into -π..π, so a soft body that has rolled many times isn't drawn at a huge angle. */
        private fun wrap(angle: Float): Float {
            var a = angle % TWO_PI
            if (a > PI.toFloat()) a -= TWO_PI
            if (a < -PI.toFloat()) a += TWO_PI
            return a
        }
    }
}

/**
 * Runs [world] in this app: steps it in [Phase.FIXED_UPDATE] by the fixed step, unless paused, then
 * moves every entity with a [PhysicsBody] and a [Transform] to its body. Returns the [Physics], which
 * is also kept as a resource: [physics] finds it again.
 *
 * Systems run in the order they're added. Add the fixed-step systems that push, steer or spawn things
 * before calling this, and those that read what happened, such as contacts, after.
 */
public fun App.addPhysics(world: PhysicsWorld = PhysicsWorld()): Physics {
    require(!resources.has<Physics>()) { "This app already runs physics: change its world with physics.world" }
    val physics = Physics(world)
    resources.insert(physics)
    addSystem(Phase.FIXED_UPDATE) { app ->
        if (!physics.isPaused) physics.step(app.world, app.time.fixedDelta.inWholeNanoseconds / 1e9f)
    }
    return physics
}

/** The [Physics] that [addPhysics] added to this app. */
public val App.physics: Physics get() = resources.get()
