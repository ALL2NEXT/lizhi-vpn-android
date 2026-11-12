package io.github.HanJason233.chimera.network.transport

import java.io.Closeable
import java.io.IOException

internal interface TransportSession : Closeable {
    fun seal(plaintext: ByteArray): ByteArray
    fun open(packet: ByteArray): ByteArray
}

internal fun interface TransportCrypto {
    fun create(publicKey: ByteArray): TransportSession
}

/** AES keys, random generation, RSA-OAEP and GCM authentication stay in C++. */
internal object NativeTransportCrypto : TransportCrypto {
    private val loaded by lazy { System.loadLibrary("native-lib") }

    override fun create(publicKey: ByteArray): TransportSession {
        try {
            loaded
            val handle = nativeCreate(publicKey)
            if (handle == 0L) throw IOException("无法创建加密通信会话")
            return object : TransportSession {
                override fun seal(plaintext: ByteArray) = nativeSeal(handle, plaintext)
                override fun open(packet: ByteArray) = nativeOpen(handle, packet)
                override fun close() = nativeRelease(handle)
            }
        } catch (error: LinkageError) {
            throw IOException("Native 加密模块不可用", error)
        }
    }

    @JvmStatic private external fun nativeCreate(publicKey: ByteArray): Long
    @JvmStatic private external fun nativeSeal(handle: Long, plaintext: ByteArray): ByteArray
    @JvmStatic private external fun nativeOpen(handle: Long, packet: ByteArray): ByteArray
    @JvmStatic private external fun nativeRelease(handle: Long)
}
