package io.github.HanJason233.chimera.util

/**
 * Small JNI bridge kept deliberately generic. The implementation lives in the
 * native library so the payload transform and its private alphabet are not
 * present in Kotlin bytecode.
 */
internal object RuntimeTuner {

    init {
        System.loadLibrary("native-lib")
    }

    @JvmStatic
    external fun c1(value: String, route: String): String
}
