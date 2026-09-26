import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    `maven-publish`
    alias(libs.plugins.androidMultiplatformLibrary)
}

kotlin {
    iosArm64()
    iosSimulatorArm64()

    android {
        namespace = "com.libretrodroid.netplay"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
        compilerOptions { jvmTarget = JvmTarget.JVM_17 }
        withHostTest { isReturnDefaultValues = true }
    }

    compilerOptions {
        optIn.addAll("kotlinx.cinterop.ExperimentalForeignApi", "kotlin.uuid.ExperimentalUuidApi", "kotlin.time.ExperimentalTime")
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    sourceSets {
        commonMain.dependencies {
            api(libs.okio)
            api(libs.kotlinx.coroutines.core)
            implementation(libs.atomicfu)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}

publishing { repositories { maven { name = "distribution"; url = rootProject.layout.buildDirectory.dir("maven").get().asFile.toURI() } } }
