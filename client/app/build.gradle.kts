import java.util.Properties
import org.gradle.api.tasks.Exec

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val localProperties = Properties()
val localPropertiesFile = rootProject.file("local.properties")
if (localPropertiesFile.exists()) {
    localProperties.load(localPropertiesFile.inputStream())
}
val ndkDir = localProperties.getProperty("ndk.dir")

android {
    namespace = "net.anapaya.toyvpn"
    compileSdk = 34

    defaultConfig {
        applicationId = "net.anapaya.toyvpn"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    implementation("net.java.dev.jna:jna:5.13.0@aar")
}

// Find the absolute path to the cargo executable
val cargoPath = "cargo"

tasks.register<Exec>("cargoBuild") {
    workingDir = file("../rust")
    // Set the environment variable for cargo-ndk
    if (ndkDir != null) {
        environment("ANDROID_NDK_HOME", ndkDir)
    } else {
        // Fail the build if NDK path is not found
        throw GradleException("ndk.dir is not set in local.properties. Please install the NDK via Android Studio's SDK Manager.")
    }
    commandLine(cargoPath, "ndk", "-t", "arm64-v8a", "-t", "armeabi-v7a", "-t", "x86_64", "-o", "../app/src/main/jniLibs", "build", "--release")
}

tasks.register<Exec>("generateUniffiBindings") {
    workingDir = file("../rust")
    // Ensure output dir exists
    doFirst {
        file("build/generated/source/uniffi/java").mkdirs()
    }
    commandLine(cargoPath, "run", "--bin", "uniffi-bindgen", "generate", "src/toyvpn.udl", "--language", "kotlin", "--out-dir", "../app/build/generated/source/uniffi/java")
}

// Rename libtoyvpn_client.so to libuniffi_toyvpn_client.so (UniFFI convention)
tasks.register("renameNativeLibs") {
    dependsOn("cargoBuild")
    doLast {
        val jniLibsDir = file("src/main/jniLibs")
        val archs = listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        archs.forEach { arch ->
            val archDir = File(jniLibsDir, arch)
            val src = File(archDir, "libtoyvpn_client.so")
            val dst = File(archDir, "libuniffi_toyvpn_client.so")
            if (src.exists()) {
                src.renameTo(dst)
                println("Renamed $src to $dst")
            }
        }
    }
}

// Hook into build
afterEvaluate {
    tasks.named("preBuild") {
        dependsOn("renameNativeLibs")
        dependsOn("generateUniffiBindings")
    }
}

android {
    sourceSets {
        getByName("main") {
            java.srcDir("build/generated/source/uniffi/java")
        }
    }
}
