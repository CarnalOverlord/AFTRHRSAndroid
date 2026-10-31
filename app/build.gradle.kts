import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.gms.google-services")
}

val signingFile = rootProject.file("release-signing.properties")
val signing = Properties().apply { if (signingFile.exists()) signingFile.inputStream().use(::load) }

android {
    namespace = "za.co.drkflm.afterhours"
    compileSdk = 36

    defaultConfig {
        applicationId = "za.co.aftrhrs"
        minSdk = 26
        targetSdk = 36
        versionCode = 44
        versionName = "1.2.5"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true
    }

    signingConfigs {
        if (signingFile.exists()) create("release") {
            storeFile = rootProject.file(signing.getProperty("storeFile"))
            storePassword = signing.getProperty("storePassword")
            keyAlias = signing.getProperty("keyAlias")
            keyPassword = signing.getProperty("keyPassword")
            enableV1Signing = true
            enableV2Signing = true
            enableV3Signing = true
            enableV4Signing = true
        }
    }
    buildTypes {
        debug { /* Use the registered Firebase Android application ID for local testing. */ }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (signingFile.exists()) signingConfig = signingConfigs.getByName("release")
        }
    }
    buildFeatures { compose = true; buildConfig = true }
    packaging.resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    // 2025.08 is the newest stable BOM compatible with the installed API 36 / AGP 8 toolchain.
    val composeBom = platform("androidx.compose:compose-bom:2025.08.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.fragment:fragment-ktx:1.8.9")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.navigation:navigation-compose:2.9.5")
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("androidx.security:security-crypto:1.1.0")
    implementation("androidx.datastore:datastore-preferences:1.1.7")
    implementation("androidx.camera:camera-camera2:1.5.0")
    implementation("androidx.camera:camera-lifecycle:1.5.0")
    implementation("androidx.camera:camera-view:1.5.0")
    implementation("com.google.android.gms:play-services-location:21.3.0")
    implementation(platform("com.google.firebase:firebase-bom:35.1.0"))
    implementation("com.google.firebase:firebase-messaging")
    implementation("io.socket:socket.io-client:2.1.1")
    implementation("io.ktor:ktor-client-core:3.2.3")
    implementation("io.ktor:ktor-client-okhttp:3.2.3")
    implementation("io.ktor:ktor-client-content-negotiation:3.2.3")
    implementation("io.ktor:ktor-serialization-kotlinx-json:3.2.3")
    implementation("io.coil-kt.coil3:coil-compose:3.3.0")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.3.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    testImplementation("junit:junit:4.13.2")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
