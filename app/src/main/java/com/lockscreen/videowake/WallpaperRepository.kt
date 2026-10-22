package com.lockscreen.videowake

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

class WallpaperRepository(private val context: Context) {

    companion object {
        private const val TAG = "WallpaperRepository"
        private const val PREFS_CUSTOM_WALLPAPERS = "custom_wallpapers_meta"
        private const val KEY_CUSTOM_LIST = "custom_list_json"

        const val ID_PRESET_TRIO = "preset_trio"
        const val ID_PRESET_WAND = "preset_wand"
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(VideoWallpaperService.PREFS_NAME, Context.MODE_PRIVATE)
    private val metaPrefs: SharedPreferences =
        context.getSharedPreferences(PREFS_CUSTOM_WALLPAPERS, Context.MODE_PRIVATE)

    private val wallpapersDir = File(context.filesDir, "wallpapers").apply { mkdirs() }
    private val thumbsDir = File(context.filesDir, "thumbs").apply { mkdirs() }

    fun getPresetWallpapers(): List<WallpaperItem> {
        return listOf(
            WallpaperItem(
                id = ID_PRESET_TRIO,
                title = "Hogwarts Trio",
                isPreset = true,
                rawResId = R.raw.lock_wake_video,
                rawResName = "lock_wake_video",
                width = 720,
                height = 1280
            ),
            WallpaperItem(
                id = ID_PRESET_WAND,
                title = "Wand Magic",
                isPreset = true,
                rawResId = R.raw.preset_wand_wake,
                rawResName = "preset_wand_wake",
                width = 720,
                height = 1280
            )
        )
    }

    fun getCustomWallpapers(): List<WallpaperItem> {
        val list = mutableListOf<WallpaperItem>()
        val jsonStr = metaPrefs.getString(KEY_CUSTOM_LIST, null) ?: return list

        try {
            val jsonArray = JSONArray(jsonStr)
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val filePath = obj.optString("filePath")
                val file = File(filePath)
                // Only include if file actually exists on disk
                if (file.exists() && file.length() > 0) {
                    list.add(
                        WallpaperItem(
                            id = obj.getString("id"),
                            title = obj.optString("title", "Custom Video"),
                            isPreset = false,
                            filePath = filePath,
                            thumbPath = obj.optString("thumbPath"),
                            durationMs = obj.optLong("durationMs", 0L),
                            width = obj.optInt("width", 0),
                            height = obj.optInt("height", 0)
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing custom wallpapers JSON", e)
        }
        return list
    }

    fun getAllWallpapers(): List<WallpaperItem> {
        val all = mutableListOf<WallpaperItem>()
        all.addAll(getPresetWallpapers())
        all.addAll(getCustomWallpapers())
        return all
    }

    fun getActiveWallpaper(): WallpaperItem {
        val activeId = prefs.getString(VideoWallpaperService.KEY_ACTIVE_ID, ID_PRESET_TRIO) ?: ID_PRESET_TRIO
        val all = getAllWallpapers()
        return all.find { it.id == activeId } ?: all.first()
    }

    fun setActiveWallpaper(item: WallpaperItem) {
        val editor = prefs.edit()
        editor.putString(VideoWallpaperService.KEY_ACTIVE_ID, item.id)
        if (item.isPreset) {
            editor.putString(VideoWallpaperService.KEY_ACTIVE_TYPE, "preset")
            editor.putString(VideoWallpaperService.KEY_ACTIVE_RES_NAME, item.rawResName)
            editor.remove(VideoWallpaperService.KEY_ACTIVE_FILE_PATH)
        } else {
            editor.putString(VideoWallpaperService.KEY_ACTIVE_TYPE, "file")
            editor.putString(VideoWallpaperService.KEY_ACTIVE_FILE_PATH, item.filePath)
            editor.remove(VideoWallpaperService.KEY_ACTIVE_RES_NAME)
        }
        editor.putLong(VideoWallpaperService.KEY_ACTIVE_TIMESTAMP, System.currentTimeMillis())
        editor.apply()
    }

    suspend fun importVideo(contentUri: Uri, originalName: String?): Result<WallpaperItem> = withContext(Dispatchers.IO) {
        try {
            val timestamp = System.currentTimeMillis()
            val destVideoFile = File(wallpapersDir, "custom_$timestamp.mp4")

            // 1. Copy video from Uri to app internal storage
            context.contentResolver.openInputStream(contentUri)?.use { input ->
                FileOutputStream(destVideoFile).use { output ->
                    input.copyTo(output)
                }
            } ?: return@withContext Result.failure(Exception("Could not open video stream"))

            if (destVideoFile.length() == 0L) {
                destVideoFile.delete()
                return@withContext Result.failure(Exception("Video file is empty"))
            }

            // 2. Extract metadata and thumbnail
            val retriever = MediaMetadataRetriever()
            var durationMs = 0L
            var width = 0
            var height = 0
            var thumbFile: File? = null

            try {
                retriever.setDataSource(destVideoFile.absolutePath)
                durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                val w = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                val h = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0

                if (rotation == 90 || rotation == 270) {
                    width = h
                    height = w
                } else {
                    width = w
                    height = h
                }

                // Extract a frame for thumbnail (at 1 second or middle)
                val targetFrameTimeUs = if (durationMs > 2000) 1_000_000L else 0L
                val frameBitmap = retriever.getFrameAtTime(targetFrameTimeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    ?: retriever.frameAtTime

                if (frameBitmap != null) {
                    thumbFile = File(thumbsDir, "thumb_$timestamp.jpg")
                    FileOutputStream(thumbFile).use { out ->
                        frameBitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error extracting metadata/thumbnail: ${e.message}")
            } finally {
                try { retriever.release() } catch (_: Exception) {}
            }

            val title = if (!originalName.isNullOrBlank()) {
                originalName.substringBeforeLast(".")
            } else {
                "Custom Video ${getCustomWallpapers().size + 1}"
            }

            val newItem = WallpaperItem(
                id = "custom_$timestamp",
                title = title.take(24),
                isPreset = false,
                filePath = destVideoFile.absolutePath,
                thumbPath = thumbFile?.absolutePath ?: "",
                durationMs = durationMs,
                width = width,
                height = height
            )

            // Save to persistent list
            saveCustomWallpaper(newItem)
            Result.success(newItem)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to import video", e)
            Result.failure(e)
        }
    }

    private fun saveCustomWallpaper(newItem: WallpaperItem) {
        val existing = getCustomWallpapers().toMutableList()
        existing.add(newItem)
        persistCustomList(existing)
    }

    fun deleteWallpaper(item: WallpaperItem): Boolean {
        if (item.isPreset) return false // Presets cannot be deleted

        // If this was active, switch back to default trio preset
        if (getActiveWallpaper().id == item.id) {
            setActiveWallpaper(getPresetWallpapers().first())
        }

        // Delete video file and thumbnail file
        try {
            if (item.filePath.isNotEmpty()) File(item.filePath).delete()
            if (item.thumbPath.isNotEmpty()) File(item.thumbPath).delete()
        } catch (e: Exception) {
            Log.w(TAG, "Error deleting wallpaper files", e)
        }

        val existing = getCustomWallpapers().toMutableList()
        existing.removeAll { it.id == item.id }
        persistCustomList(existing)
        return true
    }

    private fun persistCustomList(list: List<WallpaperItem>) {
        val jsonArray = JSONArray()
        for (item in list) {
            val obj = JSONObject().apply {
                put("id", item.id)
                put("title", item.title)
                put("filePath", item.filePath)
                put("thumbPath", item.thumbPath)
                put("durationMs", item.durationMs)
                put("width", item.width)
                put("height", item.height)
            }
            jsonArray.put(obj)
        }
        metaPrefs.edit().putString(KEY_CUSTOM_LIST, jsonArray.toString()).apply()
    }

    fun getFileForSharing(item: WallpaperItem): File? {
        return try {
            if (item.isPreset) {
                val videoDir = File(context.cacheDir, "videos").apply { mkdirs() }
                val videoFile = File(videoDir, "${item.rawResName}.mp4")
                if (!videoFile.exists() || videoFile.length() == 0L) {
                    context.resources.openRawResource(item.rawResId).use { input ->
                        FileOutputStream(videoFile).use { output ->
                            input.copyTo(output)
                        }
                    }
                }
                videoFile
            } else {
                val f = File(item.filePath)
                if (f.exists()) f else null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting file for share", e)
            null
        }
    }
}
