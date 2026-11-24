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
            """(?<![\w.])(java|javax|kotlin\.jvm|System|Character|Thread|Math|Integer)\.""" +
                """|::class\.java\b|\bsynchronized\s*\(|@(?:[a-z]+:)?(?:Synchronized|Jvm\w*)\b|\bString\.format\(""",
        )

        // ファイル全体を通してコメント（入れ子ブロックコメント含む）とリテラル中身を外し、行番号を保ったまま検査対象のコードだけを残す
        fun stripCommentsAndLiterals(source: String): List<String> {
            val out = StringBuilder(source.length)
            // 状態スタック: 0 = 通常コード, 1 = 通常文字列, 2 = 複数行文字列, 3以上 = 文字列テンプレート内の式（波括弧深さ = value - 2）
            val stack = ArrayDeque<Int>()
            stack.addLast(0)
            var blockCommentDepth = 0
            var inLineComment = false
            var inCharLiteral = false
            var i = 0
            while (i < source.length) {
                val c = source[i]
                val next = source.getOrNull(i + 1)
                if (inLineComment) {
                    if (c == '\n') {
                        inLineComment = false
                        out.append('\n')
                    } else {
                        out.append(' ')
                    }
                    i++
                    continue
                }
                if (blockCommentDepth > 0) {
                    if (c == '/' && next == '*') {
                        blockCommentDepth++
                        out.append("  ")
                        i += 2
                    } else if (c == '*' && next == '/') {
                        blockCommentDepth--
                        out.append("  ")
                        i += 2
                    } else {
                        out.append(if (c == '\n') '\n' else ' ')
                        i++
                    }
                    continue
                }
                if (inCharLiteral) {
                    if (c == '\\' && i + 1 < source.length) {
                        out.append(' ')
                        out.append(if (next == '\n') '\n' else ' ')
                        i += 2
                    } else if (c == '\'' || c == '\n') {
                        inCharLiteral = false
                        out.append(if (c == '\n') '\n' else ' ')
                        i++
                    } else {
                        out.append(' ')
                        i++
                    }
                    continue
                }
                val mode = stack.last()
                when (mode) {
                    1 -> { // 通常文字列 "..."
                        if (c == '\\' && i + 1 < source.length) {
                            out.append(' ')
                            out.append(if (next == '\n') '\n' else ' ')
                            i += 2
                        } else if (c == '$' && next == '{') {
                            stack.addLast(3)
                            out.append("  ")
                            i += 2
                        } else if (c == '"' || c == '\n') {
                            stack.removeLast()
                            out.append(if (c == '\n') '\n' else ' ')
                            i++
                        } else {
                            out.append(' ')
                            i++
                        }
                    }
                    2 -> { // 複数行文字列 """..."""
                        if (c == '$' && next == '{') {
                            stack.addLast(3)
                            out.append("  ")
                            i += 2
                        } else if (c == '"' && next == '"' && source.getOrNull(i + 2) == '"') {
                            // 連続する 4 個以上の " は末尾の 3 個が閉じ記号になる
                            var q = i + 3
                            while (q < source.length && source[q] == '"') q++
                            repeat(q - i) { out.append(' ') }
                            stack.removeLast()
                            i = q
                        } else {
                            out.append(if (c == '\n') '\n' else ' ')
                            i++
                        }
                    }
                    else -> { // 通常コード (0) または文字列テンプレート式内 (3+)
                        when {
                            c == '/' && next == '/' -> {
                                inLineComment = true
                                out.append("  ")
                                i += 2
                            }
                            c == '/' && next == '*' -> {
                                blockCommentDepth = 1
                                out.append("  ")
                                i += 2
                            }
                            c == '"' && next == '"' && source.getOrNull(i + 2) == '"' -> {
                                stack.addLast(2)
                                out.append("   ")
                                i += 3
                            }
                            c == '"' -> {
                                stack.addLast(1)
                                out.append(' ')
                                i++
                            }
                            c == '\'' -> {
                                inCharLiteral = true
                                out.append(' ')
                                i++
                            }
                            mode >= 3 && c == '{' -> {
                                stack[stack.lastIndex] = mode + 1
                                out.append(c)
                                i++
                            }
                            mode >= 3 && c == '}' -> {
                                if (mode == 3) {
                                    stack.removeLast()
                                    out.append(' ')
                                } else {
                                    stack[stack.lastIndex] = mode - 1
                                    out.append(c)
                                }
                                i++
                            }
                            else -> {
                                out.append(c)
                                i++
                            }
                        }
                    }
                }
            }
            return out.toString().split('\n')
        }

        val hits = dirs.flatMap { dir ->
            dir.asFileTree.matching { include("**/*.kt") }.flatMap { f ->
                val normalized = f.readText().replace("\r\n", "\n").replace('\r', '\n')
                val rawLines = normalized.split('\n')
                val codeLines = stripCommentsAndLiterals(normalized)
                codeLines.mapIndexedNotNull { i, code ->
                    if (!jvmOnly.containsMatchIn(code)) null
                    else "${f.relativeTo(projectDir)}:${i + 1}: ${rawLines[i].trim()}"
                }
            }
        }
        if (hits.isNotEmpty()) {
            throw GradleException("共通のコードで JVM 専用の API を使っている:\n" + hits.joinToString("\n"))
        }
    }
}

tasks.named("check") { dependsOn(checkCommonPurity) }
