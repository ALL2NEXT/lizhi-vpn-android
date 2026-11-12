# For more information about using CMake with Android Studio, read the
# documentation: https://d.android.com/studio/projects/add-native-code.html

# Sets the minimum version of CMake required to build the native library.
cmake_minimum_required(VERSION 3.10.2)

# Declares and names the project.
project("chimera-vpn")

option(CHIMERA_CRASH_ON_FINDING "除仅上报项外命中后终止应用进程" ON)
option(CHIMERA_BLOCK_SCREENSHOTS "阻止应用窗口截图" ON)
option(CHIMERA_BLOCK_HTTP_PROXY "强制安全网络传输并绕过系统 HTTP/PAC 代理" ON)
option(CHIMERA_REPORT_FINDINGS "上报软件黑名单、USB 调试和开发者选项" ON)

option(CHIMERA_DETECT_CAPTURE "检测系统 HTTP 代理配置" ON)
option(CHIMERA_DETECT_XPOSED "检测 Xposed 痕迹" ON)
option(CHIMERA_DETECT_LSPOSED "检测 LSPosed 痕迹" ON)
option(CHIMERA_DETECT_FRIDA "检测 Frida 和进程跟踪信号" ON)
option(CHIMERA_DETECT_ROOT "检测 su、Root 框架和特征挂载" ON)
option(CHIMERA_DETECT_LSPATCH "检测 LSPatch 重打包和组件注入" ON)
option(CHIMERA_DETECT_NPATCH "检测 NPatch 文件、组件和原生库" ON)
option(CHIMERA_DETECT_SCREENSHOT "检测 Android 截图回调" ON)
option(CHIMERA_VERIFY_SIGNATURE "校验当前 APK 签名 SHA-256" ON)
option(CHIMERA_DETECT_BLACKLIST "检测黑名单软件包" ON)
option(CHIMERA_DETECT_ADB "检测 ADB 调试开关" ON)
option(CHIMERA_DETECT_DEVELOPER_MODE "检测开发者选项开关" ON)
set(CHIMERA_EXPECTED_CERT_SHA256 "0C2E2593E6D882994D2E8F19F60B5CE84FEA2BE609E07803978752A423EBD39F"
        CACHE STRING "预期的应用签名证书 SHA-256")

set(CMAKE_C_VISIBILITY_PRESET hidden)
set(CMAKE_CXX_VISIBILITY_PRESET hidden)
set(CMAKE_VISIBILITY_INLINES_HIDDEN YES)

# Creates and names a library, sets it as either STATIC or SHARED, and provides the relative paths to its source code.
# You can define multiple libraries, and CMake builds them for you.
# Gradle automatically packages shared libraries with your APK.

add_library( # Sets the name of the library.
             native-lib

             # Sets the library as a shared library.
             SHARED

             # Provides a relative path to your source file(s).
             native-lib.cpp
             security/security.cpp
             security/detection_utils.cpp
             security/capture_proxy_detector.cpp
             security/xposed_detector.cpp
             security/lsposed_detector.cpp
             security/frida_detector.cpp
             security/root_detector.cpp
             security/lspatch_detector.cpp
             security/npatch_detector.cpp
             security/signature_detector.cpp
             security/blacklist_detector.cpp
             security/adb_detector.cpp
             security/developer_mode_detector.cpp
             security/screenshot_capture_detector.cpp )

target_compile_definitions(native-lib PRIVATE
        CHIMERA_DETECT_CAPTURE=$<BOOL:${CHIMERA_DETECT_CAPTURE}>
        CHIMERA_DETECT_XPOSED=$<BOOL:${CHIMERA_DETECT_XPOSED}>
        CHIMERA_DETECT_LSPOSED=$<BOOL:${CHIMERA_DETECT_LSPOSED}>
        CHIMERA_DETECT_FRIDA=$<BOOL:${CHIMERA_DETECT_FRIDA}>
        CHIMERA_DETECT_ROOT=$<BOOL:${CHIMERA_DETECT_ROOT}>
        CHIMERA_DETECT_LSPATCH=$<BOOL:${CHIMERA_DETECT_LSPATCH}>
        CHIMERA_DETECT_NPATCH=$<BOOL:${CHIMERA_DETECT_NPATCH}>
        CHIMERA_DETECT_SCREENSHOT=$<BOOL:${CHIMERA_DETECT_SCREENSHOT}>
        CHIMERA_VERIFY_SIGNATURE=$<BOOL:${CHIMERA_VERIFY_SIGNATURE}>
        CHIMERA_DETECT_BLACKLIST=$<BOOL:${CHIMERA_DETECT_BLACKLIST}>
        CHIMERA_DETECT_ADB=$<BOOL:${CHIMERA_DETECT_ADB}>
        CHIMERA_DETECT_DEVELOPER_MODE=$<BOOL:${CHIMERA_DETECT_DEVELOPER_MODE}>
        CHIMERA_CRASH_ON_FINDING=$<BOOL:${CHIMERA_CRASH_ON_FINDING}>
        CHIMERA_BLOCK_SCREENSHOTS=$<BOOL:${CHIMERA_BLOCK_SCREENSHOTS}>
        CHIMERA_BLOCK_HTTP_PROXY=$<BOOL:${CHIMERA_BLOCK_HTTP_PROXY}>
        CHIMERA_REPORT_FINDINGS=$<BOOL:${CHIMERA_REPORT_FINDINGS}>
        CHIMERA_EXPECTED_CERT_SHA256=\"${CHIMERA_EXPECTED_CERT_SHA256}\")

target_compile_options(native-lib PRIVATE
        -fvisibility=hidden
        -fvisibility-inlines-hidden
        -ffunction-sections
        -fdata-sections)

# Searches for a specified prebuilt library and stores the path as a variable.
# Because CMake includes system libraries in the search path by default, you only need to specify the name of the public NDK library you want to add.
# CMake verifies that the library exists before completing its build.

find_library( # Sets the name of the path variable.
              log-lib

              # Specifies the name of the NDK library that you want CMake to locate.
              log )
find_library(dl-lib dl)

# 16 KB page size alignment (required for Android 15+ / Google Play)
target_link_options(native-lib PRIVATE
        "-Wl,-z,max-page-size=16384"
        "-Wl,--gc-sections")

# Specifies libraries CMake should link to your target library. You can link multiple libraries, such as libraries you define in this build script, prebuilt third-party libraries, or system libraries.

target_link_libraries( # Specifies the target library.
                       native-lib

                       # Links the target library to the log library included in the NDK.
                       ${log-lib} ${dl-lib} )
