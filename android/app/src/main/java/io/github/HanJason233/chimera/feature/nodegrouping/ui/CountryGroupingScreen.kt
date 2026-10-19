package io.github.HanJason233.chimera.feature.nodegrouping.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.FilterList
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.HanJason233.chimera.data.model.NodeGroupKey
import io.github.HanJason233.chimera.data.model.NodeSource
import io.github.HanJason233.chimera.feature.nodegrouping.model.CountryContinent
import io.github.HanJason233.chimera.feature.nodegrouping.model.CountryGroupingSourceOption
import io.github.HanJason233.chimera.feature.nodegrouping.model.CountryOption
import io.github.HanJason233.chimera.ui.components.AppAlertDialog
import io.github.HanJason233.chimera.ui.components.AppScreenScaffold
import io.github.HanJason233.chimera.viewmodel.MainViewModel

@Composable
internal fun CountryGroupingDestination(
    viewModel: MainViewModel,
    onCountrySelected: (String) -> Unit,
    onViewCountryDetails: (CountryOption) -> Unit,
    onBack: () -> Unit,
    bottomContentPadding: Dp = 16.dp
) {
    val countries by viewModel.countryOptions.collectAsStateWithLifecycle()
    val selectedNodeGroupId by viewModel.selectedNodeGroupId.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val subscriptionGroups by viewModel.subscriptionGroups.collectAsStateWithLifecycle()
    val personalGroups by viewModel.userSubscriptionGroups.collectAsStateWithLifecycle()
    val allSubscriptionNodes by viewModel.allSubscriptionNodes.collectAsStateWithLifecycle()
    val selectedSourceIds by viewModel.countryGroupingSourceIds.collectAsStateWithLifecycle()
    val subscriptionNodeCounts = remember(allSubscriptionNodes) {
        allSubscriptionNodes
            .asSequence()
            .filter { it.source == NodeSource.SUBSCRIPTION }
            .mapNotNull { node -> node.subscriptionSourceId }
            .groupingBy { it }
            .eachCount()
    }
    val sourceOptions = buildList {
        subscriptionGroups.forEach { group ->
            add(
                CountryGroupingSourceOption(
                    id = NodeGroupKey.Subscription(group.sourceId).value,
                    name = group.name,
                    kind = "官方源",
                    nodeCount = subscriptionNodeCounts[group.sourceId] ?: 0
                )
            )
        }
        personalGroups.forEach { group ->
            add(
                CountryGroupingSourceOption(
                    id = group.id,
                    name = group.name,
                    kind = "私人源",
                    nodeCount = group.nodeCount
                )
            )
        }
    }

    CountryGroupingScreen(
        countries = countries,
        selectedNodeGroupId = selectedNodeGroupId,
        isLoading = isLoading,
        sourceOptions = sourceOptions,
        selectedSourceIds = selectedSourceIds,
        onCountrySelected = onCountrySelected,
        onViewCountryDetails = onViewCountryDetails,
        onSaveSourceIds = viewModel::setCountryGroupingSourceIds,
        onBack = onBack,
        bottomContentPadding = bottomContentPadding
    )
}

