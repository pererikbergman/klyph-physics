package klyph.physics.demo

import klyph.app.App
import klyph.app.Phase
import klyph.entity.Interpolated
import klyph.entity.Transform
import klyph.math.Rect
import klyph.physics.BodyKind
import klyph.physics.PhysicsWorld
import klyph.physics.Shape
import klyph.physics.bridge.DebugDraw
import klyph.physics.bridge.PhysicsBody
import klyph.physics.bridge.addPhysics
import klyph.platform.Color
import klyph.render.Sprite
import klyph.render.lookAt

/**
 * The README's example, as a game would write it: crates that are klyph sprites, each following a
 * rigid body that [addPhysics] steps. Nothing here draws the physics: klyph draws the sprites, and
 * blends each between its last two steps. Only the room is drawn by hand.
 *
 * `SANDBOX_EXAMPLE=1` runs it. With a [recording], a picture is saved each time the physics reaches a
 * step the recording asks for, and it stops after [steps] steps, if given: the physics runs on klyph's
 * clock, so how many frames that takes depends on the machine.
 */
fun App.installExample(recording: Recording? = null, steps: Long? = null) {
    // ---- the README's example, from here...
    val physics = addPhysics(PhysicsWorld().apply { solids = Scenes.room() })
    repeat(CRATES) { i ->
        val box = physics.world.rigidBody(Shape.box(16f, 16f), 250f + i % 6 * 20f, 40f + i / 6 * 22f, angle = i * 0.1f)
        val crate = world.create()
        world.add(crate, Transform, Transform())
        world.add(crate, Sprite, Sprite(assets.whitePixel, width = 16f, height = 16f, color = COLORS[i % COLORS.size]))
        world.add(crate, PhysicsBody, PhysicsBody.rigid(box))
        world.add(crate, Interpolated, Interpolated)
    }
    // ---- ...to here.

    // a plank for them to tumble down: it has no sprite, so the room's drawing shows it
    physics.world.rigidBody(Shape.box(200f, 8f), 330f, 240f, angle = 0.6f, kind = BodyKind.STATIC)
    camera.zoom = 2f
    camera.lookAt(Scenes.COLS * Scenes.TILE / 2f, Scenes.ROWS * Scenes.TILE / 2f)
    val debug = DebugDraw(assets.whitePixel, assets.defaultFont)
    addSystem(Phase.RENDER) { app ->
        val view = Rect(0f, 0f, Scenes.COLS * Scenes.TILE, Scenes.ROWS * Scenes.TILE)
        debug.solids(app.canvas, physics.world.solids!!, view)
        val bodies = physics.world.rigidBodies
        for (b in 0 until bodies.end) if (bodies.isAlive(b) && bodies.inverseMass(b) == 0f) debug.rigidBody(app.canvas, physics.world, b, PLANK)
    }
    if (recording != null) {
        var saved = 0L
        addSystem(Phase.LATE_UPDATE) { app ->
            val step = physics.stepCount
            if (step == saved) return@addSystem
            saved = step
            recording.fileAfter(step)?.let { app.window.capture(it) }
        }
    }
    if (steps != null) addSystem(Phase.LATE_UPDATE) { app -> if (physics.stepCount >= steps) app.quit() }
}

private const val CRATES = 36
private val COLORS = listOf(Color(0xF2994AFFu), Color(0xE8C887FFu), Color(0xD9A75FFFu), Color(0xB8E05CFFu))
private val PLANK = Color(0x8FA8C8FFu)
