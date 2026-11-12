package klyph.physics

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** What a joint holds: see [Joints]. */
enum class JointKind {
    /** Two points held together, free to turn about them: a wheel on its axle, a door on its hinge. */
    HINGE,

    /** Free to slide along an axis, and only that: a piston, a lift. */
    SLIDER,

    /** Neither moving nor turning against each other: two bodies made one, until it breaks. */
    WELD,

    /** Two points held a set distance apart, free to turn about each: a stick between them. */
    DISTANCE,
}

/**
 * Joints between rigid bodies, the way machines connect them: [hinge], [slider], [weld] and
 * [distance], solved the XPBD way, as in Müller et al. 2020.
 *
 * A joint's first end is a rigid body. Its second is another body, the world ([WORLD]: a fixed point
 * in it), or a particle ([particle]). Each holds a point of each end, its anchor, where it was when
 * the joint was made. A particle can't turn, so a joint to one holds its position only.
 *
 * Every pass, a joint pulls its anchors where they should be, split by how hard each end is to move
 * there, as for a contact, and turns the two ends to the angle they should have. Its [compliance]
 * says how soft that is: 0 is rigid. A hinge and a slider can have limits ([setLimits]), past which
 * they're pushed back, and a motor ([setMotor]), which drives them at a speed with at most a torque or
 * a force. A weld breaks, and is removed, when the force holding it passes its `breakForce`.
 *
 * Two bodies joined to each other don't collide with each other: a wheel can sit inside its axle box.
 *
 * Each joint is an `Int` handle; a removed one's handle is reused. One whose body or particle has
 * been removed is skipped.
 */
class Joints internal constructor(private val particles: Particles, private val bodies: RigidBodies) {
    private val slots = Slots()
    private var kind = arrayOfNulls<JointKind>(8)
    private var bodyA = IntArray(8)
    private var endB = IntArray(8)

    // Each anchor: on body A in its own frame from its centre of mass; on B the same, or a point in
    // the world for [WORLD]; nothing for a particle.
    private var anchorAX = FloatArray(8)
    private var anchorAY = FloatArray(8)
    private var anchorBX = FloatArray(8)
    private var anchorBY = FloatArray(8)

    // A slider's axis, in A's own frame; the angle B keeps to A (B's angle less A's); a distance's length.
    private var axisX = FloatArray(8)
    private var axisY = FloatArray(8)
    private var restAngle = FloatArray(8)
    private var length = FloatArray(8)
    private var compliance = FloatArray(8)
    private var breakForce = FloatArray(8)

    private var limited = BooleanArray(8)
    private var lower = FloatArray(8)
    private var upper = FloatArray(8)
    private var motorSpeed = FloatArray(8)
    private var motorMax = FloatArray(8)
    private var motorTarget = FloatArray(8)

    // How hard each part has pushed this substep: the anchors together, the angle, the motor.
    private var lambdaPoint = FloatArray(8)
    private var lambdaAngle = FloatArray(8)
    private var lambdaMotor = FloatArray(8)

    /** The force each joint held with over the last substep. */
    private var force = FloatArray(8)

    // How many joints each body has, so pairs with none skip the search in [connected].
    private var onBody = IntArray(8)

    /** One past the highest handle in use: loop `0 until end` and skip what isn't [isAlive]. */
    val end: Int get() = slots.end

    val count: Int get() = slots.count

    /**
     * Holds body [a] and [b] together at the world point ([x], [y]), free to turn about it. [b] is a
     * body, [WORLD] or a [particle].
     */
    fun hinge(a: Int, b: Int, x: Float, y: Float): Int = add(JointKind.HINGE, a, b, x, y, x, y)

    /**
     * Lets body [b] slide against [a] only along the axis ([axisX], [axisY]) through the world point
     * ([x], [y]), as the two lie now, and not turn against it. [b] is a body, [WORLD] or a [particle]:
     * a bead on a wire.
     */
    fun slider(a: Int, b: Int, x: Float, y: Float, axisX: Float, axisY: Float): Int {
        val axis = sqrt(axisX * axisX + axisY * axisY)
        require(axis > 0f) { "the axis ($axisX, $axisY) has no direction" }
        val j = add(JointKind.SLIDER, a, b, x, y, x, y)
        // the axis in A's own frame
        val ux = axisX / axis
        val uy = axisY / axis
        val c = bodies.cos[a]
        val s = bodies.sin[a]
        this.axisX[j] = c * ux + s * uy
        this.axisY[j] = -s * ux + c * uy
        return j
    }

