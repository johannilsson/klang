// Domain model, file-based persistence, comparison and export.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
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
    api(project(":dsp"))
    api(libs.kotlinx.serialization.json)

    testImplementation(kotlin("test"))
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
    // ReferenceProfilesTest checks the shipped built-in profiles against docs/reference-profiles.md.
    val docs = rootProject.projectDir.resolve("../docs")
    systemProperty("klang.docsDir", docs.absolutePath)
    inputs.dir(docs).withPropertyName("docs").withPathSensitivity(PathSensitivity.RELATIVE)
}
