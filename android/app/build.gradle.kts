import java.io.ByteArrayOutputStream
import java.io.FileInputStream
import java.util.Properties
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JavaToolchainService

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

val singBoxNdkVersion = "28.0.13004108"

// The repository root pins the sing-box source revision in this submodule.
// Android builds it directly instead of consuming a checked-in/prebuilt
// libbox.aar.
val singBoxSourceDir = rootProject.file("../core/sing-box")
val generatedLibboxAar = layout.buildDirectory.file("generated/sing-box/libbox.aar")
val xraySourceDir = rootProject.file("../core/xray-core")
val xrayRunnerDir = rootProject.file("native/xray-runner")
val generatedXrayJniDir = layout.buildDirectory.dir("generated/xray/jniLibs")
val xrayCoreVersion = "26.3.27"
val hevSourceDir = rootProject.file("../hev-socks5-tunnel")
val hevRunnerDir = rootProject.file("native/hev-runner")
val generatedHevJniDir = layout.buildDirectory.dir("generated/hev/jniLibs")
val hevTunnelVersion = "2.16.0"

val buildLibbox = tasks.register("buildLibbox") {
    group = "sing-box"
    description = "Build libbox.aar from the pinned sing-box source submodule"

    inputs.files(fileTree(singBoxSourceDir) {
        exclude(".git/**")
        exclude("build/**")
        exclude("bin/**")
        exclude("dist/**")
        exclude("libbox*.aar")
    })
    outputs.file(generatedLibboxAar)
    inputs.property("javaLanguageVersion", 17)

    doLast {
        val goExecutable = if (System.getProperty("os.name").startsWith("Windows")) "go.exe" else "go"
        check(singBoxSourceDir.resolve("go.mod").isFile) {
            "sing-box source is missing at ${singBoxSourceDir.absolutePath}. " +
                "Run `git submodule update --init --recursive`."
        }

        val localProperties = Properties()
        rootProject.file("local.properties").takeIf { it.isFile }?.inputStream()?.use {
            localProperties.load(it)
        }
        val sdkPath = localProperties.getProperty("sdk.dir")
            ?: System.getenv("ANDROID_HOME")
            ?: error("Android SDK not found. Set sdk.dir in android/local.properties or ANDROID_HOME.")
        val sdkDir = File(sdkPath)
        val ndkDir = sdkDir.resolve("ndk").resolve(singBoxNdkVersion)
        check(ndkDir.resolve("source.properties").isFile) {
            "Android NDK $singBoxNdkVersion not found at ${ndkDir.absolutePath}. " +
                "Install it from Android Studio SDK Manager or with `sdkmanager \"ndk;$singBoxNdkVersion\"`."
        }

        fun commandOutput(vararg command: String): String {
            val output = ByteArrayOutputStream()
            exec {
                commandLine(*command)
                standardOutput = output
                errorOutput = output
                isIgnoreExitValue = true
            }
            return output.toString(Charsets.UTF_8.name())
        }

        val goPath = commandOutput(goExecutable, "env", "GOPATH").trim()
        check(goPath.isNotEmpty()) { "Unable to determine GOPATH for gomobile." }
        val goBinDir = File(goPath).resolve("bin")
        val executableSuffix = if (System.getProperty("os.name").startsWith("Windows")) ".exe" else ""
        val gomobile = goBinDir.resolve("gomobile$executableSuffix")
        val gobind = goBinDir.resolve("gobind$executableSuffix")

        fun isExpectedGomobile(binary: File, commandPackage: String): Boolean {
            if (!binary.isFile) return false
            val info = commandOutput(goExecutable, "version", "-m", binary.absolutePath)
                .replace(Regex("\\s+"), " ")
            return info.contains("path $commandPackage") &&
                info.contains("mod github.com/sagernet/gomobile v0.1.11")
        }

        // sing-box's build helper requires the SagerNet gomobile fork. Install
        // it only when the local Go tool does not already provide that exact
        // version (a different gomobile fork is not compatible).
        if (!isExpectedGomobile(gomobile, "github.com/sagernet/gomobile/cmd/gomobile") ||
            !isExpectedGomobile(gobind, "github.com/sagernet/gomobile/cmd/gobind")) {
            exec {
                commandLine(goExecutable, "install", "github.com/sagernet/gomobile/cmd/gomobile@v0.1.11")
            }
            exec {
                commandLine(goExecutable, "install", "github.com/sagernet/gomobile/cmd/gobind@v0.1.11")
            }
        }

        // The binding generator requires Java 17 even when Gradle runs on a newer JDK.
        val javaHome = java17Launcher.get().metadata.installationPath.asFile
        exec {
            workingDir(singBoxSourceDir)
            environment("ANDROID_HOME", sdkDir.absolutePath)
            environment("ANDROID_SDK_HOME", sdkDir.absolutePath)
            environment("ANDROID_NDK_HOME", ndkDir.absolutePath)
            environment("NDK", ndkDir.absolutePath)
            environment("JAVA_HOME", javaHome.absolutePath)
            commandLine(
                goExecutable,
                "run",
                "./cmd/internal/build_libbox",
                "-target",
                "android",
                "-platform",
                "android"
            )
        }

        val sourceAar = singBoxSourceDir.resolve("libbox.aar")
        check(sourceAar.isFile) {
            "sing-box build completed without producing ${sourceAar.absolutePath}."
        }
        val outputAar = generatedLibboxAar.get().asFile
        outputAar.parentFile.mkdirs()
        sourceAar.copyTo(outputAar, overwrite = true)

        // build_libbox also creates the legacy artifact. The Android app uses
        // the main API-23 artifact and must not leave build products in the
        // source submodule after Gradle finishes.
        sourceAar.delete()
        singBoxSourceDir.resolve("libbox-legacy.aar").delete()
    }
}

