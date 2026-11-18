package klyph.physics.demo

import klyph.physics.PhysicsConfig
import kotlin.time.TimeSource

/**
 * Times physics with no window, one step at a time, and prints a line for each thing timed: the
 * average step, the 99th percentile, and the slowest, in milliseconds. A step fits 60 FPS when it
 * leaves room in 16.7 ms for drawing.
 *
 * Two kinds of thing are timed: every scene of the sandbox, each run for 60 steps to settle and then
 * timed; and the [StressWorld], from its first step, in parts and all together, against its targets,
 * [StressWorld.TARGET_MILLIS] and [StressWorld.WORLD_TARGET_MILLIS]. `scripts/bench.sh` builds the
 * release binary and runs it; through Gradle, timings come out slower and uneven.
 */
object Bench {
    private const val DT = 16_666_666 / 1e9f

    class Result(val name: String, val averageMillis: Float, val p99Millis: Float, val slowestMillis: Float, val particles: Int, val bodies: Int) {
        /** The average this should be under, or 0 for none. */
        var targetMillis = 0f
    }

    /** Steps [step] [steps] times, timing each, after [warmup] untimed. */
    fun time(name: String, steps: Int, warmup: Int, particles: () -> Int, bodies: () -> Int, step: () -> Unit): Result {
        repeat(warmup) { step() }
        val millis = FloatArray(steps)
        for (i in 0 until steps) {
            val start = TimeSource.Monotonic.markNow()
            step()
            millis[i] = start.elapsedNow().inWholeNanoseconds / 1e6f
        }
        val total = millis.sum()
        millis.sort()
        return Result(name, total / steps, millis[(steps * 99 / 100).coerceAtMost(steps - 1)], millis[steps - 1], particles(), bodies())
    }

    fun measure(scene: Scene, steps: Int, warmup: Int = 60): Result {
        val run = scene.create()
        return time(scene.name, steps, warmup, { run.particleCount }, { run.bodyCount }) { run.step(DT) }
    }

    /** The [StressWorld] with [boxes], [blobs] and [grains], timed from its first step for [steps]. */
    fun stress(name: String, steps: Int, boxes: Int, blobs: Int, grains: Int, config: PhysicsConfig = StressWorld.CONFIG): Result {
        val stress = StressWorld(boxes, blobs, grains, config)
        val w = stress.world
        return time(name, steps, warmup = 0, { w.particles.count }, { w.rigidBodies.count + w.softBodies.count }) { stress.step(DT) }
    }

    /** The stress workloads, [steps] steps each: each kind alone, then all, with sleeping on as by default and then off. */
    fun stressAll(steps: Int): List<Result> {
        val s = StressWorld
        val awake = s.CONFIG.copy(timeToSleep = Float.POSITIVE_INFINITY)
        return listOf(
            stress("sand", steps, 0, 0, s.GRAINS).apply { targetMillis = s.TARGET_MILLIS },
            stress("boxes", steps, s.BOXES, 0, 0),
            stress("boxes, never asleep", steps, s.BOXES, 0, 0, awake).apply { targetMillis = s.TARGET_MILLIS },
            stress("blobs", steps, 0, s.BLOBS, 0).apply { targetMillis = s.TARGET_MILLIS },
            stress("everything", steps, s.BOXES, s.BLOBS, s.GRAINS),
            stress("everything, never asleep", steps, s.BOXES, s.BLOBS, s.GRAINS, awake).apply { targetMillis = s.WORLD_TARGET_MILLIS },
        )
    }

    /**
     * Steps the [StressWorld] [steps] times untimed, for a profiler to watch: `sand`, `boxes` (never
     * asleep) or, for anything else, everything. `sample $(pgrep demo.kexe) 10` while it runs shows
     * where the time goes.
     */
    private fun profile(what: String, steps: Int) {
        val awake = StressWorld.CONFIG.copy(timeToSleep = Float.POSITIVE_INFINITY)
        val stress = when (what) {
            "boxes" -> StressWorld(StressWorld.BOXES, 0, 0, awake)
            "sand" -> StressWorld(0, 0, StressWorld.GRAINS)
            else -> StressWorld()
        }
        repeat(steps) { stress.step(DT) }
    }

    /**
     * Times every scene, then the stress workloads; [which] picks one scene by its number, counting
     * from 1 as the number keys do, `scenes` for the scenes alone, `stress` for the workloads alone, or `profile`, `profile-sand` or
     * `profile-boxes` to step the stress world untimed for a profiler.
     */
    fun run(steps: Int, which: String? = null) {
        val scene = which?.toIntOrNull()
        if (which != null && which.startsWith("profile")) {
            profile(which.removePrefix("profile").removePrefix("-"), steps)
            return
        }
        if (which != "stress") {
            println("$steps steps a scene, after 60 to settle:")
            for ((i, s) in Scenes.all.withIndex()) if (scene == null || i + 1 == scene) print(measure(s, steps))
        }
        if (scene == null && which != "scenes") {
            println("the stress world, ${StressWorld.SUBSTEPS} substeps of ${StressWorld.ITERATIONS} passes, $steps steps from the start:")
            for (r in stressAll(steps)) print(r)
        }
    }

    private fun print(r: Result) {
        val line = StringBuilder()
        line.append("  ").append(r.name.padEnd(26))
        line.append("average ").appendFixed(r.averageMillis, 2).append(" ms   ")
        line.append("99% ").appendFixed(r.p99Millis, 2).append(" ms   ")
        line.append("slowest ").appendFixed(r.slowestMillis, 2).append(" ms   ")
        line.append(r.particles).append(" particles, ").append(r.bodies).append(" bodies")
        if (r.targetMillis > 0f) {
            line.append(if (r.averageMillis < r.targetMillis) "   under " else "   OVER ").append(r.targetMillis.toInt()).append(" ms")
        }
        println(line)
    }
}
