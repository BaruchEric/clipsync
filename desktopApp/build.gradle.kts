import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

kotlin { jvmToolchain(17) }

dependencies {
    implementation(project(":shared"))
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(libs.kotlinx.coroutines.swing)
    implementation(libs.zxing.core)
}

compose.desktop {
    application {
        mainClass = "ca.beric.clipsync.desktop.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Dmg)
            packageName = "clipsync"
            packageVersion = "1.0.0"
            // jlink strips modules it can't infer from bytecode: SQLDelight's JDBC driver
            // needs java.sql; JSSE EC (TLS) needs jdk.crypto.ec.
            modules("java.sql", "jdk.crypto.ec", "java.naming")
        }
    }
}
