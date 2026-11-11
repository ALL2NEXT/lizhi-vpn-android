package io.github.HanJason233.chimera.feature.nodegrouping.data

import android.content.Context
import android.net.Uri
import android.util.Log
import io.github.HanJason233.chimera.data.local.NodeDao
import io.github.HanJason233.chimera.data.model.*
import io.github.HanJason233.chimera.data.repository.SettingsRepository
import io.github.HanJason233.chimera.feature.nodegrouping.data.icon.PersonalSourceIconStore
import io.github.HanJason233.chimera.feature.nodegrouping.data.icon.SubscriptionFaviconLoader
import io.github.HanJason233.chimera.network.subscription.ParsedSubscription
import io.github.HanJason233.chimera.network.subscription.importer.ParsedSubscriptionPart
import io.github.HanJason233.chimera.network.subscription.importer.SubscriptionImportInput
import io.github.HanJason233.chimera.network.subscription.importer.SubscriptionImportService
import io.github.HanJason233.chimera.network.subscription.importer.SubscriptionInputDetector
import io.github.HanJason233.chimera.network.nodetest.common.GlobalTestExecution
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

enum class SubscriptionGroupCreateResult {
    CREATED,
    MULTIPLE_CREATED,
    PARTIAL_CREATED,
    INVALID,
    FETCH_FAILED
}

enum class SubscriptionGroupEditResult {
    UPDATED,
    INVALID,
    FETCH_FAILED,
    NOT_FOUND
}

enum class SubscriptionGroupRefreshResult {
    REFRESHED,
    INVALID,
    FETCH_FAILED,
    NOT_FOUND,
    BUSY
}

enum class SubscriptionGroupNodeImportResult {
    IMPORTED,
    ALREADY_EXISTS,
    INVALID,
    FETCH_FAILED,
    NOT_FOUND
}

enum class LocalGroupNodeEditResult {
    UPDATED,
    INVALID,
    NOT_FOUND
}

enum class LocalGroupNodeDeleteResult {
    DELETED,
    NOT_FOUND,
    IN_USE
}

enum class SubscriptionNodeDeleteResult {
    DELETED,
    NOT_FOUND,
    IN_USE
}

