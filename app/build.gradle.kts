plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "com.example.allrecorder"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.allrecorder"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions{
        jvmTarget = "11"
    }
    buildFeatures {
        viewBinding = true
        compose = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.9.4"
    }
    packaging {
        jniLibs {
            // In case of duplicate .so files, pick the first one found.
            pickFirsts += "lib/arm64-v8a/libonnxruntime.so"
            pickFirsts += "lib/armeabi-v7a/libonnxruntime.so"
            pickFirsts += "lib/x86/libonnxruntime.so"
            pickFirsts += "lib/x86_64/libonnxruntime.so"
        }
    }
}



dependencies {

    implementation(libs.androidx.core.ktx)
    // Use activity-compose for setContent
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.benchmark.traceprocessor)

    // Implement the Compose BOM
    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    androidTestImplementation(composeBom)

    // Add the specific Compose libraries we need
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.runtime:runtime-livedata")

    // --- Lifecycle & Navigation ---
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)


    implementation(libs.material)


    // --- Database & Worker ---
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.work.runtime.ktx)

    // --- Coroutines ---
    implementation(libs.kotlinx.coroutines.android)
    implementation(files("libs/sherpa-onnx-1.12.15.aar"))

    // --- ML Libs ---
//    implementation("com.github.gkonovalov.android-vad:silero:2.0.10")
    implementation(libs.tensorflow.lite)
//    implementation(libs.tensorflow.lite.support)
//    implementation(libs.onnxruntime.android)
//    implementation(libs.pytorch.android)
//    implementation(libs.pytorch.android.torchvision)
    implementation(libs.jtransforms)

    // --- Other ---
    implementation(libs.mediapipe.tasks.text)
    implementation(libs.gson)
    implementation(libs.androidx.preference.ktx)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // --- Testing ---
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}