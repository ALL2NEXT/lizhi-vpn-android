package io.github.HanJason233.chimera.util

import java.math.BigInteger

/** Compare numeric, dotted app versions such as 1.2.10 and 1.2.9. */
fun compareAppVersions(left: String, right: String): Int? {
    fun parse(value: String): List<BigInteger>? {
        val normalized = value.trim().removePrefix("v").removePrefix("V")
        if (normalized.isEmpty() || !normalized.matches(Regex("\\d+(?:\\.\\d+)*"))) return null
        return normalized.split('.').map { it.toBigIntegerOrNull() ?: return null }
    }

    val leftParts = parse(left) ?: return null
    val rightParts = parse(right) ?: return null
    val length = maxOf(leftParts.size, rightParts.size)
    for (index in 0 until length) {
        val comparison = leftParts.getOrElse(index) { BigInteger.ZERO }
            .compareTo(rightParts.getOrElse(index) { BigInteger.ZERO })
        if (comparison != 0) return comparison
    }
    return 0
}
