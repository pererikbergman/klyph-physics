package klyph.physics.demo

import klyph.math.Rect
import klyph.physics.PhysicsWorld
import klyph.physics.bridge.DebugDraw
import klyph.render.Canvas

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

/**
 * A scene that is running: the physics it owns, moved one fixed step at a time. A step is the run's
 * own [beforeStep], then the [world]'s step, then its [afterStep]: in the app `addPhysics` takes the
 * middle, as it would in a game.
 */
interface SceneRun {
    /** The run's physics. A run may swap it for a new one in [beforeStep]. */
    val world: PhysicsWorld

    /** What the run does each fixed step of [dt] seconds before the physics moves: steering, spawning, motors. */
    fun beforeStep(dt: Float) {}

    /** What the run does after the physics moved: reading what happened, such as hits and sensors. */
    fun afterStep(dt: Float) {}

    /** One whole fixed step of [dt] seconds, without an app: [beforeStep], the world's step, [afterStep]. */
    fun step(dt: Float) {
        beforeStep(dt)
        world.step(dt)
        afterStep(dt)
    }

    /** Adds this scene's own kind of thing at ([x], [y]), such as a blob or a ball: what a right click does. */
    fun spawn(x: Float, y: Float) {}

    /** Draws the run on [canvas] with [debug]; [view] is the part of the world the camera shows. */
    fun draw(canvas: Canvas, debug: DebugDraw, view: Rect) {}

    /** How many particles, bodies and contacts the run has now, for the HUD. */
    val particleCount: Int get() = 0
    val bodyCount: Int get() = 0
    val contactCount: Int get() = 0
}
