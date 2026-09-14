import java.io.FileInputStream
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Credenziali di firma release lette da app/keystore.properties, che NON è
// versionato (vedi .gitignore). Se il file manca (es. macchina di un altro
// sviluppatore, o build debug-only), la release semplicemente non viene
// firmata qui: niente password nel sorgente.
val keystorePropertiesFile = project.file("keystore.properties")
val keystoreProperties = Properties()
val hasSigningConfig = keystorePropertiesFile.exists()
if (hasSigningConfig) {
    keystoreProperties.load(FileInputStream(keystorePropertiesFile))
}

// Stesso schema di keystore.properties: token GitHub per StatusReporter letto
// da app/github_status.properties, NON versionato (vedi .gitignore). Se il
// file manca, il token in BuildConfig resta vuoto e StatusReporter salta
// silenziosamente il check-in (vedi StatusReporter.reportStatus).
val githubStatusPropertiesFile = project.file("github_status.properties")
val githubStatusProperties = Properties()
if (githubStatusPropertiesFile.exists()) {
    githubStatusProperties.load(FileInputStream(githubStatusPropertiesFile))
}
val githubStatusToken = githubStatusProperties.getProperty("githubStatusToken", "")

android {
    namespace = "com.erdbau.mdmagent"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.erdbau.mdmagent"
        minSdk = 24
        targetSdk = 34
        versionCode = 14
        versionName = "1.2.2"

        buildConfigField("String", "GITHUB_STATUS_TOKEN", "\"$githubStatusToken\"")
    }

    signingConfigs {
        if (hasSigningConfig) {
            create("release") {
                storeFile = file(keystoreProperties["storeFile"] as String)
                storePassword = keystoreProperties["storePassword"] as String
                keyAlias = keystoreProperties["keyAlias"] as String
                keyPassword = keystoreProperties["keyPassword"] as String
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (hasSigningConfig) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        debug {
            isDebuggable = true
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
        viewBinding = false
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
}
