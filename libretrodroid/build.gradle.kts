plugins {
    alias(libs.plugins.androidLibrary)
    `maven-publish`
}

android {
    publishing { singleVariant("release") { withSourcesJar() } }
    namespace = "com.swordfish.libretrodroid"
    compileSdk = libs.versions.android.compileSdk.get().toInt()
    ndkVersion = "28.2.13676358"

    defaultConfig {
        minSdk = libs.versions.android.minSdk.get().toInt()
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
        externalNativeBuild {
            cmake { arguments += "-DANDROID_STL=c++_static" }
        }
        consumerProguardFiles("consumer-rules.pro")
    }

    externalNativeBuild {
        cmake {
            path = file("../native/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

dependencies {
    api(project(":netplay"))
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.kotlinx.coroutines.android)
}


publishing {
    publications {
        register<MavenPublication>("release") {
            afterEvaluate { from(components["release"]) }
        }
    }
    repositories { maven { name = "distribution"; url = rootProject.layout.buildDirectory.dir("maven").get().asFile.toURI() } }
}
