package io.github.HanJason233.chimera.util

import java.util.Locale

/** Resolves country names and ISO 3166-1 alpha-2 codes found in node names. */
object CountryCodeResolver {
    private val whitespacePattern = Regex("\\s+")
    private const val FIRST_REGION_INDICATOR = 0x1F1E6
    private const val LAST_REGION_INDICATOR = 0x1F1FF

    private val isoCountryCodes by lazy {
        Locale.getISOCountries().map { it.uppercase(Locale.ROOT) }.toSet()
    }

    private val aliases = mapOf(
        "中国" to "CN",
        "中國" to "CN",
        "中国大陆" to "CN",
        "中國大陸" to "CN",
        "中国台湾" to "TW",
        "中國台灣" to "TW",
        "中國臺灣" to "TW",
        "中国香港" to "HK",
        "中國香港" to "HK",
        "中国澳门" to "MO",
        "中國澳門" to "MO",
        "美国" to "US",
        "美國" to "US",
        "英国" to "GB",
        "英國" to "GB",
        "uk" to "GB",
        "u.k." to "GB",
        "usa" to "US",
        "united states" to "US",
        "united states of america" to "US",
        "can" to "CA",
        "jpn" to "JP",
        "kor" to "KR",
        "chn" to "CN",
        "rus" to "RU",
        "deu" to "DE",
        "fra" to "FR",
        "aus" to "AU",
        "sgp" to "SG",
        "hkg" to "HK",
        "twn" to "TW",
        "俄罗斯" to "RU",
        "俄羅斯" to "RU",
        "俄国" to "RU",
        "俄國" to "RU",
        "日本" to "JP",
        "韩国" to "KR",
        "韓國" to "KR",
        "南韩" to "KR",
        "南韓" to "KR",
        "朝鲜" to "KP",
        "朝鮮" to "KP",
        "台湾" to "TW",
        "臺灣" to "TW",
        "香港" to "HK",
        "澳门" to "MO",
        "澳門" to "MO",
        "新加坡" to "SG",
        "加拿大" to "CA",
        "澳大利亚" to "AU",
        "澳大利亞" to "AU",
        "澳洲" to "AU",
        "德国" to "DE",
        "德國" to "DE",
        "法国" to "FR",
        "法國" to "FR",
        "荷兰" to "NL",
        "荷蘭" to "NL",
        "印度" to "IN",
        "泰国" to "TH",
        "泰國" to "TH",
        "越南" to "VN",
        "菲律宾" to "PH",
        "菲律賓" to "PH",
        "马来西亚" to "MY",
        "馬來西亞" to "MY",
        "印度尼西亚" to "ID",
        "印度尼西亞" to "ID",
        "印尼" to "ID",
        "土耳其" to "TR",
        "瑞士" to "CH",
        "瑞典" to "SE",
        "挪威" to "NO",
        "芬兰" to "FI",
        "芬蘭" to "FI",
        "丹麦" to "DK",
        "丹麥" to "DK",
        "波兰" to "PL",
        "波蘭" to "PL",
        "乌克兰" to "UA",
        "烏克蘭" to "UA",
        "以色列" to "IL",
        "南非" to "ZA",
        "墨西哥" to "MX",
        "巴西" to "BR",
        "智利" to "CL",
        "阿根廷" to "AR",
        "新西兰" to "NZ",
        "紐西蘭" to "NZ"
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

    /** 规范化地区代码；除国家代码外，也保留 EU 等可用旗帜表示的地区代码。 */
    fun normalizeRegionCode(value: String?): String? {
        val normalized = value?.trim()?.uppercase(Locale.ROOT) ?: return null
        return normalized.takeIf {
            it.length == 2 && it.all { character -> character in 'A'..'Z' }
        }
    }

    fun normalizeCountryCode(value: String?): String? {
        val normalized = normalizeText(value ?: return null)
        if (normalized.length == 2 && normalized.all { it in 'a'..'z' }) {
            val code = normalized.uppercase(Locale.ROOT)
            if (code in isoCountryCodes) return code
        }
        return aliases[normalized]
    }

    /** 从文本中的第一面旗帜提取两位地区代码。 */
    fun fromFlagEmoji(value: String?): String? {
        val text = value ?: return null
        var index = 0
        while (index < text.length) {
            val first = text.codePointAt(index)
            val firstLength = Character.charCount(first)
            if (first in FIRST_REGION_INDICATOR..LAST_REGION_INDICATOR) {
                val secondIndex = index + firstLength
                if (secondIndex < text.length) {
                    val second = text.codePointAt(secondIndex)
                    if (second in FIRST_REGION_INDICATOR..LAST_REGION_INDICATOR) {
                        return buildString(2) {
                            append(('A'.code + first - FIRST_REGION_INDICATOR).toChar())
                            append(('A'.code + second - FIRST_REGION_INDICATOR).toChar())
                        }
                    }
                }
            }
            index += firstLength
        }
        return null
    }

    /** 将两位地区代码转换为旗帜 Emoji。 */
    fun flagEmoji(code: String?): String? {
        val normalized = normalizeRegionCode(code) ?: return null
        return buildString {
            normalized.forEach { character ->
                appendCodePoint(FIRST_REGION_INDICATOR + character.code - 'A'.code)
            }
        }
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

    fun displayName(code: String?): String? {
        val normalized = normalizeCountryCode(code) ?: return null
        return Locale("", normalized).getDisplayCountry(Locale.SIMPLIFIED_CHINESE)
            .takeIf { it.isNotBlank() }
    }

    private fun normalizeText(value: String): String =
        value.trim().replace(whitespacePattern, " ").lowercase(Locale.ROOT)
}
