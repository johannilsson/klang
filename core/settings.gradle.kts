// Platform-independent core: DSP, session model/persistence/export, CLI.
// Builds and tests on any JVM without the Android SDK:
//   ./gradlew -p core test
pluginManagement {
    repositories {
        // Google's public mirror of Maven Central first: Maven Central often answers shared CI and
        // sandbox egress with HTTP 429. Same artifacts; mavenCentral() stays as the fallback for
        // releases the mirror has not picked up yet. Before the plugin portal too, which redirects
        // most plugin artifacts to Maven Central (a 429 fails the build instead of falling through).
        maven("https://maven-central.storage-download.googleapis.com/maven2/") { name = "MavenCentralGoogleMirror" }
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        // Google's public mirror of Maven Central first: Maven Central often answers shared CI and
        // sandbox egress with HTTP 429. Same artifacts; mavenCentral() stays as the fallback for
        // releases the mirror has not picked up yet.
        maven("https://maven-central.storage-download.googleapis.com/maven2/") { name = "MavenCentralGoogleMirror" }
        mavenCentral()
    }
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}

rootProject.name = "klang-core"
include(":dsp", ":sessions", ":cli")
