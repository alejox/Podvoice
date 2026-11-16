plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.podvoice.podcam"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.podvoice.podcam"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Para publicar en Play: configura tu propia firma (signingConfig) aqui.
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    testOptions { unitTests.all { it.systemProperties(System.getProperties().filterKeys { k -> k.toString().startsWith("podcam.") }.mapKeys { it.key.toString() }) } }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
