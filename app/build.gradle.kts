import java.io.ByteArrayInputStream
import java.io.FileInputStream
import java.util.Base64
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Signing values come from the base64 LOCAL_PROPERTIES environment variable (GitHub secret)
// or, for local builds, from the root local.properties file. Keys: KEYSTORE_PASS,
// ALIAS_NAME, ALIAS_PASS. The keystore itself is app/release.keystore (git-ignored).
fun getProps(propName: String): String {
    val propsInEnv = System.getenv("LOCAL_PROPERTIES")
    if (!propsInEnv.isNullOrBlank()) {
        val props = Properties()
        props.load(ByteArrayInputStream(Base64.getDecoder().decode(propsInEnv.trim())))
        val value = props.getProperty(propName)
        if (value != null) {
            return value
        }
    }
    val propsFile = rootProject.file("local.properties")
    if (propsFile.exists()) {
        val props = Properties()
        FileInputStream(propsFile).use { props.load(it) }
        val value = props.getProperty(propName)
        if (value != null) {
            return value
        }
    }
    return ""
}

val releaseKeystoreFile = file("release.keystore")
val releaseStorePassword = getProps("KEYSTORE_PASS")
val releaseKeyAlias = getProps("ALIAS_NAME")
val releaseKeyPassword = getProps("ALIAS_PASS")
val releaseSigningConfigured = releaseKeystoreFile.isFile && listOf(
    releaseStorePassword,
    releaseKeyAlias,
    releaseKeyPassword
).all(String::isNotBlank)
val releaseRequested = gradle.startParameter.taskNames.any { it.contains("Release", ignoreCase = true) }
if (releaseRequested && !releaseSigningConfigured) {
    throw GradleException(
        "Release signing is not configured. Provide app/release.keystore and the " +
            "KEYSTORE_PASS, ALIAS_NAME and ALIAS_PASS properties (LOCAL_PROPERTIES secret " +
            "or local.properties)."
    )
}

android {
    namespace = "io.surprise.vpn"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.surprise.vpn"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }
        ndk {
            // The bundled Xray core ships four ABIs; phones use the two ARM targets.
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    signingConfigs {
        create("production") {
            if (releaseSigningConfigured) {
                storeFile = releaseKeystoreFile
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ""
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("production")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf("-Xjvm-default=all")
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt"
            )
        }
        jniLibs {
            // Keep the downloadable APK smaller by extracting native libraries at install time.
            useLegacyPackaging = true
        }
    }
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.navigation:navigation-compose:2.8.5")

    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.animation:animation")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.zxing:core:3.5.3")
    implementation(files("libs/libXray.aar"))

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
