import java.net.NetworkInterface

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.ashnai.lanchess"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.ashnai.lanchess"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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
        viewBinding = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.constraintlayout)
}

/**
 * Resolves the first non-loopback IPv4 address of this machine.
 * Kept here so the same helper can be reused by tooling later.
 */
fun resolveLocalIpv4(): String? =
    NetworkInterface.getNetworkInterfaces().toList()
        .filter { it.isUp && !it.isLoopback }
        .flatMap { it.inetAddresses.toList() }
        .map { it.hostAddress ?: "" }
        .firstOrNull { it.length <= 15 && it.count { c -> c == '.' } == 3 && it[0].isDigit() }
