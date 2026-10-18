plugins {
    kotlin("jvm") version "2.4.21"
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21"
    id("org.jetbrains.compose") version "1.12.2"
}

// -Pjbr で JetBrains Runtime 25 を使う（点検表の記録 3）
val useJbr = providers.gradleProperty("jbr").isPresent

kotlin {
    jvmToolchain {
        languageVersion = JavaLanguageVersion.of(25)
        if (useJbr) vendor = JvmVendorSpec.JETBRAINS
    }
}

dependencies {
    implementation(compose.desktop.currentOs)
}

compose.desktop {
    application {
        mainClass = "MainKt"
        // 何もしないと Gradle を動かしている JDK で起動するので、上のツールチェーンに揃える
        javaHome = javaToolchains.launcherFor {
            languageVersion = JavaLanguageVersion.of(25)
            if (useJbr) vendor = JvmVendorSpec.JETBRAINS
        }.get().metadata.installationPath.asFile.absolutePath
    }
}
