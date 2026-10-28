import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}
val componentVersion = Properties().apply {
    rootProject.file("../versions/android.properties").inputStream().use { load(it) }
}
val androidVersion = componentVersion.getProperty("version")
require(androidVersion != null && androidVersion.matches(Regex("(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)"))) {
    "versions/android.properties must contain a stable MAJOR.MINOR.PATCH version"
}
val androidVersionCode = componentVersion.getProperty("versionCode")?.toIntOrNull()
require(androidVersionCode != null && androidVersionCode in 1..2100000000) {
    "versions/android.properties must contain versionCode in 1..2100000000"
}
android {
    namespace = "dev.ignorantshuffle.companion"
    compileSdk = 36
    ndkVersion = "28.2.13676358"
    defaultConfig {
        applicationId = "dev.ignorantshuffle.android"
        minSdk = 26
        targetSdk = 36
        versionCode = androidVersionCode
        versionName = androidVersion
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
        externalNativeBuild { cmake { cppFlags += "-std=c++17" } }
    }
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" } }
    buildFeatures { compose = true; buildConfig = true }
    sourceSets.getByName("test").resources.srcDir(rootProject.file("../tests/fixtures"))
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
dependencies {
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation(platform("androidx.compose:compose-bom:2026.02.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
