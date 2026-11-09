// The engine. Pure Kotlin with no dependencies, so everything lives in commonMain and it builds for
// every target klyph does.
plugins { kotlin("multiplatform") }

kotlin {
    macosArm64()
    linuxX64()
    linuxArm64()
    mingwX64()
    sourceSets.commonTest.dependencies { implementation(kotlin("test")) }
}
