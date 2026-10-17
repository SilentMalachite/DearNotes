import org.gradle.internal.os.OperatingSystem

plugins {
    application
    kotlin("jvm") version "2.4.21"
}

group = "app.dearnotes"
version = "1.3.2"

val javafxVersion = "25.0.4"

// Windows のアップグレードで同じ製品と見なすための固定 ID。変えないこと。
val winUpgradeUuid = "5f0c7c2e-3b7a-4d4e-9a63-2a1d8e6b9c41"

val os: OperatingSystem = OperatingSystem.current()
val arch: String = System.getProperty("os.arch")
val javafxPlatform = when {
    os.isMacOsX -> if (arch == "aarch64") "mac-aarch64" else "mac"
    os.isWindows -> "win"
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

fun jdkTool(name: String): String {
    val exe = if (os.isWindows) "$name.exe" else name
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

val jlinkRuntime = tasks.register<Exec>("jlinkRuntime") {
    group = "distribution"
    val out = runtimeDir.get().asFile
    outputs.dir(out)
    doFirst { delete(out) }
    commandLine(
        jdkTool("jlink"),
        "--add-modules", listOf(
            "java.base", "java.sql", "java.desktop", "java.logging", "java.xml",
            "java.naming", "jdk.unsupported", "jdk.charsets", "jdk.localedata",
        ).joinToString(","),
        "--include-locales", "en,ja",
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
    if (os.isMacOsX) {
        args += listOf("--type", "dmg", "--mac-package-identifier", "app.dearnotes")
        file("packaging/mac/DearNotes.icns").takeIf { it.exists() }
            ?.let { args += listOf("--icon", it.absolutePath) }
    } else if (os.isWindows) {
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
