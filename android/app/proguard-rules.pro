# Add project specific ProGuard rules here.

# JNI_OnLoad resolves these classes by their original names in Release builds.
-keep class io.github.HanJason233.chimera.security.detection.NativeSecurityDetector { *; }

# HEV is the runtime-selected Android TUN adapter used by all proxy cores.
-keep class io.github.HanJason233.chimera.core.hev.** { *; }

# Keep data models for Gson
-keep class io.github.HanJason233.chimera.data.model.** { *; }
-keep interface io.github.HanJason233.chimera.data.model.** { *; }
-keep class io.github.HanJason233.chimera.data.local.PendingDailyTrafficRecord { *; }
-keepattributes Signature
-keepattributes *Annotation*

# Keep Room entities
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *

# Retrofit
-keepattributes Signature, InnerClasses, EnclosingMethod
-keepattributes *Annotation*
-keep class retrofit2.** { *; }
-keepclasseswithmembers class * {
    @retrofit2.http.* <methods>;
}
-keep interface io.github.HanJason233.chimera.network.ApiService { *; }
-keep class kotlin.coroutines.Continuation

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }

# Clash subscription YAML parsing
-keep class org.yaml.snakeyaml.** { *; }
-dontwarn org.yaml.snakeyaml.**

# SQLCipher (JNI depends on internal field/method names; do not obfuscate)
-keep class net.sqlcipher.** { *; }
-dontwarn net.sqlcipher.**
# Future-proof if migrating to newer sqlcipher-android package names
-keep class net.zetetic.database.sqlcipher.** { *; }
-dontwarn net.zetetic.database.sqlcipher.**

# Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}

# Remove verbose/debug/info logs; retain warnings and errors for security diagnostics.
-assumenosideeffects class android.util.Log {
    public static int d(...);
    public static int v(...);
    public static int i(...);
}
