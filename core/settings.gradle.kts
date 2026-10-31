// Platform-independent core: DSP, session model/persistence/export, CLI.
// Builds and tests on any JVM without the Android SDK:
//   ./gradlew -p core test
pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
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
