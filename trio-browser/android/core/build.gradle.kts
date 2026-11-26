// Pure Kotlin port of the shared core (trio-browser/desktop/src/core). Its tests run the same
// JSON vectors and golden outputs as the desktop reference implementation.
plugins {
    alias(libs.plugins.kotlin.jvm)
    // The vector and golden checks live in test fixtures so that the app's on-device tests can
    // run them on the Android runtime too (it uses ICU regular expressions, the JVM does not).
    `java-test-fixtures`
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    api(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
}

tasks.test {
    // The tests read these at run time (vectors, golden outputs, the app's regular expressions), so a change
    // there alone must run them again instead of reusing an up-to-date or cached result.
    val shared = rootDir.resolve("../shared")
    val appSources = rootDir.resolve("app/src/main/java")
    inputs.dir(shared).withPropertyName("shared").withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.dir(appSources).withPropertyName("appSources").withPathSensitivity(PathSensitivity.RELATIVE)
    systemProperty("trio.shared", shared.absolutePath)
    systemProperty("trio.app", appSources.absolutePath)
}
