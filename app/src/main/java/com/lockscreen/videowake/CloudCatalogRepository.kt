package com.lockscreen.videowake

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * CloudCatalogRepository — Fetches and parses the cloud wallpaper catalog.
 * Connects to CloudConfig.CATALOG_URL (raw GitHub JSON) with cache-busting.
 * Returns ONLY the real wallpapers defined in wallpapers.json.
 */
class CloudCatalogRepository(
    private val context: Context,
    private val downloadManager: VideoDownloadManager
) {

    companion object {
        private const val TAG = "CloudCatalogRepo"
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .build()

    suspend fun fetchCatalog(isAdminMode: Boolean = false): List<WallpaperItem> = withContext(Dispatchers.IO) {
        // Clear any old legacy cache file if it exists
        try {
            val oldCache = File(context.filesDir, "catalog_cache.json")
            if (oldCache.exists()) oldCache.delete()
        } catch (_: Exception) {}

        val jsonString = fetchRemoteJsonOrFallback()
        parseCatalog(jsonString, isAdminMode)
    }

    private fun fetchRemoteJsonOrFallback(): String {
        val urls = listOf(
            CloudConfig.CATALOG_URL,
            "https://cdn.jsdelivr.net/gh/gagankumar8294/vividwake@main/wallpapers.json",
            "https://raw.githubusercontent.com/gagankumar8294/vividwake/main/wallpapers.json"
        ).distinct()

        for (baseUrl in urls) {
            try {
                val urlWithBuster = if (baseUrl.contains("?")) {
                    "$baseUrl&_nocache=${System.currentTimeMillis()}"
                } else {
                    "$baseUrl?_nocache=${System.currentTimeMillis()}"
                }
                val request = Request.Builder()
                    .url(urlWithBuster)
                    .header("Cache-Control", "no-cache, no-store, must-revalidate")
                    .header("Pragma", "no-cache")
                    .header("Expires", "0")
                    .header("User-Agent", "VideoLockScreen-Android")
                    .build()

                httpClient.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val body = response.body?.string()
                        if (!body.isNullOrBlank()) {
                            Log.d(TAG, "Fetched live wallpapers.json from $baseUrl (${body.length} chars)")
                            return body
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed fetch from $baseUrl (${e.message}), trying next...")
            }
        }
        return getAssetCatalog()
    }

    private fun getAssetCatalog(): String {
        return try {
            context.assets.open("wallpapers.json").bufferedReader().use { it.readText() }
        } catch (_: Exception) {
            CloudConfig.getBuiltinCatalogJson()
        }
    }

    private fun parseCatalog(jsonString: String, isAdminMode: Boolean): List<WallpaperItem> {
        val list = mutableListOf<WallpaperItem>()
        try {
            val jsonArray = JSONArray(jsonString)
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val id = obj.optString("id", "cloud_wp_$i")
                val title = obj.optString("title", "Cloud Wallpaper")
                val category = obj.optString("category", "General")
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

                val tagsList = mutableListOf<String>()
                val tagsArray = obj.optJSONArray("tags")
                if (tagsArray != null) {
                    for (t in 0 until tagsArray.length()) {
                        val tag = tagsArray.optString(t)
                        if (!tag.isNullOrBlank()) tagsList.add(tag)
                    }
                }

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
                        isVipOnly = isVipOnly,
                        category = category,
                        tags = tagsList
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
