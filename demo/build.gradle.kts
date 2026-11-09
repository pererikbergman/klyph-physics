// The sandbox app. The klyph plugin makes it a game: it applies Kotlin Multiplatform for the host,
// and adds `run` and `distribute`.
plugins { id("com.eventslooped.klyph") }

klyph { entryPoint = "klyph.physics.demo.main" }

kotlin {
    sourceSets.nativeMain.dependencies {
        implementation("com.eventslooped.klyph:klyph:1.0.0")
        implementation(project(":klyph-physics-bridge"))
    }
    sourceSets.nativeTest.dependencies { implementation(kotlin("test")) }
}
