// Plugin versions live here, once; each module applies the ones it needs. The klyph plugin applies
// Kotlin Multiplatform itself, so the two must agree on the Kotlin version.
plugins {
    kotlin("multiplatform") version "2.4.20" apply false
    id("com.eventslooped.klyph") version "1.0.0" apply false
}
