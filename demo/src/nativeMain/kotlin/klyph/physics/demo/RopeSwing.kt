package klyph.physics.demo

import klyph.math.Rect
import klyph.physics.PhysicsWorld
import klyph.physics.Strand
import klyph.physics.bridge.DebugDraw
import klyph.physics.chain
import klyph.physics.rope
import klyph.platform.Color
import klyph.render.Canvas

/**
 * Ropes and chains. A rope and a chain, each with a weight, swing from where they're held level; a
 * rope with stiff bends sticks out; and a chain bridge sags across a gap with a blob dropped on it.
 */
object RopeSwingScene : Scene {
    override val name = "Rope Swing"
    override val blurb = "ropes go slack, chains swing smooth, a bridge sags under a blob"

    private const val TOP = 110f
    private const val WEIGHT_MASS = 12f
    private const val WEIGHT_RADIUS = 6f

    /** The bridge: its ends on two ledges, and longer than the gap so it sags. */
    private const val BRIDGE_SEGMENTS = 30
    private const val BRIDGE_ROW = 14

    private val WEIGHT = Color(0xF2994AFFu)
    private val ANCHOR = Color(0x3A4A66FFu)
    private val ROPE_DOT = Color(0xC9A26BFFu)
    private val CHAIN_DOT = Color(0xB8C4D9FFu)
    private val BLOB = Color(0xA678E0FFu)

    override fun create(): SceneRun = Run()

    internal class Run : SceneRun, BlobRun {
        private val t = Scenes.TILE
        override val world = PhysicsWorld().apply {
            solids = Scenes.room().apply {
                // two ledges for the bridge
                for (r in BRIDGE_ROW until Scenes.ROWS - 2) {
                    for (c in 24..26) fill(c, r)
                    for (c in 36..38) fill(c, r)
                }
            }
        }
        private val ps = world.particles
        private val cs = world.constraints

        /** A rope and a chain held level at their left end, each with a weight at the other. */
        val rope: Strand = world.rope(40f, TOP, 160f, TOP, segments = 15)
        val chain: Strand = world.chain(190f, TOP, 290f, TOP, segments = 14)
        val weights = IntArray(2)

        /** A rope that holds its shape: stiff bends at every particle, and clamped by its first two. */
        val stiff: Strand = world.rope(310f, TOP, 380f, TOP, segments = 10, bendCompliance = 0f)
            .also { world.constraints.pin(it.particles[1]) }

        /**
         * Built 1.25 times as long as the gap, then its far end moved onto the far ledge, so it sags. Its
         * particles are round and exactly touching, so the line has no gaps for a blob to slip through.
         */
        val bridge: Strand

        override val blobs: IntArray

        /** Blobs dropped with a right click. */
        private val spawned = ArrayList<Int>()

        init {
            weights[0] = ps.add(160f + 8f, TOP, mass = WEIGHT_MASS, radius = WEIGHT_RADIUS)
            cs.distance(rope.last, weights[0])
            weights[1] = ps.add(290f + 8f, TOP, mass = WEIGHT_MASS, radius = WEIGHT_RADIUS)
            cs.distance(chain.last, weights[1])

            val left = 27 * t
            val right = 36 * t
            val y = BRIDGE_ROW * t
            val length = (right - left) * 1.25f
            bridge = world.chain(left, y, left + length, y, segments = BRIDGE_SEGMENTS, pinEnd = true, radius = length / BRIDGE_SEGMENTS / 2f, collides = true)
            ps.moveTo(bridge.last, right, y)

            blobs = intArrayOf(world.softBody(Scenes.dome(), (left + right) / 2f, (BRIDGE_ROW - 3) * t))
        }

        override fun step(dt: Float) = world.step(dt)

        override fun spawn(x: Float, y: Float) {
            if (spawned.size >= Scenes.MAX_SPAWNED || !Scenes.isOpen(world, x, y)) return
            spawned += world.softBody(Scenes.dome(), x, y + Scenes.DOME_HALF_HEIGHT)
        }

        override fun draw(canvas: Canvas, debug: DebugDraw, view: Rect) {
            debug.solids(canvas, world.solids!!, view)
            debug.links(canvas, cs, ps)
            for (strand in listOf(rope, stiff)) dots(canvas, debug, strand, ROPE_DOT)
            for (strand in listOf(chain, bridge)) dots(canvas, debug, strand, CHAIN_DOT)
            for (w in weights) debug.square(canvas, ps.x(w), ps.y(w), 2f * WEIGHT_RADIUS, WEIGHT)
            debug.softBody(canvas, world, blobs[0], BLOB)
            for (blob in spawned) debug.softBody(canvas, world, blob, BLOB)
            debug.label(canvas, "rope", 100f, TOP - 22f)
            debug.label(canvas, "chain", 240f, TOP - 22f)
            debug.label(canvas, "stiff rope", 345f, TOP - 22f)
            debug.label(canvas, "bridge", 31.5f * t, (BRIDGE_ROW - 5) * t)
        }

        private fun dots(canvas: Canvas, debug: DebugDraw, strand: Strand, color: Color) {
            for (p in strand.particles) {
                val pinned = ps.inverseMass(p) == 0f
                debug.square(canvas, ps.x(p), ps.y(p), if (pinned) 6f else maxOf(2f, ps.radius(p) * 2f), if (pinned) ANCHOR else color)
            }
        }

        override val particleCount get() = ps.count
        override val bodyCount get() = world.softBodies.count + 4
    }
}
