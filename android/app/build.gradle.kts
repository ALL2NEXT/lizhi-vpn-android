import java.io.ByteArrayOutputStream
import java.io.FileInputStream
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

val singBoxNdkVersion = "28.0.13004108"

// The repository root pins the sing-box source revision in this submodule.
// Android builds it directly instead of consuming a checked-in/prebuilt
// libbox.aar.
val singBoxSourceDir = rootProject.file("../sing-box")
val generatedLibboxAar = layout.buildDirectory.file("generated/sing-box/libbox.aar")
val xraySourceDir = rootProject.file("../xray-core")
val generatedXrayJniDir = layout.buildDirectory.dir("generated/xray/jniLibs")
val xrayCoreVersion = "26.3.27"

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

        val javaHome = System.getenv("JAVA_HOME") ?: System.getProperty("java.home")
        exec {
            workingDir(singBoxSourceDir)
            environment("ANDROID_HOME", sdkDir.absolutePath)
            environment("ANDROID_SDK_HOME", sdkDir.absolutePath)
            environment("ANDROID_NDK_HOME", ndkDir.absolutePath)
            environment("NDK", ndkDir.absolutePath)
            if (!javaHome.isNullOrBlank()) {
                environment("JAVA_HOME", javaHome)
            }
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

// Xray 特意编译为独立 PIE 可执行文件并打包到 nativeLibraryDir。
// 如果在进程内再加载一个 gomobile AAR，会在 libbox 旁引入第二套 Go/JNI 运行时。
// 进程隔离可以避免符号与运行时冲突，并确保 Android TUN 始终只由 sing-box 管理。
val buildXrayCore = tasks.register("buildXrayCore") {
    group = "xray"
    description = "Build the pinned Xray-core source for the Android ABIs"

    inputs.files(fileTree(xraySourceDir) {
        exclude(".git/**")
        exclude("build/**")
        exclude("release/**")
    })
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
                workingDir(xraySourceDir)
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
                    "./main"
                )
            }
        }
    }
}

// All Android variants resolve the generated AAR before compiling Kotlin or
// packaging the APK. This also covers unit-test variants such as
// preDebugUnitTestBuild.
tasks.configureEach {
    if (name.startsWith("pre") && name.endsWith("Build")) {
        dependsOn(buildLibbox)
        dependsOn(buildXrayCore)
    }
}

android {
    namespace = "xyz.a202132.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "xyz.a202132.app"
        minSdk = 24
        targetSdk = 35
        versionCode = 22
        versionName = "3.0.1"
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
