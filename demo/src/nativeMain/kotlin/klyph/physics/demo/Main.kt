package klyph.physics.demo

import klyph.app.AppConfig
import klyph.app.runApp

/**
 * The klyph-physics sandbox: every feature of the engine, one scene at a time. The physics steps 60
 * times a second, in klyph's fixed step.
 *
 * `AppConfig.fromEnvironment` reads the `KLYPH_*` variables, so `./gradlew :demo:run -Pklyph.frames=60`
 * stops after 60 frames and `-Pklyph.screenshot=30:shot.png` saves frame 30.
 */
fun main() {
    val config = AppConfig.fromEnvironment(AppConfig(title = "klyph-physics sandbox", width = 1280, height = 720, fixedRate = 60))
    runApp(config) { installSandbox(Sandbox(Scenes.all)) }
}
