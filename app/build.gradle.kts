import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Release builds take their version from the tag (CI sets these).
val versionNameProp = (findProperty("versionName") as String?) ?: "0.1.0-dev"
val versionCodeProp = (findProperty("versionCode") as String?)?.toIntOrNull() ?: 1

// Signing: keystore.properties locally, or environment variables in CI.
val keystoreProps = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.isFile) file.inputStream().use { load(it) }
}
fun signing(name: String): String? = System.getenv("ROMSTORE_$name") ?: keystoreProps.getProperty(name)

android {
    namespace = "io.github.uposum.romstore"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.uposum.romstore"
        minSdk = 26
        targetSdk = 36
        versionCode = versionCodeProp
        versionName = versionNameProp
    }

    signingConfigs {
        create("release") {
            val store = signing("STORE_FILE")
            if (store != null && file(store).isFile) {
                storeFile = file(store)
                storePassword = signing("STORE_PASSWORD")
                keyAlias = signing("KEY_ALIAS")
                keyPassword = signing("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            val release = signingConfigs.getByName("release")
            // Without a release key (a fork, a local build) the APK is signed
            // with the debug key so it still installs.
            signingConfig = if (release.storeFile != null) release else signingConfigs.getByName("debug")
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources.excludes += setOf("META-INF/*.md", "META-INF/LICENSE*", "META-INF/NOTICE*", "META-INF/versions/9/OSGI-INF/MANIFEST.MF")
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":core"))
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.service)
    implementation(libs.documentfile)
    implementation(libs.coroutines.android)
    implementation(libs.coil.compose)
    implementation(libs.coil.network)
}
