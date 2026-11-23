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

// PIN di uscita dal kiosk da app/kiosk.properties, NON versionato (vedi
// .gitignore): il repo è pubblico e il PIN non deve stare nel sorgente.
// A differenza del token, qui il file è obbligatorio: niente APK senza PIN.
val kioskPropertiesFile = project.file("kiosk.properties")
val kioskProperties = Properties()
if (kioskPropertiesFile.exists()) {
    kioskProperties.load(FileInputStream(kioskPropertiesFile))
}
val adminExitPin: String = kioskProperties.getProperty("adminExitPin")
    ?: throw GradleException("Manca app/kiosk.properties con adminExitPin=<PIN> (vedi kiosk.properties.example)")
// Segreto del PIN mensile a rotazione (vedi RotatingPin.kt): obbligatorio anche
// lui, così una release non può partire per sbaglio senza PIN del mese.
val rotatingPinSecret: String = kioskProperties.getProperty("rotatingPinSecret")
    ?.takeIf { it.length >= 32 }
    ?: throw GradleException("Manca rotatingPinSecret (almeno 32 caratteri) in app/kiosk.properties (vedi kiosk.properties.example)")

android {
    namespace = "com.erdbau.mdmagent"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.erdbau.mdmagent"
        minSdk = 24
        targetSdk = 34
        versionCode = 29
        versionName = "1.4.3"

        buildConfigField("String", "GITHUB_STATUS_TOKEN", "\"$githubStatusToken\"")
        buildConfigField("String", "ADMIN_EXIT_PIN", "\"$adminExitPin\"")
        buildConfigField("String", "ROTATING_PIN_SECRET", "\"$rotatingPinSecret\"")
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
