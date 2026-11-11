package io.github.HanJason233.chimera.network.subscription.importer

import io.github.HanJason233.chimera.data.model.PersonalSourceUserAgentMode
import io.github.HanJason233.chimera.data.model.Node
import io.github.HanJason233.chimera.network.subscription.ParsedSubscription
import io.github.HanJason233.chimera.network.subscription.SubscriptionParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

internal data class ParsedSubscriptionPart(
    val url: String?,
    val subscription: ParsedSubscription,
    val groupLabel: String? = null
)

internal class SubscriptionImportService(
    private val subscriptionParser: SubscriptionParser = SubscriptionParser()
) {
    internal suspend fun fetchRemoteSubscriptionResults(
        urls: List<String>,
        userAgentMode: PersonalSourceUserAgentMode = PersonalSourceUserAgentMode.AUTO,
        customUserAgent: String? = null
    ): List<Result<ParsedSubscription>> = coroutineScope {
        urls.map { url ->
            async(Dispatchers.IO) {
                subscriptionParser.fetchAndParseWithMetadata(
                    url = url,
                    userAgentMode = userAgentMode,
                    customUserAgent = customUserAgent
                )
            }
        }.awaitAll()
    }

    internal suspend fun fetchRemoteSubscriptions(
        urls: List<String>,
        userAgentMode: PersonalSourceUserAgentMode = PersonalSourceUserAgentMode.AUTO,
        customUserAgent: String? = null
    ): Result<List<ParsedSubscription>> {
        val results = fetchRemoteSubscriptionResults(urls, userAgentMode, customUserAgent)
        val failure = results.firstOrNull { it.isFailure }
        return if (failure != null) {
            Result.failure(
                failure.exceptionOrNull()
                    ?: IllegalStateException("Failed to fetch remote subscription")
            )
        } else {
            Result.success(results.map { it.getOrThrow() })
        }
    }

    internal suspend fun parseFullSubscriptionContent(content: LocalSubscriptionContent): ParsedSubscription =
        withContext(Dispatchers.Default) {
            ParsedSubscription(subscriptionParser.parseSubscription(content))
        }

    internal suspend fun parseSubscriptionInput(
        input: SubscriptionImportInput,
        userAgentMode: PersonalSourceUserAgentMode = PersonalSourceUserAgentMode.AUTO,
        customUserAgent: String? = null
    ): Result<List<ParsedSubscriptionPart>> {
        return when (input) {
            is SubscriptionImportInput.RemoteSubscription -> {
                fetchRemoteSubscriptions(listOf(input.url), userAgentMode, customUserAgent).map { parsed ->
                    listOf(ParsedSubscriptionPart(input.url, parsed.single()))
                }
            }

            is SubscriptionImportInput.MultipleRemoteSubscriptions -> {
                fetchRemoteSubscriptions(input.urls, userAgentMode, customUserAgent).map { parsed ->
                    input.urls.mapIndexed { index, url ->
                        ParsedSubscriptionPart(url, parsed[index])
                    }
                }
            }

            is SubscriptionImportInput.MixedSubscription -> {
                fetchRemoteSubscriptions(input.urls, userAgentMode, customUserAgent).map { parsed ->
                    buildList {
                        add(
                            ParsedSubscriptionPart(
                                url = null,
                                subscription = parseFullSubscriptionContent(input.content),
                                groupLabel = "导入内容"
                            )
                        )
                        input.urls.forEachIndexed { index, url ->
                            add(
                                ParsedSubscriptionPart(
                                    url = url,
                                    subscription = parsed[index]
                                )
                            )
                        }
                    }
                }
            }

            is SubscriptionImportInput.FullContent -> if (input.content.isBlank()) {
                Result.success(emptyList())
            } else {
                Result.success(
                    listOf(
                        ParsedSubscriptionPart(
                            url = null,
                            subscription = parseFullSubscriptionContent(input.content)
                        )
                    )
                )
            }
        }
    }
    // 节流控制

    internal suspend fun parseSingleNode(rawLink: String): Node? = withContext(Dispatchers.Default) {
        subscriptionParser.parseSubscription(rawLink).singleOrNull()
    }
}
