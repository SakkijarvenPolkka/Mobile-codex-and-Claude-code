// AGP 9 has built-in Kotlin for Android modules; :core is a plain Kotlin/JVM module.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.jvm) apply false
}
