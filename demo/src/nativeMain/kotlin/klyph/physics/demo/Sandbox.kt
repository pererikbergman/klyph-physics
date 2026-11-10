package klyph.physics.demo

/**
 * The sandbox's rules with no engine code: which scene is on, whether time runs, stops, crawls or
 * moves one step at a time. The app turns keys into these calls and calls [fixedStep] once per
 * fixed step; a test drives exactly the same code.
 *
 * Slow motion steps the physics on one fixed step in [SLOW_MOTION_FACTOR] rather than with a
 * smaller step, so a scene behaves the same at any speed.
 */
class Sandbox(private val scenes: List<Scene>) {
    init {
        require(scenes.isNotEmpty()) { "The sandbox needs at least one scene" }
    }

    /** Which scene is on, counting from 0. */
    var index: Int = 0
        private set

    val scene: Scene get() = scenes[index]

    val sceneCount: Int get() = scenes.size

    /** The current scene's run, replaced on every switch and reset. */
    var run: SceneRun = scenes[0].create()
        private set

    var isPaused: Boolean = false
        private set

    var isSlowMotion: Boolean = false
        private set

    /** Physics steps taken since the current run started. */
    var stepCount: Long = 0
        private set

    /** Fixed steps seen in slow motion, to pick the ones that step the physics. */
    private var slowTicks = 0

    /** Switches to scene [i], counting from 0, from its start. Does nothing for a scene that does not exist. */
    fun select(i: Int) {
        if (i !in scenes.indices) return
        index = i
        reset()
    }

    fun next() = select((index + 1) % scenes.size)

    fun previous() = select((index - 1 + scenes.size) % scenes.size)

    /** Starts the current scene again. Pause and slow motion stay as they are. */
    fun reset() {
        run = scene.create()
        stepCount = 0
        slowTicks = 0
    }

    fun togglePause() {
        isPaused = !isPaused
    }

    fun toggleSlowMotion() {
        isSlowMotion = !isSlowMotion
        slowTicks = 0
    }

    /** Pauses, if it was not, and moves the physics on by exactly one step of [dt] seconds. */
    fun stepOnce(dt: Float) {
        isPaused = true
        advance(dt)
    }

    /** Called once per fixed step of [dt] seconds; returns whether the physics moved. */
    fun fixedStep(dt: Float): Boolean {
        if (isPaused) return false
        if (isSlowMotion && slowTicks++ % SLOW_MOTION_FACTOR != 0) return false
        advance(dt)
        return true
    }

    private fun advance(dt: Float) {
        run.step(dt)
        stepCount++
    }

    companion object {
        /** In slow motion the physics moves on one fixed step in this many: a quarter of the speed. */
        const val SLOW_MOTION_FACTOR = 4
    }
}
