// The port of Core/ (the RerunGuideCore Swift package): models, the guide generator, the Plex / Emby / Jellyfin
// clients. Plain Kotlin on the JVM, no Android calls, so `./gradlew :core:test` runs on any desktop JVM. The parity test
// compares it with values the Swift Core produced (src/test/resources/parity-fixture.json).
plugins { id("org.jetbrains.kotlin.jvm") }

kotlin { jvmToolchain(17) }

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    compileOnly("org.json:json:20240303")   // Android ships org.json; the app must not bundle a second copy
    testImplementation("org.json:json:20240303")
    testImplementation("junit:junit:4.13.2")
}
