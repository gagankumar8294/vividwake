package com.lockscreen.videowake

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.MediaMetadataRetriever
import android.util.LruCache
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class WallpaperAdapter(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onSelectWallpaper: (WallpaperItem) -> Unit,
    private val onDeleteWallpaper: (WallpaperItem) -> Unit,
    private val onAddVideoClicked: () -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val VIEW_TYPE_ITEM = 0
        private const val VIEW_TYPE_ADD = 1

        private val memoryCache: LruCache<String, Bitmap> = run {
            val maxMemory = (Runtime.getRuntime().maxMemory() / 1024).toInt()
            val cacheSize = maxMemory / 8 // 1/8th of memory for thumb cache
            object : LruCache<String, Bitmap>(cacheSize) {
                override fun sizeOf(key: String, bitmap: Bitmap): Int {
                    return bitmap.byteCount / 1024
                }
            }
        }
    }

    private val items = mutableListOf<WallpaperItem>()
    private var activeId: String = ""

    fun submitList(newItems: List<WallpaperItem>, currentActiveId: String) {
        items.clear()
        items.addAll(newItems)
        activeId = currentActiveId
        notifyDataSetChanged()
    }

    fun setActiveId(newActiveId: String) {
        val oldId = activeId
        activeId = newActiveId
        val oldPos = items.indexOfFirst { it.id == oldId }
        val newPos = items.indexOfFirst { it.id == newActiveId }
        if (oldPos != -1) notifyItemChanged(oldPos)
        if (newPos != -1) notifyItemChanged(newPos)
    }

    override fun getItemCount(): Int = items.size + 1 // +1 for the "Add" card

    override fun getItemViewType(position: Int): Int {
        return if (position < items.size) VIEW_TYPE_ITEM else VIEW_TYPE_ADD
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == VIEW_TYPE_ITEM) {
            val view = inflater.inflate(R.layout.item_wallpaper, parent, false)
            WallpaperViewHolder(view)
        } else {
            val view = inflater.inflate(R.layout.item_add_wallpaper, parent, false)
            AddViewHolder(view)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        if (holder is WallpaperViewHolder) {
            val item = items[position]
            val isActive = item.id == activeId
            holder.bind(item, isActive)
        } else if (holder is AddViewHolder) {
            holder.bind()
        }
    }

    inner class WallpaperViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val cardWallpaper: MaterialCardView = itemView.findViewById(R.id.cardWallpaper)
        private val ivThumbnail: ImageView = itemView.findViewById(R.id.ivThumbnail)
        private val tvTitle: TextView = itemView.findViewById(R.id.tvTitle)
        private val tvActiveBadge: TextView = itemView.findViewById(R.id.tvActiveBadge)
        private val btnDelete: ImageButton = itemView.findViewById(R.id.btnDelete)

        private var loadJob: Job? = null

        fun bind(item: WallpaperItem, isActive: Boolean) {
            tvTitle.text = item.title

            if (isActive) {
                cardWallpaper.strokeColor = Color.parseColor("#D4AF37")
                cardWallpaper.strokeWidth = dpToPx(3f)
                tvActiveBadge.visibility = View.VISIBLE
            } else {
                cardWallpaper.strokeColor = Color.parseColor("#3D3254")
                cardWallpaper.strokeWidth = dpToPx(1.5f)
                tvActiveBadge.visibility = View.GONE
            }

            if (!item.isPreset) {
                btnDelete.visibility = View.VISIBLE
                btnDelete.setOnClickListener {
                    onDeleteWallpaper(item)
                }
            } else {
                btnDelete.visibility = View.GONE
            }

            cardWallpaper.setOnClickListener {
                onSelectWallpaper(item)
            }

            // Load thumbnail asynchronously
            loadJob?.cancel()
            ivThumbnail.setImageDrawable(null)
            loadJob = scope.launch {
                val bitmap = getOrGenerateThumbnail(item)
                if (bitmap != null) {
                    ivThumbnail.setImageBitmap(bitmap)
                } else {
                    // Fallback to built-in drawable if available
                    ivThumbnail.setImageResource(R.drawable.wallpaper_thumb)
                }
            }
        }
    }

    inner class AddViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val cardAddVideo: MaterialCardView = itemView.findViewById(R.id.cardAddVideo)

        fun bind() {
            cardAddVideo.setOnClickListener {
                onAddVideoClicked()
            }
        }
    }

    private suspend fun getOrGenerateThumbnail(item: WallpaperItem): Bitmap? = withContext(Dispatchers.IO) {
        // 1. Check in-memory cache
        val cacheKey = item.id
        memoryCache.get(cacheKey)?.let { return@withContext it }

        // 2. Check disk thumbnail for custom wallpaper
        if (item.thumbPath.isNotEmpty()) {
            val thumbFile = File(item.thumbPath)
            if (thumbFile.exists()) {
                val bmp = BitmapFactory.decodeFile(thumbFile.absolutePath)
                if (bmp != null) {
                    memoryCache.put(cacheKey, bmp)
                    return@withContext bmp
                }
            }
        }

        // 3. Built-in presets
        if (item.isPreset) {
            if (item.rawResName == "lock_wake_video") {
                val bmp = BitmapFactory.decodeResource(context.resources, R.drawable.wallpaper_thumb)
                if (bmp != null) {
                    memoryCache.put(cacheKey, bmp)
                    return@withContext bmp
                }
            }

            // Extract from raw resource
            try {
                val retriever = MediaMetadataRetriever()
                val afd = context.resources.openRawResourceFd(item.rawResId)
                retriever.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                afd.close()
                val bmp = retriever.getFrameAtTime(1_000_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    ?: retriever.frameAtTime
                retriever.release()
                if (bmp != null) {
                    memoryCache.put(cacheKey, bmp)
                    return@withContext bmp
                }
            } catch (_: Exception) {}
        } else if (item.filePath.isNotEmpty()) {
            // Generate and save thumbnail on the fly if missing
            try {
                val retriever = MediaMetadataRetriever()
                retriever.setDataSource(item.filePath)
                val bmp = retriever.getFrameAtTime(1_000_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    ?: retriever.frameAtTime
                retriever.release()
                if (bmp != null) {
                    memoryCache.put(cacheKey, bmp)
                    return@withContext bmp
                }
            } catch (_: Exception) {}
        }

        return@withContext null
    }

    private fun dpToPx(dp: Float): Int {
        return (dp * context.resources.displayMetrics.density + 0.5f).toInt()
    }
}
