// The klyph glue: runs a physics world inside a klyph app and draws it. The only module that knows
// both the engine and klyph.
plugins { kotlin("multiplatform") }

kotlin {
    // Every public declaration says it's public, and says what it returns: nothing is public by accident.
    explicitApi()
    macosArm64()
    linuxX64()
    linuxArm64()
    mingwX64()
    sourceSets.commonMain.dependencies {
        api(project(":klyph-physics"))
        implementation("com.eventslooped.klyph:klyph:1.0.0")
    }
    sourceSets.commonTest.dependencies { implementation(kotlin("test")) }
}
