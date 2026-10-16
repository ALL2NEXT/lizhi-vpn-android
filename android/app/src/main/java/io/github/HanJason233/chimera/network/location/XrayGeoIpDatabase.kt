package io.github.HanJason233.chimera.network.location

import io.github.HanJason233.chimera.util.CountryCodeResolver
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.net.InetAddress

/** 读取 Xray/V2Ray geoip.dat 中的网段并按 IP 查询国家代码。 */
internal object XrayGeoIpDatabase {
    private data class FileVersion(val path: String, val length: Long, val modifiedAt: Long)
    private data class CachedDatabase(val version: FileVersion, val database: Database?)

    @Volatile
    private var cached: CachedDatabase? = null

    fun lookup(file: File, address: InetAddress): String? {
        if (!file.isFile || file.length() <= 0L) return null
        val version = runCatching {
            FileVersion(file.canonicalPath, file.length(), file.lastModified())
        }.getOrNull() ?: return null
        val current = cached?.takeIf { it.version == version }
        val database = if (current != null) {
            current.database
        } else {
            synchronized(this) {
                val current = cached
                if (current?.version == version) {
                    current.database
                } else {
                    val loaded = runCatching { readDatabase(file) }.getOrNull()
                    cached = CachedDatabase(version, loaded)
                    loaded
                }
            }
        }
        return database?.lookup(address)
    }

    private fun readDatabase(file: File): Database = Database().also { database ->
        BufferedInputStream(file.inputStream()).use { input ->
            val reader = ProtoReader(input)
            while (true) {
                val tag = reader.readTag() ?: break
                val field = tag ushr 3
                val wireType = tag and 7
                if (field == 1 && wireType == WIRE_LENGTH_DELIMITED) {
                    readGeoIp(reader.readBytes(), database)
                } else {
                    reader.skipField(wireType)
                }
            }
        }
    }

    private fun readGeoIp(bytes: ByteArray, database: Database) {
        val reader = ProtoReader(ByteArrayInputStream(bytes))
        var code: String? = null
        var reverseMatch = false
        val cidrs = mutableListOf<ByteArray>()

        while (true) {
            val tag = reader.readTag() ?: break
            val field = tag ushr 3
            val wireType = tag and 7
            when {
                field == 1 && wireType == WIRE_LENGTH_DELIMITED ->
                    code = reader.readBytes().toString(Charsets.UTF_8)
                field == 2 && wireType == WIRE_LENGTH_DELIMITED ->
                    cidrs += reader.readBytes()
                field == 3 && wireType == WIRE_VARINT ->
                    reverseMatch = reader.readVarint() != 0L
                else -> reader.skipField(wireType)
            }
        }

        // 反向匹配项表示“除该网段以外”，不能作为单个国家的归属范围。
        if (reverseMatch) return
        val countryCode = CountryCodeResolver.normalizeCountryCode(code) ?: return
        cidrs.forEach { cidr -> readCidr(cidr)?.let { (ip, prefix) ->
            database.add(ip, prefix, countryCode)
        } }
    }

    private fun readCidr(bytes: ByteArray): Pair<ByteArray, Int>? {
        val reader = ProtoReader(ByteArrayInputStream(bytes))
        var ip: ByteArray? = null
        var prefix: Long? = null
        while (true) {
            val tag = reader.readTag() ?: break
            val field = tag ushr 3
            val wireType = tag and 7
            when {
                field == 1 && wireType == WIRE_LENGTH_DELIMITED -> ip = reader.readBytes()
                field == 2 && wireType == WIRE_VARINT -> prefix = reader.readVarint()
                else -> reader.skipField(wireType)
            }
        }
        val address = ip ?: return null
        val prefixBits = prefix?.takeIf { it in 0L..128L }?.toInt() ?: return null
        if (address.size !in setOf(4, 16)) return null
        if (prefixBits > address.size * 8) return null
        return address to prefixBits
    }

    private class Database {
        private val ipv4 = PrefixIndex(32)
        private val ipv6 = PrefixIndex(128)

        fun add(address: ByteArray, prefix: Int, countryCode: String) {
            when (address.size) {
                4 -> ipv4.add(address, prefix, countryCode)
                16 -> ipv6.add(address, prefix, countryCode)
            }
        }

        fun lookup(address: InetAddress): String? {
            val bytes = address.address
            val normalized = when {
                bytes.size == 4 -> bytes
                bytes.size == 16 && isIpv4Mapped(bytes) -> bytes.copyOfRange(12, 16)
                else -> bytes
            }
            return when (normalized.size) {
                4 -> ipv4.find(normalized)
                16 -> ipv6.find(normalized)
                else -> null
            }
        }

