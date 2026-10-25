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
        private const val CACHE_FILE_NAME = "catalog_cache.json"
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    suspend fun fetchCatalog(isAdminMode: Boolean = false): List<WallpaperItem> = withContext(Dispatchers.IO) {
        val jsonString = fetchRemoteJsonOrFallback()
        parseCatalog(jsonString, isAdminMode)
    }

    private fun fetchRemoteJsonOrFallback(): String {
        try {
            if (CloudConfig.CATALOG_URL.isNotBlank()) {
                val urlWithBuster = if (CloudConfig.CATALOG_URL.contains("?")) {
                    "${CloudConfig.CATALOG_URL}&_nocache=${System.currentTimeMillis()}"
                } else {
                    "${CloudConfig.CATALOG_URL}?_nocache=${System.currentTimeMillis()}"
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
                            Log.d(TAG, "Fetched live wallpapers.json from remote (${body.length} chars)")
                            saveLocalCachedCatalog(body)
                            return body
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Remote catalog fetch failed (${e.message}), using bundled/cached catalog")
        }
        return getLocalCachedOrAssetCatalog()
    }

    private fun saveLocalCachedCatalog(json: String) {
        try {
            val file = File(context.filesDir, CACHE_FILE_NAME)
            file.writeText(json)
        } catch (_: Exception) {}
    }

    private fun getLocalCachedOrAssetCatalog(): String {
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
                        category = category
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
