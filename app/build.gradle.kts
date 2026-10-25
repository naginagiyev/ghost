import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use(::load)
}

fun signingValue(name: String): String? = System.getenv(name) ?: keystoreProperties.getProperty(name)

android {
    namespace = "app.ghost"
    compileSdk = 37

    defaultConfig {
        applicationId = "app.ghost"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0.0"
        ndk { abiFilters += "arm64-v8a" }
    }

    signingConfigs {
        create("release") {
            signingValue("GHOST_KEYSTORE")?.let { storeFile = file(it) }
            storePassword = signingValue("GHOST_KEYSTORE_PASSWORD")
            keyAlias = signingValue("GHOST_KEY_ALIAS")
            keyPassword = signingValue("GHOST_KEY_PASSWORD")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            val release = signingConfigs.getByName("release")
            signingConfig = if (release.storeFile != null) release else signingConfigs.getByName("debug")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.foundation)
    implementation(libs.compose.ui)
    implementation(libs.compose.runtime)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.process)
    implementation(libs.room.runtime)
    implementation(libs.litertlm)
    implementation(libs.pdfbox) { exclude(group = "org.bouncycastle") }
    ksp(libs.room.compiler)
    testImplementation(libs.junit)
}
