plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
}

// Native (Audacity core + bridge + JNI) build switches, see gradle.properties.
val buildNative = (project.findProperty("audacity.buildNative") as String? ?: "true").toBoolean()
val nativeAbis = (project.findProperty("audacity.abis") as String? ?: "arm64-v8a,x86_64")
    .split(',').map { it.trim() }.filter { it.isNotEmpty() }

android {
    namespace = "io.github.sakkijarvenpolkka.audacity.engine"
    compileSdk = 36
    ndkVersion = "28.2.13676358"

    defaultConfig {
        minSdk = 28
        consumerProguardFiles("consumer-rules.pro")
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
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
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

// ---------------------------------------------------------------------------
// Nyquist runtime and plug-ins as assets (assets/audacity/{nyquist,plug-ins}).
// The file lists are read from the CMakeLists.txt of native/audacity/nyquist
// (set( RUNTIME ...)) and native/audacity/plug-ins (set( SOURCES ...)), i.e.
// exactly what Audacity 3.7.9 installs; build files and the test/sample
// sources next to them are not packaged. AssetInstaller extracts them to
// filesDir/audacity/ at runtime (API.md §2.1).
// ---------------------------------------------------------------------------
abstract class PackageAudacityAssetsTask : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val nyquistDir: DirectoryProperty

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val pluginsDir: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    private fun cmakeList(cmakeLists: File, variable: String): List<String> {
        val text = cmakeLists.readText()
        val match = Regex("""set\(\s*$variable\s+([^)]*)\)""").find(text)
            ?: throw GradleException("no set( $variable ...) in $cmakeLists")
        return match.groupValues[1].lines()
            .map { it.substringBefore('#').trim() }
            .flatMap { it.split(Regex("\\s+")) }
            .filter { it.isNotEmpty() }
    }

    private fun copyListed(srcDir: File, variable: String, dest: File): Int {
        val files = cmakeList(File(srcDir, "CMakeLists.txt"), variable)
        if (files.isEmpty()) throw GradleException("empty $variable list in $srcDir/CMakeLists.txt")
        for (name in files) {
            val src = File(srcDir, name)
            if (!src.isFile) throw GradleException("$src is listed in CMakeLists.txt but missing")
            src.copyTo(File(dest, name), overwrite = true)
        }
        return files.size
    }

    @TaskAction
    fun run() {
        val root = outputDir.get().asFile
        root.deleteRecursively()
        val base = File(root, "audacity")
        val n = copyListed(nyquistDir.get().asFile, "RUNTIME", File(base, "nyquist")) +
            copyListed(pluginsDir.get().asFile, "SOURCES", File(base, "plug-ins"))
        logger.info("packaged $n Audacity runtime files into $root")
    }
}

val packageAudacityAssets = tasks.register<PackageAudacityAssetsTask>("packageAudacityAssets") {
    nyquistDir.set(rootProject.layout.projectDirectory.dir("native/audacity/nyquist"))
    pluginsDir.set(rootProject.layout.projectDirectory.dir("native/audacity/plug-ins"))
    outputDir.set(layout.buildDirectory.dir("generated/audacityAssets"))
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(
            packageAudacityAssets,
            PackageAudacityAssetsTask::outputDir,
        )
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    api(libs.kotlinx.coroutines.android)
    api(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.kotlinx.coroutines.test)
}
