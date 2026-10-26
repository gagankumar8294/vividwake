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
            val idx = rows.indexOfFirst { it is StoreRow.Item && it.item.id == itemId }
            if (idx != -1) {
                notifyItemChanged(idx, progress)
            }
        }
    }

    fun finishDownload(updated: WallpaperItem) {
        downloadingProgressMap.remove(updated.id)
        val idx = rows.indexOfFirst { it is StoreRow.Item && it.item.id == updated.id }
        if (idx != -1) {
            rows[idx] = StoreRow.Item(updated)
            notifyItemChanged(idx)
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
                btnApply.text = "Select"
                btnDeleteLocal.visibility = View.VISIBLE
            } else {
                pbDownload.visibility = View.GONE
                tvProgress.visibility = View.GONE
                btnDownload.visibility = View.VISIBLE
                btnDownload.text = if (item.sizeDisplay.isNotBlank()) "Download (${item.sizeDisplay})" else "Download"
                btnApply.visibility = View.GONE
                btnDeleteLocal.visibility = View.GONE
            }

            // Click anywhere on card: if downloaded, load preview; if not downloaded, start download
            card.setOnClickListener {
                if (item.isDownloaded) {
                    onApply(item)
                } else {
                    onDownload(item)
                }
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
            if (progress >= 100) {
                pbDownload.visibility = View.GONE
                tvProgress.visibility = View.GONE
                btnDownload.visibility = View.GONE
                btnApply.visibility = View.VISIBLE
                btnApply.text = "Select"
                btnDeleteLocal.visibility = View.VISIBLE
            } else {
                pbDownload.visibility = View.VISIBLE
                tvProgress.visibility = View.VISIBLE
                pbDownload.progress = progress
                tvProgress.text = "Downloading $progress%..."
                btnDownload.visibility = View.GONE
                btnApply.visibility = View.GONE
                btnDeleteLocal.visibility = View.GONE
            }
        }

        private fun loadThumbnail(item: WallpaperItem) {
            thumbJob?.cancel()

            // If item has a custom thumbUrl provided, load that lightweight image
            if (item.thumbUrl.isNotBlank()) {
                val cachedMem = memoryCache.get(item.id)
                if (cachedMem != null) {
                    ivThumb.setImageBitmap(cachedMem)
                    return
                }

                val thumbsDir = File(context.filesDir, CloudConfig.FOLDER_CLOUD_THUMBS).apply { mkdirs() }
                val cacheFile = File(thumbsDir, "thumb_${item.id}.jpg")

                thumbJob = scope.launch {
                    val bitmap = withContext(Dispatchers.IO) {
                        if (cacheFile.exists() && cacheFile.length() > 0) {
                            try {
                                return@withContext BitmapFactory.decodeFile(cacheFile.absolutePath)
                            } catch (_: Exception) {}
                        }
                        try {
                            val conn = URL(item.thumbUrl).openConnection().apply {
                                connectTimeout = 4000
                                readTimeout = 4000
                            }
                            val downloaded = conn.getInputStream().use { stream ->
                                BitmapFactory.decodeStream(stream)
                            }
                            if (downloaded != null) {
                                saveBitmapToFile(downloaded, cacheFile)
                                return@withContext downloaded
                            }
                        } catch (_: Exception) {}
                        null
                    }

                    if (bitmap != null) {
                        memoryCache.put(item.id, bitmap)
                        ivThumb.setImageBitmap(bitmap)
                    } else {
                        ivThumb.setImageDrawable(null)
                    }
                }
            } else {
                // Keep the allocated frame space clean and empty as requested
                ivThumb.setImageDrawable(null)
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