    /**
     * Holds body [a] and [b] together at the world point ([x], [y]), and at the angle they have now, as
     * softly as [compliance]. Above [breakForce] it breaks: it's removed. [b] is a body, [WORLD] or a
     * [particle].
     */
    fun weld(a: Int, b: Int, x: Float, y: Float, compliance: Float = 0f, breakForce: Float = Float.POSITIVE_INFINITY): Int {
        require(breakForce > 0f) { "breakForce is $breakForce: it must be more than 0" }
        val j = add(JointKind.WELD, a, b, x, y, x, y)
        this.compliance[j] = requireCompliance(compliance)
        this.breakForce[j] = breakForce
        return j
    }

    /**
     * Holds the world point ([ax], [ay]) of body [a] and ([bx], [by]) of [b] as far apart as they are
     * now, as softly as [compliance]. [b] is a body, [WORLD] or a [particle].
     */
    fun distance(a: Int, b: Int, ax: Float, ay: Float, bx: Float, by: Float, compliance: Float = 0f): Int {
        val j = add(JointKind.DISTANCE, a, b, ax, ay, bx, by)
        this.compliance[j] = requireCompliance(compliance)
        length[j] = sqrt((bx - ax) * (bx - ax) + (by - ay) * (by - ay))
        return j
    }

    /**
     * Keeps hinge [j]'s angle (how far [b] has turned against [a] since it was made, in radians), or
     * slider [j]'s position (how far [b] has slid along the axis, in units), from [lower] to [upper].
     */
    fun setLimits(j: Int, lower: Float, upper: Float) {
        require(kind[j] == JointKind.HINGE || kind[j] == JointKind.SLIDER) { "only a hinge or a slider has limits" }
        require(lower <= upper) { "lower $lower is above upper $upper" }
        limited[j] = true
        this.lower[j] = lower
        this.upper[j] = upper
    }

    fun clearLimits(j: Int) {
        limited[j] = false
    }

    /**
     * Drives hinge [j] at [speed] radians a second, with at most [maxTorque], or slider [j] at [speed]
     * units a second, with at most [maxTorque] as a force. A [maxTorque] of 0 turns the motor off.
     */
    fun setMotor(j: Int, speed: Float, maxTorque: Float) {
        require(kind[j] == JointKind.HINGE || kind[j] == JointKind.SLIDER) { "only a hinge or a slider has a motor" }
        require(maxTorque >= 0f) { "maxTorque is $maxTorque: it must not be negative" }
        motorSpeed[j] = speed
        motorMax[j] = maxTorque
    }

    fun remove(j: Int) {
        if (!slots.isAlive(j)) return
        slots.remove(j)
        onBody[bodyA[j]]--
        if (endB[j] >= 0) onBody[endB[j]]--
    }

    fun isAlive(j: Int): Boolean = slots.isAlive(j)

    fun kind(j: Int): JointKind = kind[j]!!

    /** Joint [j]'s first end, a body, and its second: a body, [WORLD] or a [particle]. */
    fun bodyA(j: Int): Int = bodyA[j]

    fun endB(j: Int): Int = endB[j]

    /** Where joint [j] holds its first end, in the world now. */
    fun anchorAX(j: Int): Float = bodies.worldX(bodyA[j], anchorAX[j], anchorAY[j])

    fun anchorAY(j: Int): Float = bodies.worldY(bodyA[j], anchorAX[j], anchorAY[j])

    /** Where joint [j] holds its second end, in the world now. */
    fun anchorBX(j: Int): Float {
        val b = endB[j]
        return when {
            b >= 0 -> bodies.worldX(b, anchorBX[j], anchorBY[j])
            b == WORLD -> anchorBX[j]
            else -> particles.x(particleOf(b))
        }
    }

    fun anchorBY(j: Int): Float {
        val b = endB[j]
        return when {
            b >= 0 -> bodies.worldY(b, anchorBX[j], anchorBY[j])
            b == WORLD -> anchorBY[j]
            else -> particles.y(particleOf(b))
        }
    }

    /** How far hinge [j]'s second end has turned against its first since it was made, in radians. */
    fun angle(j: Int): Float = angleOfB(endB[j]) - bodies.angle[bodyA[j]] - restAngle[j]

