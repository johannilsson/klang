import java.util.Base64

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Shared signing key (see docs/signing.md). Android only installs an update over an installed app when both
// are signed with the same key, and the default debug key is generated per machine, so builds from CI and from
// other machines could not upgrade each other. The key comes from the environment (GitHub secrets in CI, the
// environment's variables in the cloud sandbox); it is never in the repository. Without it (forks, pull
// requests from forks, a fresh checkout) the build falls back to the default debug key and says so.
val signingEnv = listOf("KLANG_KEYSTORE_BASE64", "KLANG_KEYSTORE_PASSWORD", "KLANG_KEY_ALIAS", "KLANG_KEY_PASSWORD")
    .associateWith { providers.environmentVariable(it).orNull?.trim().orEmpty() }
val sharedKeystore: File? = run {
    val present = signingEnv.filterValues { it.isNotEmpty() }.keys
    when {
        present.isEmpty() -> null
        present.size < signingEnv.size -> throw GradleException(
            "Incomplete signing configuration: missing ${(signingEnv.keys - present).joinToString()}. Set all of " +
                "${signingEnv.keys.joinToString()} or none (docs/signing.md); a partial set would silently produce a " +
                "build that cannot upgrade the installed app.",
        )
        else -> layout.buildDirectory.file("signing/klang-shared.jks").get().asFile.also { f ->
            f.parentFile.mkdirs()
            f.writeBytes(
                try {
                    Base64.getMimeDecoder().decode(signingEnv.getValue("KLANG_KEYSTORE_BASE64"))
                } catch (e: IllegalArgumentException) {
                    throw GradleException("KLANG_KEYSTORE_BASE64 is not valid base64 (docs/signing.md).", e)
                },
            )
        }
    }
}
logger.lifecycle(
    if (sharedKeystore != null) "Signing with the shared Klang key (alias ${signingEnv.getValue("KLANG_KEY_ALIAS")})."
    else "No shared signing key configured: using the default debug key. Builds will not upgrade a build signed with another key.",
)

android {
    namespace = "se.klang.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "se.klang.app"
        // 29: AudioRecord.getActiveRecordingConfiguration() and isClientSilenced() for capture diagnostics.
        minSdk = 29
        targetSdk = 37
        versionCode = 5
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (sharedKeystore != null) {
            create("shared") {
                storeFile = sharedKeystore
                storePassword = signingEnv.getValue("KLANG_KEYSTORE_PASSWORD")
                keyAlias = signingEnv.getValue("KLANG_KEY_ALIAS")
                keyPassword = signingEnv.getValue("KLANG_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            // The debug build is the one that is sideloaded, so it carries the shared key too.
            signingConfigs.findByName("shared")?.let { signingConfig = it }
        }
        release {
            signingConfigs.findByName("shared")?.let { signingConfig = it }
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    testOptions {
        unitTests {
            // Robolectric and Compose UI tests on the JVM need the merged resources and manifest.
            isIncludeAndroidResources = true
            all {
                // Robolectric's SDK 36 runtime sets FileDescriptor internals through jdk.internal.access.
                it.jvmArgs("--add-opens=java.base/jdk.internal.access=ALL-UNNAMED")
                // ScreenshotsTest renders the screens to PNGs for design review: only when asked (scripts/screens.sh
                // sets KLANG_SCREENSHOTS), so it does not slow down the normal test run.
                if (System.getenv("KLANG_SCREENSHOTS").isNullOrEmpty()) it.exclude("**/ScreenshotsTest*")
                // Robolectric downloads its android-all runtime (about 200 MB) itself, from Maven Central by
                // default; use the same mirror as the build (settings.gradle.kts) to avoid HTTP 429.
                it.systemProperty("robolectric.dependency.repo.url", "https://maven-central.storage-download.googleapis.com/maven2/")
                it.systemProperty("robolectric.dependency.repo.id", "MavenCentralGoogleMirror")
            }
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // Pure Kotlin core from the included build in ./core
    implementation("se.klang.core:sessions")
    implementation("se.klang.core:dsp")

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.core)
    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.ui.test.junit4)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}