/** Coordinates personal subscription group CRUD, refresh, import, and icon operations. */
internal class SubscriptionGroupCoordinator(
    private val context: Context,
    private val scope: CoroutineScope,
    private val settingsRepository: SettingsRepository,
    private val nodeDao: suspend () -> NodeDao,
    private val subscriptionImportService: SubscriptionImportService,
    private val nodeCompatibilityCheck: suspend (Node) -> Boolean,
    private val isTesting: StateFlow<Boolean>,
    private val activeConnectionNode: StateFlow<Node?>,
    private val activeSubscriptionSourceId: StateFlow<String?>,
    private val refreshingPersonalSourceCounts: MutableStateFlow<Map<String, Int>>
) {
    private val tag = "SubscriptionGroupCoordinator"

    fun createSubscriptionGroup(
        input: String?,
        onResult: (SubscriptionGroupCreateResult, Int, Int, Int) -> Unit = { _, _, _, _ -> }
    ) {
        scope.launch {
            val normalizedInput = input?.trim().orEmpty()
            val importInput = SubscriptionInputDetector.detect(normalizedInput)
            var failedSourceCount = 0
            val hasMultipleRemoteInput = importInput is SubscriptionImportInput.MultipleRemoteSubscriptions ||
                importInput is SubscriptionImportInput.MixedSubscription
            // 澶氫釜杩滅▼璁㈤槄閫愭潯澶勭悊锛屽崟涓摼鎺ュけ璐ヤ笉闃绘柇鍏朵粬閾炬帴鍒涘缓銆?
            val parsedParts = if (hasMultipleRemoteInput) {
                val remoteUrls = when (importInput) {
                    is SubscriptionImportInput.MultipleRemoteSubscriptions -> importInput.urls
                    is SubscriptionImportInput.MixedSubscription -> importInput.urls
                    else -> emptyList()
                }
                val contentParts = if (importInput is SubscriptionImportInput.MixedSubscription) {
                    val contentPart = subscriptionImportService.parseFullSubscriptionContent(importInput.content)
                    if (contentPart.nodes.isEmpty()) {
                        // 鏈湴閰嶇疆鍙彁鍙栧彲鐢ㄨ妭鐐癸紝鍏朵綑鍐呭涓㈠純锛屼笉鍒涘缓绌虹殑鏈湴鏉ユ簮銆?
                        emptyList()
                    } else {
                        listOf(ParsedSubscriptionPart(null, contentPart, "瀵煎叆鍐呭"))
                    }
                } else {
                    emptyList()
                }
                val remoteParts = subscriptionImportService.fetchRemoteSubscriptionResults(remoteUrls)
                    .mapIndexedNotNull { index, result ->
                        val parsed = result.getOrNull()
                        if (parsed == null || parsed.nodes.isEmpty()) {
                            failedSourceCount++
                            Log.e(
                                tag,
                                "Unable to import remote subscription #${index + 1}",
                                result.exceptionOrNull()
                            )
                            null
                        } else {
                            val groupLabel = if (importInput is SubscriptionImportInput.MixedSubscription) {
                                "杩滅▼ ${index + 1}"
                            } else {
                                null
                            }
                            ParsedSubscriptionPart(remoteUrls[index], parsed, groupLabel)
                        }
                    }
                contentParts + remoteParts
            } else {
                val parsedSubscriptions = subscriptionImportService.parseSubscriptionInput(importInput)
                if (parsedSubscriptions.isFailure) {
                    Log.e(
                        tag,
                        "Unable to fetch user subscription group",
                        parsedSubscriptions.exceptionOrNull()
                    )
                    onResult(SubscriptionGroupCreateResult.FETCH_FAILED, 0, 0, 1)
                    return@launch
                }
                parsedSubscriptions.getOrThrow()
            }.ifEmpty {
                listOf(ParsedSubscriptionPart(null, ParsedSubscription(emptyList())))
            }

            if (
                hasMultipleRemoteInput &&
                parsedParts.all { it.subscription.nodes.isEmpty() }
            ) {
                onResult(
                    SubscriptionGroupCreateResult.FETCH_FAILED,
                    0,
                    0,
                    failedSourceCount
                )
                return@launch
            }

            // 璁㈤槄瀵煎叆涓嶅簲渚濊禆褰撳墠閫変腑鐨勬牳蹇冦€?
            // 淇濈暀鍘熷 URI锛岀瓑瀹為檯鍑嗗鏍稿績浼氳瘽鏃跺啀鏍￠獙鍏煎鎬с€?
            if (normalizedInput.isNotBlank() && parsedParts.any { it.subscription.nodes.isEmpty() }) {
                onResult(SubscriptionGroupCreateResult.INVALID, 0, 0, failedSourceCount)
                return@launch
            }

            data class PreparedGroup(
                val group: NodeGroupEntity,
                val nodes: List<Node>,
                val members: List<NodeGroupMemberEntity>
            )

            val preparedGroups = parsedParts.map { part ->
                val groupId = "group_${UUID.randomUUID()}"
                val groupName = subscriptionGroupName(part)
                val parsed = part.subscription
                val createdAt = System.currentTimeMillis()
                val group = NodeGroupEntity(
                    id = groupId,
                    name = groupName,
                    type = NodeGroupStorageType.SUBSCRIPTION.name,
                    subscriptionUrl = part.url,
                    subscriptionUploadBytes = parsed.userInfo?.uploadBytes,
                    subscriptionDownloadBytes = parsed.userInfo?.downloadBytes,
                    subscriptionTotalBytes = parsed.userInfo?.totalBytes,
                    subscriptionExpireAt = parsed.userInfo?.expireAtMillis,
                    subscriptionUpdatedAt = createdAt,
                    createdAt = createdAt
                )
                val localNodes = parsed.nodes
                    .distinctBy(Node::getRawLinkPlain)
                    .mapIndexed { index, node ->
                        node.copy(
                            id = "${groupId}_$index",
                            source = NodeSource.LOCAL_GROUP,
                            subscriptionSourceId = groupId,
                            subscriptionSourceName = groupName,
                            latency = -1,
                            tcpingLatency = -1,
                            urlTestLatency = -1,
                            isAvailable = true,
                            lastTestedAt = 0L,
                            downloadMbps = 0f,
                            uploadMbps = 0f
                        )
                    }
                val members = localNodes.mapIndexed { index, node ->
                    NodeGroupMemberEntity(
                        groupId = groupId,
                        nodeId = node.id,
                        sortOrder = index
                    )
                }
                PreparedGroup(group, localNodes, members)
            }

            withContext(Dispatchers.IO) {
                val dao = nodeDao()
                preparedGroups.forEach { prepared ->
                    dao.insertNodeGroupWithNodes(
                        prepared.group,
                        prepared.nodes,
                        prepared.members
                    )
                }
            }
            preparedGroups.forEach { prepared ->
                fetchPersonalSourceIconForGroup(prepared.group.id)
            }
            settingsRepository.setSelectedNodeGroup(
                NodeGroupKey.Imported(preparedGroups.first().group.id)
            )
            val totalImportedNodes = preparedGroups.sumOf { it.nodes.size }
            val result = if (failedSourceCount > 0) {
                SubscriptionGroupCreateResult.PARTIAL_CREATED
            } else if (preparedGroups.size > 1) {
                SubscriptionGroupCreateResult.MULTIPLE_CREATED
            } else {
                SubscriptionGroupCreateResult.CREATED
            }
            onResult(result, totalImportedNodes, preparedGroups.size, failedSourceCount)
        }
    }

    private fun subscriptionGroupName(part: ParsedSubscriptionPart): String =
        part.subscription.name?.trim()?.takeIf(String::isNotEmpty)
            ?: part.url?.let { url ->
                Uri.parse(url).host
                    ?.removePrefix("www.")
                    ?.takeIf(String::isNotEmpty)
            }
            ?: part.groupLabel?.trim()?.takeIf(String::isNotEmpty)
            ?: if (part.subscription.nodes.isEmpty()) "新建私人源" else "导入内容"

    /** 灏嗚妭鐐?URI 杩藉姞鍒版湰鍦拌闃呯粍锛屼繚鐣欑粍鍐呭凡鏈夎妭鐐广€?*/
    fun importNodeUrisIntoSubscriptionGroup(
        groupId: String,
        input: String,
        onResult: (SubscriptionGroupNodeImportResult, Int) -> Unit = { _, _ -> }
    ) {
        scope.launch {
            val normalizedInput = input.trim()
            if (normalizedInput.isBlank()) {
                onResult(SubscriptionGroupNodeImportResult.INVALID, 0)
                return@launch
            }

            val dao = nodeDao()
            val group = withContext(Dispatchers.IO) {
                dao.getNodeGroupById(groupId)
            }
            if (group == null || group.type !in setOf(
                    NodeGroupStorageType.IMPORTED.name,
                    NodeGroupStorageType.SUBSCRIPTION.name
                )
            ) {
                onResult(SubscriptionGroupNodeImportResult.NOT_FOUND, 0)
                return@launch
            }

            val importInput = SubscriptionInputDetector.detect(normalizedInput)
            val parsedSubscriptions = subscriptionImportService.parseSubscriptionInput(importInput)
            if (parsedSubscriptions.isFailure) {
                Log.e(
                    tag,
                    "Unable to import nodes into local subscription group",
                    parsedSubscriptions.exceptionOrNull()
                )
                onResult(SubscriptionGroupNodeImportResult.FETCH_FAILED, 0)
                return@launch
            }

            // 鍗充娇褰撳墠鏍稿績鏃犳硶澶勭悊鍖呭惈 ECH 鎴栬瘉涔﹀瓧娈电殑 URI锛屼篃瑕佷繚鐣欒闃呰妭鐐广€?
            val nodesToImport = parsedSubscriptions.getOrThrow().flatMap { it.subscription.nodes }
            if (nodesToImport.isEmpty()) {
                onResult(SubscriptionGroupNodeImportResult.INVALID, 0)
                return@launch
            }

            val existingNodes = withContext(Dispatchers.IO) {
                dao.getNodesByGroup(groupId).first()
            }
            val existingRawLinks = existingNodes.map(Node::getRawLinkPlain).toSet()
            val nodesToAdd = nodesToImport
                .distinctBy(Node::getRawLinkPlain)
                .filterNot { it.getRawLinkPlain() in existingRawLinks }
            if (nodesToAdd.isEmpty()) {
                onResult(SubscriptionGroupNodeImportResult.ALREADY_EXISTS, 0)
                return@launch
            }

            val localNodes = nodesToAdd.mapIndexed { index, node ->
                node.copy(
                    id = "${groupId}_${UUID.randomUUID()}",
                    source = NodeSource.LOCAL_GROUP,
                    subscriptionSourceId = groupId,
                    subscriptionSourceName = group.name,
                    latency = -1,
                    tcpingLatency = -1,
                    urlTestLatency = -1,
                    isAvailable = true,
                    lastTestedAt = 0L,
                    sortOrder = existingNodes.size + index,
                    downloadMbps = 0f,
                    uploadMbps = 0f
                )
            }
            val members = localNodes.mapIndexed { index, node ->
                NodeGroupMemberEntity(
                    groupId = groupId,
                    nodeId = node.id,
                    sortOrder = existingNodes.size + index
                )
            }

            withContext(Dispatchers.IO) {
                dao.appendNodesToGroup(localNodes, members)
            }
            onResult(SubscriptionGroupNodeImportResult.IMPORTED, localNodes.size)
        }
    }

    /** 鏇存柊鏈湴璁㈤槄缁勪腑鐨勫崟涓妭鐐癸紝淇濈暀鑺傜偣 ID 鍜岀粍鍐呮帓搴忋€?*/
    fun updateLocalGroupNode(
        groupId: String,
        nodeId: String,
        name: String,
        rawLink: String,
        onResult: (LocalGroupNodeEditResult) -> Unit = {}
    ) {
        scope.launch {
            val normalizedName = name.trim()
            if (normalizedName.isBlank()) {
                onResult(LocalGroupNodeEditResult.INVALID)
                return@launch
            }

            val dao = nodeDao()
            val group = withContext(Dispatchers.IO) {
                dao.getNodeGroupById(groupId)
            } ?: run {
                onResult(LocalGroupNodeEditResult.NOT_FOUND)
                return@launch
            }
            val existingNode = withContext(Dispatchers.IO) {
                dao.getNodesByGroup(groupId).first().firstOrNull { it.id == nodeId }
            } ?: run {
                onResult(LocalGroupNodeEditResult.NOT_FOUND)
                return@launch
            }

            val normalizedLink = rawLink.trim()
            if (normalizedLink.isBlank() || normalizedLink == existingNode.rawLink) {
                withContext(Dispatchers.IO) {
                    dao.insertNode(existingNode.copy(name = normalizedName))
                }
                onResult(LocalGroupNodeEditResult.UPDATED)
                return@launch
            }

            val parsedNode = withContext(Dispatchers.Default) {
                subscriptionImportService.parseSingleNode(normalizedLink)
            } ?: run {
                onResult(LocalGroupNodeEditResult.INVALID)
                return@launch
            }
            if (!withContext(Dispatchers.IO) { nodeCompatibilityCheck(parsedNode) }) {
                onResult(LocalGroupNodeEditResult.INVALID)
                return@launch
            }

            val updatedNode = parsedNode.copy(
                id = existingNode.id,
                name = normalizedName,
                subscriptionSourceId = groupId,
                subscriptionSourceName = group.name,
                source = NodeSource.LOCAL_GROUP,
                latency = -1,
                tcpingLatency = -1,
                urlTestLatency = -1,
                isAvailable = true,
                lastTestedAt = 0L,
                sortOrder = existingNode.sortOrder,
                downloadMbps = 0f,
                uploadMbps = 0f
            )
            withContext(Dispatchers.IO) {
                dao.insertNode(updatedNode)
            }
            onResult(LocalGroupNodeEditResult.UPDATED)
        }
    }

    /** 浠庢湰鍦拌闃呯粍绉婚櫎涓€涓妭鐐癸紱褰撳墠姝ｅ湪浣跨敤鐨勮妭鐐逛笉鑳藉垹闄ゃ€?*/
    fun deleteLocalGroupNode(
        groupId: String,
        nodeId: String,
        onResult: (LocalGroupNodeDeleteResult) -> Unit = {}
    ) {
        scope.launch {
            if (activeConnectionNode.value?.id == nodeId) {
                onResult(LocalGroupNodeDeleteResult.IN_USE)
                return@launch
            }

            val deleted = withContext(Dispatchers.IO) {
                nodeDao().deleteLocalGroupNodeFromGroup(groupId, nodeId)
            }
            if (!deleted) {
                onResult(LocalGroupNodeDeleteResult.NOT_FOUND)
                return@launch
            }
            if (settingsRepository.selectedNodeId.first() == nodeId) {
                settingsRepository.setSelectedNodeId(null)
            }
            onResult(LocalGroupNodeDeleteResult.DELETED)
        }
    }

    /** 删除本地订阅列表中的节点；下次刷新时源仍包含该节点则会重新导入。 */
    fun deleteSubscriptionNode(
        node: Node,
        onResult: (SubscriptionNodeDeleteResult) -> Unit = {}
    ) {
        scope.launch {
            if (activeConnectionNode.value?.id == node.id) {
                onResult(SubscriptionNodeDeleteResult.IN_USE)
                return@launch
            }
            val sourceId = node.subscriptionSourceId?.takeIf(String::isNotBlank)
            if (sourceId == null) {
                onResult(SubscriptionNodeDeleteResult.NOT_FOUND)
                return@launch
            }

            val deleted = withContext(Dispatchers.IO) {
                nodeDao().deleteSubscriptionNodeFromSource(sourceId, node.id)
            }
            if (!deleted) {
                onResult(SubscriptionNodeDeleteResult.NOT_FOUND)
                return@launch
            }
            if (settingsRepository.selectedNodeId.first() == node.id) {
                settingsRepository.setSelectedNodeId(null)
            }
            onResult(SubscriptionNodeDeleteResult.DELETED)
        }
    }

    /** 鏇存柊鏈湴璁㈤槄缁勫悕绉板強鍙€夎闃呭唴瀹广€傝緭鍏ラ摼鎺ュ彉鍖栨椂浼氶噸鏂板鍏ョ粍鍐呰妭鐐广€?*/
    fun updateSubscriptionGroup(
        groupId: String,
        name: String,
        input: String?,
        userAgentMode: PersonalSourceUserAgentMode,
        customUserAgent: String?,
        onResult: (SubscriptionGroupEditResult, Int) -> Unit = { _, _ -> }
    ) {
        scope.launch {
            val normalizedName = name.trim()
            if (normalizedName.isBlank()) {
                onResult(SubscriptionGroupEditResult.INVALID, 0)
                return@launch
            }
            val normalizedCustomUserAgent = customUserAgent?.trim()
                ?.takeIf(String::isNotBlank)
                ?.takeIf { userAgentMode == PersonalSourceUserAgentMode.CUSTOM }
            if (userAgentMode == PersonalSourceUserAgentMode.CUSTOM &&
                normalizedCustomUserAgent == null
            ) {
                onResult(SubscriptionGroupEditResult.INVALID, 0)
                return@launch
            }

            val dao = nodeDao()
            val existingGroup = withContext(Dispatchers.IO) {
                dao.getNodeGroupById(groupId)
            }
            if (existingGroup == null) {
                onResult(SubscriptionGroupEditResult.NOT_FOUND, 0)
                return@launch
            }

            val normalizedInput = input?.trim().orEmpty()
            val currentUrl = existingGroup.subscriptionUrl.orEmpty()
            if (normalizedInput.isBlank() || normalizedInput == currentUrl) {
                val updated = withContext(Dispatchers.IO) {
                    dao.updateNodeGroupMetadata(
                        groupId = groupId,
                        name = normalizedName,
                        subscriptionUrl = existingGroup.subscriptionUrl,
                        userAgentMode = userAgentMode.name,
                        customUserAgent = normalizedCustomUserAgent
                    )
                }
                onResult(
                    if (updated) SubscriptionGroupEditResult.UPDATED
                    else SubscriptionGroupEditResult.NOT_FOUND,
                    0
                )
                return@launch
            }

            val importInput = SubscriptionInputDetector.detect(normalizedInput)
            val parsedSubscriptions = subscriptionImportService.parseSubscriptionInput(
                importInput,
                userAgentMode,
                normalizedCustomUserAgent
            )
            if (parsedSubscriptions.isFailure) {
                Log.e(
                    tag,
                    "Unable to fetch updated user subscription group",
                    parsedSubscriptions.exceptionOrNull()
                )
                onResult(SubscriptionGroupEditResult.FETCH_FAILED, 0)
                return@launch
            }
            val parsedParts = parsedSubscriptions.getOrThrow()
            val parsed = ParsedSubscription(
                nodes = parsedParts.flatMap { it.subscription.nodes },
                userInfo = parsedParts.singleOrNull()?.subscription?.userInfo
            )
            val subscriptionUrl = parsedParts.mapNotNull { it.url }.joinToString("\n").ifBlank { null }

            val importedNodes = parsed.nodes
            if (importedNodes.isEmpty()) {
                onResult(SubscriptionGroupEditResult.INVALID, 0)
                return@launch
            }

            val localNodes = importedNodes
                .distinctBy(Node::getRawLinkPlain)
                .mapIndexed { index, node ->
                    node.copy(
                        id = "${groupId}_$index",
                        source = NodeSource.LOCAL_GROUP,
                        subscriptionSourceId = groupId,
                        subscriptionSourceName = normalizedName,
                        latency = -1,
                        tcpingLatency = -1,
                        urlTestLatency = -1,
                        isAvailable = true,
                        lastTestedAt = 0L,
                        downloadMbps = 0f,
                        uploadMbps = 0f
                    )
                }
            val members = localNodes.mapIndexed { index, node ->
                NodeGroupMemberEntity(
                    groupId = groupId,
                    nodeId = node.id,
                    sortOrder = index
                )
            }
            val updated = withContext(Dispatchers.IO) {
                dao.replaceLocalGroupNodes(
                    groupId = groupId,
                    name = normalizedName,
                    subscriptionUrl = subscriptionUrl,
                    userAgentMode = userAgentMode.name,
                    customUserAgent = normalizedCustomUserAgent,
                    subscriptionUploadBytes = parsed.userInfo?.uploadBytes,
                    subscriptionDownloadBytes = parsed.userInfo?.downloadBytes,
                    subscriptionTotalBytes = parsed.userInfo?.totalBytes,
                    subscriptionExpireAt = parsed.userInfo?.expireAtMillis,
                    subscriptionUpdatedAt = System.currentTimeMillis(),
                    nodes = localNodes,
                    members = members
                )
            }
            onResult(
                if (updated) SubscriptionGroupEditResult.UPDATED
                else SubscriptionGroupEditResult.NOT_FOUND,
                localNodes.size
            )
        }
    }

    /** 绔嬪嵆閲嶆柊鑾峰彇甯﹁闃呴摼鎺ョ殑鏈湴鍒嗙粍锛屽苟鏇挎崲缁勫唴鑺傜偣銆?*/
    fun refreshUserSubscriptionGroup(
        groupId: String,
        onResult: (SubscriptionGroupRefreshResult) -> Unit = {}
    ) {
        updatePersonalSourceRefreshCount(groupId, increment = true)
        scope.launch {
            try {
                if (
                    GlobalTestExecution.isFetching() ||
                    isTesting.value ||
                    GlobalTestExecution.mutex.isLocked
                ) {
                    onResult(SubscriptionGroupRefreshResult.BUSY)
                    return@launch
                }

                onResult(refreshUserSubscriptionGroupInternal(groupId))
            } finally {
                updatePersonalSourceRefreshCount(groupId, increment = false)
            }
        }
    }

    private fun updatePersonalSourceRefreshCount(groupId: String, increment: Boolean) {
        refreshingPersonalSourceCounts.update { counts ->
            val count = counts[groupId] ?: 0
            if (increment) {
                counts + (groupId to count + 1)
            } else if (count <= 1) {
                counts - groupId
            } else {
                counts + (groupId to count - 1)
            }
        }
    }

    private suspend fun <T> withPersonalSourceRefreshing(
        groupId: String,
        action: suspend () -> T
    ): T {
        updatePersonalSourceRefreshCount(groupId, increment = true)
        return try {
            action()
        } finally {
            updatePersonalSourceRefreshCount(groupId, increment = false)
        }
    }

    /** 鍐峰惎鍔ㄦ椂椤哄簭鏇存柊鍚敤浜嗚嚜鍔ㄦ洿鏂扮殑杩滅▼绉佷汉婧愩€?*/
    suspend fun refreshUserSubscriptionGroupsOnStartup(
        autoRefreshOverrides: Map<String, Boolean>
    ) {
        val groups = withContext(Dispatchers.IO) {
            nodeDao().getUserSubscriptionGroups().first()
        }
        groups.forEach { group ->
            val importInput = SubscriptionInputDetector.detect(group.subscriptionUrl.orEmpty())
            if (
                autoRefreshOverrides[group.id] == true &&
                    (importInput is SubscriptionImportInput.RemoteSubscription ||
                        importInput is SubscriptionImportInput.MultipleRemoteSubscriptions)
            ) {
                when (withPersonalSourceRefreshing(group.id) {
                    refreshUserSubscriptionGroupInternal(group.id)
                }) {
                    SubscriptionGroupRefreshResult.REFRESHED -> Unit
                    else -> Log.w(tag, "Unable to refresh personal subscription group on startup: ${group.id}")
                }
            }
        }
    }

    private suspend fun refreshUserSubscriptionGroupInternal(
        groupId: String
    ): SubscriptionGroupRefreshResult {
        val dao = nodeDao()
        val group = withContext(Dispatchers.IO) {
            dao.getNodeGroupById(groupId)
        } ?: return SubscriptionGroupRefreshResult.NOT_FOUND
        if (group.type !in setOf(
                NodeGroupStorageType.IMPORTED.name,
                NodeGroupStorageType.SUBSCRIPTION.name
            )
        ) {
            return SubscriptionGroupRefreshResult.NOT_FOUND
        }

        val importInput = SubscriptionInputDetector.detect(group.subscriptionUrl.orEmpty())
        if (
            importInput !is SubscriptionImportInput.RemoteSubscription &&
            importInput !is SubscriptionImportInput.MultipleRemoteSubscriptions
        ) {
            return SubscriptionGroupRefreshResult.INVALID
        }
        val parsedSubscriptions = subscriptionImportService.parseSubscriptionInput(
            importInput,
            PersonalSourceUserAgentMode.fromStorage(group.userAgentMode),
            group.customUserAgent
        )
        if (parsedSubscriptions.isFailure) {
            Log.e(
                tag,
                "Unable to refresh user subscription group",
                parsedSubscriptions.exceptionOrNull()
            )
            return SubscriptionGroupRefreshResult.FETCH_FAILED
        }
        val parsedParts = parsedSubscriptions.getOrThrow()
        val parsed = ParsedSubscription(
            nodes = parsedParts.flatMap { it.subscription.nodes },
            userInfo = parsedParts.singleOrNull()?.subscription?.userInfo
        )
        val subscriptionUrl = parsedParts.mapNotNull { it.url }.joinToString("\n")

        val importedNodes = parsed.nodes.distinctBy(Node::getRawLinkPlain)
        if (importedNodes.isEmpty()) {
            return SubscriptionGroupRefreshResult.INVALID
        }

        val localNodes = importedNodes.mapIndexed { index, node ->
            node.copy(
                id = "${groupId}_$index",
                source = NodeSource.LOCAL_GROUP,
                subscriptionSourceId = groupId,
                subscriptionSourceName = group.name,
                latency = -1,
                tcpingLatency = -1,
                urlTestLatency = -1,
                isAvailable = true,
                lastTestedAt = 0L,
                sortOrder = index,
                downloadMbps = 0f,
                uploadMbps = 0f
            )
        }
        val members = localNodes.mapIndexed { index, node ->
            NodeGroupMemberEntity(
                groupId = groupId,
                nodeId = node.id,
                sortOrder = index
            )
        }
        val refreshed = withContext(Dispatchers.IO) {
            dao.replaceLocalGroupNodes(
                groupId = groupId,
                name = group.name,
                subscriptionUrl = subscriptionUrl,
                userAgentMode = group.userAgentMode,
                customUserAgent = group.customUserAgent,
                subscriptionUploadBytes = parsed.userInfo?.uploadBytes,
                subscriptionDownloadBytes = parsed.userInfo?.downloadBytes,
                subscriptionTotalBytes = parsed.userInfo?.totalBytes,
                subscriptionExpireAt = parsed.userInfo?.expireAtMillis,
                subscriptionUpdatedAt = System.currentTimeMillis(),
                nodes = localNodes,
                members = members
            )
        }
        return if (refreshed) {
            SubscriptionGroupRefreshResult.REFRESHED
        } else {
            SubscriptionGroupRefreshResult.NOT_FOUND
        }
    }

    /** 鍒犻櫎鏈湴璁㈤槄缁勫強鍏惰妭鐐广€?*/
    fun deleteSubscriptionGroup(groupId: String, onResult: (Boolean) -> Unit = {}) {
        scope.launch {
            val deleted = withContext(Dispatchers.IO) {
                val selectedGroupId = settingsRepository.selectedNodeGroupId.first()
                val dao = nodeDao()
                val group = dao.getNodeGroupById(groupId)
                if (group == null) {
                    false
                } else {
                    dao.deleteNodeGroupWithNodes(groupId)
                    PersonalSourceIconStore.delete(context, group.localIconFile)
                    if (selectedGroupId == NodeGroupKey.Imported(groupId).value) {
                        val fallbackSourceId = activeSubscriptionSourceId.value
                        settingsRepository.setSelectedCountryCode(null)
                        settingsRepository.setSelectedSubscriptionSourceId(fallbackSourceId)
                        settingsRepository.setSelectedNodeGroupId(
                            fallbackSourceId
                                ?.takeIf(String::isNotBlank)
                                ?.let { NodeGroupKey.Subscription(it).value }
                        )
                        settingsRepository.setSelectedNodeId(null)
                    }
                    true
                }
            }
            onResult(deleted)
        }
    }

    /** 淇濆瓨绉佷汉婧愬崱鐗囧浘鏍囨潵婧愬強鍙€夎繙绋嬮摼鎺?鏈湴鍥剧墖銆?*/
    fun updatePersonalSourceIcon(
        groupId: String,
        iconMode: String,
        customIconUrl: String?,
        localIconFile: String?,
        onResult: (Boolean) -> Unit = {}
    ) {
        scope.launch {
            val updated = withContext(Dispatchers.IO) {
                nodeDao().updateNodeGroupIcon(
                    groupId = groupId,
                    iconMode = iconMode,
                    customIconUrl = customIconUrl,
                    localIconFile = localIconFile
                ) > 0
            }
            if (updated) fetchPersonalSourceIconForGroup(groupId)
            onResult(updated)
        }
    }

    private fun fetchPersonalSourceIconForGroup(groupId: String) {
        scope.launch(Dispatchers.IO) {
            val group = nodeDao().getNodeGroupById(groupId) ?: return@launch
            val iconUrl = when (PersonalSourceIconMode.fromStorage(group.iconMode)) {
                PersonalSourceIconMode.AUTO_REMOTE ->
                    SubscriptionFaviconLoader.faviconUrl(group.subscriptionUrl)
                PersonalSourceIconMode.CUSTOM_REMOTE ->
                    SubscriptionFaviconLoader.customImageUrl(group.customIconUrl)
                PersonalSourceIconMode.DEFAULT,
                PersonalSourceIconMode.LOCAL -> null
            } ?: return@launch

            SubscriptionFaviconLoader.fetchAndCache(
                context.applicationContext,
                iconUrl
            )
        }
    }

    /** 浠庢湰鏈虹Щ闄や竴涓畼鏂硅闃呮簮鐨勮妭鐐癸紱杩滅璁㈤槄婧愪粛鍙湪鍚庣画鍒锋柊鏃堕噸鏂板鍏ャ€?*/
    fun deleteOfficialSubscription(sourceId: String, onResult: (Boolean) -> Unit = {}) {
        scope.launch {
            if (sourceId.isBlank()) {
                onResult(false)
                return@launch
            }

            val dao = nodeDao()
            val sourceNodes = withContext(Dispatchers.IO) {
                dao.getSubscriptionNodesBySource(sourceId).first()
            }
            if (sourceNodes.isEmpty()) {
                onResult(false)
                return@launch
            }

            withContext(Dispatchers.IO) {
                dao.deleteSubscriptionNodesBySource(sourceId)
            }

            val sourceNodeIds = sourceNodes.mapTo(mutableSetOf(), Node::id)
            if (settingsRepository.selectedNodeId.first() in sourceNodeIds) {
                settingsRepository.setSelectedNodeId(null)
            }
            if (settingsRepository.selectedNodeGroupId.first() == NodeGroupKey.Subscription(sourceId).value) {
                settingsRepository.setSelectedNodeGroupId(null)
            }
            if (settingsRepository.selectedSubscriptionSourceId.first() == sourceId) {
                settingsRepository.setSelectedSubscriptionSourceId(null)
            }
            onResult(true)
        }
    }
}
