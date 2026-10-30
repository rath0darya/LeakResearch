plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val releaseStoreFile = providers.environmentVariable("LEAKRESEARCH_KEYSTORE_PATH").orNull
val releaseStorePassword = providers.environmentVariable("LEAKRESEARCH_KEYSTORE_PASSWORD").orNull
val releaseKeyAlias = providers.environmentVariable("LEAKRESEARCH_KEY_ALIAS").orNull
val releaseKeyPassword = providers.environmentVariable("LEAKRESEARCH_KEY_PASSWORD").orNull

android {
    namespace = "com.leakresearch"
    compileSdk = 35

    defaultConfig {
        // Keep this ID unchanged: it is the identity Android uses for app updates.
        applicationId = "com.leakresearch"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "1.0.1"
    }

    signingConfigs {
        create("release") {
            if (!releaseStoreFile.isNullOrBlank()) {
                storeFile = file(releaseStoreFile)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        getByName("release") {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = false
        }
    }

    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

tasks.configureEach {
    if (name == "validateSigningRelease") {
        doFirst {
            check(!releaseStoreFile.isNullOrBlank() &&
                !releaseStorePassword.isNullOrBlank() &&
                !releaseKeyAlias.isNullOrBlank() &&
                !releaseKeyPassword.isNullOrBlank()) {
                "Release signing secrets are missing. Configure LEAKRESEARCH_KEYSTORE_PATH, LEAKRESEARCH_KEYSTORE_PASSWORD, LEAKRESEARCH_KEY_ALIAS and LEAKRESEARCH_KEY_PASSWORD."
            }
        }
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.fragment:fragment-ktx:1.8.5")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.biometric:biometric:1.2.0-alpha05")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