@Composable
private fun CountryGroupingScreen(
    countries: List<CountryOption>,
    selectedNodeGroupId: String?,
    isLoading: Boolean,
    sourceOptions: List<CountryGroupingSourceOption>,
    selectedSourceIds: Set<String>?,
    onCountrySelected: (String) -> Unit,
    onViewCountryDetails: (CountryOption) -> Unit,
    onSaveSourceIds: (Set<String>) -> Unit,
    onBack: () -> Unit,
    bottomContentPadding: Dp
) {
    var sourceDialogVisible by rememberSaveable { mutableStateOf(false) }
    var collapsedContinentMask by rememberSaveable { mutableIntStateOf(0) }
    var draftSourceIds by remember { mutableStateOf(emptySet<String>()) }
    val availableSourceIds = remember(sourceOptions) {
        sourceOptions.mapTo(mutableSetOf()) { it.id }
    }
    val isAllSourcesSelected = selectedSourceIds == null ||
        selectedSourceIds.containsAll(availableSourceIds)
    val countriesByContinent = remember(countries) {
        countries.groupBy(CountryOption::continent)
    }

    AppScreenScaffold(
        title = "地区组",
        onBack = onBack,
        showBackNavigation = false,
        actions = {
            IconButton(
                onClick = {
                    draftSourceIds = selectedSourceIds
                        ?.intersect(availableSourceIds)
                        ?: availableSourceIds
                    sourceDialogVisible = true
                }
            ) {
                Icon(
                    imageVector = Icons.Rounded.FilterList,
                    contentDescription = "选择参与地区组的仓库源",
                    tint = if (isAllSourcesSelected) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.primary
                    }
                )
            }
        },
        contentPadding = PaddingValues(
            start = 0.dp,
            top = 16.dp,
            end = 0.dp,
            bottom = 0.dp
        ),
        contentWindowInsets = WindowInsets(0, 0, 0, 0)
    ) {
        when {
            countries.isNotEmpty() -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = bottomContentPadding),
                verticalArrangement = Arrangement.spacedBy(0.dp)
            ) {
                CountryContinent.entries.forEach { continent ->
                    val sectionCountries = countriesByContinent[continent].orEmpty()
                    if (sectionCountries.isNotEmpty()) {
                        val continentCollapseBit = 1 shl continent.ordinal
                        val expanded = (collapsedContinentMask and continentCollapseBit) == 0
                        item(key = "continent:${continent.name}") {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(role = Role.Button) {
                                        collapsedContinentMask =
                                            collapsedContinentMask xor continentCollapseBit
                                    }
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = continent.title,
                                    modifier = Modifier.weight(1f),
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Icon(
                                    imageVector = if (expanded) {
                                        Icons.Default.KeyboardArrowUp
                                    } else {
                                        Icons.Default.KeyboardArrowDown
                                    },
                                    contentDescription = if (expanded) {
                                        "收起${continent.title}"
                                    } else {
                                        "展开${continent.title}"
                                    },
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        if (expanded) {
                            items(
                                items = sectionCountries,
                                key = CountryOption::code
                            ) { country ->
                                CountryCard(
                                    country = country,
                                    selected = selectedNodeGroupId ==
                                        NodeGroupKey.Country(country.code).value,
                                    onClick = { onCountrySelected(country.code) },
                                    onViewDetails = { onViewCountryDetails(country) }
                                )
                                Divider(
                                    modifier = Modifier.padding(horizontal = 12.dp),
                                    color = MaterialTheme.colorScheme.outlineVariant
                                )
                            }
                        }
                    }
                }
            }

            isLoading -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(modifier = Modifier.size(32.dp))
            }

            else -> Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = bottomContentPadding),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (sourceOptions.isNotEmpty() && selectedSourceIds?.isEmpty() == true) {
                        "尚未选择仓库源，请点击右上角选择"
                    } else {
                        "暂无可选择的地区组"
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center
                )
            }
        }
    }

    if (sourceDialogVisible) {
        AppAlertDialog(
            onDismissRequest = { sourceDialogVisible = false },
            title = { Text("选择仓库源") },
            text = {
                Column {
                    Text(
                        text = "所选仓库源中的节点会按地区重新汇总。",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(onClick = { draftSourceIds = availableSourceIds }) {
                            Text("全选")
                        }
                        TextButton(onClick = { draftSourceIds = emptySet() }) {
                            Text("清空")
                        }
                    }
                    if (sourceOptions.isEmpty()) {
                        Text(
                            text = "暂无仓库源",
                            modifier = Modifier.padding(vertical = 12.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier.heightIn(max = 360.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            items(sourceOptions, key = CountryGroupingSourceOption::id) { option ->
                                val checked = option.id in draftSourceIds
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .toggleable(value = checked, role = Role.Checkbox) {
                                            draftSourceIds = if (checked) {
                                                draftSourceIds - option.id
                                            } else {
                                                draftSourceIds + option.id
                                            }
                                        }
                                        .padding(vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = option.name,
                                            style = MaterialTheme.typography.bodyLarge,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = "${option.kind} · ${option.nodeCount} 个节点",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Checkbox(checked = checked, onCheckedChange = null)
                                }
                            }
                        }
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { sourceDialogVisible = false }) {
                    Text("取消")
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onSaveSourceIds(draftSourceIds)
                        sourceDialogVisible = false
                    }
                ) {
                    Text("应用")
                }
            }
        )
    }
}

@Composable
private fun CountryCard(
    country: CountryOption,
    selected: Boolean,
    onClick: () -> Unit,
    onViewDetails: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 64.dp)
                .padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = country.marker,
                fontSize = if (country.code == "TW") 18.sp else 34.sp,
                lineHeight = if (country.code == "TW") 22.sp else 38.sp,
                fontWeight = if (country.code == "TW") FontWeight.Bold else FontWeight.Normal
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = country.name,
                    color = if (selected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${country.nodeCount} 台服务器",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
            IconButton(onClick = onViewDetails) {
                Icon(
                    imageVector = Icons.Rounded.ChevronRight,
                    contentDescription = "查看当前地区的线路详细信息",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