// Xray is compiled as a standalone PIE process and packaged into nativeLibraryDir.
// HEV owns the Android TUN; this runner only handles core lifecycle control.
val buildXrayCore = tasks.register("buildXrayCore") {
    group = "xray"
    description = "Build the pinned Xray-core source for the Android ABIs"

    inputs.files(fileTree(xraySourceDir) {
        exclude(".git/**")
        exclude("build/**")
        exclude("release/**")
    })
    inputs.files(fileTree(xrayRunnerDir) { exclude("*.exe") })
    inputs.property("ndkVersion", singBoxNdkVersion)
    inputs.property("androidApi", 24)
    val arm32Output = generatedXrayJniDir.map { it.file("armeabi-v7a/libxray.so") }
    val arm64Output = generatedXrayJniDir.map { it.file("arm64-v8a/libxray.so") }
    outputs.files(arm32Output, arm64Output)

    doLast {
        val goExecutable = if (System.getProperty("os.name").startsWith("Windows")) "go.exe" else "go"
        check(xraySourceDir.resolve("go.mod").isFile) {
            "Xray-core source is missing at ${xraySourceDir.absolutePath}. " +
                "Run `git submodule update --init --recursive`."
        }

        val localProperties = Properties()
        rootProject.file("local.properties").takeIf { it.isFile }?.inputStream()?.use {
            localProperties.load(it)
        }
        val sdkPath = localProperties.getProperty("sdk.dir")
            ?: System.getenv("ANDROID_HOME")
            ?: error("Android SDK not found. Set sdk.dir in android/local.properties or ANDROID_HOME.")
        val hostTag = when {
            System.getProperty("os.name").startsWith("Windows") -> "windows-x86_64"
            System.getProperty("os.name").startsWith("Mac") -> "darwin-x86_64"
            else -> "linux-x86_64"
        }
        val clangSuffix = if (hostTag.startsWith("windows")) ".cmd" else ""
        val clangBin = File(sdkPath).resolve("ndk/$singBoxNdkVersion/toolchains/llvm/prebuilt/$hostTag/bin")
        check(clangBin.isDirectory) { "Android NDK toolchain not found at ${clangBin.absolutePath}" }

        data class XrayAbi(
            val androidAbi: String,
            val goArch: String,
            val clangTarget: String,
            val goArm: String? = null
        )
        val abis = listOf(
            XrayAbi("armeabi-v7a", "arm", "armv7a-linux-androideabi", "7"),
            XrayAbi("arm64-v8a", "arm64", "aarch64-linux-android")
        )
        abis.forEach { abi ->
            val output = generatedXrayJniDir.get().file("${abi.androidAbi}/libxray.so").asFile
            output.parentFile.mkdirs()
            val clang = clangBin.resolve("${abi.clangTarget}24-clang$clangSuffix")
            check(clang.isFile) { "Android clang not found at ${clang.absolutePath}" }
            exec {
                workingDir(xrayRunnerDir)
                environment("GOOS", "android")
                environment("GOARCH", abi.goArch)
                environment("CGO_ENABLED", "1")
                environment("CC", clang.absolutePath)
                if (abi.goArm != null) environment("GOARM", abi.goArm)
                commandLine(
                    goExecutable,
                    "build",
                    "-trimpath",
                    "-buildvcs=false",
                    "-buildmode=pie",
                    "-ldflags=-s -w -buildid=",
                    "-o",
                    output.absolutePath,
                    "."
                )
            }
        }
    }
}

