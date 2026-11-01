pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        // Google's public mirror of Maven Central first: Maven Central often answers shared CI and
        // sandbox egress with HTTP 429. Same artifacts; mavenCentral() stays as the fallback for
        // releases the mirror has not picked up yet.
        maven("https://maven-central.storage-download.googleapis.com/maven2/") { name = "MavenCentralGoogleMirror" }
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        // Google's public mirror of Maven Central first: Maven Central often answers shared CI and
        // sandbox egress with HTTP 429. Same artifacts; mavenCentral() stays as the fallback for
        // releases the mirror has not picked up yet.
        maven("https://maven-central.storage-download.googleapis.com/maven2/") { name = "MavenCentralGoogleMirror" }
        mavenCentral()
    }
}

rootProject.name = "Klang"
include(":app")

// Platform-independent DSP, session model, comparison and export. Also builds on its own
// (no Android SDK needed): ./gradlew -p core test
includeBuild("core")
