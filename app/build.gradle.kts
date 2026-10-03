import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

fun getSecret(name: String): String {
    // 1. Try .env in root directory
    val envFile = rootProject.file(".env")
    if (envFile.exists()) {
        val props = Properties()
        envFile.inputStream().use { props.load(it) }
        val key = props.getProperty(name)
        if (!key.isNullOrBlank()) return key.trim()
    }
    // 2. Try local.properties in root directory
    val localPropFile = rootProject.file("local.properties")
    if (localPropFile.exists()) {
        val props = Properties()
        localPropFile.inputStream().use { props.load(it) }
        val key = props.getProperty(name)
        if (!key.isNullOrBlank()) return key.trim()
    }
    // 3. Fallback to Gradle property or system environment variable
    return (project.findProperty(name) as? String)
        ?: System.getenv(name)
        ?: ""
}

android {
    namespace = "com.paa.assistant"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.paa.assistant"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Native libs only for real phones (arm64) and the emulator (x86_64) — keeps the APK small
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }

        // Gemini API Key – loaded from .env or local.properties
        buildConfigField(
            "String",
            "GEMINI_API_KEY",
            "\"${getSecret("GEMINI_API_KEY")}\""
        )
        // Tavily web-search key (free tier) – fallback when Gemini search grounding is unavailable
        buildConfigField(
            "String",
            "TAVILY_API_KEY",
            "\"${getSecret("TAVILY_API_KEY")}\""
        )
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isDebuggable = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    testOptions {
        // android.util.Log etc. return defaults in JVM unit tests instead of throwing
        unitTests.isReturnDefaultValues = true
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    // Core AndroidX
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    // Jetpack Compose BOM (manages all compose versions together)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    debugImplementation(libs.androidx.ui.tooling)

    // Jetpack Glance (Home Screen Widget)
    implementation(libs.androidx.glance.appwidget)
    implementation(libs.androidx.glance.material3)

    // Room Database
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // Hilt Dependency Injection
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)

    // Navigation
    implementation(libs.androidx.navigation.compose)

    // WorkManager (for background proactive tasks)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.hilt.work)
    ksp(libs.hilt.compiler.work)

    // Google Generative AI SDK (Gemini)
    implementation(libs.generativeai)

    // Kotlin Coroutines
    implementation(libs.kotlinx.coroutines.android)

    // DataStore (for user preferences and settings)
    implementation(libs.androidx.datastore.preferences)

    // Accompanist Permissions (easy runtime permission handling in Compose)
    implementation(libs.accompanist.permissions)

    // DocumentFile for Storage Access Framework (SAF) folder crawling
    implementation("androidx.documentfile:documentfile:1.0.1")

    // Google Play Services Location & Geofencing
    implementation(libs.play.services.location)

    // On-device LLM for private chat processing (model file pushed separately, not bundled)
    implementation(libs.mediapipe.tasks.genai)

    // Vosk offline speech recognition (English-India model bundled in assets/model-en-in)
    implementation("com.alphacephei:vosk-android:0.3.75")
    implementation("net.java.dev.jna:jna:5.18.1@aar")

    // Reading Kaggle's .tar.gz model downloads reliably
    implementation("org.apache.commons:commons-compress:1.28.0")

    // SQLCipher encrypted Room database
    implementation(libs.sqlcipher.android)
    implementation(libs.androidx.sqlite)

    // Unit tests
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")  // real org.json for JVM unit tests
}
