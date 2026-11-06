plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.example.screentranslator"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.example.screentranslator"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("com.google.mlkit:text-recognition:16.0.1")   // OCR
    implementation("com.google.mlkit:language-id:17.0.6")        // deteksi bahasa
    implementation("com.google.mlkit:translate:17.0.3")          // Google Translate (on-device)
}
