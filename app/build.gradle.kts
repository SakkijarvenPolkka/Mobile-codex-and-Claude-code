plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

val nativeAbis = (project.findProperty("audacity.abis") as String? ?: "arm64-v8a,x86_64")
    .split(',').map { it.trim() }.filter { it.isNotEmpty() }

android {
    namespace = "io.github.sakkijarvenpolkka.audacity"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.sakkijarvenpolkka.audacity"
        minSdk = 28
        targetSdk = 36
        versionCode = 1
        versionName = "3.7.9-android.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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

    // One APK per ABI (the Audacity core is ~70 native libraries per ABI):
    // app-arm64-v8a-*.apk for phones, app-x86_64-*.apk for emulators and
    // Chromebooks. -Paudacity.universalApk=true adds a universal APK.
    splits {
        abi {
            isEnable = true
            reset()
            include(*nativeAbis.toTypedArray())
            isUniversalApk = (project.findProperty("audacity.universalApk") as String? ?: "false").toBoolean()
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
    implementation(project(":engine"))
    implementation(project(":editor"))
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
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

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
