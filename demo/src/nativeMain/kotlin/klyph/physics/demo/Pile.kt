package klyph.physics.demo

import klyph.math.Rect
import klyph.physics.PhysicsWorld
import klyph.physics.SoftBodies
import klyph.physics.SoftBodyShape
import klyph.physics.SoftBodyTuning
import klyph.physics.bridge.DebugDraw
import klyph.platform.Color
import klyph.render.Canvas
import kotlin.random.Random

/**
 * Twenty soft bodies, domes, balls and boxes, dropped one after another into a bin, where they pile up
 * without passing through each other. With details on, the yellow marks are particles standing on
 * something, a floor or another body.
 */
object PileScene : Scene {
    override val name = "Blob Pile"
    override val blurb = "twenty bodies in a bin. none of them goes through another"

    /** How many bodies fall, and how many steps apart. */
    const val COUNT = 20
    const val EVERY = 12

    private val COLORS = listOf(
        Color(0xF27BB6FFu), Color(0xF2994AFFu), Color(0x8FA8C8FFu), Color(0xA678E0FFu), Color(0xB8E05CFFu), Color(0x5BC0EBFFu),
    )

    private val SHAPES: List<SoftBodyShape> = listOf(
        SoftBodies.semiDisc(radius = 13.3f, arcSegments = 14, baseSegments = 8, coreMass = 34f, heightScale = 1.3f),
        SoftBodies.circle(radius = 12f, segments = 18, coreMass = 10f),
        SoftBodies.box(width = 26f, height = 18f, across = 7, down = 5, coreMass = 20f),
    )

    override fun create(): SceneRun = Run()

    internal class Run : SceneRun, BlobRun {
        private val t = Scenes.TILE
        override val world = PhysicsWorld().apply {
            solids = Scenes.room().apply {
                // A bin 14 tiles wide in the middle of the room.
                for (r in 9..20) {
                    fill(12, r)
                    fill(27, r)
                }
            }
        }
        private val bodies = world.softBodies
        private val random = Random(11)
        private val colors = ArrayList<Color>()
        private var steps = 0

        override val blobs: IntArray get() = IntArray(bodies.count) { it }

        override fun step(dt: Float) {
            if (steps % EVERY == 0 && bodies.count < COUNT) {
                val i = bodies.count
                val x = (14 + random.nextFloat() * 12) * t
                bodies.add(SHAPES[i % SHAPES.size], x, 4 * t, SoftBodyTuning(spokeCompliance = 0.001f, areaCompliance = 0.0006f))
                colors += COLORS[i % COLORS.size]
            }
            steps++
            world.step(dt)
        }

        override fun draw(canvas: Canvas, debug: DebugDraw, view: Rect) {
            debug.solids(canvas, world.solids!!, view)
            for (body in 0 until bodies.end) debug.softBody(canvas, world, body, colors[body])
            debug.contacts(canvas, world)
        }

        override val particleCount get() = world.particles.count
        override val bodyCount get() = bodies.count
        override val contactCount get() = (0 until bodies.end).sumOf { bodies.floorContactCount(it) }
    }
}
