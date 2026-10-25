package com.lockscreen.videowake

import android.content.Context
import android.graphics.BitmapFactory
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
import java.net.URL

/**
 * StoreWallpaperAdapter — RecyclerView Adapter for the Explore Store catalog.
 * Supports live streaming preview taps, download progress, and local deletion.
 */
class StoreWallpaperAdapter(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onPreview: (WallpaperItem) -> Unit,
    private val onDownload: (WallpaperItem) -> Unit,
    private val onApply: (WallpaperItem) -> Unit,
    private val onDeleteLocal: (WallpaperItem) -> Unit
) : RecyclerView.Adapter<StoreWallpaperAdapter.StoreViewHolder>() {

    private val items = mutableListOf<WallpaperItem>()
    private val downloadingProgressMap = mutableMapOf<String, Int>()

    fun submitList(newItems: List<WallpaperItem>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    fun setDownloadingProgress(itemId: String, progress: Int) {
        if (progress >= 100) {
            downloadingProgressMap.remove(itemId)
        } else {
            downloadingProgressMap[itemId] = progress
        }
        val idx = items.indexOfFirst { it.id == itemId }
        if (idx != -1) {
            notifyItemChanged(idx, progress)
        }
    }

    fun updateItem(updated: WallpaperItem) {
        val idx = items.indexOfFirst { it.id == updated.id }
        if (idx != -1) {
            items[idx] = updated
            notifyItemChanged(idx)
        }
    }

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): StoreViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_store_wallpaper, parent, false)
        return StoreViewHolder(view)
    }

    override fun onBindViewHolder(holder: StoreViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun onBindViewHolder(holder: StoreViewHolder, position: Int, payloads: List<Any>) {
        if (payloads.isNotEmpty() && payloads[0] is Int) {
            holder.updateProgressOnly(payloads[0] as Int)
        } else {
            holder.bind(items[position])
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
                btnDownload.text = if (item.sizeDisplay.isNotBlank()) "⬇ Download (${item.sizeDisplay})" else "⬇ Download"
                btnApply.visibility = View.GONE
                btnDeleteLocal.visibility = View.GONE
            }

            // Click on the entire card to trigger live preview streaming
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
            ivThumb.setImageResource(R.drawable.wallpaper_thumb)

            thumbJob = scope.launch {
                val bitmap = withContext(Dispatchers.IO) {
                    if (item.thumbUrl.isNotBlank()) {
                        try {
                            val conn = URL(item.thumbUrl).openConnection().apply {
                                connectTimeout = 6000
                                readTimeout = 6000
                            }
                            conn.getInputStream().use { stream ->
                                BitmapFactory.decodeStream(stream)
                            }
                        } catch (_: Exception) { null }
                    } else null
                }
                if (bitmap != null) {
                    ivThumb.setImageBitmap(bitmap)
                }
            }
        }
    }
}
