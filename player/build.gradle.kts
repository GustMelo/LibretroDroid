plugins {
    alias(libs.plugins.kotlinMultiplatform)
    `maven-publish`
}

val engineDir = rootProject.layout.projectDirectory.dir(".cache/engine")

val buildEngine = tasks.register<Exec>("buildNativeEngine") {
    inputs.dir(rootProject.file("native/src"))
    inputs.dir(rootProject.file("native/bindings/c"))
    inputs.dir(rootProject.file("native/third_party"))
    inputs.dir(rootProject.file(".cache/angle/include"))
    inputs.file(rootProject.file("native/CMakeLists.txt"))
    inputs.file(rootProject.file("native/scripts/build-engine-ios.sh"))
    outputs.dir(engineDir)
    commandLine(rootProject.file("native/scripts/build-engine-ios.sh"))
}

kotlin {
    mapOf(iosArm64() to "ios-arm64", iosSimulatorArm64() to "ios-simulator-arm64").forEach { (target, slice) ->
        target.compilations.getByName("main").cinterops.create("retroengine") {
            definitionFile = project.file("src/nativeInterop/cinterop/retroengine.def")
            includeDirs(engineDir.dir("include"))
            extraOpts("-libraryPath", engineDir.dir(slice).asFile.absolutePath)
        }
        val interopTask = "cinteropRetroengine${target.name.replaceFirstChar { it.uppercase() }}"
        tasks.matching { it.name == interopTask }.configureEach {
            inputs.file(engineDir.file("$slice/libretroengine.a"))
                .withPropertyName("nativeEngineArchive")
                .withPathSensitivity(PathSensitivity.NONE)
        }
    }

    compilerOptions {
        optIn.addAll("kotlinx.cinterop.ExperimentalForeignApi", "kotlinx.cinterop.BetaInteropApi")
    }

    sourceSets {
        iosMain.dependencies {
            api(project(":netplay"))
        }
    }
}

tasks.matching { it.name.startsWith("cinteropRetroengine") }.configureEach { dependsOn(buildEngine) }

publishing { repositories { maven { name = "distribution"; url = rootProject.layout.buildDirectory.dir("maven").get().asFile.toURI() } } }
