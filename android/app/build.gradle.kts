import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val gameSlug = providers.gradleProperty("gameSlug").orNull ?: "base"
val gameName = providers.gradleProperty("gameName").orNull ?: "PS1App"
val packageSuffix = providers.gradleProperty("packageSuffix").orNull ?: ""
val gameVersionCode = providers.gradleProperty("versionCode").orNull?.toIntOrNull() ?: 1

val basePackage = "com.farsipsx.ps1app"
val finalPackage = if (packageSuffix.isBlank()) basePackage else "$basePackage.$packageSuffix"

val keystoreFile = rootProject.file("release.jks")
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}

android {
    namespace = basePackage
    compileSdk = 34
    ndkVersion = "25.2.9519653"

    defaultConfig {
        applicationId = finalPackage
        minSdk = 21
        targetSdk = 34
        versionCode = gameVersionCode
        versionName = "1.0"

        // Name shown under the icon comes from the online panel.
        resValue("string", "app_name", gameName)
        resValue("string", "game_slug", gameSlug)
        resValue("string", "game_package", finalPackage)

        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
                abiFilters += listOf("arm64-v8a", "armeabi-v7a")
            }
        }
    }

    signingConfigs {
        create("release") {
            if (keystoreFile.exists()) {
                storeFile = keystoreFile
                storePassword = keystoreProps.getProperty("storePassword")
                    ?: System.getenv("KEYSTORE_PASSWORD") ?: ""
                keyAlias = keystoreProps.getProperty("keyAlias")
                    ?: System.getenv("KEY_ALIAS") ?: "ps1app"
                keyPassword = keystoreProps.getProperty("keyPassword")
                    ?: System.getenv("KEY_PASSWORD")
                    ?: System.getenv("KEYSTORE_PASSWORD") ?: ""
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
        }
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (keystoreFile.exists()) signingConfig = signingConfigs.getByName("release")
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources {
            excludes += "/META-INF/*"
        }
    }
}
