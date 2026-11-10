package klyph.physics.demo

import kotlin.time.TimeSource

/**
 * Times every scene's physics with no window: each one is run for [warmup] steps to settle and
 * compile, then [steps] more, timed one by one. Prints a line a scene, with the average and the
 * slowest step in milliseconds: a scene runs at 60 FPS when its slowest step leaves room in 16.7 ms
 * for drawing. `SANDBOX_BENCH=600 ./gradlew :demo:runRelease` runs it on the release build.
 */
object Bench {
    private const val DT = 16_666_666 / 1e9f

    class Result(val scene: String, val averageMillis: Float, val slowestMillis: Float, val particles: Int)

    fun measure(scene: Scene, steps: Int, warmup: Int = 60): Result {
        val run = scene.create()
        repeat(warmup) { run.step(DT) }
        var total = 0f
        var slowest = 0f
        repeat(steps) {
            val start = TimeSource.Monotonic.markNow()
            run.step(DT)
            val millis = start.elapsedNow().inWholeNanoseconds / 1e6f
            total += millis
            if (millis > slowest) slowest = millis
        }
        return Result(scene.name, total / steps, slowest, run.particleCount)
    }

    fun run(steps: Int) {
        println("$steps steps a scene, after 60 to settle:")
        for (scene in Scenes.all) {
            val r = measure(scene, steps)
            val line = StringBuilder()
            line.append("  ").append(r.scene.padEnd(18))
            line.append("average ").appendFixed(r.averageMillis, 2).append(" ms   ")
            line.append("slowest ").appendFixed(r.slowestMillis, 2).append(" ms   ")
            line.append(r.particles).append(" particles")
            println(line)
        }
    }
}
