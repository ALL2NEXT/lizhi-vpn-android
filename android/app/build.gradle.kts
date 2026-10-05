import java.io.FileInputStream
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

val singBoxNdkVersion = "28.0.13004108"
val targetAbis = when (providers.gradleProperty("targetAbi").orNull) {
    null, "", "all" -> listOf("armeabi-v7a", "arm64-v8a")
    "arm64-v8a" -> listOf("arm64-v8a")
    "armeabi-v7a" -> listOf("armeabi-v7a")
    else -> error("Unsupported targetAbi. Use arm64-v8a, armeabi-v7a, or all.")
}

// The repository root pins the sing-box source revision in this submodule.
// Android builds the CLI runner directly instead of embedding libbox in the
// application process.
val singBoxSourceDir = rootProject.file("../core/sing-box")
val singBoxRunnerDir = rootProject.file("native/singbox-runner")
val generatedSingBoxJniDir = layout.buildDirectory.dir("generated/singbox/jniLibs")
val singBoxCoreVersion = "1.14.2"
val xraySourceDir = rootProject.file("../core/xray-core")
val xrayRunnerDir = rootProject.file("native/xray-runner")
val generatedXrayJniDir = layout.buildDirectory.dir("generated/xray/jniLibs")
val xrayCoreVersion = "26.3.27"
val hevSourceDir = rootProject.file("../hev-socks5-tunnel")
val hevRunnerDir = rootProject.file("native/hev-runner")
val generatedHevJniDir = layout.buildDirectory.dir("generated/hev/jniLibs")
val hevTunnelVersion = "2.16.0"

// sing-box is compiled as a standalone Android PIE process and packaged into
// nativeLibraryDir. HEV owns TUN; LAN sharing can add a separate SOCKS inbound.
val buildSingBoxCore = tasks.register("buildSingBoxCore") {
    group = "sing-box"
    description = "Build the pinned sing-box source for the Android ABIs"

    inputs.files(fileTree(singBoxSourceDir) {
        exclude(".git/**")
        exclude("build/**")
        exclude("bin/**")
        exclude("dist/**")
    })
    inputs.files(fileTree(singBoxRunnerDir) { exclude("*.exe") })
    inputs.property("version", singBoxCoreVersion)
    inputs.property("ndkVersion", singBoxNdkVersion)
    inputs.property("androidApi", 24)
    outputs.files(targetAbis.map { generatedSingBoxJniDir.map { dir -> dir.file("$it/libsingbox.so") } })

    doLast {
        val goExecutable = if (System.getProperty("os.name").startsWith("Windows")) "go.exe" else "go"
        check(singBoxSourceDir.resolve("go.mod").isFile) {
            "sing-box source is missing at ${singBoxSourceDir.absolutePath}. " +
                "Run `git submodule update --init --recursive`."
        }
        check(singBoxRunnerDir.resolve("go.mod").isFile) {
            "sing-box runner is missing at ${singBoxRunnerDir.absolutePath}."
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

        data class SingBoxAbi(
            val androidAbi: String,
            val goArch: String,
            val clangTarget: String,
            val goArm: String? = null
        )
        val abis = listOf(
            SingBoxAbi("armeabi-v7a", "arm", "armv7a-linux-androideabi", "7"),
            SingBoxAbi("arm64-v8a", "arm64", "aarch64-linux-android")
        ).filter { it.androidAbi in targetAbis }
        val tags = listOf(
            "with_gvisor", "with_quic", "with_wireguard", "with_utls",
            "with_naive_outbound", "with_clash_api", "with_conntrack",
            "badlinkname", "tfogo_checklinkname0", "with_tailscale",
            "ts_omit_logtail", "ts_omit_ssh", "ts_omit_drive", "ts_omit_taildrop",
            "ts_omit_webclient", "ts_omit_doctor", "ts_omit_capture", "ts_omit_kube",
            "ts_omit_aws", "ts_omit_synology", "ts_omit_bird"
        ).joinToString(",")
        abis.forEach { abi ->
            val output = generatedSingBoxJniDir.get().file("${abi.androidAbi}/libsingbox.so").asFile
            output.parentFile.mkdirs()
            val clang = clangBin.resolve("${abi.clangTarget}24-clang$clangSuffix")
            check(clang.isFile) { "Android clang not found at ${clang.absolutePath}" }
            exec {
                workingDir(singBoxRunnerDir)
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
                    "-tags", tags,
                    "-buildmode=pie",
                    "-ldflags=-X github.com/sagernet/sing-box/constant.Version=$singBoxCoreVersion -X internal/godebug.defaultGODEBUG=multipathtcp=0 -s -w -buildid= -checklinkname=0",
                    "-o", output.absolutePath,
                    "."
                )
            }
        }
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
    outputs.files(targetAbis.map { generatedXrayJniDir.map { dir -> dir.file("$it/libxray.so") } })

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
        ).filter { it.androidAbi in targetAbis }
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
    outputs.files(targetAbis.map { generatedHevJniDir.map { dir -> dir.file("$it/libhev.so") } })

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
                "APP_ABI=${targetAbis.joinToString(" ")}",
                "APP_PLATFORM=android-24",
                "NDK_OUT=${outputRoot.absolutePath}",
                "NDK_LIBS_OUT=${libsRoot.absolutePath}",
                "HEV_ROOT=${stagedSource.absolutePath}",
                "REV_ID=$hevTunnelVersion",
                "V=1")
        }
        targetAbis.forEach { abi ->
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

// Native core/data-plane binaries are required only when JNI folders are merged.
tasks.configureEach {
    if (name.startsWith("merge") && name.endsWith("JniLibFolders")) {
        dependsOn(buildSingBoxCore)
        dependsOn(buildXrayCore)
        dependsOn(buildHevTunnel)
    }
}

android {
    namespace = "io.github.HanJason233.chimera"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.HanJason233.chimera"
        minSdk = 24
        targetSdk = 35
        versionCode = 23
        versionName = "1.0.0"
        buildConfigField("String", "XRAY_CORE_VERSION", "\"$xrayCoreVersion\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }

        externalNativeBuild {
            cmake {
                cppFlags("")
                // 限制只打包 ARM 架构的 .so 文件
                abiFilters(*targetAbis.toTypedArray())
            }
        }
    }

    // 分别输出 armeabi-v7a 与 arm64-v8a APK，不再合并成单个多 ABI APK。
    splits {
        abi {
            isEnable = true
            reset()
            include(*targetAbis.toTypedArray())
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
                rootProject.file("chimera-release.jks").takeIf { it.exists() }
                    ?: rootProject.file("firefly-release.jks")
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
    sourceSets.getByName("main").jniLibs.srcDir(generatedSingBoxJniDir)
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
    implementation("com.google.zxing:core:3.5.4")
    
    // Navigation
    implementation("androidx.navigation:navigation-compose:2.7.6")
    
    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    
    // Network
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.retrofit2:retrofit:2.9.0")
    implementation("com.squareup.retrofit2:converter-gson:2.9.0")
    implementation("com.google.code.gson:gson:2.10.1")
    implementation("org.yaml:snakeyaml:2.2")
    
    // Room Database
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")
    implementation("net.zetetic:sqlcipher-android:4.13.0")
    
    // DataStore
    implementation("androidx.datastore:datastore-preferences:1.0.0")
    implementation("androidx.documentfile:documentfile:1.0.1")
    
    // Testing
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.16.1")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    androidTestImplementation(platform("androidx.compose:compose-bom:2023.10.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

}
