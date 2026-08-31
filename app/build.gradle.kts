plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val vCode = (findProperty("versionCode") as String?)?.toIntOrNull() ?: 1
val vName = (findProperty("versionName") as String?) ?: "1.0"

android {
    namespace = "ir.jaru.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "ir.jaru.app"
        minSdk = 26
        targetSdk = 35
        versionCode = vCode
        versionName = vName
        resourceConfigurations += setOf("fa", "en")
    }

    signingConfigs {
        create("release") {
            storeFile = rootProject.file("release.jks")
            storePassword = "jaru-apk-2026"
            keyAlias = "jaru"
            keyPassword = "jaru-apk-2026"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            isDebuggable = false
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    buildFeatures { buildConfig = true }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/*.kotlin_module",
                "kotlin-tooling-metadata.json",
                "DebugProbesKt.bin"
            )
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.constraintlayout:constraintlayout:2.2.0")
}