    /** How far slider [j]'s second end has slid along its axis since it was made, in units. */
    fun position(j: Int): Float {
        val a = bodyA[j]
        val ux = bodies.cos[a] * axisX[j] - bodies.sin[a] * axisY[j]
        val uy = bodies.sin[a] * axisX[j] + bodies.cos[a] * axisY[j]
        return (anchorBX(j) - anchorAX(j)) * ux + (anchorBY(j) - anchorAY(j)) * uy
    }

    /** Whether hinge or slider [j] has limits, and what they are: see [setLimits]. */
    fun hasLimits(j: Int): Boolean = limited[j]

    fun lowerLimit(j: Int): Float = lower[j]

    fun upperLimit(j: Int): Float = upper[j]

    /** Whether hinge or slider [j] has a motor on, and how fast it drives: see [setMotor]. */
    fun hasMotor(j: Int): Boolean = motorMax[j] > 0f

    fun motorSpeed(j: Int): Float = motorSpeed[j]

    /** Slider [j]'s axis in the world now: it turns with its first end. */
    fun axisX(j: Int): Float = bodies.cos[bodyA[j]] * axisX[j] - bodies.sin[bodyA[j]] * axisY[j]

    fun axisY(j: Int): Float = bodies.sin[bodyA[j]] * axisX[j] + bodies.cos[bodyA[j]] * axisY[j]

    /** The angle hinge [j]'s two ends had when it was made: its second end's less its first's. */
    fun restAngle(j: Int): Float = restAngle[j]

    /** The force joint [j] held its anchors together with over the last substep. */
    fun force(j: Int): Float = force[j]

    private fun add(k: JointKind, a: Int, b: Int, ax: Float, ay: Float, bx: Float, by: Float): Int {
        require(bodies.isAlive(a)) { "body $a is not alive" }
        require(b != a) { "a joint needs two different ends" }
        when {
            b >= 0 -> require(bodies.isAlive(b)) { "body $b is not alive" }
            b == WORLD -> {}
            else -> require(particles.isAlive(particleOf(b))) { "particle ${particleOf(b)} is not alive" }
        }
        val j = slots.take()
        if (j >= bodyA.size) grow()
        if (max(a, b) >= onBody.size) onBody = onBody.copyOf(max(max(a, b) + 1, 2 * onBody.size))
        kind[j] = k
        bodyA[j] = a
        endB[j] = b
        toLocal(a, ax, ay)
        anchorAX[j] = localX
        anchorAY[j] = localY
        if (b >= 0) {
            toLocal(b, bx, by)
            anchorBX[j] = localX
            anchorBY[j] = localY
        } else {
            anchorBX[j] = bx
            anchorBY[j] = by
        }
        restAngle[j] = angleOfB(b) - bodies.angle[a]
        axisX[j] = 1f
        axisY[j] = 0f
        length[j] = 0f
        compliance[j] = 0f
        breakForce[j] = Float.POSITIVE_INFINITY
        limited[j] = false
        motorMax[j] = 0f
        motorSpeed[j] = 0f
        force[j] = 0f
        onBody[a]++
        if (b >= 0) onBody[b]++
        return j
    }

    /** Whether bodies [p] and [q] are joined to each other, so their contacts are skipped. */
    internal fun connected(p: Int, q: Int): Boolean {
        if (p >= onBody.size || q >= onBody.size || onBody[p] == 0 || onBody[q] == 0) return false
        for (j in 0 until slots.end) {
            if (!slots.isAlive(j)) continue
            val a = bodyA[j]
            val b = endB[j]
            if (a == p && b == q || a == q && b == p) return true
        }
        return false
    }

    /** Forgets how hard each has pushed, and aims each motor a substep of [h] seconds further on: the start of a substep. */
    internal fun beginSubstep(h: Float) {
        for (j in 0 until slots.end) {
            if (!slots.isAlive(j)) continue
            lambdaPoint[j] = 0f
            lambdaAngle[j] = 0f
            lambdaMotor[j] = 0f
            if (motorMax[j] > 0f) motorTarget[j] = (if (kind[j] == JointKind.HINGE) angleBefore(j) else positionBefore(j)) + motorSpeed[j] * h
        }
    }

