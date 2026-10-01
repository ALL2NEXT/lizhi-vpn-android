package io.github.HanJason233.chimera.network

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.Locale
import java.io.FileOutputStream
import java.io.InputStream

/**
 * 下载状态枚举
 */
enum class DownloadStatus {
    IDLE,
    DOWNLOADING,
    PAUSED,
    COMPLETED,
    ERROR,
    CANCELED
}

/**
 * 下载状态数据类
 */
data class DownloadState(
    val status: DownloadStatus = DownloadStatus.IDLE,
    val progress: Int = 0,               // 0-100
    val speed: String = "0 KB/s",
    val downloadedBytes: Long = 0,
    val totalBytes: Long = 0,
    val error: String? = null,
    val file: File? = null,
    val fileUri: Uri? = null,
    val consecutiveFailures: Int = 0      // 连续失败次数（不含无网络）
)

data class DownloadArtifact(
    val file: File? = null,
    val uri: Uri? = null
)

object DownloadManager {
    private const val TAG = "DownloadManager"
    private val client = NetworkClient.withUserAgent(OkHttpClient.Builder()).build()
    
    // 用于 UI 观察的 StateFlow
    private val _downloadState = MutableStateFlow(DownloadState())
    val downloadState = _downloadState.asStateFlow()
    
    private var downloadUrl: String = ""
    private var targetFile: File? = null
    private var targetDirectoryUri: String? = null
    
    // 控制标志
    @Volatile
    private var isPaused = false
    @Volatile
    private var isCancelled = false
    
    // 连续失败计数器（排除无网络错误）
    private var consecutiveFailures = 0
    
