rootProject.name = "LibretroDroidExtended"

pluginManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
    }
}

include(":netplay")
include(":player")
include(":libretrodroid")
project(":libretrodroid").projectDir = file("libretrodroid")
project(":netplay").projectDir = file("netplay")
project(":player").projectDir = file("player")
