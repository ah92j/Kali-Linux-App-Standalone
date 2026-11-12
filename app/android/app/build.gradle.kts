import java.util.Properties

plugins {
    id("com.android.application")
    id("kotlin-android")
    // The Flutter Gradle Plugin must be applied after the Android and Kotlin Gradle plugins.
    id("dev.flutter.flutter-gradle-plugin")
}

// Permanent release signing configuration.
val releaseKeystore = Properties().apply {
    rootProject.file("key.properties")
        .takeIf { it.isFile }
        ?.inputStream()
        ?.use(::load)
}

fun releaseSetting(property: String, env: String): String? =
    (releaseKeystore.getProperty(property) ?: System.getenv(env))
        ?.trim()
        ?.takeIf { it.isNotEmpty() }

// All four signing values are mandatory.
val releaseStoreFile = releaseSetting("storeFile", "DROIDDESK_KEYSTORE")
    ?: throw GradleException("Missing permanent release keystore path.")

val releaseStorePassword = releaseSetting("storePassword", "DROIDDESK_KEYSTORE_PASSWORD")
    ?: throw GradleException("Missing permanent keystore password.")

val releaseKeyAlias = releaseSetting("keyAlias", "DROIDDESK_KEY_ALIAS")
    ?: throw GradleException("Missing permanent release key alias.")

val releaseKeyPassword = releaseSetting("keyPassword", "DROIDDESK_KEY_PASSWORD")
    ?: throw GradleException("Missing permanent release key password.")

val releaseStore = file(releaseStoreFile)

if (!releaseStore.isFile) {
    throw GradleException("Permanent release keystore does not exist.")
}

android {
    namespace = "com.orailnoor.droiddesk"
    compileSdk = flutter.compileSdkVersion
    ndkVersion = flutter.ndkVersion

    buildFeatures {
        aidl = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = JavaVersion.VERSION_17.toString()
    }

    defaultConfig {
        applicationId = "com.orailnoor.droiddesk"
        minSdk = 28
        targetSdk = 28

        versionCode = flutter.versionCode
        versionName = flutter.versionName

        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    // Always use the permanent release keystore.
    signingConfigs {
        create("release") {
            storeFile = releaseStore
            storePassword = releaseStorePassword
            keyAlias = releaseKeyAlias
            keyPassword = releaseKeyPassword
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")

            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    // Enable native C/C++ build support.
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }

    packaging {
        jniLibs.useLegacyPackaging = true
    }
}

flutter {
    source = "../.."
}

dependencies {
    // Pure-Java XZ decoder for Android-safe Kali rootfs extraction.
    implementation("org.tukaani:xz:1.12")
    implementation("org.apache.commons:commons-compress:1.28.0")
    testImplementation("junit:junit:4.13.2")
}
