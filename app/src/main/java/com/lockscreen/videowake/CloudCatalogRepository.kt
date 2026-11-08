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
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    suspend fun fetchCatalog(
        userEmail: String? = null,
        userId: String? = null,
        assignedIds: Set<String> = emptySet()
    ): List<WallpaperItem> = withContext(Dispatchers.IO) {
        // Clear any old legacy cache file if it exists
        try {
            val oldCache = File(context.filesDir, "catalog_cache.json")
            if (oldCache.exists()) oldCache.delete()
        } catch (_: Exception) {}

        val jsonString = fetchRemoteJsonOrFallback(userEmail, userId)
        parseCatalog(jsonString, userEmail, userId, assignedIds)
    }

    private fun fetchRemoteJsonOrFallback(userEmail: String?, userId: String?): String {
        val params = mutableListOf<String>()
        if (!userEmail.isNullOrBlank()) {
            params.add("userEmail=" + java.net.URLEncoder.encode(userEmail, "UTF-8"))
        }
        if (!userId.isNullOrBlank()) {
            params.add("userId=" + java.net.URLEncoder.encode(userId, "UTF-8"))
        }
        val queryStr = if (params.isNotEmpty()) "?" + params.joinToString("&") else ""

        val urls = listOf(
            "${CloudConfig.ANALYTICS_BASE_URL}/api/wallpapers/catalog$queryStr",
            "http://127.0.0.1:5000/api/wallpapers/catalog$queryStr",
            "https://raw.githubusercontent.com/gagankumar8294/vividwake/main/wallpapers.json",
            "https://cdn.jsdelivr.net/gh/gagankumar8294/vividwake@main/wallpapers.json"
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
                        if (!body.isNullOrBlank() && body.trim() != "[]" && body.trim() != "{}") {
                            Log.d(TAG, "Fetched live wallpapers.json from $baseUrl (${body.length} chars)")
                            return body
                        } else {
                            Log.d(TAG, "Response from $baseUrl was empty array/object, trying fallback...")
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

    private fun parseCatalog(
        jsonString: String,
        userEmail: String? = null,
        userId: String? = null,
        assignedIds: Set<String> = emptySet()
    ): List<WallpaperItem> {
        val list = mutableListOf<WallpaperItem>()
        try {
            val downloadedIds = downloadManager.getDownloadedIds()
            val jsonArray = JSONArray(jsonString)
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val id = obj.optString("id", "cloud_wp_$i")
                val title = obj.optString("title", "Cloud Wallpaper")
                val category = obj.optString("category", "General")
                val description = obj.optString("description", "")
                val status = obj.optString("status", "draft")
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

                val allowedUsersList = mutableListOf<String>()
                val allowedArr = obj.optJSONArray("allowedUsers")
                if (allowedArr != null) {
                    for (u in 0 until allowedArr.length()) {
                        val uStr = allowedArr.optString(u)?.lowercase()?.trim()
                        if (!uStr.isNullOrBlank()) allowedUsersList.add(uStr)
                    }
                }

                val blockedUsersList = mutableListOf<String>()
                val blockedArr = obj.optJSONArray("blockedUsers")
                if (blockedArr != null) {
                    for (u in 0 until blockedArr.length()) {
                        val uStr = blockedArr.optString(u)?.lowercase()?.trim()
                        if (!uStr.isNullOrBlank()) blockedUsersList.add(uStr)
                    }
                }

                val normEmail = userEmail?.lowercase()?.trim() ?: ""
                val normUid = userId?.trim() ?: ""

                // 1. Explicitly blocked check
                val isBlocked = (normEmail.isNotBlank() && blockedUsersList.contains(normEmail)) ||
                                (normUid.isNotBlank() && blockedUsersList.contains(normUid))
                if (isBlocked) {
                    continue
                }

                val isServerGifted = obj.optBoolean("isGifted", false)

                // 2. Explicitly allowed check
                val isExplicitlyAllowed = isServerGifted ||
                                         (normEmail.isNotBlank() && allowedUsersList.contains(normEmail)) ||
                                         (normUid.isNotBlank() && allowedUsersList.contains(normUid)) ||
                                         assignedIds.contains(id)

                // 3. User Visibility rule:
                // Normal users on mobile see published wallpapers (unless blocked)
                // Draft / Restricted wallpapers are visible ONLY if user is explicitly granted access or gifted by the Web Admin!
                val isVisible = (status == "published") || isExplicitlyAllowed
                if (!isVisible) {
                    continue
                }

                val isGifted = isServerGifted || (isExplicitlyAllowed && status != "published")

                val isDownloaded = if (downloadedIds.contains(id)) {
                    val f = downloadManager.getLocalVideoFile(id)
                    f.exists() && f.length() > 0
                } else {
                    false
                }
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
                        isGifted = isGifted,
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
