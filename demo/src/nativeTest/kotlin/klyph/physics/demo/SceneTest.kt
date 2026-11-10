package klyph.physics.demo

import klyph.physics.Outline
import klyph.physics.SoftBody
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertTrue

/** Runs the scenes made of blobs without a window and checks nothing breaks. */
class SceneTest {
    /** The step the app takes, to the last bit: klyph's 1/60 s is 16 666 666 ns, a hair under `1f / 60f`. */
    private val dt = 16_666_666 / 1e9f
    private val roomWidth = Scenes.COLS * Scenes.TILE
    private val roomHeight = Scenes.ROWS * Scenes.TILE

    @Test
    fun everyBlobSceneRunsForTenSecondsWithNothingLostOrTorn() {
        for (scene in Scenes.all) {
            val run = scene.create() as? BlobRun ?: continue
            check(scene.name, run, steps = 600)
        }
    }

    @Test
    fun theBlobGardenRunsForTenMinutesWithNothingLostOrTorn() {
        check(BlobGardenScene.name, BlobGardenScene.create() as BlobRun, steps = 36_000)
    }

    @Test
    fun theFountainReusesItsHandlesSoItsStoreStopsGrowing() {
        val run = FountainScene.create() as FountainScene.Run
        repeat(600) { run.step(dt) }
        val ps = run.world.particles
        val end = ps.end
        assertTrue(ps.count in 100..end, "about a second and a half of particles alive: ${ps.count}")
        repeat(600) { run.step(dt) }
        assertTrue(ps.end <= end + FountainScene.PER_STEP * 10, "the store stopped growing: ${ps.end} after $end")
        for (p in 0 until ps.end) if (ps.isAlive(p)) assertTrue(ps.x(p).isFinite() && ps.y(p).isFinite())
    }

    @Test
    fun theSpringsSwingForAMinuteWithoutBlowingUp() {
        val run = SpringsScene.create() as SpringsScene.Run
        repeat(3600) { run.step(dt) }
        val ps = run.world.particles
        for (p in 0 until ps.end) assertTrue(ps.x(p).isFinite() && ps.y(p).isFinite(), "particle $p")
        for (p in 0 until ps.end) assertTrue(ps.y(p) in 0f..roomHeight, "particle $p is still in the room: y = ${ps.y(p)}")
    }

    /**
     * Steps [run] [steps] times. Every step, every blob must be finite and inside the room. A blob may
     * snag on a corner and stretch for a moment, but not stay stretched: that is tearing. Its core may
     * lag outside its skin on a hard landing, but not stay out: then it went through a wall alone.
     */
    private fun check(name: String, run: BlobRun, steps: Int) {
        val stretchedFor = IntArray(run.blobs.size)
        val coreOutFor = IntArray(run.blobs.size)
        repeat(steps) { step ->
            (run as SceneRun).step(dt)
            for ((i, blob) in run.blobs.withIndex()) {
                val body = blob.body
                val where = "$name, blob $i, step $step"
                assertTrue(body.isFinite(), "$where: not finite")
                assertTrue(body.minX() >= 0f && body.maxX() <= roomWidth && body.minY() >= 0f && body.maxY() <= roomHeight, "$where: left the room")
                val stretch = body.maxEdgeStretch()
                stretchedFor[i] = if (stretch > MAX_STRETCH) stretchedFor[i] + 1 else 0
                assertTrue(stretchedFor[i] <= MAX_STRETCHED_STEPS, "$where: torn, stretched $stretch times for ${stretchedFor[i]} steps")
                coreOutFor[i] = if (coreDistanceOutside(body) > MAX_CORE_OUTSIDE) coreOutFor[i] + 1 else 0
                assertTrue(coreOutFor[i] <= MAX_STRETCHED_STEPS, "$where: the core left its skin for ${coreOutFor[i]} steps")
            }
        }
    }

    private val nearest = FloatArray(2)

    /** How far the core of [body] is outside its outline, or 0 when it's inside. */
    private fun coreDistanceOutside(body: SoftBody): Float {
        val core = body.ringCount
        val x = body.positionX(core)
        val y = body.positionY(core)
        if (Outline.contains(body, x, y)) return 0f
        return sqrt(Outline.nearestPoint(body, x, y, nearest))
    }

    private companion object {
        /** A core this far outside its skin is no longer just lagging behind it. */
        const val MAX_CORE_OUTSIDE = 3f

        /** An outline edge more than this many times its rest length is over-stretched. */
        const val MAX_STRETCH = 2.5f

        /** Half a second: longer than this over-stretched, or with its core out, and a body is broken, not just bumped. */
        const val MAX_STRETCHED_STEPS = 30
    }
}
