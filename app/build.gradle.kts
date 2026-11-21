plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
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
    testImplementation(libs.junit)
    // android.jar only stubs org.json; local unit tests need a real implementation.
    testImplementation(libs.org.json)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}

// gradle.lockfile pins what ships and what the tests run; the CI vulnerability
// scan reads it. Build tooling is covered by gradle/verification-metadata.xml.
configurations.matching { it.name.endsWith("RuntimeClasspath") }.configureEach {
    resolutionStrategy.activateDependencyLocking()
}