    /** One pass over every joint, for a substep of [h] seconds. */
    internal fun solve(h: Float) {
        val hh = h * h
        for (j in 0 until slots.end) {
            if (!slots.isAlive(j) || !endsAlive(j)) continue
            when (kind[j]!!) {
                JointKind.HINGE -> {
                    if (motorMax[j] > 0f) driveAngle(j, hh)
                    holdPoints(j, 0f, hh)
                    if (limited[j]) limitAngle(j)
                }
                JointKind.SLIDER -> {
                    if (motorMax[j] > 0f) driveSlide(j, hh)
                    holdOnAxis(j)
                    if (limited[j]) limitSlide(j)
                    holdAngle(j, 0f, hh)
                }
                JointKind.WELD -> {
                    holdPoints(j, compliance[j], hh)
                    holdAngle(j, compliance[j], hh)
                }
                JointKind.DISTANCE -> holdDistance(j, compliance[j], hh)
            }
        }
    }

    /** Ends a substep of [h] seconds: each joint's force is how hard it pulled, and a weld pulled too hard breaks. */
    internal fun endSubstep(h: Float) {
        val hh = h * h
        for (j in 0 until slots.end) {
            if (!slots.isAlive(j)) continue
            force[j] = abs(lambdaPoint[j]) / hh
            if (force[j] > breakForce[j]) remove(j)
        }
    }

    // Body A's anchor from its centre, and the anchors in the world, as [measure] found them.
    private var rax = 0f
    private var ray = 0f
    private var rbx = 0f
    private var rby = 0f
    private var pax = 0f
    private var pay = 0f
    private var pbx = 0f
    private var pby = 0f

    /** Where joint [j]'s anchors are now, into the r- and p- fields. */
    private fun measure(j: Int) {
        val a = bodyA[j]
        rax = bodies.cos[a] * anchorAX[j] - bodies.sin[a] * anchorAY[j]
        ray = bodies.sin[a] * anchorAX[j] + bodies.cos[a] * anchorAY[j]
        pax = bodies.x[a] + rax
        pay = bodies.y[a] + ray
        val b = endB[j]
        when {
            b >= 0 -> {
                rbx = bodies.cos[b] * anchorBX[j] - bodies.sin[b] * anchorBY[j]
                rby = bodies.sin[b] * anchorBX[j] + bodies.cos[b] * anchorBY[j]
                pbx = bodies.x[b] + rbx
                pby = bodies.y[b] + rby
            }
            b == WORLD -> {
                rbx = 0f
                rby = 0f
                pbx = anchorBX[j]
                pby = anchorBY[j]
            }
            else -> {
                val p = particleOf(b)
                rbx = 0f
                rby = 0f
                pbx = particles.x[p]
                pby = particles.y[p]
            }
        }
    }

    /**
     * Moves joint [j]'s anchors along the unit ([nx], [ny]) to take [error] off how far B's is past A's
     * that way, as softly as [softness] (compliance over h²), with [lambda] pushed so far; returns the
     * push, to add to it. The anchors must have been [measure]d.
     */
    private fun pull(j: Int, nx: Float, ny: Float, error: Float, softness: Float, lambda: Float): Float {
        val a = bodyA[j]
        val w = bodies.inverseMassAt(a, rax, ray, nx, ny) + inverseMassOfB(endB[j], nx, ny) + softness
        if (w == 0f) return 0f
        val change = (-error - softness * lambda) / w
        bodies.shove(a, -change * nx, -change * ny, rax, ray)
        moveB(endB[j], change * nx, change * ny)
        return change
    }

    /** Pulls joint [j]'s anchors onto each other. */
    private fun holdPoints(j: Int, compliance: Float, hh: Float) {
        measure(j)
        val dx = pbx - pax
        val dy = pby - pay
        val apart = sqrt(dx * dx + dy * dy)
        if (apart < TINY) return
        lambdaPoint[j] += pull(j, dx / apart, dy / apart, apart, compliance / hh, lambdaPoint[j])
    }

    /** Pulls joint [j]'s anchors to [length] apart. */
    private fun holdDistance(j: Int, compliance: Float, hh: Float) {
        measure(j)
        val dx = pbx - pax
        val dy = pby - pay
        val apart = sqrt(dx * dx + dy * dy)
        if (apart < TINY) return
        lambdaPoint[j] += pull(j, dx / apart, dy / apart, apart - length[j], compliance / hh, lambdaPoint[j])
    }

