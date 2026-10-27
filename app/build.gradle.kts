plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "io.paimon.fx991"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.paimon.fx991"
        minSdk = 26
        targetSdk = 36
        versionCode = 220
        versionName = "2.3.0-fonts"

        ndk {
            // From Batch F, ML Kit introduces .so files for 4 ABIs (around 41 MB total), inflating APK to 53 MB.
            // Modern devices are arm64, so default to arm64-v8a only (saves ~30 MB).
            // Pass -PallAbi if multi-architecture (e.g. x86_64) is needed.
            abiFilters += if (project.hasProperty("allAbi")) {
                listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
            } else {
                listOf("arm64-v8a")
            }
        }
    }

    signingConfigs {
        // debug variant also disables v3; keep v1+v2
        getByName("debug") {
            enableV1Signing = true
            enableV2Signing = true
            enableV3Signing = false
        }
        create("release") {
            val propsFile = rootProject.file("keystore.properties")
            if (propsFile.exists()) {
                // Parse key=value manually to avoid importing java.util in Kotlin DSL
                val props = propsFile.readLines().mapNotNull {
                    val i = it.indexOf('=')
                    if (i > 0) it.substring(0, i).trim() to it.substring(i + 1).trim() else null
                }.toMap()
                storeFile = rootProject.file(props["storeFile"]!!)
                storePassword = props["storePassword"]!!
                keyAlias = props["keyAlias"]!!
                keyPassword = props["keyPassword"]!!
            }
            // Some OEM package installers cannot parse v3 signing blocks ("Parse error: no signing file");
            // keep v1 (JAR) + v2, disable v3 (verification: apksigner verify -v must show v2=true / v3=false)
            enableV1Signing = true
            enableV2Signing = true
            enableV3Signing = false
        }
    }

    buildTypes {
        debug {
            // Some OEM ROMs block packages with isDebuggable=true from file managers;
            // keep debug key signing with isDebuggable turned off.
            isDebuggable = false
        }
        release {
            // Release variant: non-debuggable + dedicated release key signing (keystore in keystore.properties).
            isMinifyEnabled = false
            isDebuggable = false
            signingConfig = signingConfigs.getByName("release")
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
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.09.00"))
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    // Local offline OCR: bundled ML Kit models shipped inside APK, fully offline.
    // Chinese recognition model also handles Latin letters and digits; Latin model acts as fallback.
    implementation("com.google.mlkit:text-recognition-chinese:16.0.1")
    implementation("com.google.mlkit:text-recognition:16.0.1")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
