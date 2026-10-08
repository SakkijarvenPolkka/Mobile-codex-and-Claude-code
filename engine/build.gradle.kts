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
// Nyquist runtime, plug-ins and the engine's translations as assets
// (assets/audacity/{nyquist,plug-ins,locale}).
// The file lists are read from the CMakeLists.txt of native/audacity/nyquist
// (set( RUNTIME ...)) and native/audacity/plug-ins (set( SOURCES ...)), i.e.
// exactly what Audacity 3.7.9 installs; build files and the test/sample
// sources next to them are not packaged. The gettext catalogs are the
// compiled native/audacity/locale/<lang>/LC_MESSAGES/*.mo (not the .po
// sources). AssetInstaller extracts them to filesDir/audacity/ at runtime
// (API.md §2.1).
// ---------------------------------------------------------------------------
abstract class PackageAudacityAssetsTask : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val nyquistDir: DirectoryProperty

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val pluginsDir: DirectoryProperty

    // native/audacity/locale: only the <lang>/LC_MESSAGES/<domain>.mo files are packaged
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val localeFiles: ConfigurableFileCollection

    @get:Internal
    abstract val localeDir: DirectoryProperty

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

    private fun copyCatalogs(localeRoot: File, dest: File): Int {
        var n = 0
        for (mo in localeFiles.files.sortedBy { it.path }) {
            val rel = mo.relativeTo(localeRoot).invariantSeparatorsPath
            // <lang>/LC_MESSAGES/<domain>.mo
            if (!Regex("""[A-Za-z0-9_@-]+/LC_MESSAGES/[^/]+\.mo""").matches(rel)) continue
            mo.copyTo(File(dest, rel), overwrite = true)
            n++
        }
        return n
    }

    @TaskAction
    fun run() {
        val root = outputDir.get().asFile
        root.deleteRecursively()
        val base = File(root, "audacity")
        val n = copyListed(nyquistDir.get().asFile, "RUNTIME", File(base, "nyquist")) +
            copyListed(pluginsDir.get().asFile, "SOURCES", File(base, "plug-ins"))
        val catalogs = copyCatalogs(localeDir.get().asFile, File(base, "locale"))
        logger.info("packaged $n Audacity runtime files and $catalogs catalogs into $root")
    }
}

val packageAudacityAssets = tasks.register<PackageAudacityAssetsTask>("packageAudacityAssets") {
    nyquistDir.set(rootProject.layout.projectDirectory.dir("native/audacity/nyquist"))
    pluginsDir.set(rootProject.layout.projectDirectory.dir("native/audacity/plug-ins"))
    val locale = rootProject.layout.projectDirectory.dir("native/audacity/locale")
    localeDir.set(locale)
    localeFiles.from(rootProject.fileTree(locale) { include("*/LC_MESSAGES/*.mo") })
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

// Host contract test of the typed facade (NativeContractHostTest): with
//   -Paudacity.hostJniLibrary=<libaudacity-jni.so> -Paudacity.hostContractTest=true
//   --tests '*NativeContractHostTest*'   (alone: the engine starts once per JVM)
if ((project.findProperty("audacity.hostContractTest") as String?)?.trim()?.toBoolean() == true) {
    tasks.withType<Test>().configureEach {
        systemProperty("audacity.hostContractTest", "true")
    }
}

// ---------------------------------------------------------------------------
// [jni] BEGIN -- owned by the JNI glue (native/jni/README.md); please keep
// other edits outside this block.
// Host end-to-end test NativeHostIntegrationTest: loads the host build of
// libaudacity-jni.so into the unit-test JVM. Skipped unless
//   -Paudacity.hostJniLibrary=<absolute path to libaudacity-jni.so>
// is given (e.g. $PWD/native/build-host/lib/libaudacity-jni.so); then the
// unit tests also run with -Xcheck:jni and are never up to date (the native
// library is not a tracked input).
// ---------------------------------------------------------------------------
val hostJniLibrary = (project.findProperty("audacity.hostJniLibrary") as String?)
    ?.trim()?.takeIf { it.isNotEmpty() }
if (hostJniLibrary != null) {
    tasks.withType<Test>().configureEach {
        systemProperty("audacity.jni.library", file(hostJniLibrary).absolutePath)
        jvmArgs("-Xcheck:jni")
        outputs.upToDateWhen { false }
        outputs.cacheIf { false }
    }
}
// [jni] END
