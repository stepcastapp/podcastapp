plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.nsavage.stepcast"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.nsavage.stepcast"
        minSdk = 26
        targetSdk = 35
        // bump versionCode on every meaningful cut; versionName tracks the
        // feature era (0.2 = post-review-program daily driver). The Play
        // release workflow overrides both per upload via -P properties.
        versionCode = (project.findProperty("stepcastVersionCode") as String?)
            ?.toInt() ?: 2
        versionName = (project.findProperty("stepcastVersionName") as String?)
            ?: "0.2.0"
    }

    // Committed convenience key so every CI build (ephemeral runners!) signs
    // identically and installs update over update. NOT for Play submission —
    // the "play" config below carries the real upload key.
    signingConfigs {
        create("shared") {
            storeFile = rootProject.file("stepcast-debug.keystore")
            storePassword = "skipcast123" // legacy value baked into the keystore
            keyAlias = "skipcast" // legacy alias baked into the keystore
            keyPassword = "skipcast123"
        }
        // Play upload key, injected via environment (GitHub Actions secrets
        // in the play-release workflow, or a local shell) — NEVER committed.
        // Only materializes when the env is present, so every ordinary build
        // silently keeps the shared key.
        val uploadStore = System.getenv("STEPCAST_UPLOAD_KEYSTORE")
        if (!uploadStore.isNullOrBlank()) {
            create("play") {
                storeFile = file(uploadStore)
                storePassword = System.getenv("STEPCAST_UPLOAD_STORE_PASSWORD")
                keyAlias = System.getenv("STEPCAST_UPLOAD_KEY_ALIAS")
                    ?: "stepcast-upload"
                keyPassword = System.getenv("STEPCAST_UPLOAD_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("shared")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // play key when the env provides one, shared key otherwise
            signingConfig = signingConfigs.findByName("play")
                ?: signingConfigs.getByName("shared")
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
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.session)
    implementation(libs.coil.compose)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.coroutines.guava)
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.documentfile)
    implementation(libs.androidx.glance.appwidget)
    testImplementation(libs.junit)
}
