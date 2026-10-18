// 使い捨ての試作。本体のビルドには含めない
pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}

// JetBrains Runtime を使うとき（-Pjbr）に、手元になければ取ってくる
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        google()
    }
}

rootProject.name = "compose-ime-spike"