// HEV is the TUN-to-SOCKS data plane. It is compiled as
// an Android PIE executable instead of loaded into the app process, so a
// native teardown failure cannot take down the UI or VPN controller process.
val buildHevTunnel = tasks.register("buildHevTunnel") {
    group = "hev"
    description = "Build the pinned HEV tun2socks compatibility runner"

    inputs.files(fileTree(hevSourceDir) {
        exclude(".git/**")
        exclude("bin/**")
        exclude("build/**")
    })
    inputs.files(fileTree(hevRunnerDir) { exclude("*.exe") })
    inputs.property("version", hevTunnelVersion)
    inputs.property("ndkVersion", singBoxNdkVersion)
    inputs.property("androidApi", 24)
    val arm32Output = generatedHevJniDir.map { it.file("armeabi-v7a/libhev.so") }
    val arm64Output = generatedHevJniDir.map { it.file("arm64-v8a/libhev.so") }
    outputs.files(arm32Output, arm64Output)

    doLast {
        check(hevSourceDir.resolve("Android.mk").isFile &&
            hevSourceDir.resolve("src/core/.git").exists()) {
            "HEV source is missing at ${hevSourceDir.absolutePath}. " +
                "Run `git submodule update --init --recursive`."
        }
        val localProperties = Properties()
        rootProject.file("local.properties").takeIf { it.isFile }?.inputStream()?.use {
            localProperties.load(it)
        }
        val sdkPath = localProperties.getProperty("sdk.dir") ?: System.getenv("ANDROID_HOME")
            ?: error("Android SDK not found. Set sdk.dir in android/local.properties or ANDROID_HOME.")
        val ndkDir = File(sdkPath).resolve("ndk").resolve(singBoxNdkVersion)
        val ndkBuild = ndkDir.resolve(if (System.getProperty("os.name").startsWith("Windows")) "ndk-build.cmd" else "ndk-build")
        check(ndkBuild.isFile) { "Android ndk-build not found at ${ndkBuild.absolutePath}" }

        val outputRoot = layout.buildDirectory.dir("generated/hev/ndk").get().asFile
        val libsRoot = generatedHevJniDir.get().asFile
        val stagedSource = layout.buildDirectory.dir("generated/hev/source").get().asFile
        outputRoot.deleteRecursively()
        libsRoot.deleteRecursively()
        stagedSource.deleteRecursively()
        libsRoot.mkdirs()
        copy {
            from(hevSourceDir)
            exclude("**/.git/**")
            exclude("**/bin/**")
            exclude("**/build/**")
            into(stagedSource)
        }
        // The upstream repository uses Git symlinks for public headers. A
        // Windows checkout may materialize those as one-line placeholders;
        // resolve them in the generated build copy without modifying the
        // checked-out submodule.
        stagedSource.walkTopDown().filter { it.isFile && it.length() <= 1_024 }.forEach { file ->
            val target = file.readText(Charsets.UTF_8).trim()
            if (target.startsWith("../") && !target.contains('\n') && !target.contains('\r')) {
                val resolved = file.parentFile.resolve(target).canonicalFile
                check(resolved.isFile) { "HEV source link target is missing: $target" }
                resolved.copyTo(file, overwrite = true)
            }
        }
        // HEV exposes counters to this runner from a separate pthread. The
        // pinned upstream declarations are plain size_t, so make only the
        // generated build copy atomic and avoid a C data race while polling.
        val tunnelSource = stagedSource.resolve("src/hev-socks5-tunnel.c")
        val tunnelText = tunnelSource.readText(Charsets.UTF_8)
        check(tunnelText.contains("static size_t stat_tx_packets;")) {
            "Pinned HEV counter declarations changed; refresh the Android atomic patch."
        }
        tunnelSource.writeText(
            tunnelText
                .replace("#include <signal.h>", "#include <signal.h>\n#include <stdatomic.h>")
                .replace("static size_t stat_", "static _Atomic size_t stat_"),
            Charsets.UTF_8
        )
        exec {
            commandLine(ndkBuild.absolutePath,
                "NDK_PROJECT_PATH=null",
                "APP_BUILD_SCRIPT=${hevRunnerDir.resolve("Android.mk").absolutePath}",
                "APP_ABI=armeabi-v7a arm64-v8a",
                "APP_PLATFORM=android-24",
                "NDK_OUT=${outputRoot.absolutePath}",
                "NDK_LIBS_OUT=${libsRoot.absolutePath}",
                "HEV_ROOT=${stagedSource.absolutePath}",
                "REV_ID=$hevTunnelVersion",
                "V=1")
        }
        listOf("armeabi-v7a", "arm64-v8a").forEach { abi ->
            val binary = outputRoot.resolve("local/$abi/hev-runner")
            check(binary.isFile) { "HEV runner was not built for $abi" }
            val packaged = libsRoot.resolve("$abi/libhev.so")
            binary.copyTo(packaged, overwrite = true)
            // ndk-build also installs the executable under its module name;
            // keep only the stable libhev.so name consumed by ProcessBuilder.
            libsRoot.resolve("$abi/hev-runner").delete()
            packaged.setExecutable(true, false)
        }
    }
}

