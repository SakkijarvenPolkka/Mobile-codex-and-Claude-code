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
    systemProperty("trio.shared", rootDir.resolve("../shared").absolutePath)
}
