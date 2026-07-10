plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.healthguard.core.ui"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        minSdk = 24
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    // api: the shared components' public signatures expose domain types
    // (DayDetail, RecordedTake, Frequency, …), Compose types (Modifier,
    // SnackbarHostState, Color), and kotlinx-datetime types (LocalDate,
    // TimeZone), so consumers need them on their compile classpath.
    api(project(":core:domain"))
    api(platform(libs.androidx.compose.bom))
    api(libs.androidx.compose.ui)
    api(libs.androidx.compose.ui.graphics)
    api(libs.androidx.compose.material3)
    api(libs.kotlinx.datetime)
    implementation(libs.androidx.compose.material.icons.core)
    testImplementation(libs.junit)
}
