plugins {
    kotlin("multiplatform")
}

repositories {
    mavenCentral()
}

kotlin {
    jvmToolchain(25)
    jvm()
    compilerOptions {
        extraWarnings.set(true)
    }
    sourceSets {
        commonMain.dependencies {
            // Page.dailyDate が公開の型として使うので api にする
            api("org.jetbrains.kotlinx:kotlinx-datetime:0.8.0")
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        jvmTest.dependencies {
            implementation(project.dependencies.platform("org.junit:junit-bom:6.1.3"))
            implementation("org.junit.jupiter:junit-jupiter")
            runtimeOnly("org.junit.platform:junit-platform-launcher")
        }
    }
}

tasks.named<Test>("jvmTest") {
    useJUnitPlatform()
    testLogging { exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
}

// ターゲットが JVM だけの間は、commonMain で JDK の API を使ってもコンパイルが通ってしまう。ここで止める
val checkCommonPurity = tasks.register("checkCommonPurity") {
    group = "verification"
    description = "commonMain で JVM 専用の API を使っていないか確かめる"
    val src = layout.projectDirectory.dir("src/commonMain")
    inputs.dir(src)
    doLast {
        // 前に「.」が付くもの（Clock.System など Kotlin 側の名前）は除く
        val jvmOnly = Regex("""(?<![\w.])(java|javax|System|Character|Thread)\.|@Synchronized""")
        val hits = src.asFileTree.matching { include("**/*.kt") }.flatMap { f ->
            f.readLines().mapIndexedNotNull { i, line ->
                // コメントの中の説明（「Java の Character.isWhitespace と同じ」など）は見ない
                val code = line.substringBefore("//").trim()
                if (code.startsWith("*") || code.startsWith("/*") || !jvmOnly.containsMatchIn(code)) null
                else "${f.relativeTo(src.asFile)}:${i + 1}: $code"
            }
        }
        if (hits.isNotEmpty()) {
            throw GradleException("commonMain で JVM 専用の API を使っている:\n" + hits.joinToString("\n"))
        }
    }
}

tasks.named("check") { dependsOn(checkCommonPurity) }

// ルートと同じく `./gradlew test` で core のテストも走るようにする
tasks.register("test") {
    group = "verification"
    description = "core のテストを走らせる（jvmTest と同じ）"
    dependsOn("jvmTest")
}
