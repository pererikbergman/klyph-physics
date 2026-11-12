package klyph.physics.demo

import klyph.app.App
import klyph.app.Phase
import klyph.app.deltaSeconds
import klyph.math.Rect
import klyph.physics.bridge.DebugDraw
import klyph.physics.bridge.DebugView
import klyph.platform.Color
import klyph.platform.MouseButton
import klyph.platform.Scancode
import klyph.render.lookAt
import klyph.render.visibleHeight
import klyph.render.visibleWidth
import klyph.text.TextAlign
import klyph.text.TextShadow
import kotlin.time.TimeSource

// klyph names no constants for these keys; its keyboard state is indexed by SDL scancode, so these are SDL's.
private val DIGIT_0 = Scancode(39u)
private val LEFT_BRACKET = Scancode(47u)
private val RIGHT_BRACKET = Scancode(48u)
private val PERIOD = Scancode(55u)

/** The keys that choose scene 1, 2, ... 9, and 0 for the tenth. */
private val SCENE_KEYS = listOf(
    Scancode.DIGIT_1, Scancode.DIGIT_2, Scancode.DIGIT_3, Scancode.DIGIT_4, Scancode.DIGIT_5,
    Scancode.DIGIT_6, Scancode.DIGIT_7, Scancode.DIGIT_8, Scancode.DIGIT_9, DIGIT_0,
)

/** How much each new frame or step time moves the HUD's averages: small, so the numbers stay readable. */
private const val SMOOTHING = 0.05f

private const val MARGIN = 16f

/** Distance between the HUD's lines of text at scale 2. */
private const val LINE = 22f
private val DIM = Color(0xAAB4C8FFu)
private val BADGE = Color(0xFFD36BFFu)

/**
 * Runs [sandbox] in this app: keys drive it, fixed steps move it, a [DebugDraw] shows it, starting at
 * the [DebugView] [view], and a HUD tells what is going on.
 *
 * For pictures: with a [recording] the physics steps once per frame, not on the clock, and saves
 * the frames the recording names. [clean] keeps only the scene's title and blurb on screen, and a
 * [hand] grabs and drags something with no one at the mouse.
 */
