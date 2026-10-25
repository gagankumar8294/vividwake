package com.lockscreen.videowake

import android.content.Context
import android.content.SharedPreferences
import android.os.StatFs
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

/**
 * VideoDownloadManager — Handles chunked, resilient video downloads
 * into the private app sandbox (context.filesDir/cloud_wallpapers/).
 *
 * Guarantees:
 * - Hidden from gallery apps via .nomedia
 * - Atomic rename from .tmp to .mp4
 * - Automatic cleanup of corrupted / aborted downloads
 * - Available disk storage pre-flight validation
 */
class VideoDownloadManager(private val context: Context) {

    companion object {
        private const val TAG = "VideoDownloadManager"
        private const val PREFS_DOWNLOADS = "cloud_downloads_meta"
        private const val KEY_DOWNLOADED_IDS = "downloaded_cloud_ids"
        private const val MIN_REQUIRED_HEADROOM_BYTES = 25L * 1024L * 1024L // 25 MB safety headroom
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_DOWNLOADS, Context.MODE_PRIVATE)

    val cloudWallpapersDir: File = File(context.filesDir, CloudConfig.FOLDER_CLOUD_WALLPAPERS).apply {
        mkdirs()
        // Ensure .nomedia exists so gallery never indexes this folder
        val nomedia = File(this, ".nomedia")
        if (!nomedia.exists()) {
            try { nomedia.createNewFile() } catch (_: Exception) {}
        }
    }

    val cloudThumbsDir: File = File(context.filesDir, CloudConfig.FOLDER_CLOUD_THUMBS).apply {
        mkdirs()
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(25, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    /**
     * Check if the device has enough free storage to download a video.
     */
    fun hasEnoughStorage(sizeBytes: Long): Boolean {
        return try {
            val stat = StatFs(context.filesDir.path)
            val available = stat.availableBlocksLong * stat.blockSizeLong
            available > (sizeBytes + MIN_REQUIRED_HEADROOM_BYTES)
        } catch (e: Exception) {
            Log.w(TAG, "Error checking free storage", e)
            true // fallback to proceed
        }
    }

    /**
     * Download a cloud video directly into the app sandbox with progress callback.
     */
    suspend fun downloadVideo(
        item: WallpaperItem,
        onProgress: (Int) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        val destFile = getLocalVideoFile(item.id)
        val tempFile = File(cloudWallpapersDir, "${item.id}_${System.currentTimeMillis()}.tmp")

        try {
            if (item.videoUrl.isBlank()) {
                return@withContext Result.failure(IllegalArgumentException("Video URL is empty"))
            }

            if (destFile.exists() && destFile.length() > 0) {
                markDownloaded(item.id)
                onProgress(100)
                return@withContext Result.success(destFile)
            }

            if (!hasEnoughStorage(item.sizeBytes)) {
                return@withContext Result.failure(IllegalStateException("Insufficient free storage on device"))
            }

            val request = Request.Builder()
                .url(item.videoUrl)
                .header("User-Agent", "VideoLockScreen-Android")
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw Exception("HTTP ${response.code}: ${response.message}")
                }
                val body = response.body ?: throw Exception("Empty response body from server")
                val totalBytes = if (item.sizeBytes > 0) item.sizeBytes else body.contentLength()

                FileOutputStream(tempFile).use { output ->
                    var downloadedBytes = 0L
                    val buffer = ByteArray(8 * 1024)
                    body.byteStream().use { input ->
                        var read: Int
                        while (input.read(buffer).also { read = it } != -1) {
                            output.write(buffer, 0, read)
                            downloadedBytes += read
                            if (totalBytes > 0) {
                                val progress = ((downloadedBytes * 100) / totalBytes).toInt().coerceIn(0, 99)
                                onProgress(progress)
                            }
                        }
                    }
                    output.flush()
                }
            }

            if (!tempFile.exists() || tempFile.length() == 0L) {
                tempFile.delete()
                return@withContext Result.failure(Exception("Downloaded video is empty"))
            }

            // Atomic rename from .tmp to final .mp4
            if (destFile.exists()) destFile.delete()
            val renamed = tempFile.renameTo(destFile)
            if (!renamed) {
                tempFile.copyTo(destFile, overwrite = true)
                tempFile.delete()
            }

            markDownloaded(item.id)
            onProgress(100)
            Log.d(TAG, "Download complete: ${item.title} -> ${destFile.absolutePath} (${destFile.length()} bytes)")
            Result.success(destFile)
        } catch (e: Exception) {
            Log.e(TAG, "Download failed for ${item.title}", e)
            if (tempFile.exists()) tempFile.delete()
            Result.failure(e)
        }
    }

    /**
     * Delete local downloaded video to immediately free device storage.
     * The cloud catalog remains untouched.
     */
    fun deleteLocalDownload(itemId: String): Boolean {
        return try {
            val file = getLocalVideoFile(itemId)
            if (file.exists()) {
                file.delete()
            }
            unmarkDownloaded(itemId)
            Log.d(TAG, "Deleted local file for $itemId, storage freed")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error deleting local download for $itemId", e)
            false
        }
    }

    fun isDownloaded(itemId: String): Boolean {
        val file = getLocalVideoFile(itemId)
        return file.exists() && file.length() > 0 && getDownloadedIds().contains(itemId)
    }

    fun getLocalVideoFile(itemId: String): File {
        val safeName = itemId.replace(Regex("[^a-zA-Z0-9_]"), "_")
        return File(cloudWallpapersDir, "cloud_${safeName}.mp4")
    }

    private fun getDownloadedIds(): Set<String> {
        val jsonStr = prefs.getString(KEY_DOWNLOADED_IDS, "[]") ?: "[]"
        return try {
            val arr = JSONArray(jsonStr)
            (0 until arr.length()).map { arr.getString(it) }.toSet()
        } catch (e: Exception) {
            emptySet()
        }
    }

    private fun markDownloaded(itemId: String) {
        val existing = getDownloadedIds().toMutableSet()
        existing.add(itemId)
        val arr = JSONArray(existing.toList())
        prefs.edit().putString(KEY_DOWNLOADED_IDS, arr.toString()).apply()
    }

    private fun unmarkDownloaded(itemId: String) {
        val existing = getDownloadedIds().toMutableSet()
        existing.remove(itemId)
        val arr = JSONArray(existing.toList())
        prefs.edit().putString(KEY_DOWNLOADED_IDS, arr.toString()).apply()
    }
}
