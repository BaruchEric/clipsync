plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.sqldelight)
    alias(libs.plugins.kotlinSerialization)
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
            implementation(libs.libsodium.bindings)
            implementation(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
        // Intermediate source set shared by the two JVM targets (Android + desktop):
        // hosts the Ktor/TLS transport and cert generation written once for both.
        val jvmShared by creating {
            dependsOn(commonMain.get())
            dependencies {
                implementation(libs.ktor.server.netty)
                implementation(libs.ktor.server.websockets)
                implementation(libs.ktor.client.okhttp)
                implementation(libs.ktor.client.websockets)
                implementation(libs.ktor.network.tls)
                implementation(libs.bouncycastle.bcpkix)
            }
        }
        androidMain.get().dependsOn(jvmShared)
        androidMain.dependencies {
            implementation(libs.sqldelight.driver.android)
        }
        val desktopMain by getting {
            dependsOn(jvmShared)
            dependencies {
                implementation(libs.jna)
                implementation(libs.sqldelight.driver.sqlite)
                implementation(libs.jmdns)
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
    // Forward the opt-in flag for the multicast-dependent mDNS smoke test to the test JVM.
    System.getProperty("clipsync.mdns.test")?.let { systemProperty("clipsync.mdns.test", it) }
}
