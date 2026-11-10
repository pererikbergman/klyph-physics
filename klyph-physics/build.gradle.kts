// The engine. Pure Kotlin with no dependencies, so everything lives in commonMain and it builds for
// every target klyph does. The JVM target is there for the tests: only the JVM can count the bytes a
// step allocates.
plugins { kotlin("multiplatform") }

kotlin {
    jvm()
    macosArm64()
    linuxX64()
    linuxArm64()
    mingwX64()
    sourceSets.commonTest.dependencies { implementation(kotlin("test")) }
}
