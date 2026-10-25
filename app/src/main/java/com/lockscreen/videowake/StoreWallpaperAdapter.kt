package com.lockscreen.videowake

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.util.Log
import android.util.LruCache
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.URL

/**
 * StoreWallpaperAdapter — Dynamic multi-category catalog adapter for the Explore Store.
 * Supports:
 * - Dynamic category section headers with count badges
 * - Unique per-video thumbnail extraction with 2-tier caching (LruCache in RAM + disk)
 * - Material vector button icons
 * - Live streaming preview taps & background download progress
 */
class StoreWallpaperAdapter(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onPreview: (WallpaperItem) -> Unit,
    private val onDownload: (WallpaperItem) -> Unit,
    private val onApply: (WallpaperItem) -> Unit,
    private val onDeleteLocal: (WallpaperItem) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val TAG = "StoreWallpaperAdapter"
        private const val VIEW_TYPE_HEADER = 0
        private const val VIEW_TYPE_ITEM = 1

        private val memoryCache: LruCache<String, Bitmap> = run {
            val maxMemory = (Runtime.getRuntime().maxMemory() / 1024).toInt()
            val cacheSize = maxMemory / 8
            object : LruCache<String, Bitmap>(cacheSize) {
                override fun sizeOf(key: String, bitmap: Bitmap): Int {
                    return bitmap.byteCount / 1024
                }
            }
        }
    }

    sealed class StoreRow {
        data class Header(val categoryName: String, val count: Int) : StoreRow()
        data class Item(val item: WallpaperItem) : StoreRow()
    }

    private val rows = mutableListOf<StoreRow>()
    private val downloadingProgressMap = mutableMapOf<String, Int>()

    fun submitList(catalogItems: List<WallpaperItem>) {
        rows.clear()
        // Group by category dynamically based on JSON records
        val grouped = catalogItems.groupBy { it.category.ifBlank { "General" } }
        grouped.forEach { (catName, itemsInCat) ->
            rows.add(StoreRow.Header(catName, itemsInCat.size))
            itemsInCat.forEach { item ->
                rows.add(StoreRow.Item(item))
            }
        }
        notifyDataSetChanged()
    }

    fun setDownloadingProgress(itemId: String, progress: Int) {
        if (progress >= 100) {
            downloadingProgressMap.remove(itemId)
        } else {
            downloadingProgressMap[itemId] = progress
        }
        val idx = rows.indexOfFirst { it is StoreRow.Item && it.item.id == itemId }
        if (idx != -1) {
            notifyItemChanged(idx, progress)
        }
    }

    fun updateItem(updated: WallpaperItem) {
        val idx = rows.indexOfFirst { it is StoreRow.Item && it.item.id == updated.id }
        if (idx != -1) {
            rows[idx] = StoreRow.Item(updated)
            notifyItemChanged(idx)
        }
    }

    override fun getItemCount(): Int = rows.size

    override fun getItemViewType(position: Int): Int {
        return when (rows[position]) {
            is StoreRow.Header -> VIEW_TYPE_HEADER
            is StoreRow.Item -> VIEW_TYPE_ITEM
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == VIEW_TYPE_HEADER) {
            val view = inflater.inflate(R.layout.item_store_category_header, parent, false)
            CategoryHeaderViewHolder(view)
        } else {
            val view = inflater.inflate(R.layout.item_store_wallpaper, parent, false)
            StoreViewHolder(view)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows[position]) {
            is StoreRow.Header -> (holder as CategoryHeaderViewHolder).bind(row.categoryName, row.count)
            is StoreRow.Item -> (holder as StoreViewHolder).bind(row.item)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int, payloads: List<Any>) {
        if (holder is StoreViewHolder && payloads.isNotEmpty() && payloads[0] is Int) {
            holder.updateProgressOnly(payloads[0] as Int)
        } else {
            onBindViewHolder(holder, position)
        }
    }

    inner class CategoryHeaderViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvTitle: TextView = itemView.findViewById(R.id.tvCategoryTitle)
        private val tvCount: TextView = itemView.findViewById(R.id.tvCategoryCount)

        fun bind(title: String, count: Int) {
            tvTitle.text = title
            tvCount.text = if (count == 1) "1 Wallpaper" else "$count Wallpapers"
        }
    }

    inner class StoreViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val card: MaterialCardView = itemView.findViewById(R.id.cardStoreItem)
        private val ivThumb: ImageView = itemView.findViewById(R.id.ivStoreThumb)
        private val tvTitle: TextView = itemView.findViewById(R.id.tvStoreTitle)
        private val tvDescription: TextView = itemView.findViewById(R.id.tvStoreDescription)
        private val tvSize: TextView = itemView.findViewById(R.id.tvStoreSize)
        private val tvFeaturedBadge: TextView = itemView.findViewById(R.id.tvStoreFeaturedBadge)
        private val tvHiddenBadge: TextView = itemView.findViewById(R.id.tvStoreHiddenBadge)
        private val tvDownloadedBadge: TextView = itemView.findViewById(R.id.tvStoreDownloadedBadge)
        private val pbDownload: ProgressBar = itemView.findViewById(R.id.pbStoreDownload)
        private val tvProgress: TextView = itemView.findViewById(R.id.tvStoreProgressText)
        private val btnDownload: MaterialButton = itemView.findViewById(R.id.btnStoreDownload)
        private val btnApply: MaterialButton = itemView.findViewById(R.id.btnStoreApply)
        private val btnDeleteLocal: MaterialButton = itemView.findViewById(R.id.btnStoreDeleteLocal)

        private var thumbJob: Job? = null
        private val analytics = AnalyticsManager.getInstance(context)

        fun bind(item: WallpaperItem) {
            tvTitle.text = item.title
            tvDescription.text = item.description.ifBlank { "4K animated loop lock screen wallpaper." }
            tvSize.text = item.sizeDisplay.ifBlank { "Cloud Video" }

            // Badges
            tvFeaturedBadge.visibility = if (item.isFeatured) View.VISIBLE else View.GONE
            tvHiddenBadge.visibility = if (item.status != "published") View.VISIBLE else View.GONE
            tvDownloadedBadge.visibility = if (item.isDownloaded) View.VISIBLE else View.GONE

            val currentProgress = downloadingProgressMap[item.id]
            val isDownloading = currentProgress != null

            if (isDownloading && currentProgress != null) {
                pbDownload.visibility = View.VISIBLE
                tvProgress.visibility = View.VISIBLE
                pbDownload.progress = currentProgress
                tvProgress.text = "Downloading $currentProgress%..."
                btnDownload.visibility = View.GONE
                btnApply.visibility = View.GONE
                btnDeleteLocal.visibility = View.GONE
            } else if (item.isDownloaded) {
                pbDownload.visibility = View.GONE
                tvProgress.visibility = View.GONE
                btnDownload.visibility = View.GONE
                btnApply.visibility = View.VISIBLE
                btnDeleteLocal.visibility = View.VISIBLE
            } else {
                pbDownload.visibility = View.GONE
                tvProgress.visibility = View.GONE
                btnDownload.visibility = View.VISIBLE
                btnDownload.text = if (item.sizeDisplay.isNotBlank()) "Download (${item.sizeDisplay})" else "Download"
                btnApply.visibility = View.GONE
                btnDeleteLocal.visibility = View.GONE
            }

            // Click card to stream live preview
            card.setOnClickListener {
                onPreview(item)
            }

            btnDownload.setOnClickListener {
                onDownload(item)
            }

            btnApply.setOnClickListener {
                onApply(item)
            }

            btnDeleteLocal.setOnClickListener {
                onDeleteLocal(item)
            }

            loadThumbnail(item)
        }

        fun updateProgressOnly(progress: Int) {
            pbDownload.visibility = View.VISIBLE
            tvProgress.visibility = View.VISIBLE
            pbDownload.progress = progress
            tvProgress.text = "Downloading $progress%..."
            btnDownload.visibility = View.GONE
            btnApply.visibility = View.GONE
            btnDeleteLocal.visibility = View.GONE
        }

        private fun loadThumbnail(item: WallpaperItem) {
            thumbJob?.cancel()

            // 1. Check in-memory LruCache
            val cachedMem = memoryCache.get(item.id)
            if (cachedMem != null) {
                ivThumb.setImageBitmap(cachedMem)
                return
            }

            // Fallback placeholder while loading
            ivThumb.setImageResource(R.drawable.wallpaper_thumb)

            val thumbsDir = File(context.filesDir, CloudConfig.FOLDER_CLOUD_THUMBS).apply { mkdirs() }
            val cacheFile = File(thumbsDir, "thumb_${item.id}.jpg")
            val start = System.currentTimeMillis()

            thumbJob = scope.launch {
                val bitmap = withContext(Dispatchers.IO) {
                    // 2. Check disk cache
                    if (cacheFile.exists() && cacheFile.length() > 0) {
                        try {
                            val decoded = BitmapFactory.decodeFile(cacheFile.absolutePath)
                            if (decoded != null) return@withContext decoded
                        } catch (_: Exception) {}
                    }

                    // 3. Download from thumbUrl if provided
                    if (item.thumbUrl.isNotBlank()) {
                        try {
                            val conn = URL(item.thumbUrl).openConnection().apply {
                                connectTimeout = 5000
                                readTimeout = 5000
                            }
                            val downloaded = conn.getInputStream().use { stream ->
                                BitmapFactory.decodeStream(stream)
                            }
                            if (downloaded != null) {
                                saveBitmapToFile(downloaded, cacheFile)
                                return@withContext downloaded
                            }
                        } catch (_: Exception) {}
                    }

                    // 4. Extract frame from local video if already downloaded
                    val localVideo = File(context.filesDir, "${CloudConfig.FOLDER_CLOUD_WALLPAPERS}/${item.id}.mp4")
                    if (localVideo.exists() && localVideo.length() > 0) {
                        val frame = extractFrameFromFile(localVideo)
                        if (frame != null) {
                            saveBitmapToFile(frame, cacheFile)
                            return@withContext frame
                        }
                    }

                    // 5. Extract frame directly from remote videoUrl
                    if (item.videoUrl.isNotBlank()) {
                        val frame = extractFrameFromUrl(item.videoUrl)
                        if (frame != null) {
                            saveBitmapToFile(frame, cacheFile)
                            return@withContext frame
                        }
                    }

                    null
                }

                if (bitmap != null) {
                    memoryCache.put(item.id, bitmap)
                    ivThumb.setImageBitmap(bitmap)
                    val duration = System.currentTimeMillis() - start
                    analytics.logAction("thumbnail_ready", "SUCCESS", duration, mapOf(
                        "id" to item.id,
                        "title" to item.title
                    ))
                }
            }
        }

        private fun extractFrameFromFile(file: File): Bitmap? {
            val retriever = MediaMetadataRetriever()
            return try {
                retriever.setDataSource(file.absolutePath)
                retriever.getFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            } catch (e: Exception) {
                Log.w(TAG, "File frame extraction failed: ${e.message}")
                null
            } finally {
                try { retriever.release() } catch (_: Exception) {}
            }
        }

        private fun extractFrameFromUrl(url: String): Bitmap? {
            val retriever = MediaMetadataRetriever()
            return try {
                retriever.setDataSource(url, HashMap<String, String>())
                retriever.getFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            } catch (e: Exception) {
                Log.w(TAG, "Remote frame extraction failed for $url: ${e.message}")
                null
            } finally {
                try { retriever.release() } catch (_: Exception) {}
            }
        }

        private fun saveBitmapToFile(bitmap: Bitmap, file: File) {
            try {
                FileOutputStream(file).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed saving thumb cache: ${e.message}")
            }
        }
    }
}
