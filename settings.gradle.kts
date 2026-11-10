pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

rootProject.name = "klyph-physics"

// The engine: pure Kotlin, no dependencies.
include(":klyph-physics")
// The klyph glue: stepping a world in an app, and drawing what the physics sees.
include(":klyph-physics-bridge")
// The sandbox app that shows every feature. The klyph plugin needs SDL3 just to configure it, and
// CI's Linux runner has none, so there `-Pdemo=false` leaves it out; the engine needs nothing.
if (providers.gradleProperty("demo").orNull != "false") include(":demo")
