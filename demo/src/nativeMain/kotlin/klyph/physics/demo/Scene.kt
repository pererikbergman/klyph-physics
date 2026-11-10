package klyph.physics.demo

/**
 * One scene of the sandbox: a name, a one-line blurb, and a way to start a fresh run of it. The
 * sandbox calls [create] when the scene is chosen and again on every reset, so a run never has to
 * know how to put itself back.
 */
interface Scene {
    /** Shown big in the corner, such as "Blob Garden". */
    val name: String

    /** One line under the name: what to look at, or what to try. */
    val blurb: String

    /** A fresh run of this scene, at its start. */
    fun create(): SceneRun
}

/** A scene that is running: the physics it owns, moved one fixed step at a time. */
interface SceneRun {
    /** Advances the run's physics by one fixed step of [dt] seconds. */
    fun step(dt: Float)

    /** How many particles, bodies and contacts the run has now, for the HUD. */
    val particleCount: Int get() = 0
    val bodyCount: Int get() = 0
    val contactCount: Int get() = 0
}
