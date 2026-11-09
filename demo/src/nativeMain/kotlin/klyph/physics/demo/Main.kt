package klyph.physics.demo

import klyph.app.AppConfig
import klyph.app.runApp

/**
 * The klyph-physics sandbox. For now an empty window; scenes arrive with the sandbox shell.
 *
 * `AppConfig.fromEnvironment` reads the `KLYPH_*` variables, so `./gradlew :demo:run -Pklyph.frames=60`
 * stops after 60 frames and `-Pklyph.screenshot=30:shot.png` saves frame 30.
 */
fun main() {
    val config = AppConfig.fromEnvironment(AppConfig(title = "klyph-physics sandbox", width = 1280, height = 720))
    runApp(config) {}
}