fun App.installSandbox(
    sandbox: Sandbox,
    view: DebugView = DebugView.OFF,
    grid: Boolean = false,
    recording: Recording? = null,
    clean: Boolean = false,
    hand: ScriptedHand? = null,
) {
    val debug = DebugDraw(assets.whitePixel, assets.defaultFont).apply {
        this.view = view
        showBroadphase = grid
    }
    // Two points per world unit: the 640 x 368 units of a scene fill a 1280 x 720 window.
    camera.zoom = ZOOM
    camera.lookAt(Scenes.COLS * Scenes.TILE / 2f, Scenes.ROWS * Scenes.TILE / 2f)
    var stepMillis = 0f
    var frameSeconds = 1f / 60f
    val shadow = TextShadow()
    val title = StringBuilder()
    val numbers = StringBuilder()

    addSystem(Phase.INPUT) { app ->
        val keys = app.input.keyboard
        if (keys.isJustPressed(Scancode.ESCAPE)) app.quit()
        if (keys.isJustPressed(Scancode.F1)) debug.view = debug.view.next()
        if (keys.isJustPressed(Scancode.B)) debug.showBroadphase = !debug.showBroadphase
        for ((i, key) in SCENE_KEYS.withIndex()) if (keys.isJustPressed(key)) sandbox.select(i)
        if (keys.isJustPressed(LEFT_BRACKET)) sandbox.previous()
        if (keys.isJustPressed(RIGHT_BRACKET)) sandbox.next()
        if (keys.isJustPressed(Scancode.R)) sandbox.reset()
        if (keys.isJustPressed(Scancode.SPACE)) sandbox.togglePause()
        if (keys.isJustPressed(Scancode.S)) sandbox.toggleSlowMotion()
        if (keys.isJustPressed(PERIOD)) sandbox.stepOnce(app.time.fixedDelta.inWholeNanoseconds / 1e9f)

        val mouse = app.input.mouse
        val at = mouse.worldPosition
        if (mouse.isJustPressed(MouseButton.LEFT)) sandbox.grab(at.x, at.y)
        if (mouse.isDown(MouseButton.LEFT)) sandbox.moveGrab(at.x, at.y)
        if (mouse.isJustReleased(MouseButton.LEFT)) sandbox.release()
        if (mouse.isJustPressed(MouseButton.RIGHT)) sandbox.spawn(at.x, at.y)
    }

    if (recording != null) {
        addSystem(Phase.UPDATE) { app ->
            hand?.drive(sandbox)
            sandbox.fixedStep(app.time.fixedDelta.inWholeNanoseconds / 1e9f)
            recording.fileAfter(sandbox.stepCount)?.let { app.window.capture(it) }
        }
    }

    addSystem(Phase.FIXED_UPDATE) { app ->
        if (recording != null) return@addSystem
        val start = TimeSource.Monotonic.markNow()
        hand?.drive(sandbox)
        if (sandbox.fixedStep(app.time.deltaSeconds)) {
            val millis = start.elapsedNow().inWholeNanoseconds / 1e6f
            stepMillis += (millis - stepMillis) * SMOOTHING
        }
    }

    addSystem(Phase.RENDER) { app ->
        val canvas = app.canvas
        val font = app.assets.defaultFont
        val width = app.window.width.toFloat()
        val height = app.window.height.toFloat()
        frameSeconds += (app.time.deltaSeconds - frameSeconds) * SMOOTHING
        val run = sandbox.run
        val camera = app.camera
        val viewWidth = camera.visibleWidth
        val viewHeight = camera.visibleHeight
        run.draw(canvas, debug, Rect(camera.x - viewWidth / 2f, camera.y - viewHeight / 2f, viewWidth, viewHeight))
        run.world?.let { world ->
            debug.overlay(canvas, world)
            drawHand(canvas, debug, world)
        }

        title.clear()
        title.append(sandbox.index + 1).append('/').append(sandbox.sceneCount).append("  ").append(sandbox.scene.name)
        canvas.drawText(font, title, MARGIN, MARGIN, scale = 3f, layer = HUD_LAYER, shadow = shadow)
        canvas.drawText(font, sandbox.scene.blurb, MARGIN, MARGIN + 34f, color = DIM, scale = 2f, layer = HUD_LAYER, shadow = shadow)
        if (clean) return@addSystem

        // One line at a time: the built-in font's own line spacing is tighter than its glyphs at this scale.
        var line = 0
        fun stat(text: StringBuilder) {
            canvas.drawText(font, text, width - MARGIN, MARGIN + line++ * LINE, scale = 2f, align = TextAlign.RIGHT, layer = HUD_LAYER, shadow = shadow)
        }
        numbers.clear()
        stat(numbers.appendFixed(if (frameSeconds > 0f) 1f / frameSeconds else 0f, 0).append(" fps"))
        numbers.clear()
        stat(numbers.append("step ").appendFixed(stepMillis, 2).append(" ms"))
        numbers.clear()
        stat(numbers.append("particles ").append(run.particleCount))
        numbers.clear()
        stat(numbers.append("bodies ").append(run.bodyCount))
        numbers.clear()
        stat(numbers.append("contacts ").append(run.contactCount))
        numbers.clear()
        stat(numbers.append("step #").append(sandbox.stepCount))
        if (debug.view != DebugView.OFF) stat(VIEW_NAMES[debug.view.ordinal])
        if (debug.showBroadphase) stat(GRID_NAME)

        val badge = when {
            sandbox.isPaused -> "PAUSED"
            sandbox.isSlowMotion -> "SLOW MOTION x1/4"
            else -> null
        }
        if (badge != null) canvas.drawText(font, badge, width / 2f, MARGIN, color = BADGE, scale = 3f, align = TextAlign.CENTER, layer = HUD_LAYER, shadow = shadow)

        canvas.drawText(
            font,
            "left mouse grab and drag  right mouse spawn",
            MARGIN,
            height - MARGIN - 16f - LINE,
            color = DIM,
            scale = 2f,
            layer = HUD_LAYER,
            shadow = shadow,
        )
        canvas.drawText(
            font,
            "0-9 [ ] scene  R reset  Space pause  . step  S slow  F1 view  B grid  F3 stats",
            MARGIN,
            height - MARGIN - 16f,
            color = DIM,
            scale = 2f,
            layer = HUD_LAYER,
            shadow = shadow,
        )
    }
}

private const val HUD_LAYER = 100

/** What the HUD calls each [DebugView], made once so the HUD makes no strings. */
private val VIEW_NAMES = listOf(
    StringBuilder("view off"), StringBuilder("view bodies"), StringBuilder("view constraints"), StringBuilder("view everything"),
)
private val GRID_NAME = StringBuilder("broadphase on")

private val HAND = Color(0xFFD36BFFu)

/** The line from what's grabbed to the mouse, and a dot on the point held. */
private fun drawHand(canvas: klyph.render.Canvas, debug: DebugDraw, world: klyph.physics.PhysicsWorld) {
    val hand = world.hand
    if (!hand.isHolding) return
    val x = hand.heldX
    val y = hand.heldY
    debug.line(canvas, x, y, hand.targetX, hand.targetY, 1f, HAND, debug.layer + 10)
    debug.square(canvas, x, y, 4f, HAND)
}

private const val ZOOM = 2f

/** Appends [value] with [decimals] digits after the point, rounded, without making a new string. */
internal fun StringBuilder.appendFixed(value: Float, decimals: Int): StringBuilder {
    var scale = 1L
    repeat(decimals) { scale *= 10 }
    val negative = value < 0f
    val scaled = ((if (negative) -value else value) * scale + 0.5f).toLong()
    if (negative && scaled != 0L) append('-')
    append(scaled / scale)
    if (decimals > 0) {
        append('.')
        val fraction = scaled % scale
        var digits = scale / 10
        while (digits > 0) {
            append(fraction / digits % 10)
            digits /= 10
        }
    }
    return this
}
