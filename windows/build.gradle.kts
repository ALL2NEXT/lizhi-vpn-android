import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.4.10"
    id("org.jetbrains.compose") version "1.11.1"
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.10"
}

group = "xyz.a202132"
version = "3.0.2"

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation("org.jetbrains.compose.material3:material3:1.9.0")
    implementation("org.jetbrains.compose.material:material-icons-extended:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.10.2")
    implementation("com.google.code.gson:gson:2.13.2")
    implementation("com.github.sarxos:webcam-capture:0.3.12")
    implementation("com.google.zxing:core:3.5.3")
    implementation("com.google.zxing:javase:3.5.3")

    testImplementation(kotlin("test-junit5"))
}

sourceSets {
    main {
        // Reuse the current connection-state artwork instead of maintaining copies.
        resources.srcDirs(
            "../android/app/src/main/res/drawable-nodpi",
            "../android/app/src/main/assets"
        )
    }
}

val buildSingBox by tasks.registering(Exec::class) {
    group = "firefly windows"
    description = "Build the pinned sing-box source and Windows runtime resources"
    workingDir(projectDir)
    commandLine(
        "powershell.exe",
        "-NoProfile",
        "-ExecutionPolicy",
        "Bypass",
        "-File",
        file("scripts/build-sing-box.ps1").absolutePath
    )
    inputs.dir("../sing-box")
    inputs.file("scripts/build-sing-box.ps1")
    outputs.files(
        "runtime/windows-x64/sing-box.exe",
        "runtime/windows-x64/libcronet.dll"
    )
}

tasks.matching { task ->
    task.name == "run" ||
        task.name == "createDistributable" ||
        task.name.startsWith("package", ignoreCase = true)
}.configureEach {
    dependsOn(buildSingBox)
}

tasks.matching { it.name == "prepareAppResources" }.configureEach {
    dependsOn(buildSingBox)
}

val verifyRuntimeModules by tasks.registering {
    group = "verification"
    description = "Verify modules required by the packaged FireflyVPN runtime"
    dependsOn("createRuntimeImage")

    val releaseFile = layout.buildDirectory.file("compose/tmp/main/runtime/release")
    inputs.file(releaseFile)
    doLast {
        val runtimeMetadata = releaseFile.get().asFile.readText(Charsets.UTF_8)
        check("java.net.http" in runtimeMetadata) {
            "Packaged runtime is missing java.net.http; BackendClient cannot start"
        }
    }
}

tasks.matching {
    it.name.startsWith("package", ignoreCase = true) ||
        it.name.contains("Distributable", ignoreCase = true)
}.configureEach {
    dependsOn(verifyRuntimeModules)
}

tasks.test {
    useJUnitPlatform()
}

compose.desktop {
    application {
        mainClass = "xyz.a202132.windows.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Exe)
            modules("java.net.http")
            packageName = "FireflyVPN"
            packageVersion = project.version.toString()
            description = "FireflyVPN Windows client"
            vendor = "FireflyVPN"
            licenseFile.set(rootProject.file("../LICENSE"))
            appResourcesRootDir.set(project.layout.projectDirectory.dir("runtime"))

            windows {
                // Reuse the Android launcher artwork for the Windows app,
                // including the generated executable, shortcuts and taskbar.
                iconFile.set(project.file("src/main/resources/firefly.ico"))
                dirChooser = true
                menuGroup = "FireflyVPN"
                upgradeUuid = "f0f23d8d-0a4b-4f66-94f9-77b9308a0b9d"
            }
        }
    }
}
