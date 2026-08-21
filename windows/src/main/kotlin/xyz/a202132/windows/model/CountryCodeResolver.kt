package xyz.a202132.windows.model

import java.util.LinkedHashMap
import java.util.Locale

/** Mirrors Android's country inference so node cards can always show a useful flag. */
internal object CountryCodeResolver {
    private val isoCountryCodes by lazy {
        Locale.getISOCountries().map { it.uppercase(Locale.ROOT) }.toSet()
    }

    private val aliases = mapOf(
        "\u4e2d\u56fd" to "CN",
        "\u4e2d\u56fd\u5927\u9646" to "CN",
        "\u7f8e\u56fd" to "US",
        "\u82f1\u56fd" to "GB",
        "\u65e5\u672c" to "JP",
        "\u97e9\u56fd" to "KR",
        "\u671d\u9c9c" to "KP",
        "\u53f0\u6e7e" to "TW",
        "\u9999\u6e2f" to "HK",
        "\u6fb3\u95e8" to "MO",
        "\u65b0\u52a0\u5761" to "SG",
        "\u52a0\u62ff\u5927" to "CA",
        "\u6fb3\u5927\u5229\u4e9a" to "AU",
        "\u4fc4\u7f57\u65af" to "RU",
        "\u5fb7\u56fd" to "DE",
        "\u6cd5\u56fd" to "FR",
        "\u8377\u5170" to "NL",
        "\u5370\u5ea6" to "IN",
        "\u6cf0\u56fd" to "TH",
        "\u8d8a\u5357" to "VN",
        "\u9a6c\u6765\u897f\u4e9a" to "MY",
        "\u83f2\u5f8b\u5bbe" to "PH",
        "\u5370\u5c3c" to "ID",
        "\u571f\u8033\u5176" to "TR",
        "\u745e\u58eb" to "CH",
        "\u745e\u5178" to "SE",
        "\u632a\u5a01" to "NO",
        "\u82ac\u5170" to "FI",
        "\u4e39\u9ea6" to "DK",
        "\u6ce2\u5170" to "PL",
        "\u4e4c\u514b\u5170" to "UA",
        "\u4ee5\u8272\u5217" to "IL",
        "\u5357\u975e" to "ZA",
        "\u58a8\u897f\u54e5" to "MX",
        "\u5df4\u897f" to "BR",
        "\u667a\u5229" to "CL",
        "\u963f\u6839\u5ef7" to "AR",
        "\u65b0\u897f\u5170" to "NZ",
        "uk" to "GB",
        "u.k." to "GB",
        "usa" to "US",
        "united states" to "US",
        "jpn" to "JP",
        "kor" to "KR",
        "chn" to "CN",
        "rus" to "RU",
        "deu" to "DE",
        "fra" to "FR",
        "aus" to "AU",
        "sgp" to "SG",
        "hkg" to "HK",
        "twn" to "TW"
    ).mapKeys { (name, _) -> normalizeText(name) }

    private val countryNames by lazy {
        val names = LinkedHashMap<String, String>()
        Locale.getISOCountries().forEach { rawCode ->
            val code = rawCode.uppercase(Locale.ROOT)
            val locale = Locale("", code)
            listOf(
                locale.getDisplayCountry(Locale.SIMPLIFIED_CHINESE),
                locale.getDisplayCountry(Locale.TRADITIONAL_CHINESE),
                locale.getDisplayCountry(Locale.ENGLISH)
            ).forEach { displayName ->
                val normalized = normalizeText(displayName)
                if (normalized.isNotBlank() && normalized != code.lowercase(Locale.ROOT)) {
                    names[normalized] = code
                }
            }
        }
        names.putAll(aliases)
        names.entries.sortedByDescending { it.key.length }
    }

    private val codePattern = Regex("(?<![A-Za-z])([A-Za-z]{2})(?![A-Za-z])")

    fun normalizeCountryCode(value: String?): String? {
        val normalized = normalizeText(value ?: return null)
        if (normalized.length == 2 && normalized.all { it in 'a'..'z' }) {
            val code = normalized.uppercase(Locale.ROOT)
            if (code in isoCountryCodes) return code
        }
        return aliases[normalized]
    }

    fun fromNodeName(name: String?): String? {
        val normalizedName = normalizeText(name ?: return null)
        if (normalizedName.isBlank()) return null

        var bestStart = Int.MAX_VALUE
        var bestLength = 0
        var bestCode: String? = null
        countryNames.forEach { (countryName, code) ->
            val start = normalizedName.indexOf(countryName)
            if (start >= 0 && (start < bestStart || (start == bestStart && countryName.length > bestLength))) {
                bestStart = start
                bestLength = countryName.length
                bestCode = code
            }
        }
        codePattern.findAll(normalizedName).forEach { match ->
            val code = normalizeCountryCode(match.groupValues[1]) ?: return@forEach
            val start = match.range.first
            if (start < bestStart || (start == bestStart && match.value.length > bestLength)) {
                bestStart = start
                bestLength = match.value.length
                bestCode = code
            }
        }
        return bestCode
    }

    private fun normalizeText(value: String): String =
        value.trim().replace(Regex("\\s+"), " ").lowercase(Locale.ROOT)
}
