plugins {
    id("com.android.application")
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
        versionCode = 4
        versionName = "0.2.0"
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

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    // Android's org.json is a stub in local unit tests; this is the real implementation.
    testImplementation("org.json:json:20240303")
}
