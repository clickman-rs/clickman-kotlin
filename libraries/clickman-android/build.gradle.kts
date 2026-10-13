import java.time.Duration

plugins {
    id("clickman.android-library")
    `maven-publish`
}

android {
    namespace = "com.lockvoid.clickman.android"
    compileSdk = 36

    defaultConfig {
        minSdk = 28
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    publishing { singleVariant("release") { withSourcesJar() } }
}

afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("library") {
                from(components["release"])
                artifactId = "clickman-android"
            }
        }
    }
}

dependencies {
    api(project(":clickman"))
    api(libs.androidx.sqlite.framework)
    implementation(libs.androidx.lifecycle.process)
    testImplementation(testFixtures(project(":clickman")))
    testImplementation(libs.androidx.sqlite.bundled.jvm)
    testImplementation(libs.kotlin.test.junit)
}

tasks.withType<Test>().configureEach {
    timeout.set(Duration.ofMinutes(10))
}
