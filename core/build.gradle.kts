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

// ターゲットが JVM だけの間は、共通のコードで JDK の API を使ってもコンパイルが通ってしまう。ここで止める
val checkCommonPurity = tasks.register("checkCommonPurity") {
    group = "verification"
    description = "commonMain と commonTest で JVM 専用の API を使っていないか確かめる"
    val dirs = listOf("src/commonMain", "src/commonTest").map { layout.projectDirectory.dir(it) }
    inputs.files(dirs)
    doLast {
        val jvmOnly = Regex(
            // 前に「.」が付くもの（Clock.System、kotlin.math.max など Kotlin 側の名前）は除く
            """(?<![\w.])(java|javax|System|Character|Thread|Math|Integer)\.""" +
                """|::class\.java\b|\bsynchronized\s*\(|@Synchronized|\bString\.format\(""",
        )

        // 文字列の中身と、行の途中から始まるコメントを外す（"https://" の後ろも見落とさない）
        fun codeOf(line: String): String {
            val out = StringBuilder()
            var i = 0
            var quote: String? = null
            while (i < line.length) {
                val rest = line.substring(i)
                when {
                    quote == null && rest.startsWith("//") -> break
                    quote == null && rest.startsWith("\"\"\"") -> { quote = "\"\"\""; i += 3; continue }
                    quote == null && line[i] == '"' -> { quote = "\""; i++; continue }
                    // 文字リテラル（'"' や '\\'）は丸ごと飛ばす
                    quote == null && line[i] == '\'' -> {
                        val end = line.indexOf('\'', i + if (line.getOrNull(i + 1) == '\\') 3 else 2)
                        i = if (end < 0) line.length else end + 1
                        continue
                    }
                    quote == "\"" && line[i] == '\\' -> { i += 2; continue }
                    quote != null && rest.startsWith(quote) -> { i += quote.length; quote = null; continue }
                    quote == null -> out.append(line[i])
                }
                i++
            }
            return out.toString().trim()
        }

        val hits = dirs.flatMap { dir ->
            dir.asFileTree.matching { include("**/*.kt") }.flatMap { f ->
                f.readLines().mapIndexedNotNull { i, line ->
                    val code = codeOf(line)
                    // 複数行のコメントの中の説明（「Java の Character.isWhitespace と同じ」など）は見ない
                    if (code.startsWith("*") || code.startsWith("/*") || !jvmOnly.containsMatchIn(code)) null
                    else "${f.relativeTo(projectDir)}:${i + 1}: ${line.trim()}"
                }
            }
        }
        if (hits.isNotEmpty()) {
            throw GradleException("共通のコードで JVM 専用の API を使っている:\n" + hits.joinToString("\n"))
        }
    }
}

tasks.named("check") { dependsOn(checkCommonPurity) }
