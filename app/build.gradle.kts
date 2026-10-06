plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

val buildNative = (project.findProperty("audacity.buildNative") as String? ?: "true").toBoolean()
val nativeAbis = (project.findProperty("audacity.abis") as String? ?: "arm64-v8a,x86_64")
    .split(',').map { it.trim() }.filter { it.isNotEmpty() }

android {
    namespace = "io.github.sakkijarvenpolkka.audacity"
    compileSdk = 36
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "io.github.sakkijarvenpolkka.audacity"
        minSdk = 28
        targetSdk = 36
        versionCode = 1
        versionName = "3.7.9-android.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("boolean", "NATIVE_ENGINE", buildNative.toString())

        if (buildNative) {
            ndk {
                abiFilters += nativeAbis
            }
            externalNativeBuild {
                cmake {
                    arguments += listOf(
                        "-DANDROID_STL=c++_shared",
                        "-DCMAKE_BUILD_TYPE=Release",
                    )
                }
            }
        }
    }

    if (buildNative) {
        externalNativeBuild {
            cmake {
                path = file("../native/CMakeLists.txt")
                version = "3.31.6"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // Signed with the debug key so that `assembleRelease` produces an
            // installable APK out of the box; replace for distribution.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        jniLibs {
            // Keep the native libraries uncompressed and page aligned.
            useLegacyPackaging = false
        }
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.documentfile)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.kotlinx.coroutines.android)

    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
}
