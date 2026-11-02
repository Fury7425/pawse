plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "app.pawse.core.model"
    compileSdk = 36

    defaultConfig {
        minSdk = 28
        // Phones only. The native library needs ARMv8.2 with dot product, which
        // Llama checks for before loading it.
        ndk { abiFilters += "arm64-v8a" }
        externalNativeBuild {
            // Release even in debug builds: unoptimised llama.cpp is unusably slow.
            cmake { arguments += "-DCMAKE_BUILD_TYPE=Release" }
        }
    }

    externalNativeBuild {
        cmake { path("src/main/cpp/CMakeLists.txt") }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin { jvmToolchain(17) }

dependencies {
    // NumberCheck, Fact and tr(): the model words numbers, the engine owns them.
    implementation(project(":core-scoring"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.work.runtime.ktx)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    // Gemini Nano through AICore, used instead of the download on phones that already have it.
    implementation(libs.mlkit.genai.prompt)

    testImplementation(libs.junit)
    testImplementation(libs.kotest.assertions)
}
