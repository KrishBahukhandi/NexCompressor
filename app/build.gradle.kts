plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.nexcompress.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.nexcompress.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }

        // --- Online conversion service config ---
        // Supply these via ~/.gradle/gradle.properties or -P flags (keep secrets out of VCS):
        //   CONVERT_API_KEY=your_key      CONVERT_BASE_URL=https://v2.convertapi.com
        // Leave CONVERT_API_KEY empty to run the online conversions in built-in DEMO mode.
        val convertApiKey = (project.findProperty("CONVERT_API_KEY") as? String).orEmpty()
        val convertBaseUrl = (project.findProperty("CONVERT_BASE_URL") as? String)
            ?: "https://v2.convertapi.com"
        buildConfigField("String", "ONLINE_CONVERT_API_KEY", "\"$convertApiKey\"")
        buildConfigField("String", "ONLINE_CONVERT_BASE_URL", "\"$convertBaseUrl\"")
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
        debug {
            applicationIdSuffix = ".debug"
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    // Core + lifecycle
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    // Compose
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)

    // Drag-to-reorder support for the image batch / PDF page editors
    implementation(libs.reorderable)

    // PDFBox-Android (tom_roush) — lossless on-device PDF editing engine:
    // page reorder/rotate/delete, merge, split, password protect, signature overlay.
    // Apache-2.0, fully offline — performs NO network I/O.
    implementation(libs.pdfbox.android)

    // Coroutines (heavy multi-threaded file tasks)
    implementation(libs.kotlinx.coroutines.android)

    // Room (local caching ledger)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // Google Mobile Ads (AdMob) — the ONLY networked dependency in the app.
    implementation(libs.play.services.ads)

    // Tooling
    debugImplementation(libs.androidx.ui.tooling)
}
