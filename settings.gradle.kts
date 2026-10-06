pluginManagement {
    repositories {
        google()
        mavenCentral()
        // Google's mirror of Maven Central, used as a fallback when
        // repo.maven.apache.org rate-limits (HTTP 429).
        maven("https://maven-central.storage-download.googleapis.com/maven2/")
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven("https://maven-central.storage-download.googleapis.com/maven2/")
    }
}

rootProject.name = "AudacityAndroid"
include(":app")
