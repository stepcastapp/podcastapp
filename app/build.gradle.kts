plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.stepcast.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.stepcast.app"
        minSdk = 26
        // Play requirement (2026): new releases must target Android 16
        targetSdk = 36
        // bump versionCode on every meaningful cut; versionName tracks the
        // feature era (0.2 = post-review-program daily driver). The Play
        // release workflow overrides both per upload via -P properties.
        versionCode = (project.findProperty("stepcastVersionCode") as String?)
            ?.toInt() ?: 2
        versionName = (project.findProperty("stepcastVersionName") as String?)
            ?: "0.2.0"
        // Podcast Index directory credentials (optional; empty = off). From
        // -PpodcastIndexKey/-PpodcastIndexSecret or the environment, e.g.
        // GitHub secrets PODCASTINDEX_KEY / PODCASTINDEX_SECRET in CI.
        fun cred(prop: String, env: String): String =
            ((project.findProperty(prop) as String?) ?: System.getenv(env) ?: "")
                .replace("\"", "")
        buildConfigField("String", "PODCASTINDEX_KEY", "\"${cred("podcastIndexKey", "PODCASTINDEX_KEY")}\"")
        buildConfigField(
            "String", "PODCASTINDEX_SECRET",
            "\"${cred("podcastIndexSecret", "PODCASTINDEX_SECRET")}\""
        )
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
    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all { test ->
            // optional mirror for Robolectric's runtime jars (-ProbolectricRepo=…)
            // when Maven Central rate-limits a local/agent environment
            (project.findProperty("robolectricRepo") as String?)?.let {
                test.systemProperty("robolectric.dependency.repo.url", it)
            }
        }
    }
    // migration tests read the exported schema JSONs as assets. Robolectric
    // unit tests only see the variant's merged assets (test-source assets
    // aren't merged), so they ride in the DEBUG variant — a few KB of JSON
    // that never reaches the release APK.
    sourceSets {
        getByName("debug").assets.srcDir("$projectDir/schemas")
    }
    buildFeatures {
        compose = true
        // BuildConfig.DEBUG gates the destructive-migration fallback
        buildConfig = true
    }
}

// Room writes each schema version's JSON here (committed) — the input the
// migration tests validate real upgrades against
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
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
    implementation(libs.androidx.media3.datasource.okhttp)
    implementation(libs.androidx.media3.database)
    // Chromecast: CastPlayer + the Cast framework (Google Play services)
    implementation(libs.androidx.media3.cast)
    // the Cast route dialogs (already pulled in by the Cast framework;
    // declared so the Cast button can reference them)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.mediarouter)
    implementation(libs.coil.compose)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.coroutines.guava)
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.documentfile)
    implementation(libs.androidx.glance.appwidget)
    // applies the libraries' (Compose, Media3…) baseline profiles on
    // sideloaded installs too, not just Play's cloud profiles — faster cold
    // start and less jank on first runs
    implementation(libs.androidx.profileinstaller)
    // drag-to-reorder for the queue (see QueueScreen)
    implementation(libs.reorderable)
    testImplementation(libs.junit)
    // JVM Android runtime for tests that need real SQLite / XmlPullParser:
    // migration tests, the RSS parser, the refresh merge logic
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.room.testing)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.androidx.work.testing)
}
