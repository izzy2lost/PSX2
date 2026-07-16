import java.util.Properties
import java.io.FileInputStream
import java.text.SimpleDateFormat
import java.util.Date

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Load keystore properties
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties()
if (keystorePropertiesFile.exists()) {
    keystoreProperties.load(FileInputStream(keystorePropertiesFile))
}
val signingKeys = listOf("storeFile", "storePassword", "keyAlias", "keyPassword")
val missingSigningKeys = signingKeys.filter { keystoreProperties.getProperty(it)?.trim().isNullOrEmpty() }
var hasReleaseSigning = keystorePropertiesFile.exists() && missingSigningKeys.isEmpty()
if (hasReleaseSigning) {
    val configuredStoreFile = file(keystoreProperties.getProperty("storeFile"))
    if (!configuredStoreFile.exists()) {
        logger.lifecycle("[app] Release keystore file not found at ${configuredStoreFile}. Falling back to debug signing for release.")
        hasReleaseSigning = false
    }
} else {
    logger.lifecycle("[app] Release signing not fully configured (${missingSigningKeys}). Falling back to debug signing for release.")
}

android {
    namespace = "com.izzy2lost.psx2"
    compileSdk = 36
    ndkVersion = "30.0.14904198"

    signingConfigs {
        create("release") {
            if (hasReleaseSigning) {
                storeFile = file(keystoreProperties["storeFile"] as String)
                storePassword = keystoreProperties["storePassword"] as String
                keyAlias = keystoreProperties["keyAlias"] as String
                keyPassword = keystoreProperties["keyPassword"] as String
            }
        }
    }

    defaultConfig {
        applicationId = "com.izzy2lost.psx2"
        minSdk = 26
        targetSdk = 36
        versionCode = 25
        versionName = "1.2.3"
    }

    base {
        archivesName.set("PSX2_${defaultConfig.versionCode}_${SimpleDateFormat("yyyyMMddHHmm").format(Date())}")
    }

    defaultConfig {
        externalNativeBuild {
            cmake {
                // Avoid LTO core on Android to prevent x86 objects from leaking into arm64 link
                arguments(
                    "-DANDROID=true",
                    "-DCMAKE_BUILD_TYPE=Release",
                    "-DANDROID_STL=c++_static",
                    "-DPCSX2_PROFILER=OFF"
                )
            }
        }
        ndk {
            abiFilters.add("arm64-v8a")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (hasReleaseSigning) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
            ndk {
                debugSymbolLevel = "SYMBOL_TABLE"
            }
        }
        debug {
            isMinifyEnabled = false
            isShrinkResources = false
            isDebuggable = true
        }
    }

    bundle {
        language {
            enableSplit = false
        }
        density {
            enableSplit = false
        }
        abi {
            enableSplit = true
        }
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
        isCoreLibraryDesugaringEnabled = true
    }

    kotlinOptions {
        jvmTarget = "21"
    }

    buildFeatures {
        compose = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    externalNativeBuild {
        cmake {
            version = "3.22.1"
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
        resources {
            excludes.addAll(listOf("META-INF/DEPENDENCIES", "META-INF/LICENSE", "META-INF/NOTICE", "META-INF/NOTICE.txt"))
        }
    }

    buildToolsVersion = "36.1.0"
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.0.4")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("com.google.android.material:material:1.14.0-alpha07")
    implementation("androidx.constraintlayout:constraintlayout:2.2.1")
    implementation("androidx.documentfile:documentfile:1.1.0")
    implementation("androidx.recyclerview:recyclerview:1.4.0")
    implementation("androidx.viewpager2:viewpager2:1.1.0")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    implementation("androidx.activity:activity:1.12.1")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("com.github.bumptech.glide:glide:4.16.0")
    annotationProcessor("com.github.bumptech.glide:compiler:4.16.0")

    // Kotlin Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Jetpack Compose
    val composeBom = platform("androidx.compose:compose-bom:2024.06.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.0")

    // Coil for asynchronous image loading in Compose
    implementation("io.coil-kt:coil-compose:2.6.0")
}

val javaToolchainService = project.extensions.getByType(org.gradle.jvm.toolchain.JavaToolchainService::class.java)

tasks.withType<org.gradle.api.tasks.compile.JavaCompile>().configureEach {
    javaCompiler.set(javaToolchainService.compilerFor {
        languageVersion.set(org.gradle.jvm.toolchain.JavaLanguageVersion.of(21))
    })
}
