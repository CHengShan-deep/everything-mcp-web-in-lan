plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "dev.emwl.host"
    compileSdk = 36

    // Release signing stays out of the repository: point these at a local keystore or set the
    // matching environment variables in CI. Without them the release build is simply unsigned.
    val releaseKeystore = System.getenv("EMWL_KEYSTORE") ?: (findProperty("emwlKeystore") as String?)

    defaultConfig {
        applicationId = "dev.emwl.host"
        minSdk = 29
        targetSdk = 36
        versionCode = 2
        versionName = "0.2.0"
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = System.getenv("EMWL_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("EMWL_KEY_ALIAS")
                keyPassword = System.getenv("EMWL_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            if (releaseKeystore != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "/META-INF/INDEX.LIST")
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation(platform("androidx.compose:compose-bom:2026.05.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("io.ktor:ktor-server-core:3.2.3")
    implementation("io.ktor:ktor-server-cio:3.2.3")

    testImplementation("junit:junit:4.13.2")
    testImplementation("io.ktor:ktor-server-test-host:3.2.3")
    testImplementation("io.ktor:ktor-client-content-negotiation:3.2.3")
    testImplementation("org.json:json:20240303")
}
