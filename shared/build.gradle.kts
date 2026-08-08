plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.sqldelight)
}

kotlin {
    jvmToolchain(17)
    androidTarget()
    jvm("desktop")

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.sqldelight.runtime)
            implementation(libs.sqldelight.coroutines)
        }
        androidMain.dependencies {
            implementation(libs.sqldelight.driver.android)
        }
        val desktopMain by getting {
            dependencies {
                implementation(libs.jna)
                implementation(libs.sqldelight.driver.sqlite)
            }
        }
        val desktopTest by getting {
            dependencies {
                implementation(libs.kotlin.test)
                implementation(libs.kotlinx.coroutines.test)
                implementation(libs.sqldelight.driver.sqlite)
            }
        }
    }
}

android {
    namespace = "ca.beric.clipsync.shared"
    compileSdk = 36
    defaultConfig { minSdk = 29 }
}

sqldelight {
    databases {
        create("ClipsyncDb") {
            packageName.set("ca.beric.clipsync.db")
        }
    }
}

tasks.named<Test>("desktopTest") {
    systemProperty("java.awt.headless", "false")
}
