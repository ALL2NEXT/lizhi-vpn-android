package xyz.a202132.app.ui.screens

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import xyz.a202132.app.AppConfig
import xyz.a202132.app.network.NetworkClient
import xyz.a202132.app.ui.components.AppScreenScaffold
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

private data class WebNavigationItem(
    val title: String,
    val url: String
)

private val WEB_NAVIGATION_ITEMS = listOf(
    WebNavigationItem(
        title = "中科大测速",
        url = AppConfig.USTC_SPEED_TEST_URL
    ),
    WebNavigationItem(
        title = "南大测速",
        url = AppConfig.NJU_SPEED_TEST_URL
    ),
    WebNavigationItem(
        title = "奈飞测速",
        url = AppConfig.NETFLIX_SPEED_TEST_URL
    ),
    WebNavigationItem(
        title = "Google 测速",
        url = AppConfig.GOOGLE_SPEED_TEST_URL
    )
)

private val faviconClient: OkHttpClient by lazy {
    NetworkClient.withUserAgent(OkHttpClient.Builder())
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .callTimeout(10, TimeUnit.SECONDS)
        .build()
}

private val faviconCache = ConcurrentHashMap<String, Bitmap>()

@Composable
fun WebNavigationScreen(
    onBack: () -> Unit,
    bottomContentPadding: Dp = 16.dp
) {
    val context = LocalContext.current

    AppScreenScaffold(
        title = "导航",
        subtitle = "常用测速网站",
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
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 72.dp),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = bottomContentPadding),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(
                items = WEB_NAVIGATION_ITEMS,
                key = WebNavigationItem::url
            ) { item ->
                WebNavigationCard(
                    item = item,
                    onClick = { openWebsite(context, item.url) }
                )
            }
        }
    }
}

@Composable
private fun WebNavigationCard(
    item: WebNavigationItem,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp, vertical = 9.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            WebsiteFavicon(
                item = item,
                modifier = Modifier.size(44.dp)
            )
            Text(
                text = item.title,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun WebsiteFavicon(
    item: WebNavigationItem,
    modifier: Modifier = Modifier
) {
    val bitmap by produceState<Bitmap?>(initialValue = null, key1 = item.url) {
        value = loadFavicon(item.url)
    }

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(13.dp),
        color = MaterialTheme.colorScheme.primaryContainer
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap!!.asImageBitmap(),
                contentDescription = item.title,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(5.dp)
            )
        } else {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Outlined.Language,
                    contentDescription = item.title,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
    }
}

private suspend fun loadFavicon(url: String): Bitmap? = withContext(Dispatchers.IO) {
    faviconCache[url]?.let { return@withContext it }

    val siteUri = Uri.parse(url)
    val host = siteUri.host ?: return@withContext null
    val scheme = siteUri.scheme ?: "https"
    val faviconUrls = listOf(
        Uri.Builder()
            .scheme(scheme)
            .authority(host)
            .path("/favicon.ico")
            .build()
            .toString(),
        Uri.Builder()
            .scheme("https")
            .authority("icons.duckduckgo.com")
            .path("/ip3/$host.ico")
            .build()
            .toString(),
        Uri.Builder()
            .scheme("https")
            .authority("www.google.com")
            .path("/s2/favicons")
            .appendQueryParameter("domain", host)
            .appendQueryParameter("sz", "64")
            .build()
            .toString()
    )

    faviconUrls.firstNotNullOfOrNull { faviconUrl ->
        runCatching {
            val request = Request.Builder()
                .url(faviconUrl)
                .header("Accept", "image/*")
                .build()
            faviconClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val bytes = response.body?.bytes() ?: return@use null
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            }
        }.getOrNull()
    }?.also { bitmap ->
        faviconCache[url] = bitmap
    }
}

private fun openWebsite(context: Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, "没有可用的浏览器", Toast.LENGTH_SHORT).show()
    }
}
