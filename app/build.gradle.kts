plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "org.cmu.gemmavision2"
    compileSdk = 35

    defaultConfig {
        applicationId = "org.cmu.gemmavision2"
        // minSdk = 31 (Android 12) is the floor for AICore + AI Edge.
        // Any device with the RAM/NPU to run Gemma 4 multimodal is on
        // Android 12+ in 2026 anyway, so the loss of older devices is moot.
        minSdk = 31
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        // Pulled from local.properties or CI env. Picovoice issues per-app keys.
        buildConfigField(
            "String",
            "PORCUPINE_ACCESS_KEY",
            "\"${project.findProperty("PORCUPINE_ACCESS_KEY") ?: ""}\""
        )
        buildConfigField(
            "String",
            "OPENFOODFACTS_USER_AGENT",
            "\"GemmaVision2/0.1 (research; cmu)\""
        )

        vectorDrawables { useSupportLibrary = true }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    sourceSets {
        named("main") {
            java.srcDirs("src/main/kotlin")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // Models (.litertlm / .gguf) live in assets — keep them uncompressed
    // so MediaPipe can mmap directly from APK without extraction.
    androidResources {
        noCompress.addAll(listOf("litertlm", "gguf", "task", "ppn", "onnx"))
    }

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/DEPENDENCIES",
            )
        }
    }
}

dependencies {
    // ── Inference: LiteRT-LM (the official .litertlm runtime for Gemma 4) ──
    // This is the same Maven artifact used by the Google AI Edge Gallery
    // reference app (com.google.ai.edge.gallery on Play Store).
    // MediaPipe Tasks GenAI is intentionally NOT in the classpath — it
    // only loads the legacy .task bundles, which Google hasn't published
    // for Gemma 4. See docs/04-build-and-run.md.
    implementation(libs.litertlm)

    // ── ML Kit classical pipelines (OCR reconciliation, translation, barcodes) ──
    implementation(libs.mlkit.text.recognition)
    implementation(libs.mlkit.translate)
    implementation(libs.mlkit.language.id)
    implementation(libs.mlkit.barcode)

    // ── Camera ──
    implementation(libs.camera.core)
    implementation(libs.camera.camera2)
    implementation(libs.camera.lifecycle)
    implementation(libs.camera.view)

    // ── Wake-word ──
    implementation(libs.porcupine)

    // ── TTS: Android TextToSpeech is the primary path (no extra dep).
    // sherpa-onnx / Piper INT8 is a v2.1 polish item — when added it must be
    // vendored as a local .aar from https://github.com/k2-fsa/sherpa-onnx/releases
    // since k2-fsa does not publish to Maven Central.

    // ── Coroutines ──
    implementation(libs.coroutines.core)
    implementation(libs.coroutines.android)

    // ── JSON ──
    implementation(libs.moshi)
    implementation(libs.moshi.kotlin)

    // ── Network (function-call tool dispatchers) ──
    implementation(libs.okhttp)

    // ── Compose UI ──
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.material3)
    implementation(libs.compose.ui)
    implementation(libs.compose.tooling.preview)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.ktx)
}
