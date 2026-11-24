import java.util.Locale

plugins {
    application
    kotlin("jvm") version "2.4.21"
    kotlin("multiplatform") version "2.4.21" apply false
}

group = "app.dearnotes"
version = "1.3.4"

val javafxVersion = "25.0.4"

// Windows のアップグレードで同じ製品と見なすための固定 ID。変えないこと。
val winUpgradeUuid = "5f0c7c2e-3b7a-4d4e-9a63-2a1d8e6b9c41"

// Gradle 内部 API を使わず標準のシステムプロパティから OS を判定する
val osName: String = System.getProperty("os.name").lowercase(Locale.ROOT)
val isMacOs: Boolean = osName.contains("mac") || osName.contains("darwin")
val isWindows: Boolean = osName.startsWith("windows")
val arch: String = System.getProperty("os.arch")
val javafxPlatform = when {
    isMacOs -> if (arch == "aarch64") "mac-aarch64" else "mac"
    isWindows -> "win"
    else -> if (arch == "aarch64") "linux-aarch64" else "linux"
}

java {
    toolchain { languageVersion = JavaLanguageVersion.of(25) }
}

kotlin {
    jvmToolchain(25)
    compilerOptions {
        // 既定の警告に加えて、使っていない変数や不要な null 判定なども知らせる
        extraWarnings.set(true)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(project(":core"))
    for (module in listOf("base", "graphics", "controls")) {
        implementation("org.openjfx:javafx-$module:$javafxVersion:$javafxPlatform")
    }
    implementation("org.fxmisc.richtext:richtextfx:0.11.7")
    implementation("org.xerial:sqlite-jdbc:3.53.4.0")

    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

application {
    mainClass = "app.dearnotes.Launcher"
    applicationDefaultJvmArgs = listOf("--enable-native-access=ALL-UNNAMED")
}

// ./gradlew run -Pdearnotes.dataDir=/tmp/x で、別のデータフォルダを使って試せる
tasks.named<JavaExec>("run") {
    providers.gradleProperty("dearnotes.dataDir").orNull?.let { systemProperty("dearnotes.dataDir", it) }
}

tasks.test {
    // `./gradlew test` で core のテストも走らせる（KMP の core には test タスクがない）
    dependsOn(":core:jvmTest")
    useJUnitPlatform()
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    // CI のログで、失敗したテストの期待値と実際の値、メッセージまで読めるようにする
    testLogging { exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
}

tasks.jar {
    archiveFileName = "dearnotes.jar"
    manifest { attributes("Main-Class" to "app.dearnotes.Launcher") }
}

// ---- 配布: jlink で小さな JRE を作り、jpackage で .dmg / .msi に包む ----

val toolchainHome: Provider<String> = javaToolchains
    .launcherFor { languageVersion = JavaLanguageVersion.of(25) }
    .map { it.metadata.installationPath.asFile.absolutePath }

val toolchainVersion: Provider<String> = javaToolchains
    .launcherFor { languageVersion = JavaLanguageVersion.of(25) }
    .map { "${it.metadata.vendor}:${it.metadata.jvmVersion}" }

fun jdkTool(name: String): String {
    val exe = if (isWindows) "$name.exe" else name
    return "${toolchainHome.get()}/bin/$exe"
}

val packageInput = layout.buildDirectory.dir("jpackage-input")
val runtimeDir = layout.buildDirectory.dir("runtime")
val installerDir = layout.buildDirectory.dir("jpackage")

val copyLibs = tasks.register<Sync>("copyLibs") {
    group = "distribution"
    from(tasks.jar)
    from(configurations.runtimeClasspath)
    into(packageInput)
}

val jlinkModules = listOf(
    "java.base", "java.sql", "java.desktop", "java.logging", "java.xml",
    "java.naming", "jdk.unsupported", "jdk.charsets", "jdk.localedata",
)
val jlinkLocales = "en,ja"

val jlinkRuntime = tasks.register<Exec>("jlinkRuntime") {
    group = "distribution"
    val out = runtimeDir.get().asFile
    // モジュール構成・ロケール・JDK の変更時に古い runtime を UP-TO-DATE と誤判定しないよう入力を宣言する
    inputs.property("modules", jlinkModules)
    inputs.property("locales", jlinkLocales)
    inputs.property("toolchainHome", toolchainHome)
    inputs.property("toolchainVersion", toolchainVersion)
    outputs.dir(out)
    doFirst { delete(out) }
    commandLine(
        jdkTool("jlink"),
        "--add-modules", jlinkModules.joinToString(","),
        "--include-locales", jlinkLocales,
        "--strip-debug", "--no-header-files", "--no-man-pages",
        "--output", out.absolutePath,
    )
}

tasks.register<Exec>("jpackage") {
    group = "distribution"
    dependsOn(copyLibs, jlinkRuntime)
    val dest = installerDir.get().asFile
    doFirst { delete(dest) }
    val args = mutableListOf(
        jdkTool("jpackage"),
        "--name", "DearNotes",
        "--app-version", version.toString(),
        "--vendor", "DearNotes",
        "--description", "ブロックで書くメモアプリ",
        "--input", packageInput.get().asFile.absolutePath,
        "--main-jar", "dearnotes.jar",
        "--main-class", "app.dearnotes.Launcher",
        "--runtime-image", runtimeDir.get().asFile.absolutePath,
        "--dest", dest.absolutePath,
        "--java-options", "--enable-native-access=ALL-UNNAMED",
        "--java-options", "-Dfile.encoding=UTF-8",
    )
    if (isMacOs) {
        args += listOf("--type", "dmg", "--mac-package-identifier", "app.dearnotes")
        file("packaging/mac/DearNotes.icns").takeIf { it.exists() }
            ?.let { args += listOf("--icon", it.absolutePath) }
    } else if (isWindows) {
        args += listOf(
            "--type", "msi", "--win-menu", "--win-shortcut", "--win-dir-chooser",
            "--win-per-user-install", "--win-upgrade-uuid", winUpgradeUuid,
        )
        file("packaging/win/DearNotes.ico").takeIf { it.exists() }
            ?.let { args += listOf("--icon", it.absolutePath) }
    } else {
        args += listOf("--type", "app-image")
    }
    commandLine(args)
}
