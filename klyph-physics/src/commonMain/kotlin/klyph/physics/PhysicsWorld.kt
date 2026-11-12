package klyph.physics

import kotlin.math.exp
import kotlin.math.sqrt

/**
 * Everything that moves, and the one way to move time: [step]. The world owns its stores; bodies,
 * particles and constraints are `Int` handles into them, so a step touches plain arrays and allocates
 * nothing. The same calls in the same order give the same results, to the bit.
 */
class PhysicsWorld(val config: PhysicsConfig = PhysicsConfig()) {
    val particles = Particles()

    /** Distances, ropes, pins and areas between [particles]. */
    val constraints = Constraints(particles)

    /** Bodies that keep their shape and turn: see [RigidBodies]. */
    val rigidBodies = RigidBodies()

    /** Soft bodies: groups of [particles] held together by [constraints]. */
    val softBodies = SoftBodyStore(this)

    /** What happened during the last step: which particles stood on a floor, and what it was tagged. */
    val events = Events(particles)

    /** The level's walls, floors, slopes and moving solids, or `null` for none. Every particle is pushed out of them. */
    var solids: SolidGrid? = null

    /** The hand that drags particles about: see [grab]. */
    val hand = Grab(this)

    private val collisions = Collisions(this)

    /** Hinges, sliders, welds and distance joints between rigid bodies: see [Joints]. */
    val joints = Joints(particles, rigidBodies)

    /** Particles held to points on rigid bodies: see [attach] and [Attachments]. */
    val attachments = Attachments(particles, rigidBodies)

    private val coupling = Coupling(this)

    /** Where rigid bodies touched each other and the solids in the last substep, for drawing: see [RigidContacts]. */
    val rigidContacts = RigidContacts(this)

    /**
     * The boxes this step's collisions were found from, for drawing: one per soft body and one per
     * colliding particle that isn't part of a body. A box's id is `2·particle` for a particle and
     * `2·body + 1` for a body. Read it; don't change it.
     */
    val broadphase: Broadphase get() = collisions.broadphase

    /** The length of the last substep, in seconds: what velocities in units per second are measured against. */
    var substepLength: Float = 1f / 60f / config.substeps
        private set

    /** How many times [step] has run. */
    var stepCount: Long = 0
        private set

    /**
     * Advances the world by [dt] seconds, in [PhysicsConfig.substeps] substeps. Each one moves every
     * particle and rigid body and finds where rigid bodies touch, then [PhysicsConfig.iterations] times
     * solves the [constraints], [attachments] and [joints], keeps bodies out of each other, soft and rigid
     * alike, and pushes everything out of the
     * [solids], so walls have the last word. Last, rigid bodies take their velocities from how far they
     * moved, and their contacts' friction and bounce change them.
     */
    fun step(dt: Float) {
        require(dt > 0f && dt.isFinite()) { "dt is $dt: it must be a finite time more than 0" }
        val h = dt / config.substeps
        substepLength = h
        val keep = exp(-config.damping * h)
        val restingMove = config.bounceSpeed * h
        val gravity = config.gravity
        events.beginStep()
        hand.beginStep()
        rigidBodies.beginStep(dt)
        collisions.beginStep(dt)
        coupling.beginStep(dt)
        rigidContacts.beginStep(dt)
        repeat(config.substeps) {
            integrate(h, keep)
            rigidBodies.integrate(h, keep, gravity.x, gravity.y, config.maxStep)
            rigidContacts.find()
            constraints.beginSubstep()
            attachments.beginSubstep()
            joints.beginSubstep(h)
            hand.beginSubstep()
            repeat(config.iterations) { pass ->
                constraints.solve(h)
                attachments.solve(h)
                joints.solve(h)
                hand.solve(h)
                // Grip just before the last pass, so collisions still have the last word over it.
                if (pass == config.iterations - 1) particles.applyFriction(rigidBodies)
                collisions.solve()
                coupling.solve()
                solids?.collide(particles, events, restingMove)
                rigidContacts.solve()
            }
            joints.endSubstep(h)
            rigidBodies.updateVelocities(h)
            rigidContacts.solveVelocities(h, config.bounceSpeed, config.iterations)
        }
        constraints.tear()
        stepCount++
    }

