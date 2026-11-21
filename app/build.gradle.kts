plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.google.services)
}

android {
    namespace = "app.frict.android"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "app.frict.android"
        minSdk = 35
        targetSdk = 37
        versionCode = 1
        versionName = "0.0.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".dev"
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
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

// Only the debug build has a google-services.json (the dev Firebase project) until the
// release project exists; a release build without one runs poll-only.
googleServices {
    missingGoogleServicesStrategy = com.google.gms.googleservices.GoogleServicesPlugin.MissingGoogleServicesStrategy.WARN
}

// gradle.lockfile pins what ships and what the tests run; the CI vulnerability
// scan reads it. Build tooling is covered by gradle/verification-metadata.xml.
configurations.matching { it.name.endsWith("RuntimeClasspath") }.configureEach {
    resolutionStrategy.activateDependencyLocking()
    // Only kotlin-stdlib's metadata names it, as a constraint, and --write-locks leaves it
    // out; checking the lock then resolves it and fails. Its classes are in kotlin-stdlib.
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib-common")
}