// JVM tests/lint need libbox types but do not execute the Xray or HEV Android
// binaries. Avoid rebuilding both native data paths for those tasks.
tasks.configureEach {
    if (name.startsWith("pre") && name.endsWith("Build")) {
        dependsOn(buildLibbox)
    }
    if (name.startsWith("merge") && name.endsWith("JniLibFolders")) {
        dependsOn(buildXrayCore)
        dependsOn(buildHevTunnel)
    }
}

val javaToolchains = extensions.getByType<JavaToolchainService>()
val java17Launcher = javaToolchains.launcherFor {
    languageVersion.set(JavaLanguageVersion.of(17))
}

android {
    namespace = "xyz.a202132.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "xyz.a202132.app"
        minSdk = 24
        targetSdk = 35
        versionCode = 23
        versionName = "3.0.2"
        buildConfigField("String", "XRAY_CORE_VERSION", "\"$xrayCoreVersion\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }

        externalNativeBuild {
            cmake {
                cppFlags("")
                // 限制只打包 ARM 架构的 .so 文件
                abiFilters("armeabi-v7a", "arm64-v8a")
            }
        }
    }

    // 分别输出 armeabi-v7a 与 arm64-v8a APK，不再合并成单个多 ABI APK。
    splits {
        abi {
            isEnable = true
            reset()
            include("armeabi-v7a", "arm64-v8a")
            isUniversalApk = false
        }
    }

    signingConfigs {
        create("release") {
            val keystorePropertiesFile = rootProject.file("keystore.properties")
            val keystoreProperties = Properties()
            if (keystorePropertiesFile.exists()) {
                keystoreProperties.load(FileInputStream(keystorePropertiesFile))
            }

            storeFile = if (keystoreProperties["storeFile"] != null) {
                rootProject.file(keystoreProperties["storeFile"] as String)
            } else {
                rootProject.file("firefly-release.jks")
            }
            storePassword = keystoreProperties["storePassword"] as String? ?: ""
            keyAlias = keystoreProperties["keyAlias"] as String? ?: ""
            keyPassword = keystoreProperties["keyPassword"] as String? ?: ""
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
        }
        debug {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.6"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        // 压缩 native libraries 以减小 APK 体积
        // 代价是首次启动时需要解压，略慢一点点
        jniLibs {
            useLegacyPackaging = true
        }
    }

    sourceSets.getByName("main").jniLibs.srcDir(generatedXrayJniDir)
    sourceSets.getByName("main").jniLibs.srcDir(generatedHevJniDir)

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    ndkVersion = singBoxNdkVersion
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.0.4")

    // Core Android
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    
    // Jetpack Compose - using stable BOM version
    implementation(platform("androidx.compose:compose-bom:2023.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material:material")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation("androidx.fragment:fragment-ktx:1.6.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.7.0")

    // Camera / QR scanning
    implementation("androidx.camera:camera-camera2:1.3.1")
    implementation("androidx.camera:camera-lifecycle:1.3.1")
    implementation("androidx.camera:camera-view:1.3.1")
    implementation("com.google.mlkit:barcode-scanning:17.2.0")
    
    // Navigation
    implementation("androidx.navigation:navigation-compose:2.7.6")
    
    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    
    // Network
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.retrofit2:retrofit:2.9.0")
    implementation("com.squareup.retrofit2:converter-gson:2.9.0")
    implementation("com.google.code.gson:gson:2.10.1")
    
    // Room Database
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")
    implementation("net.zetetic:sqlcipher-android:4.13.0")
    
    // DataStore
    implementation("androidx.datastore:datastore-preferences:1.0.0")
    
    // sing-box core is compiled from the pinned source submodule for every
    // clean build; no prebuilt AAR is consumed from app/libs.
    implementation(files(generatedLibboxAar))
    
    // Testing
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    androidTestImplementation(platform("androidx.compose:compose-bom:2023.10.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

}
