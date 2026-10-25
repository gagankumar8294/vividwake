package com.lockscreen.videowake

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * CloudCatalogRepository — Fetches and parses the cloud wallpaper catalog.
 * Connects to CloudConfig.CATALOG_URL with seamless fallback to built-in presets.
 */
class CloudCatalogRepository(
    private val context: Context,
    private val downloadManager: VideoDownloadManager
) {

    companion object {
        private const val TAG = "CloudCatalogRepo"
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    /**
     * Fetch catalog from remote URL or fallback to local built-in JSON.
     * @param isAdminMode If true, returns draft/hidden wallpapers with admin tags.
     */
    suspend fun fetchCatalog(isAdminMode: Boolean = false): List<WallpaperItem> = withContext(Dispatchers.IO) {
        val jsonString = fetchRemoteJsonOrFallback()
        parseCatalog(jsonString, isAdminMode)
    }

    private fun fetchRemoteJsonOrFallback(): String {
        try {
            if (CloudConfig.CATALOG_URL.isNotBlank()) {
                val request = Request.Builder()
                    .url(CloudConfig.CATALOG_URL)
                    .header("User-Agent", "VideoLockScreen-Android")
                    .build()

                httpClient.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val body = response.body?.string()
                        if (!body.isNullOrBlank()) {
                            Log.d(TAG, "Successfully fetched catalog from remote: ${body.length} chars")
                            return body
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Remote catalog fetch failed (${e.message}), using fallback catalog")
        }
        return CloudConfig.getBuiltinCatalogJson()
    }

    private fun parseCatalog(jsonString: String, isAdminMode: Boolean): List<WallpaperItem> {
        val list = mutableListOf<WallpaperItem>()
        try {
            val jsonArray = JSONArray(jsonString)
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val id = obj.optString("id", "cloud_wp_$i")
                val title = obj.optString("title", "Cloud Wallpaper")
                val description = obj.optString("description", "")
                val status = obj.optString("status", "published")
                val isFeatured = obj.optBoolean("isFeatured", false)
                val isVipOnly = obj.optBoolean("isVipOnly", false)
                val videoUrl = obj.optString("videoUrl", "")
                val thumbUrl = obj.optString("thumbUrl", "")
                val sizeBytes = obj.optLong("sizeBytes", 0L)
                val sizeDisplay = obj.optString("sizeDisplay", formatBytes(sizeBytes))
                val durationMs = obj.optLong("durationMs", 0L)
                val width = obj.optInt("width", 1080)
                val height = obj.optInt("height", 1920)

                // Visibility rule:
                // Non-admin sees ONLY "published".
                // Admin sees "published", "hidden", and "draft".
                if (!isAdminMode && status != "published") {
                    continue
                }

                val isDownloaded = downloadManager.isDownloaded(id)
                val localPath = if (isDownloaded) downloadManager.getLocalVideoFile(id).absolutePath else ""

                list.add(
                    WallpaperItem(
                        id = id,
                        title = title,
                        isPreset = false,
                        filePath = localPath,
                        durationMs = durationMs,
                        width = width,
                        height = height,
                        isCloud = true,
                        cloudId = id,
                        videoUrl = videoUrl,
                        thumbUrl = thumbUrl,
                        sizeBytes = sizeBytes,
                        sizeDisplay = sizeDisplay,
                        isDownloaded = isDownloaded,
                        description = description,
                        status = status,
                        isFeatured = isFeatured,
                        isVipOnly = isVipOnly
                    )
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing catalog JSON", e)
        }
        return list
    }

    private fun formatBytes(bytes: Long): String {
        return when {
            bytes <= 0 -> "Cloud Video"
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            else -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
        }
    }
}