    /**
     * Removes particle [p] and every constraint on it. Use this rather than `particles.remove`
     * when [p] has constraints: its handle will be reused, and a new particle shouldn't inherit them.
     */
    fun removeParticle(p: Int) {
        val c = constraints
        for (link in 0 until c.linkEnd) if (c.isLinkAlive(link) && (c.linkA(link) == p || c.linkB(link) == p)) c.removeLink(link)
        for (pin in 0 until c.pinEnd) if (c.isPinAlive(pin) && c.pinnedParticle(pin) == p) c.removePin(pin)
        for (area in 0 until c.areaEnd) if (c.isAreaAlive(area) && c.ringContains(area, p)) c.removeArea(area)
        for (bend in 0 until c.bendEnd) if (c.isBendAlive(bend) && (c.bendA(bend) == p || c.bendB(bend) == p || c.bendC(bend) == p)) c.removeBend(bend)
        particles.remove(p)
    }

    /**
     * Takes hold of the dynamic rigid body at ([x], [y]), by that point, or else the movable particle
     * nearest it, within [reach], and pulls it towards that point with a soft pin until [release].
     * Returns the particle, or -1 for a body (see [grabbedBody]) or nothing in reach. One thing at a
     * time: grabbing again lets go of the last. See [Grab].
     */
    fun grab(x: Float, y: Float, reach: Float = Grab.REACH, compliance: Float = Grab.COMPLIANCE): Int = hand.grab(x, y, reach, compliance)

    /** Moves the point the grabbed particle is pulled towards. However far it jumps, it pulls gently. */
    fun moveGrab(x: Float, y: Float) = hand.moveTo(x, y)

    /** Lets go of the grabbed particle, if any. */
    fun release() = hand.release()

    /** The particle being grabbed, or -1. */
    val grabbed: Int get() = hand.particle

    /** The rigid body being grabbed, or -1. */
    val grabbedBody: Int get() = hand.body

    /**
     * Adds a soft body of [shape] with its middle at [centerX] and its bottom on [baseY], and returns
     * its handle in [softBodies].
     */
    fun softBody(
        shape: SoftBodyShape,
        centerX: Float,
        baseY: Float,
        tuning: SoftBodyTuning = SoftBodyTuning(),
        material: Material = Material.DEFAULT,
    ): Int = softBodies.add(shape, centerX, baseY, tuning, material = material)

    /**
     * Adds a rigid body made of [shape] with the shape's origin at ([x], [y]), turned by [angle], and
     * returns its handle in [rigidBodies]. Its mass and inertia come from the shape's size and [density].
     */
    fun rigidBody(
        shape: Shape,
        x: Float,
        y: Float,
        angle: Float = 0f,
        density: Float = RigidBodies.DENSITY,
        material: Material = Material.DEFAULT,
        kind: BodyKind = BodyKind.DYNAMIC,
    ): Int = rigidBodies.add(listOf(shape), x, y, angle, density, material, kind)

    /** Adds a rigid body made of several [shapes], such as a hammer of two boxes: see [RigidBodies.add]. */
    fun rigidBody(
        shapes: List<Shape>,
        x: Float,
        y: Float,
        angle: Float = 0f,
        density: Float = RigidBodies.DENSITY,
        material: Material = Material.DEFAULT,
        kind: BodyKind = BodyKind.DYNAMIC,
    ): Int = rigidBodies.add(shapes, x, y, angle, density, material, kind)

    /**
     * Holds particle [p] to rigid body [body] at the point where [p] is now, as softly as [compliance],
     * so each drags the other: a rope tied to a crate. Returns the handle in [attachments].
     */
    fun attach(p: Int, body: Int, compliance: Float = 0f): Int = attachments.attach(p, body, compliance)

    /** Velocity of particle [p] in units per second, over the last substep. */
    fun velocityX(p: Int): Float = particles.movedX(p) / substepLength

    fun velocityY(p: Int): Float = particles.movedY(p) / substepLength

    /** Gives particle [p] a velocity of ([vx], [vy]) units per second. */
    fun setVelocity(p: Int, vx: Float, vy: Float) = particles.setVelocity(p, vx, vy, substepLength)

    /**
     * Moves every particle that can move: it repeats its last move, a little weaker for [keep], plus
     * gravity, capped at [PhysicsConfig.maxStep].
     */
    private fun integrate(h: Float, keep: Float) {
        val ps = particles
        val x = ps.x
        val y = ps.y
        val prevX = ps.prevX
        val prevY = ps.prevY
        val invMass = ps.invMass
        val gx = config.gravity.x * h * h
        val gy = config.gravity.y * h * h
        val maxStep = config.maxStep
        for (p in 0 until ps.end) {
            if (invMass[p] == 0f) continue
            var dx = (x[p] - prevX[p]) * keep + gx
            var dy = (y[p] - prevY[p]) * keep + gy
            val length = sqrt(dx * dx + dy * dy)
            if (length > maxStep) {
                dx *= maxStep / length
                dy *= maxStep / length
            }
            prevX[p] = x[p]
            prevY[p] = y[p]
            x[p] += dx
            y[p] += dy
        }
    }
}
