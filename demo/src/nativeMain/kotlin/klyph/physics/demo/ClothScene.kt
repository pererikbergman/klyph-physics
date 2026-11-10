package klyph.physics.demo

import klyph.math.Rect
import klyph.physics.Cloth
import klyph.physics.PhysicsWorld
import klyph.physics.bridge.DebugDraw
import klyph.physics.cloth
import klyph.platform.Color
import klyph.render.Canvas
import klyph.render.DrawSpace
import kotlin.math.sin

/**
 * Cloth. A flag on a pole flaps in gusts of wind; a curtain hangs in the middle; and a heavy ball drops
 * onto a net held at its sides and tears through it. Every few seconds the net is hung back up whole.
 */
object ClothScene : Scene {
    override val name = "Cloth"
    override val blurb = "a flag in the wind, a curtain, and a net that tears"

    /** Steps between hanging the torn sheet back up. */
    const val EVERY = 480

    private const val SPACING = 7f
    private const val BALL_MASS = 400f
    private const val BALL_RADIUS = 10f
    private const val NET_COLS = 18

    private val FLAG = Color(0xF27BB6FFu)
    private val CURTAIN = Color(0x5BC0EBFFu)
    private val SHEET = Color(0xF2D45CFFu)
    private val BALL = Color(0xF2994AFFu)
    private val POLE = Color(0x8FA8C8FFu)

    override fun create(): SceneRun = Run()

    internal class Run : SceneRun {
        override var world = PhysicsWorld().apply { solids = Scenes.room() }
            private set
        lateinit var flag: Cloth
            private set
        lateinit var curtain: Cloth
            private set
        lateinit var sheet: Cloth
            private set
        var ball = 0
            private set

        /** Balls dropped with a right click, gone when the scene is hung back up. */
        private val spawned = ArrayList<Int>()
        private var steps = 0
        private var seconds = 0f

        init {
            build()
        }

        /** Makes everything afresh: a world with the flag, the curtain, the whole sheet and the ball up high. */
        private fun build() {
            world = PhysicsWorld().apply { solids = Scenes.room() }
            spawned.clear()
            val w = world
            // a flag held along its left edge, on a pole
            flag = w.cloth(56f, 100f, cols = 16, rows = 10, spacing = SPACING, bendCompliance = 0.001f, pinned = { col, _ -> col == 0 })
            // a curtain held along its top
            curtain = w.cloth(230f, 100f, cols = 14, rows = 18, spacing = SPACING, radius = 2f, collides = true)
            // a net held at its two sides, and a heavy ball above it: it lands on the net's top edge, drags
            // it down into a V, and tears through
            sheet = w.cloth(
                440f, 180f, cols = NET_COLS, rows = 8, spacing = SPACING, tearAt = 1.35f, radius = 2f, collides = true,
                pinned = { col, _ -> col == 0 || col == NET_COLS - 1 },
            )
            ball = w.particles.add(440f + (NET_COLS - 1) * SPACING / 2f, 40f, mass = BALL_MASS, radius = BALL_RADIUS, collides = true)
        }

        override fun step(dt: Float) {
            if (steps > 0 && steps % EVERY == 0) build()
            steps++
            seconds += dt
            // gusts: a steady breeze to the right that swells and drops, plus a flutter that runs along the flag
            val gust = 1200f + 700f * sin(seconds * 1.3f) * sin(seconds * 0.47f)
            for (p in flag.particles) {
                if (world.particles.inverseMass(p) == 0f) continue
                val flutter = 400f * sin(seconds * 9f - world.particles.x(p) * 0.08f)
                world.setVelocity(p, world.velocityX(p) + gust * dt * 4f, world.velocityY(p) + flutter * dt * 4f)
            }
            world.step(dt)
        }

        override fun spawn(x: Float, y: Float) {
            if (spawned.size >= Scenes.MAX_SPAWNED || !Scenes.isOpen(world, x, y)) return
            spawned += world.particles.add(x, y, mass = BALL_MASS, radius = BALL_RADIUS, collides = true)
        }

        override fun draw(canvas: Canvas, debug: DebugDraw, view: Rect) {
            debug.solids(canvas, world.solids!!, view)
            val ps = world.particles
            canvas.drawRect(52f, 96f, 3f, 240f, POLE, debug.layer, DrawSpace.WORLD)
            for ((cloth, color) in listOf(flag to FLAG, curtain to CURTAIN, sheet to SHEET)) mesh(canvas, debug, cloth, color)
            debug.square(canvas, ps.x(ball), ps.y(ball), 2f * BALL_RADIUS, BALL)
            for (b in spawned) debug.square(canvas, ps.x(b), ps.y(b), 2f * BALL_RADIUS, BALL)
        }

        /** The cloth's rows and columns that are still there, in its colour; the shear links aren't drawn. */
        private fun mesh(canvas: Canvas, debug: DebugDraw, cloth: Cloth, color: Color) {
            val ps = world.particles
            val cs = world.constraints
            val structural = (cloth.cols - 1) * cloth.rows + cloth.cols * (cloth.rows - 1)
            for (k in 0 until structural) {
                val link = cloth.links[k]
                if (!cs.isLinkAlive(link)) continue
                val a = cs.linkA(link)
                val b = cs.linkB(link)
                debug.line(canvas, ps.x(a), ps.y(a), ps.x(b), ps.y(b), 1f, color, debug.layer)
            }
        }

        override val particleCount get() = world.particles.count
        override val bodyCount get() = 4
    }
}
