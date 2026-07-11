import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.healthguard"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.healthguard"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            // Overridable per developer machine without touching the repo:
            // set healthguard.proxyBaseUrl in local.properties (e.g. the Mac's
            // LAN IP for a Wi-Fi device, or 127.0.0.1 with adb reverse).
            val localProperties = Properties().apply {
                val file = rootProject.file("local.properties")
                if (file.exists()) file.inputStream().use { load(it) }
            }
            val proxyBaseUrl =
                localProperties.getProperty("healthguard.proxyBaseUrl") ?: "http://10.0.2.2:8787"
            buildConfigField("String", "PROXY_BASE_URL", "\"$proxyBaseUrl\"")
        }
        release {
            optimization {
                enable = false
            }
            buildConfigField(
                "String",
                "PROXY_BASE_URL",
                "\"https://healthguard-proxy.example.workers.dev\"",
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
}

dependencies {
    implementation(project(":core:domain"))
    implementation(project(":core:data"))
    implementation(project(":core:ui"))
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    // Play/Delete list icons; material3 no longer brings the icon set along.
    implementation(libs.androidx.compose.material.icons.core)
    // Camera/assistant glyphs; R8 strips the unused rest in release builds.
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    // collectAsStateWithLifecycle/repeatOnLifecycle collection in the shell.
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    // TimeZone for next-dose countdowns; shared keeps it as an
    // implementation detail so the app declares its own dependency.
    implementation(libs.kotlinx.datetime)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.koin.android)
    implementation(libs.koin.androidx.compose)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    // In-memory SQLite for exercising the real MedicationRepository in unit tests.
    testImplementation(libs.sqldelight.sqlite.driver)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}