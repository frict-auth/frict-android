plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.google.services)
}

// Release signing and version come from the release workflow; see docs/release.md.
fun env(name: String): String? = System.getenv(name)?.takeIf(String::isNotEmpty)

val appVersionName = env("VERSION") ?: "0.0.1"
val appVersionCode = appVersionName.split(".").map(String::toInt)
    .let { (major, minor, patch) -> major * 1_000_000 + minor * 1_000 + patch }

android {
    namespace = "app.frict.android"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "app.frict.android"
        minSdk = 35
        targetSdk = 37
        versionCode = appVersionCode
        versionName = appVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "PRIVACY_POLICY_URL", "\"https://privacy.frict.app/\"")
    }

    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        create("release") {
            storeFile = env("KEYSTORE_FILE")?.let(::file)
            storePassword = env("KEYSTORE_PASSWORD")
            keyAlias = env("KEY_ALIAS")
            keyPassword = env("KEY_PASSWORD")
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".dev"
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    lint {
        warningsAsErrors = true
        abortOnError = true
        disable += setOf("AndroidGradlePluginVersion", "GradleDependency", "NewerVersionAvailable")
        informational += "OldTargetApi"
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.compose)
    implementation(libs.zxing.core)
    testImplementation(libs.junit)
    // android.jar only stubs org.json; local unit tests need a real implementation.
    testImplementation(libs.org.json)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.junit)
    // Compose UI tests bring espresso-core 3.5.0, which reflects on
    // InputManager.getInstance, gone in Android 17.
    androidTestImplementation(libs.androidx.espresso.core)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}

// gradle.lockfile pins what ships and what the tests run; the CI vulnerability
// scan reads it. Build tooling is covered by gradle/verification-metadata.xml.
configurations.matching { it.name.endsWith("RuntimeClasspath") }.configureEach {
    resolutionStrategy.activateDependencyLocking()
    // Only kotlin-stdlib's metadata names it, as a constraint, and --write-locks leaves it
    // out; checking the lock then resolves it and fails. Its classes are in kotlin-stdlib.
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib-common")
}
