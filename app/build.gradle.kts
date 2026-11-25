plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("io.github.takahirom.roborazzi")
}

android {
    namespace = "ai.pathshala.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "ai.pathshala.app"
        minSdk = 28
        targetSdk = 35
        versionCode = 1
        versionName = "1.0-phase1"
        // Arm-only build: the product targets Arm smartphones (arm64-v8a).
        // x86_64 is kept so the UI can be exercised on an emulator in template mode.
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    testOptions { unitTests { isIncludeAndroidResources = true } }
    packaging {
        jniLibs { useLegacyPackaging = true }
    }
}

dependencies {
    implementation(project(":core"))

    // ExecuTorch Android runtime (LLM runner + XNNPACK/KleidiAI CPU backend), verified against 1.5.1 API
    implementation("org.pytorch:executorch-android:1.5.1")
    implementation("com.facebook.fbjni:fbjni:0.7.0")
    implementation("com.facebook.soloader:nativeloader:0.10.5")

    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.core:core-ktx:1.13.1")

    // Off-device screenshots of the real UI (template mode) for docs/video: ./gradlew :app:recordRoborazziDebug
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.13")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    testImplementation("io.github.takahirom.roborazzi:roborazzi:1.32.2")
    testImplementation("io.github.takahirom.roborazzi:roborazzi-compose:1.32.2")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