    /** Pulls slider [j]'s second anchor back onto the axis through its first. */
    private fun holdOnAxis(j: Int) {
        measure(j)
        val a = bodyA[j]
        val ux = bodies.cos[a] * axisX[j] - bodies.sin[a] * axisY[j]
        val uy = bodies.sin[a] * axisX[j] + bodies.cos[a] * axisY[j]
        // across the axis
        val vx = -uy
        val vy = ux
        val off = (pbx - pax) * vx + (pby - pay) * vy
        lambdaPoint[j] += pull(j, vx, vy, off, 0f, 0f)
    }

    /** Pushes slider [j] back inside its limits along its axis. */
    private fun limitSlide(j: Int) {
        measure(j)
        val a = bodyA[j]
        val ux = bodies.cos[a] * axisX[j] - bodies.sin[a] * axisY[j]
        val uy = bodies.sin[a] * axisX[j] + bodies.cos[a] * axisY[j]
        val along = (pbx - pax) * ux + (pby - pay) * uy
        val past = if (along < lower[j]) along - lower[j] else if (along > upper[j]) along - upper[j] else return
        pull(j, ux, uy, past, 0f, 0f)
    }

    /** Drives slider [j] towards this substep's [motorTarget], with no more than its force. */
    private fun driveSlide(j: Int, hh: Float) {
        measure(j)
        val a = bodyA[j]
        val ux = bodies.cos[a] * axisX[j] - bodies.sin[a] * axisY[j]
        val uy = bodies.sin[a] * axisX[j] + bodies.cos[a] * axisY[j]
        val along = (pbx - pax) * ux + (pby - pay) * uy
        val w = bodies.inverseMassAt(a, rax, ray, ux, uy) + inverseMassOfB(endB[j], ux, uy)
        if (w == 0f) return
        val most = motorMax[j] * hh
        val total = (lambdaMotor[j] - (along - motorTarget[j]) / w).coerceIn(-most, most)
        val change = total - lambdaMotor[j]
        lambdaMotor[j] = total
        bodies.shove(a, -change * ux, -change * uy, rax, ray)
        moveB(endB[j], change * ux, change * uy)
    }

    /** Turns joint [j]'s two ends to [restAngle] apart, as softly as [compliance]. A particle has no angle to keep. */
    private fun holdAngle(j: Int, compliance: Float, hh: Float) {
        if (endB[j] < WORLD) return
        val softness = compliance / hh
        lambdaAngle[j] += turn(j, angle(j), softness, lambdaAngle[j])
    }

    /** Turns hinge [j] back inside its limits. */
    private fun limitAngle(j: Int) {
        if (endB[j] < WORLD) return
        val now = angle(j)
        val past = if (now < lower[j]) now - lower[j] else if (now > upper[j]) now - upper[j] else return
        turn(j, past, 0f, 0f)
    }

    /** Drives hinge [j] towards this substep's [motorTarget], with no more than its torque. */
    private fun driveAngle(j: Int, hh: Float) {
        if (endB[j] < WORLD) return
        val w = turnability(j)
        if (w == 0f) return
        val most = motorMax[j] * hh
        val total = (lambdaMotor[j] - (angle(j) - motorTarget[j]) / w).coerceIn(-most, most)
        val change = total - lambdaMotor[j]
        lambdaMotor[j] = total
        twist(j, change)
    }

    /** Turns joint [j]'s ends against each other to take [error] off B's angle less A's; returns the push. */
    private fun turn(j: Int, error: Float, softness: Float, lambda: Float): Float {
        val w = turnability(j) + softness
        if (w == 0f) return 0f
        val change = (-error - softness * lambda) / w
        twist(j, change)
        return change
    }

    /** How easily joint [j]'s two ends turn against each other: the sum of their inverse inertias. */
    private fun turnability(j: Int): Float {
        val b = endB[j]
        return bodies.invInertia[bodyA[j]] + (if (b >= 0) bodies.invInertia[b] else 0f)
    }

    /** Turns B by [amount] over its inertia, and A back the other way. */
    private fun twist(j: Int, amount: Float) {
        bodies.twist(bodyA[j], -amount)
        val b = endB[j]
        if (b >= 0) bodies.twist(b, amount)
    }

    /** How hard end [b] is to move at its anchor (the r- fields) along ([nx], [ny]). */
    private fun inverseMassOfB(b: Int, nx: Float, ny: Float): Float = when {
        b >= 0 -> bodies.inverseMassAt(b, rbx, rby, nx, ny)
        b == WORLD -> 0f
        else -> particles.invMass[particleOf(b)]
    }

