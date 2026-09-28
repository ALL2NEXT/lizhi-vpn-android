package xyz.a202132.app.feature.nodegrouping.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import xyz.a202132.app.data.model.NodeGroupKey
import xyz.a202132.app.feature.nodegrouping.model.CountryOption
import xyz.a202132.app.ui.components.AppScreenScaffold
import xyz.a202132.app.viewmodel.MainViewModel

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

    CountryGroupingScreen(
        countries = countries,
        selectedNodeGroupId = selectedNodeGroupId,
        isLoading = isLoading,
        onCountrySelected = onCountrySelected,
        onViewCountryDetails = onViewCountryDetails,
        onBack = onBack,
        bottomContentPadding = bottomContentPadding
    )
}

@Composable
private fun CountryGroupingScreen(
    countries: List<CountryOption>,
    selectedNodeGroupId: String?,
    isLoading: Boolean,
    onCountrySelected: (String) -> Unit,
    onViewCountryDetails: (CountryOption) -> Unit,
    onBack: () -> Unit,
    bottomContentPadding: Dp
) {
    AppScreenScaffold(
        title = "按地区分组",
        onBack = onBack,
        showBackNavigation = false,
        contentPadding = PaddingValues(
            start = 20.dp,
            top = 16.dp,
            end = 20.dp,
            bottom = 0.dp
        ),
        contentWindowInsets = WindowInsets(0, 0, 0, 0)
    ) {
        when {
            countries.isNotEmpty() -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = bottomContentPadding),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(
                    items = countries,
                    key = CountryOption::code
                ) { country ->
                    CountryCard(
                        country = country,
                        selected = selectedNodeGroupId == NodeGroupKey.Country(country.code).value,
                        onClick = { onCountrySelected(country.code) },
                        onViewDetails = { onViewCountryDetails(country) }
                    )
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
                    text = "暂无可选择的地区",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center
                )
            }
        }
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
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 72.dp)
                .padding(horizontal = 18.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = country.flag,
                fontSize = 34.sp,
                lineHeight = 38.sp
            )
            Text(
                text = country.name,
                modifier = Modifier.weight(1f),
                color = if (selected) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "${country.nodeCount} 台服务器",
                color = if (selected) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                fontSize = 13.sp,
                maxLines = 1
            )
            IconButton(onClick = onViewDetails) {
                Icon(
                    imageVector = Icons.Rounded.ChevronRight,
                    contentDescription = "查看当前地区的线路详细信息",
                    tint = if (selected) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
        }
    }
}
