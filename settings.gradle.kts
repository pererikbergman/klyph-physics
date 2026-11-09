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
// The sandbox app that shows every feature.
include(":demo")
