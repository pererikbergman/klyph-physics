package klyph.physics.demo

import klyph.math.Rect
import klyph.physics.Particles
import klyph.physics.PhysicsWorld
import klyph.physics.bridge.DebugDraw
import klyph.platform.Color
import klyph.render.Canvas
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Every kind of constraint, one station each: a rigid rod, a soft spring, a rope that goes slack, a
 * soft pin chasing a moving point, and a water balloon held round by its area.
 */
object SpringsScene : Scene {
    override val name = "Springs"
    override val blurb = "rod, spring, rope, pin, area. same solver, different compliance"

    private const val TOP = 70f
    private const val SPRING_COMPLIANCE = 0.02f
    private const val PIN_COMPLIANCE = 0.002f
    private const val BALLOON_RADIUS = 34f
    private const val BALLOON_PARTICLES = 20
    private const val BALLOON_AREA_COMPLIANCE = 0.0002f

    private val WEIGHT = Color(0xF2994AFFu)
    private val ANCHOR = Color(0x3A4A66FFu)
    private val TARGET = Color(0xFF6B6BFFu)
    private val BALLOON = Color(0x5BC0EBFFu)

    override fun create(): SceneRun = Run()

    internal class Run : SceneRun {
        val world = PhysicsWorld()
        private val ps = world.particles
        private val cs = world.constraints
        private val anchors = IntArray(3)
        private val weights = IntArray(4)
        private val chasePin: Int
        private val balloon = IntArray(BALLOON_PARTICLES)
        private var seconds = 0f
        private val pinCenterX = 448f

        init {
            // 1. a rigid rod, starting level so it swings.
            anchors[0] = ps.add(64f, TOP, mass = Particles.STATIC)
            weights[0] = ps.add(124f, TOP, mass = 2f, radius = 4f)
            cs.distance(anchors[0], weights[0])
            // 2. a soft spring: it sags by mass x gravity x compliance and bounces about that.
            anchors[1] = ps.add(192f, TOP, mass = Particles.STATIC)
            weights[1] = ps.add(192f, TOP + 60f, mass = 2f, radius = 4f)
            cs.distance(anchors[1], weights[1], compliance = SPRING_COMPLIANCE)
            // 3. a rope with its weight thrown up and sideways: it goes slack, then snaps taut.
            anchors[2] = ps.add(300f, TOP, mass = Particles.STATIC)
            weights[2] = ps.add(300f, TOP + 80f, mass = 2f, radius = 4f)
            cs.rope(anchors[2], weights[2])
            world.setVelocity(weights[2], 220f, -900f)
            // 4. a soft pin: no anchor, just a point that moves in a circle and a weight that chases it.
            weights[3] = ps.add(pinCenterX, TOP + 70f, mass = 2f, radius = 4f)
            chasePin = cs.pin(weights[3], compliance = PIN_COMPLIANCE)
            // 5. a water balloon: a ring of stiff edges, held round by its area, hung from its top.
            for (k in 0 until BALLOON_PARTICLES) {
                val angle = -PI.toFloat() / 2f + 2f * PI.toFloat() * k / BALLOON_PARTICLES
                balloon[k] = ps.add(564f + cos(angle) * BALLOON_RADIUS, TOP + BALLOON_RADIUS + sin(angle) * BALLOON_RADIUS, mass = 0.2f)
            }
            for (k in 0 until BALLOON_PARTICLES) cs.distance(balloon[k], balloon[(k + 1) % BALLOON_PARTICLES])
            cs.area(balloon, compliance = BALLOON_AREA_COMPLIANCE)
            cs.pin(balloon[0])
        }

        override val particleCount: Int get() = ps.count
        override val bodyCount: Int get() = 5

        override fun step(dt: Float) {
            seconds += dt
            val angle = seconds * 2f
            cs.movePin(chasePin, pinCenterX + cos(angle) * 40f, TOP + 70f + sin(angle) * 40f)
            world.step(dt)
        }

        override fun draw(canvas: Canvas, debug: DebugDraw, view: Rect) {
            debug.links(canvas, cs, ps)
            for (k in 0 until BALLOON_PARTICLES) debug.square(canvas, ps.x(balloon[k]), ps.y(balloon[k]), 2f, BALLOON)
            for (a in anchors) debug.square(canvas, ps.x(a), ps.y(a), 8f, ANCHOR)
            for (w in weights) debug.square(canvas, ps.x(w), ps.y(w), 8f, WEIGHT)
            debug.square(canvas, pinCenterX + cos(seconds * 2f) * 40f, TOP + 70f + sin(seconds * 2f) * 40f, 4f, TARGET)
            debug.label(canvas, "rod", 64f, TOP - 18f)
            debug.label(canvas, "spring", 192f, TOP - 18f)
            debug.label(canvas, "rope", 300f, TOP - 18f)
            debug.label(canvas, "soft pin", pinCenterX, TOP - 18f)
            debug.label(canvas, "area", 564f, TOP - 18f)
        }
    }
}