    /**
     * 开始或继续下载
     */
    suspend fun startDownload(
        url: String,
        context: Context,
        versionName: String,
        downloadDirectoryUri: String? = null
    ) {
        withContext(Dispatchers.IO) {
            try {
                val finalFileName = "update_$versionName.apk"
                val tempFileName = "update_$versionName.temp"
                val cacheDirectory = context.externalCacheDir ?: context.cacheDir
                val finalFile = File(cacheDirectory, finalFileName)
                val tempFile = File(cacheDirectory, tempFileName)
                val normalizedDirectoryUri = downloadDirectoryUri?.takeIf { it.isNotBlank() }
                val downloadDirectory = normalizedDirectoryUri?.let { uriString ->
                    DocumentFile.fromTreeUri(context, Uri.parse(uriString))
                        ?.takeIf { it.exists() && it.isDirectory && it.canWrite() }
                        ?: throw IllegalStateException("自定义下载目录不可用，请重新选择")
                }

                // 1. 检查最终文件是否存在且有效
                val existingDocumentFile = downloadDirectory?.findFile(finalFileName)
                    ?.takeIf { it.exists() && it.isFile && it.length() > 0 }
                if (
                    (downloadDirectory == null && finalFile.exists() && finalFile.length() > 0) ||
                    existingDocumentFile != null
                ) {
                    Log.d(TAG, "Final file exists, skipping download")
                    _downloadState.value = DownloadState(
                        status = DownloadStatus.COMPLETED,
                        progress = 100,
                        file = if (downloadDirectory == null) finalFile else null,
                        fileUri = existingDocumentFile?.uri,
                        totalBytes = existingDocumentFile?.length() ?: finalFile.length(),
                        downloadedBytes = existingDocumentFile?.length() ?: finalFile.length()
                    )
                    return@withContext
                }

                // 初始化检查
                if (downloadUrl != url || targetDirectoryUri != normalizedDirectoryUri) {
                    // 新下载请求
                    downloadUrl = url
                    targetDirectoryUri = normalizedDirectoryUri
                    targetFile = tempFile // 处理临时文件

                    // 清理旧缓存文件
                    if (tempFile.exists()) { }

                    // 清除其他版本中的旧文件
                    cacheDirectory.listFiles()?.forEach { file ->
                        if (file.name.startsWith("update_") && file.name != finalFileName && file.name != tempFileName) {
                            file.delete()
                        }
                    }
                    
                    _downloadState.value = DownloadState(status = DownloadStatus.DOWNLOADING)
                } else {
                    if (_downloadState.value.status == DownloadStatus.PAUSED || 
                        _downloadState.value.status == DownloadStatus.ERROR) {
                        targetFile = tempFile // 确保定位到临时位置
                         _downloadState.value = _downloadState.value.copy(status = DownloadStatus.DOWNLOADING, error = null)
                    } else if (_downloadState.value.status == DownloadStatus.COMPLETED) {
                        return@withContext
                    }
                    // 如果 targetFile 为空（例如，应用程序重启），则将其重置
                    if (targetFile == null) targetFile = tempFile
                }
                
                isPaused = false
                isCancelled = false
                var downloadedLength = if (targetFile!!.exists()) targetFile!!.length() else 0L
                var resumed = downloadedLength > 0

                fun buildRequest(length: Long): Request {
                    val builder = Request.Builder().url(url)
                    if (length > 0) {
                        Log.d(TAG, "Resuming download from bytes=$length")
                        builder.header("Range", "bytes=$length-")
                    }
                    return builder.build()
                }

                var response = client.newCall(buildRequest(downloadedLength)).execute()

                // HTTP 416 的非递归一次性回退：清除临时文件并重试一次完整下载
                if (!response.isSuccessful && response.code == 416 && resumed) {
                    response.close()
                    if (targetFile!!.exists() && !targetFile!!.delete()) {
                        Log.w(TAG, "Failed to delete stale temp file on 416: ${targetFile!!.absolutePath}")
                    }
                    downloadedLength = 0L
                    resumed = false
                    response = client.newCall(buildRequest(downloadedLength)).execute()
                }

                // A server or proxy may ignore Range and return the complete file with 200.
                // Never append that body to a partial file, otherwise the APK is corrupted.
                if (resumed && response.code == 200) {
                    response.close()
                    if (targetFile!!.exists() && !targetFile!!.delete()) {
                        throw Exception("Failed to reset partial download")
                    }
                    downloadedLength = 0L
                    resumed = false
                    response = client.newCall(buildRequest(downloadedLength)).execute()
                }

                if (resumed && response.code != 206) {
                    val responseCode = response.code
                    response.close()
                    throw Exception("Server did not honor Range request: $responseCode")
                }

                if (!response.isSuccessful) {
                    if (response.code == 416) {
                        downloadUrl = ""
                        _downloadState.value = _downloadState.value.copy(
                            status = DownloadStatus.ERROR,
                            error = "服务器拒绝续传，请重试"
                        )
                        return@withContext
                    }
                    throw Exception("Download failed: ${response.code}")
                }

                if (resumed) {
                    val contentRange = response.header("Content-Range")
                    val rangeStart = contentRange
                        ?.substringAfter("bytes ", "")
                        ?.substringBefore('-', "")
                        ?.toLongOrNull()
                    if (rangeStart != downloadedLength) {
                        response.close()
                        throw Exception("Invalid Content-Range for resumed download: $contentRange")
                    }
                }

                val body = response.body ?: throw Exception("Response body is null")
                val totalLength = if (resumed) body.contentLength() + downloadedLength else body.contentLength()
                
                var inputStream: InputStream? = null
                var outputStream: FileOutputStream? = null
                
                try {
                    inputStream = body.byteStream()
                    outputStream = FileOutputStream(targetFile!!, resumed) // Append mode when resume succeeds
                    
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    var totalBytesRead = downloadedLength
                    var lastUpdateTime = System.currentTimeMillis()
                    var lastBytesRead = totalBytesRead
                    
                    while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                        if (isCancelled) {
                            targetFile?.delete()
                            _downloadState.value = DownloadState(status = DownloadStatus.CANCELED)
                            return@withContext
                        }
                        
                        if (isPaused) {
                            _downloadState.value = _downloadState.value.copy(status = DownloadStatus.PAUSED)
                            return@withContext
                        }
                        
                        outputStream.write(buffer, 0, bytesRead)
                        totalBytesRead += bytesRead

                        // 每 500 毫秒计算一次速度和进度
                        val currentTime = System.currentTimeMillis()
                        if (currentTime - lastUpdateTime >= 500) {
                            val timeDiff = currentTime - lastUpdateTime
                            val bytesDiff = totalBytesRead - lastBytesRead
                            val speed = calculateSpeed(bytesDiff, timeDiff)
                            val progress = if (totalLength > 0) ((totalBytesRead * 100) / totalLength).toInt() else 0
                            
                            _downloadState.value = DownloadState(
                                status = DownloadStatus.DOWNLOADING,
                                progress = progress.coerceIn(0, 100),
                                speed = speed,
                                downloadedBytes = totalBytesRead,
                                totalBytes = totalLength
                            )
                            
                            lastUpdateTime = currentTime
                            lastBytesRead = totalBytesRead
                        }
                    }

                    // 关闭临时文件后，再将其移动到默认目录或复制到 SAF 目录。
                    outputStream.flush()
                    outputStream.close()
                    outputStream = null
                    inputStream.close()
                    inputStream = null
                    body.close()

                    val downloadedArtifact = if (downloadDirectory == null) {
                        if (!targetFile!!.renameTo(finalFile)) {
                            throw Exception("Failed to rename temp file to apk")
                        }
                        DownloadArtifact(file = finalFile)
                    } else {
                        val existingFile = downloadDirectory.findFile(finalFileName)
                        if (existingFile != null && !existingFile.delete()) {
                            throw Exception("无法替换自定义目录中的旧安装包")
                        }
                        val destinationFile = downloadDirectory.createFile(
                            "application/vnd.android.package-archive",
                            finalFileName
                        ) ?: throw Exception("无法在自定义目录创建安装包")

                        try {
                            val destinationOutput = context.contentResolver
                                .openOutputStream(destinationFile.uri, "rwt")
                                ?: throw Exception("无法写入自定义下载目录")
                            destinationOutput.use { output ->
                                targetFile!!.inputStream().use { input ->
                                    input.copyTo(output)
                                }
                            }
                            if (!destinationFile.exists() || destinationFile.length() <= 0) {
                                throw Exception("写入自定义下载目录后的安装包无效")
                            }
                            if (!targetFile!!.delete()) {
                                Log.w(TAG, "Failed to delete temporary APK: ${targetFile!!.absolutePath}")
                            }
                            DownloadArtifact(uri = destinationFile.uri)
                        } catch (error: Exception) {
                            destinationFile.delete()
                            throw error
                        }
                    }

                    consecutiveFailures = 0 // 下载成功，重置失败计数
                    _downloadState.value = DownloadState(
                        status = DownloadStatus.COMPLETED,
                        progress = 100,
                        speed = "0 KB/s",
                        downloadedBytes = totalBytesRead,
                        totalBytes = totalLength,
                        file = downloadedArtifact.file,
                        fileUri = downloadedArtifact.uri
                    )
                    
                } finally {
                    inputStream?.close()
                    outputStream?.close()
                    body.close()
                }
                
            } catch (e: Exception) {
                if (!isCancelled && !isPaused) {
                    Log.e(TAG, "Download error", e)
                    
                    // 判断是否为网络不可用错误
                    val isNetworkError = e is java.net.UnknownHostException ||
                            e is java.net.ConnectException ||
                            e is java.net.NoRouteToHostException ||
                            e is java.net.SocketTimeoutException ||
                            (e.message?.contains("Unable to resolve host", ignoreCase = true) == true) ||
                            (e.message?.contains("No address associated", ignoreCase = true) == true)
                    
                    // 非网络错误才计入连续失败次数
                    if (!isNetworkError) {
                        consecutiveFailures++
                    }
                    
                    _downloadState.value = _downloadState.value.copy(
                        status = DownloadStatus.ERROR,
                        error = e.message,
                        consecutiveFailures = consecutiveFailures
                    )
                }
            }
        }
    }
    
    fun pauseDownload() {
        if (_downloadState.value.status == DownloadStatus.DOWNLOADING) {
            isPaused = true
        }
    }
    
    fun cancelDownload() {
        isCancelled = true
        // Delete temp file if it exists
        targetFile?.delete() 
        consecutiveFailures = 0 // 取消时重置失败计数
        _downloadState.value = DownloadState(status = DownloadStatus.CANCELED)
        downloadUrl = "" // Reset url to force fresh start next time
        targetDirectoryUri = null
    }
    
    fun resetState() {
        _downloadState.value = DownloadState()
        downloadUrl = ""
        targetFile = null
        targetDirectoryUri = null
        isPaused = false
        isCancelled = false
        consecutiveFailures = 0
    }
    
    // Check if valid APK exists
    fun isApkReady(
        context: Context,
        versionName: String,
        downloadDirectoryUri: String? = null
    ): DownloadArtifact? {
        val fileName = "update_$versionName.apk"
        val normalizedDirectoryUri = downloadDirectoryUri?.takeIf { it.isNotBlank() }
        if (normalizedDirectoryUri != null) {
            val documentFile = runCatching {
                DocumentFile.fromTreeUri(context, Uri.parse(normalizedDirectoryUri))
                    ?.findFile(fileName)
            }.getOrNull()
            return documentFile?.takeIf {
                it.exists() && it.isFile && it.length() > 0
            }?.let { DownloadArtifact(uri = it.uri) }
        }

        val file = File(context.externalCacheDir ?: context.cacheDir, fileName)
        return if (file.exists() && file.length() > 0) {
            DownloadArtifact(file = file)
        } else {
            null
        }
    }

    private fun calculateSpeed(bytesDiff: Long, timeMs: Long): String {
        if (timeMs == 0L) return "0 KB/s"
        val bytesPerSec = (bytesDiff * 1000) / timeMs
        return when {
            bytesPerSec >= 1024 * 1024 -> String.format(Locale.US, "%.1f MB/s", bytesPerSec / (1024.0 * 1024.0))
            bytesPerSec >= 1024 -> String.format(Locale.US, "%.1f KB/s", bytesPerSec / 1024.0)
            else -> "$bytesPerSec B/s"
        }
    }
}