    /** Pushes end [b] by ([px], [py]) at its anchor. */
    private fun moveB(b: Int, px: Float, py: Float) {
        when {
            b >= 0 -> bodies.shove(b, px, py, rbx, rby)
            b == WORLD -> {}
            else -> {
                val p = particleOf(b)
                val w = particles.invMass[p]
                particles.x[p] += w * px
                particles.y[p] += w * py
            }
        }
    }

    /** Hinge [j]'s angle at the start of the substep. */
    private fun angleBefore(j: Int): Float {
        val b = endB[j]
        val before = if (b >= 0) bodies.prevAngle[b] else 0f
        return before - bodies.prevAngle[bodyA[j]] - restAngle[j]
    }

    /** Slider [j]'s position at the start of the substep. */
    private fun positionBefore(j: Int): Float {
        val a = bodyA[j]
        val ca = cos(bodies.prevAngle[a])
        val sa = sin(bodies.prevAngle[a])
        val ax = bodies.prevX[a] + ca * anchorAX[j] - sa * anchorAY[j]
        val ay = bodies.prevY[a] + sa * anchorAX[j] + ca * anchorAY[j]
        val b = endB[j]
        val bx: Float
        val by: Float
        when {
            b >= 0 -> {
                val cb = cos(bodies.prevAngle[b])
                val sb = sin(bodies.prevAngle[b])
                bx = bodies.prevX[b] + cb * anchorBX[j] - sb * anchorBY[j]
                by = bodies.prevY[b] + sb * anchorBX[j] + cb * anchorBY[j]
            }
            b == WORLD -> {
                bx = anchorBX[j]
                by = anchorBY[j]
            }
            else -> {
                val p = particleOf(b)
                bx = particles.prevX[p]
                by = particles.prevY[p]
            }
        }
        val ux = ca * axisX[j] - sa * axisY[j]
        val uy = sa * axisX[j] + ca * axisY[j]
        return (bx - ax) * ux + (by - ay) * uy
    }

    private fun endsAlive(j: Int): Boolean {
        if (!bodies.isAlive(bodyA[j])) return false
        val b = endB[j]
        return when {
            b >= 0 -> bodies.isAlive(b)
            b == WORLD -> true
            else -> particles.isAlive(particleOf(b))
        }
    }

    private fun angleOfB(b: Int): Float = if (b >= 0) bodies.angle[b] else 0f

    // A world point in a body's own frame, from [toLocal].
    private var localX = 0f
    private var localY = 0f

    private fun toLocal(b: Int, x: Float, y: Float) {
        val dx = x - bodies.x[b]
        val dy = y - bodies.y[b]
        val c = bodies.cos[b]
        val s = bodies.sin[b]
        localX = c * dx + s * dy
        localY = -s * dx + c * dy
    }

    private fun requireCompliance(compliance: Float): Float {
        require(compliance >= 0f) { "compliance is $compliance: it must not be negative" }
        return compliance
    }

    private fun grow() {
        val size = bodyA.size * 2
        kind = kind.copyOf(size)
        bodyA = bodyA.copyOf(size)
        endB = endB.copyOf(size)
        anchorAX = anchorAX.copyOf(size)
        anchorAY = anchorAY.copyOf(size)
        anchorBX = anchorBX.copyOf(size)
        anchorBY = anchorBY.copyOf(size)
        axisX = axisX.copyOf(size)
        axisY = axisY.copyOf(size)
        restAngle = restAngle.copyOf(size)
        length = length.copyOf(size)
        compliance = compliance.copyOf(size)
        breakForce = breakForce.copyOf(size)
        limited = limited.copyOf(size)
        lower = lower.copyOf(size)
        upper = upper.copyOf(size)
        motorSpeed = motorSpeed.copyOf(size)
        motorMax = motorMax.copyOf(size)
        motorTarget = motorTarget.copyOf(size)
        lambdaPoint = lambdaPoint.copyOf(size)
        lambdaAngle = lambdaAngle.copyOf(size)
        lambdaMotor = lambdaMotor.copyOf(size)
        force = force.copyOf(size)
    }

    companion object {
        /** A joint's second end that is a fixed point in the world. */
        const val WORLD = -1

        /** A joint's second end that is particle [p]. */
        fun particle(p: Int): Int = -2 - p

        private fun particleOf(end: Int): Int = -2 - end

        private const val TINY = 1e-6f
    }
}
