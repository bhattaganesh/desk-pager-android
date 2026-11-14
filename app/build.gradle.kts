plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Release signing comes from ~/.gradle/gradle.properties so no key material lives in the repo.
val keystorePath = providers.gradleProperty("DESKPAGER_KEYSTORE").orNull

android {
    namespace = "np.com.ganeshbhatt.deskpager"
    compileSdk = 36

    defaultConfig {
        applicationId = "np.com.ganeshbhatt.deskpager"
        minSdk = 26
        targetSdk = 36
        versionCode = 9
        versionName = "0.5.0"
    }

    signingConfigs {
        if (keystorePath != null) {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = providers.gradleProperty("DESKPAGER_KEYSTORE_PASSWORD").get()
                keyAlias = providers.gradleProperty("DESKPAGER_KEY_ALIAS").get()
                keyPassword = providers.gradleProperty("DESKPAGER_KEY_PASSWORD").get()
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    // 2026.06.01 (Compose 1.11) is the newest BOM that still builds against compileSdk 36; 2026.08+ needs 37.
    implementation(platform("androidx.compose:compose-bom:2026.06.01"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.13.0")
    // In-app QR scanning through Google Play Services, so the app needs no camera permission.
    implementation("com.google.android.gms:play-services-code-scanner:16.1.0")
    testImplementation("junit:junit:4.13.2")
    // Android's org.json is a stub in local unit tests; this is the real implementation.
    testImplementation("org.json:json:20240303")
}
