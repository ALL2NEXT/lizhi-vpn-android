package io.github.HanJason233.chimera.feature.nodegrouping.domain

import io.github.HanJason233.chimera.data.model.Node
import io.github.HanJason233.chimera.feature.nodegrouping.model.CountryContinent
import io.github.HanJason233.chimera.feature.nodegrouping.model.CountryOption
import io.github.HanJason233.chimera.util.CountryCodeResolver

/** 地区组策略：将所选仓库源的节点按地区聚合并统计数量。 */
internal object CountryGroupingStrategy {
    // 按联合国 M49 地理分区归类；北美洲包含中美洲和加勒比地区，南极洲单独列出。
    private val countryCodesByContinent = mapOf(
        CountryContinent.ASIA to setOf(
            "AF", "AM", "AZ", "BH", "BD", "BT", "BN", "KH", "CN", "CY", "GE", "HK",
            "IN", "ID", "IR", "IQ", "IL", "JP", "JO", "KZ", "KP", "KR", "KW", "KG",
            "LA", "LB", "MO", "MY", "MV", "MN", "MM", "NP", "OM", "PK", "PS", "PH",
            "QA", "SA", "SG", "LK", "SY", "TW", "TJ", "TH", "TL", "TR", "TM", "AE",
            "UZ", "VN", "YE"
        ),
        CountryContinent.NORTH_AMERICA to setOf(
            "AI", "AG", "AW", "BS", "BB", "BZ", "BM", "BQ", "VG", "CA", "KY", "CR",
            "CU", "CW", "DM", "DO", "SV", "GL", "GD", "GP", "GT", "HT", "HN", "JM",
            "MQ", "MX", "MS", "NI", "PA", "PR", "BL", "KN", "LC", "MF", "PM", "VC",
            "SX", "TT", "TC", "US", "VI"
        ),
        CountryContinent.OCEANIA to setOf(
            "AS", "AU", "CX", "CC", "CK", "FJ", "PF", "GU", "KI", "MH", "FM", "NR",
            "NC", "NZ", "NU", "NF", "MP", "PW", "PG", "PN", "WS", "SB", "TK", "TO",
            "TV", "UM", "VU", "WF", "HM"
        ),
        CountryContinent.EUROPE to setOf(
            "AX", "AL", "AD", "AT", "BY", "BE", "BA", "BG", "HR", "CZ", "DK", "EE",
            "FO", "FI", "FR", "DE", "GI", "GR", "GG", "HU", "IS", "IE", "IM", "IT",
            "JE", "LV", "LI", "LT", "LU", "MT", "MD", "MC", "ME", "NL", "MK", "NO",
            "PL", "PT", "RO", "RU", "SM", "RS", "SK", "SI", "ES", "SJ", "SE", "CH",
            "UA", "GB", "VA"
        ),
        CountryContinent.AFRICA to setOf(
            "DZ", "AO", "BJ", "BW", "BF", "BI", "CV", "CM", "CF", "TD", "KM", "CG",
            "CD", "CI", "DJ", "EG", "GQ", "ER", "SZ", "ET", "GA", "GM", "GH", "GN",
            "GW", "KE", "LS", "LR", "LY", "MG", "MW", "ML", "MR", "MU", "MA", "MZ",
            "NA", "NE", "NG", "RE", "RW", "ST", "SN", "SC", "SL", "SH", "SO", "ZA",
            "SS", "SD", "TZ", "TG", "TN", "UG", "EH", "IO", "YT", "ZM", "ZW", "TF"
        ),
        CountryContinent.SOUTH_AMERICA to setOf(
            "AR", "BO", "BV", "BR", "CL", "CO", "EC", "FK", "GF", "GY", "PY", "PE",
            "GS", "SR", "UY", "VE"
        ),
        CountryContinent.ANTARCTICA to setOf("AQ")
    )
    private val continentByCountryCode = countryCodesByContinent
        .flatMap { (continent, codes) -> codes.map { code -> code to continent } }
        .toMap()

    fun groups(nodes: List<Node>): List<CountryOption> =
        nodes.asSequence()
            .mapNotNull(Node::getCountryCode)
            .groupingBy { it }
            .eachCount()
            .mapNotNull { (code, nodeCount) ->
                val continent = continentByCountryCode[code] ?: return@mapNotNull null
                val name = when (code) {
                    "CN" -> "中国大陆"
                    "TW" -> "中国台湾"
                    "HK" -> "中国香港"
                    "MO" -> "中国澳门"
                    else -> CountryCodeResolver.displayName(code) ?: return@mapNotNull null
                }
                CountryOption(
                    code = code,
                    name = name,
                    marker = if (code == "TW") "TW" else {
                        CountryCodeResolver.flagEmoji(code) ?: return@mapNotNull null
                    },
                    nodeCount = nodeCount,
                    continent = continent
                )
            }
            .sortedWith(
                compareBy<CountryOption> { it.continent.ordinal }
                    .thenBy(CountryOption::name)
                    .thenBy(CountryOption::code)
            )
}
