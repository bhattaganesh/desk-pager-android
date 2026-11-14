plugins {
    id("com.android.application") version "9.5.1" apply false
    // Must equal the Kotlin version AGP bundles (see `gradlew buildEnvironment`).
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.10" apply false
}