        private fun isIpv4Mapped(address: ByteArray): Boolean =
            address.take(10).all { it == 0.toByte() } &&
                address[10] == 0xFF.toByte() && address[11] == 0xFF.toByte()
    }

    private class PrefixIndex(maxPrefix: Int) {
        private val entries = Array(maxPrefix + 1) { HashMap<String, String>() }

        fun add(address: ByteArray, prefix: Int, countryCode: String) {
            entries[prefix][maskedKey(address, prefix)] = countryCode
        }

        fun find(address: ByteArray): String? {
            for (prefix in address.size * 8 downTo 0) {
                entries[prefix][maskedKey(address, prefix)]?.let { return it }
            }
            return null
        }

        private fun maskedKey(address: ByteArray, prefix: Int): String {
            val masked = address.copyOf()
            val fullBytes = prefix / 8
            val remainingBits = prefix % 8
            if (remainingBits > 0) {
                val mask = 0xFF shl (8 - remainingBits)
                masked[fullBytes] = (masked[fullBytes].toInt() and mask).toByte()
            }
            val zeroFrom = fullBytes + if (remainingBits == 0) 0 else 1
            for (index in zeroFrom until masked.size) masked[index] = 0
            return buildString(masked.size * 2) {
                masked.forEach { byte ->
                    val value = byte.toInt() and 0xFF
                    append(HEX[value ushr 4])
                    append(HEX[value and 0x0F])
                }
            }
        }
    }

    /** 只实现 geoip.dat 所用到的 protobuf 字段读取，避免引入额外运行库。 */
    private class ProtoReader(private val input: InputStream) {
        fun readTag(): Int? {
            val first = input.read()
            if (first == -1) return null
            val tag = readVarint(first)
            require(tag in 1L..Int.MAX_VALUE.toLong()) { "geoip.dat 包含无效字段标记" }
            return tag.toInt()
        }

        fun readVarint(): Long {
            val first = input.read()
            if (first == -1) throw IllegalArgumentException("geoip.dat 数据意外结束")
            return readVarint(first)
        }

        fun readBytes(): ByteArray {
            val length = readVarint()
            require(length in 0L..MAX_FIELD_SIZE.toLong()) { "geoip.dat 字段长度无效" }
            val bytes = ByteArray(length.toInt())
            var offset = 0
            while (offset < bytes.size) {
                val count = input.read(bytes, offset, bytes.size - offset)
                if (count <= 0) throw IllegalArgumentException("geoip.dat 数据意外结束")
                offset += count
            }
            return bytes
        }

        fun skipField(wireType: Int) {
            when (wireType) {
                WIRE_VARINT -> readVarint()
                WIRE_FIXED64 -> skipBytes(8)
                WIRE_LENGTH_DELIMITED -> {
                    val length = readVarint()
                    require(length in 0L..MAX_FIELD_SIZE.toLong()) { "geoip.dat 字段长度无效" }
                    skipBytes(length)
                }
                WIRE_FIXED32 -> skipBytes(4)
                else -> throw IllegalArgumentException("geoip.dat 包含不支持的 protobuf 字段")
            }
        }

        private fun readVarint(firstByte: Int): Long {
            var result = 0L
            var shift = 0
            var current = firstByte
            while (shift < 64) {
                result = result or ((current and 0x7F).toLong() shl shift)
                if ((current and 0x80) == 0) return result
                shift += 7
                current = input.read()
                if (current == -1) throw IllegalArgumentException("geoip.dat 数据意外结束")
            }
            throw IllegalArgumentException("geoip.dat 包含无效 varint")
        }

        private fun skipBytes(length: Long) {
            var remaining = length
            while (remaining > 0) {
                val skipped = input.skip(remaining)
                if (skipped > 0) {
                    remaining -= skipped
                } else {
                    if (input.read() == -1) throw IllegalArgumentException("geoip.dat 数据意外结束")
                    remaining--
                }
            }
        }
    }

    private const val WIRE_VARINT = 0
    private const val WIRE_FIXED64 = 1
    private const val WIRE_LENGTH_DELIMITED = 2
    private const val WIRE_FIXED32 = 5
    private const val MAX_FIELD_SIZE = 32 * 1024 * 1024
    private const val HEX = "0123456789abcdef"
}
