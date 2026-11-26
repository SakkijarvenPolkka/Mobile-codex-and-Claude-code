pluginManagement {
    repositories {
        google()
        // Google's mirror of Maven Central first: the main Maven Central host can answer
        // "429 Too Many Requests" to parallel resolution, and Gradle then fails instead of
        // falling through to the next repository.
        maven("https://maven-central.storage-download.googleapis.com/maven2/")
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        maven("https://maven-central.storage-download.googleapis.com/maven2/")
        mavenCentral()
    }
}

rootProject.name = "trio-android"
include(":core", ":app")
