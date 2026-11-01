// Pure DSP: no Android, no I/O frameworks, no serialization. Deterministic.
plugins {
    alias(libs.plugins.kotlin.jvm)
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
    testImplementation(kotlin("test"))
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
    // Opt-in regeneration of docs/validation-synthetic.md (see ValidationReportTest).
    System.getProperty("klang.writeReport")?.let { systemProperty("klang.writeReport", it) }
    // Opt-in diagnostics (see LowBandBiasDiagnosticTest).
    listOf("klang.diagnostics", "klang.diagnosticSeeds").forEach { k -> System.getProperty(k)?.let { systemProperty(k, it) } }
    systemProperty("klang.reportPath", rootProject.file("../docs/validation-synthetic.md").absolutePath)
}
