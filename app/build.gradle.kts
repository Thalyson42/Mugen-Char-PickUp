plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.ikemenselect"
    compileSdk = 34

    // Chave fixa do projeto. Para o Android aceitar uma atualização "por cima", o APK novo precisa ter
    // o mesmo applicationId, a MESMA assinatura e um versionCode maior. A chave de debug muda a cada
    // build no GitHub, por isso todos os APKs (debug e release) usam esta.
    signingConfigs {
        create("pickup") {
            storeFile = file("pickup.keystore")
            storePassword = "pickup123"
            keyAlias = "pickup"
            keyPassword = "pickup123"
        }
    }

    defaultConfig {
        applicationId = "com.ikemenselect"
        minSdk = 26
        targetSdk = 34
        // No GitHub o número da execução sempre cresce, então cada build novo vale como atualização.
        versionCode = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 2
        versionName = "0.2.1"
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("pickup")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("pickup")
        }
    }

    lint {
        checkReleaseBuilds = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }
}

dependencies {
    implementation(project(":core"))

    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
