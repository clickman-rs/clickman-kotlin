import java.time.Duration

// The client on the JVM; the host hands in its SQLite driver, so no Android import and no bundled SQLite.
plugins {
    id("clickman.kotlin-jvm")
    // Only the tests declare @Serializable events; the library reads their descriptors.
    id("clickman.kotlin-serialization")
    `java-library`
    `java-test-fixtures`
    `maven-publish`
}

java { withSourcesJar() }

publishing {
    publications {
        create<MavenPublication>("library") {
            from(components["java"])
            artifactId = "clickman"
        }
    }
}

dependencies {
    api(libs.androidx.sqlite)
    // Typed events are declared with kotlinx.serialization, whose types the public API takes.
    api(libs.kotlinx.serialization.core)
    implementation(libs.kotlinx.serialization.json)
    testFixturesApi(libs.kotlinx.serialization.json)
    testImplementation(libs.androidx.sqlite.bundled)
    testImplementation(libs.kotlin.test.junit)
}

tasks.test {
    useJUnit()
    val fixtures = rootProject.projectDir.resolve("protocol/fixtures").canonicalFile
    val standardEvents = rootProject.projectDir.resolve("protocol/standard-events.json").canonicalFile
    inputs.dir(fixtures).withPropertyName("fixtures").withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.file(standardEvents).withPropertyName("standardEvents").withPathSensitivity(PathSensitivity.RELATIVE)
    systemProperty("clickman.fixtures", fixtures.absolutePath)
    systemProperty("clickman.standardEvents", standardEvents.absolutePath)
    timeout.set(Duration.ofMinutes(10))
    testLogging { events("failed") }
}
