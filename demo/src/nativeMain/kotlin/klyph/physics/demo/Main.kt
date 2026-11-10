package klyph.physics.demo

import klyph.app.AppConfig
import klyph.app.runApp
import klyph.physics.bridge.DebugView
import klyph.platform.Sdl

/**
 * The klyph-physics sandbox: every feature of the engine, one scene at a time. The physics steps 60
 * times a second, in klyph's fixed step.
 *
 * `AppConfig.fromEnvironment` reads the `KLYPH_*` variables, so `./gradlew :demo:run -Pklyph.frames=60`
 * stops after 60 frames and `-Pklyph.screenshot=30:shot.png` saves frame 30. `SANDBOX_SCENE=3`
 * starts on the third scene and `SANDBOX_DETAILS=3` with the debug view at level 3 of 0 to 3 (see
 * [DebugView]), and `SANDBOX_GRID=1` with the broadphase shown, for pictures taken without
 * a keyboard. `SANDBOX_RECORD=2:/tmp/frames` saves every second step as a numbered PNG (see
 * [Recording]), `SANDBOX_CLEAN=1` leaves only the title on screen, and `SANDBOX_DRAG=320,200` grabs
 * what is there and drags it about (see [ScriptedHand]). `scripts/capture.sh` uses them.
 */
fun main() {
    val config = AppConfig.fromEnvironment(AppConfig(title = "klyph-physics sandbox", width = 1280, height = 720, fixedRate = 60))
    val sandbox = Sandbox(Scenes.all)
    Sdl.environmentVariable("SANDBOX_SCENE")?.toIntOrNull()?.let { sandbox.select(it - 1) }
    val level = Sdl.environmentVariable("SANDBOX_DETAILS")?.toIntOrNull() ?: 0
    val view = DebugView.entries[level.coerceIn(0, DebugView.entries.size - 1)]
    val grid = Sdl.environmentVariable("SANDBOX_GRID") == "1"
    val recording = Recording.parse(Sdl.environmentVariable("SANDBOX_RECORD"))
    val clean = Sdl.environmentVariable("SANDBOX_CLEAN") == "1"
    val hand = ScriptedHand.parse(Sdl.environmentVariable("SANDBOX_DRAG"))
    runApp(config) { installSandbox(sandbox, view, grid, recording, clean, hand) }
}
