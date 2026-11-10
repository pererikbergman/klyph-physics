package klyph.physics.demo

import klyph.app.App
import klyph.app.Phase
import klyph.app.deltaSeconds
import klyph.platform.Color
import klyph.platform.Scancode
import klyph.text.TextAlign
import klyph.text.TextShadow
import kotlin.time.TimeSource

/** The keys that choose scene 1, 2, ... 9. */
private val SCENE_KEYS = listOf(
    Scancode.DIGIT_1, Scancode.DIGIT_2, Scancode.DIGIT_3, Scancode.DIGIT_4, Scancode.DIGIT_5,
    Scancode.DIGIT_6, Scancode.DIGIT_7, Scancode.DIGIT_8, Scancode.DIGIT_9,
)

// klyph names no constants for these keys; its keyboard state is indexed by SDL scancode, so these are SDL's.
private val LEFT_BRACKET = Scancode(47u)
private val RIGHT_BRACKET = Scancode(48u)
private val PERIOD = Scancode(55u)

/** How much each new frame or step time moves the HUD's averages: small, so the numbers stay readable. */
private const val SMOOTHING = 0.05f

private const val MARGIN = 16f

/** Distance between the HUD's lines of text at scale 2. */
private const val LINE = 22f
private val DIM = Color(0xAAB4C8FFu)
private val BADGE = Color(0xFFD36BFFu)

/** Runs [sandbox] in this app: keys drive it, fixed steps move it, and a HUD tells what is going on. */
fun App.installSandbox(sandbox: Sandbox) {
    var stepMillis = 0f
    var frameSeconds = 1f / 60f
    val shadow = TextShadow()
    val title = StringBuilder()
    val numbers = StringBuilder()

    addSystem(Phase.INPUT) { app ->
        val keys = app.input.keyboard
        if (keys.isJustPressed(Scancode.ESCAPE)) app.quit()
        for ((i, key) in SCENE_KEYS.withIndex()) if (keys.isJustPressed(key)) sandbox.select(i)
        if (keys.isJustPressed(LEFT_BRACKET)) sandbox.previous()
        if (keys.isJustPressed(RIGHT_BRACKET)) sandbox.next()
        if (keys.isJustPressed(Scancode.R)) sandbox.reset()
        if (keys.isJustPressed(Scancode.SPACE)) sandbox.togglePause()
        if (keys.isJustPressed(Scancode.S)) sandbox.toggleSlowMotion()
        if (keys.isJustPressed(PERIOD)) sandbox.stepOnce(app.time.fixedDelta.inWholeNanoseconds / 1e9f)
    }

    addSystem(Phase.FIXED_UPDATE) { app ->
        val start = TimeSource.Monotonic.markNow()
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

        title.clear()
        title.append(sandbox.index + 1).append('/').append(sandbox.sceneCount).append("  ").append(sandbox.scene.name)
        canvas.drawText(font, title, MARGIN, MARGIN, scale = 3f, layer = HUD_LAYER, shadow = shadow)
        canvas.drawText(font, sandbox.scene.blurb, MARGIN, MARGIN + 34f, color = DIM, scale = 2f, layer = HUD_LAYER, shadow = shadow)

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

        val badge = when {
            sandbox.isPaused -> "PAUSED"
            sandbox.isSlowMotion -> "SLOW MOTION x1/4"
            else -> null
        }
        if (badge != null) canvas.drawText(font, badge, width / 2f, MARGIN, color = BADGE, scale = 3f, align = TextAlign.CENTER, layer = HUD_LAYER, shadow = shadow)

        canvas.drawText(
            font,
            "1-9 [ ] scene   R reset   Space pause   . step   S slow   F3 stats   Esc quit",
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
