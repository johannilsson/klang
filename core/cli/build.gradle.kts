// Desktop analysis of imported WAV files, for validation against reference tools (e.g. REW).
plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

// Target Java 17 bytecode (Android-compatible) without requiring a JDK 17 toolchain.
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":sessions"))
}

application {
    mainClass.set("se.klang.cli.MainKt")
    applicationName = "klang-analyse"
    applicationDefaultJvmArgs = listOf("-Dstdout.encoding=UTF-8", "-Dfile.encoding=UTF-8")
}
