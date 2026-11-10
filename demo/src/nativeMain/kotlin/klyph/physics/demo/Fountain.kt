package klyph.physics.demo

import klyph.math.Rect
import klyph.physics.PhysicsWorld
import klyph.physics.bridge.DebugDraw
import klyph.platform.Color
import klyph.render.Canvas
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * A fountain of loose particles in a [PhysicsWorld]: a few hundred alive at once, each one added at
 * the nozzle and removed when it falls out of sight, so the world's handles are reused all the time.
 * Nothing collides yet; this is the world and its step on their own.
 */
object FountainScene : Scene {
    override val name = "Fountain"
    override val blurb = "one world, one step. particles in, particles out, handles reused"

    /** Particles added each step. */
    const val PER_STEP = 6
    private const val SPEED_MIN = 650f
    private const val SPEED_MAX = 1000f

    /** How far either side of straight up a particle may leave the nozzle, in radians. */
    private const val SPREAD = 0.3f

    private val SLOW = Color(0x5B8DEFFFu)
    private val FAST = Color(0xF2F6FFFFu)

    override fun create(): SceneRun = Run()

    internal class Run : SceneRun {
        val world = PhysicsWorld()
        val random = Random(7)
        val nozzleX = Scenes.COLS * Scenes.TILE / 2f
        val nozzleY = Scenes.ROWS * Scenes.TILE - 8f
        val bottom = Scenes.ROWS * Scenes.TILE + 8f

        override val particleCount: Int get() = world.particles.count

        override fun step(dt: Float) {
            val ps = world.particles
            for (p in 0 until ps.end) if (ps.isAlive(p) && ps.y(p) > bottom) ps.remove(p)
            repeat(PER_STEP) {
                val angle = -PI.toFloat() / 2f + (random.nextFloat() * 2f - 1f) * SPREAD
                val speed = SPEED_MIN + random.nextFloat() * (SPEED_MAX - SPEED_MIN)
                val p = ps.add(nozzleX + random.nextFloat() * 4f - 2f, nozzleY, radius = 1f)
                world.setVelocity(p, cos(angle) * speed, sin(angle) * speed)
            }
            world.step(dt)
        }

        override fun draw(canvas: Canvas, debug: DebugDraw, view: Rect) {
            debug.particles(canvas, world.particles) { p ->
                val speed = world.velocityX(p) * world.velocityX(p) + world.velocityY(p) * world.velocityY(p)
                lerp(SLOW, FAST, min(1f, speed / (SPEED_MAX * SPEED_MAX)))
            }
            debug.square(canvas, nozzleX, nozzleY + 4f, 10f, NOZZLE)
        }
    }

    private val NOZZLE = Color(0x3A4A66FFu)

    private fun lerp(a: Color, b: Color, t: Float) =
        Color(a.red + (b.red - a.red) * t, a.green + (b.green - a.green) * t, a.blue + (b.blue - a.blue) * t, 1f)
}
