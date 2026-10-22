package com.lockscreen.videowake

import android.app.Activity
import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Log
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.Locale

class MainActivity : AppCompatActivity(), TextureView.SurfaceTextureListener {

    private lateinit var tvDeviceBrand: TextView
    private lateinit var tvPrimaryInstruction: TextView
    private lateinit var tvActiveWallpaperSubtitle: TextView
    private lateinit var previewTextureView: TextureView
    private lateinit var rvWallpapers: RecyclerView
    private lateinit var layoutImporting: LinearLayout
    private lateinit var tvImportingStatus: TextView
    private lateinit var btnSetWallpaper: MaterialButton
    private lateinit var btnDirectGalleryHandOff: MaterialButton
    private lateinit var btnClearLockOverride: MaterialButton
    private lateinit var btnExportVideo: MaterialButton
    private lateinit var rgPlaybackMode: RadioGroup
    private lateinit var rbFreezeLastFrame: RadioButton
    private lateinit var rbLoop: RadioButton
    private lateinit var switchMute: SwitchMaterial

    private lateinit var prefs: SharedPreferences
    private lateinit var repository: WallpaperRepository
    private lateinit var adapter: WallpaperAdapter

    private var previewPlayer: MediaPlayer? = null
    private var previewSurface: Surface? = null
    private var previewWidth = 0
    private var previewHeight = 0

    private var isSamsung = false
    private var isXiaomi = false
    private var isColorOS = false

    // SAF Save Video to chosen folder
    private val saveVideoLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val uri: Uri? = result.data?.data
            if (uri != null) {
                saveActiveVideoToUri(uri)
            }
        }
    }

    // Video Picker to import any video
    private val pickVideoLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            importVideoFromUri(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences(VideoWallpaperService.PREFS_NAME, Context.MODE_PRIVATE)
        repository = WallpaperRepository(this)

        tvDeviceBrand = findViewById(R.id.tvDeviceBrand)
        tvPrimaryInstruction = findViewById(R.id.tvPrimaryInstruction)
        tvActiveWallpaperSubtitle = findViewById(R.id.tvActiveWallpaperSubtitle)
        previewTextureView = findViewById(R.id.previewTextureView)
        rvWallpapers = findViewById(R.id.rvWallpapers)
        layoutImporting = findViewById(R.id.layoutImporting)
        tvImportingStatus = findViewById(R.id.tvImportingStatus)
        btnSetWallpaper = findViewById(R.id.btnSetWallpaper)
        btnDirectGalleryHandOff = findViewById(R.id.btnDirectGalleryHandOff)
        btnClearLockOverride = findViewById(R.id.btnClearLockOverride)
        btnExportVideo = findViewById(R.id.btnExportVideo)
        rgPlaybackMode = findViewById(R.id.rgPlaybackMode)
        rbFreezeLastFrame = findViewById(R.id.rbFreezeLastFrame)
        rbLoop = findViewById(R.id.rbLoop)
        switchMute = findViewById(R.id.switchMute)

        previewTextureView.surfaceTextureListener = this

        detectDeviceBrand()
        setupGalleryRecyclerView()
        loadSavedSettings()
        setupListeners()
    }

    private fun detectDeviceBrand() {
        val manufacturer = Build.MANUFACTURER.lowercase(Locale.ROOT)
        val brand = Build.BRAND.lowercase(Locale.ROOT)

        isSamsung = manufacturer.contains("samsung") || brand.contains("samsung")
        isXiaomi = manufacturer.contains("xiaomi") || manufacturer.contains("redmi") ||
                manufacturer.contains("poco") || brand.contains("xiaomi") ||
                brand.contains("redmi") || brand.contains("poco")
        isColorOS = manufacturer.contains("oneplus") || manufacturer.contains("oppo") ||
                manufacturer.contains("realme")

        when {
            isSamsung -> {
                tvDeviceBrand.text = "📱 Detected: Samsung Galaxy (One UI)"
                btnSetWallpaper.text = "📱 Apply to Samsung Lock Screen"
                tvPrimaryInstruction.text = "Opens Samsung One UI lock screen video setup directly"
            }
            isXiaomi -> {
                tvDeviceBrand.text = "📱 Detected: Xiaomi / Redmi / Poco (HyperOS/MIUI)"
                btnSetWallpaper.text = "📱 Open in Xiaomi Video Wallpaper"
                tvPrimaryInstruction.text = "Opens video player: tap '...' ➔ 'Set video wallpaper' ➔ 'Lock screen'"
            }
            isColorOS -> {
                val brandName = Build.MANUFACTURER.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }
                tvDeviceBrand.text = "📱 Detected: $brandName (OxygenOS / ColorOS)"
                btnSetWallpaper.text = "✨ Apply to Lock Screen"
                tvPrimaryInstruction.text = "Select 'Home and Lock screen'. Note: turn off 'Lock Screen Magazine' if it reverts."
            }
            else -> {
                val brandName = Build.MANUFACTURER.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }
                tvDeviceBrand.text = "📱 Detected: $brandName (Native AOSP)"
                btnSetWallpaper.text = "✨ Apply to Lock Screen"
                tvPrimaryInstruction.text = "Tap 'Set wallpaper' ➔ choose 'Home screen and lock screen'"
            }
        }
    }

    private fun setupGalleryRecyclerView() {
        adapter = WallpaperAdapter(
            context = this,
            scope = lifecycleScope,
            onSelectWallpaper = { item ->
                selectWallpaper(item)
            },
            onDeleteWallpaper = { item ->
                confirmDeleteWallpaper(item)
            },
            onAddVideoClicked = {
                openVideoPicker()
            }
        )

        rvWallpapers.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        rvWallpapers.adapter = adapter

        refreshGallery()
    }

    private fun refreshGallery() {
        val allWallpapers = repository.getAllWallpapers()
        val activeItem = repository.getActiveWallpaper()
        adapter.submitList(allWallpapers, activeItem.id)
        updateSubtitle(activeItem)
    }

    private fun updateSubtitle(item: WallpaperItem) {
        val modeText = if (prefs.getBoolean(VideoWallpaperService.KEY_LOOP, false)) "Continuous Loop" else "Plays on wake, freezes on final frame"
        tvActiveWallpaperSubtitle.text = "Active: ${item.title} • $modeText"
    }

    private fun selectWallpaper(item: WallpaperItem) {
        repository.setActiveWallpaper(item)
        adapter.setActiveId(item.id)
        updateSubtitle(item)
        playActiveVideoInPreview(item)
        Toast.makeText(this, "Active lock screen set to: ${item.title}", Toast.LENGTH_SHORT).show()
    }

    private fun confirmDeleteWallpaper(item: WallpaperItem) {
        AlertDialog.Builder(this)
            .setTitle("Delete Wallpaper")
            .setMessage("Remove '${item.title}' from your gallery?")
            .setPositiveButton("Delete") { _, _ ->
                repository.deleteWallpaper(item)
                refreshGallery()
                val current = repository.getActiveWallpaper()
                playActiveVideoInPreview(current)
                Toast.makeText(this, "Wallpaper removed", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun openVideoPicker() {
        try {
            pickVideoLauncher.launch("video/*")
        } catch (e: Exception) {
            Toast.makeText(this, "Could not open media picker: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun importVideoFromUri(uri: Uri) {
        layoutImporting.visibility = View.VISIBLE
        tvImportingStatus.text = "Importing and optimizing video..."

        lifecycleScope.launch {
            val fileName = queryDisplayName(uri)
            val result = repository.importVideo(uri, fileName)

            layoutImporting.visibility = View.GONE
            result.onSuccess { newItem ->
                refreshGallery()
                selectWallpaper(newItem)
                Toast.makeText(this@MainActivity, "✨ Added '${newItem.title}' to gallery!", Toast.LENGTH_LONG).show()
            }.onFailure { err ->
                Toast.makeText(this@MainActivity, "Failed to import video: ${err.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun queryDisplayName(uri: Uri): String? {
        var name: String? = null
        try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIdx >= 0) name = cursor.getString(nameIdx)
                }
            }
        } catch (_: Exception) {}
        return name
    }

    private fun loadSavedSettings() {
        val isLoop = prefs.getBoolean(VideoWallpaperService.KEY_LOOP, false)
        if (isLoop) {
            rbLoop.isChecked = true
        } else {
            rbFreezeLastFrame.isChecked = true
        }

        val isMute = prefs.getBoolean(VideoWallpaperService.KEY_MUTE, true)
        switchMute.isChecked = isMute
    }

    private fun setupListeners() {
        btnSetWallpaper.setOnClickListener {
            val activeItem = repository.getActiveWallpaper()
            when {
                isSamsung -> {
                    val uri = getActiveVideoUri(activeItem)
                    if (uri != null) launchSamsungVideoWallpaper(uri) else openLiveWallpaperChooser()
                }
                isXiaomi -> {
                    val uri = getActiveVideoUri(activeItem)
                    if (uri != null) launchXiaomiVideoWallpaper(uri) else openLiveWallpaperChooser()
                }
                else -> {
                    clearLockScreenOverrideSilently()
                    openLiveWallpaperChooser()
                }
            }
        }

        btnDirectGalleryHandOff.setOnClickListener {
            val activeItem = repository.getActiveWallpaper()
            val uri = getActiveVideoUri(activeItem)
            if (uri != null) {
                launchGalleryVideo(uri)
            } else {
                Toast.makeText(this, "Could not prepare video file", Toast.LENGTH_SHORT).show()
            }
        }

        btnClearLockOverride.setOnClickListener {
            clearLockScreenOverrideSilently()
            Toast.makeText(this, "Cleared lock photo override! Select 'Home screen and lock screen' in preview", Toast.LENGTH_LONG).show()
            openLiveWallpaperChooser()
        }

        btnExportVideo.setOnClickListener {
            promptSaveVideoLocation()
        }

        rgPlaybackMode.setOnCheckedChangeListener { _, checkedId ->
            val loop = (checkedId == R.id.rbLoop)
            prefs.edit().putBoolean(VideoWallpaperService.KEY_LOOP, loop).apply()
            updateSubtitle(repository.getActiveWallpaper())
            Toast.makeText(
                this,
                if (loop) "Mode: Continuous Loop" else "Mode: Wake Animation (Freeze on End Frame)",
                Toast.LENGTH_SHORT
            ).show()
        }

        switchMute.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(VideoWallpaperService.KEY_MUTE, isChecked).apply()
            previewPlayer?.let {
                val vol = if (isChecked) 0f else 1f
                it.setVolume(vol, vol)
            }
        }
    }

    private fun getActiveVideoUri(item: WallpaperItem): Uri? {
        return try {
            val file = repository.getFileForSharing(item) ?: return null
            FileProvider.getUriForFile(this, "${packageName}.fileprovider", file)
        } catch (e: Exception) {
            Log.e("MainActivity", "Error getting Uri for active video", e)
            null
        }
    }

    private fun launchSamsungVideoWallpaper(uri: Uri) {
        try {
            val intent = Intent(Intent.ACTION_ATTACH_DATA).apply {
                setDataAndType(uri, "video/mp4")
                putExtra("mimeType", "video/mp4")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(Intent.createChooser(intent, "Set as Lock Screen Wallpaper"))
        } catch (e: Exception) {
            launchGalleryVideo(uri)
        }
    }

    private fun launchXiaomiVideoWallpaper(uri: Uri) {
        try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "video/mp4")
                setPackage("com.miui.gallery")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
            Toast.makeText(this, "Tap '...' at bottom ➔ 'Set video wallpaper' ➔ 'Lock screen'", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            launchGalleryVideo(uri)
        }
    }

    private fun launchGalleryVideo(uri: Uri) {
        try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "video/mp4")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(Intent.createChooser(intent, "Open Video to Set as Wallpaper"))
            Toast.makeText(this, "In video options, tap 'Set as video wallpaper' ➔ 'Lock screen'", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Could not open video player: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun promptSaveVideoLocation() {
        try {
            val activeItem = repository.getActiveWallpaper()
            val fileName = "${activeItem.title.replace(Regex("[^a-zA-Z0-9_]"), "_")}.mp4"
            val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "video/mp4"
                putExtra(Intent.EXTRA_TITLE, fileName)
            }
            saveVideoLauncher.launch(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Could not open folder picker: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun saveActiveVideoToUri(destUri: Uri) {
        val activeItem = repository.getActiveWallpaper()
        try {
            contentResolver.openOutputStream(destUri)?.use { output ->
                if (activeItem.isPreset) {
                    resources.openRawResource(activeItem.rawResId).use { input ->
                        input.copyTo(output)
                    }
                } else {
                    val f = File(activeItem.filePath)
                    if (f.exists()) {
                        FileInputStream(f).use { input ->
                            input.copyTo(output)
                        }
                    }
                }
                output.flush()
            }
            Toast.makeText(
                this,
                "Video saved to chosen folder!",
                Toast.LENGTH_LONG
            ).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Save failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun clearLockScreenOverrideSilently() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                val wm = WallpaperManager.getInstance(this)
                wm.clear(WallpaperManager.FLAG_LOCK)
            }
        } catch (_: Exception) {}
    }

    private fun openLiveWallpaperChooser() {
        val component = ComponentName(this, VideoWallpaperService::class.java)

        try {
            val intent = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).apply {
                putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, component)
                putExtra("SET_LOCKSCREEN_WALLPAPER", true)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
            return
        } catch (_: Exception) {}

        try {
            val fallbackIntent = Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(fallbackIntent)
            return
        } catch (_: Exception) {}

        try {
            val fallbackIntent = Intent(Intent.ACTION_SET_WALLPAPER).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(fallbackIntent)
            return
        } catch (_: Exception) {}

        try {
            val settingsIntent = Intent(android.provider.Settings.ACTION_DISPLAY_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(settingsIntent)
        } catch (_: Exception) {
            Toast.makeText(this, "Please open Settings -> Wallpaper -> Live Wallpapers", Toast.LENGTH_LONG).show()
        }
    }

    override fun onSurfaceTextureAvailable(surfaceTexture: SurfaceTexture, width: Int, height: Int) {
        previewSurface = Surface(surfaceTexture)
        previewWidth = width
        previewHeight = height
        playActiveVideoInPreview(repository.getActiveWallpaper())
    }

    override fun onSurfaceTextureSizeChanged(surfaceTexture: SurfaceTexture, width: Int, height: Int) {
        previewWidth = width
        previewHeight = height
        adjustPreviewAspect(previewPlayer?.videoWidth ?: 720, previewPlayer?.videoHeight ?: 1280, 0)
    }

    override fun onSurfaceTextureDestroyed(surfaceTexture: SurfaceTexture): Boolean {
        stopPreviewPlayback()
        previewSurface?.release()
        previewSurface = null
        return true
    }

    override fun onSurfaceTextureUpdated(surfaceTexture: SurfaceTexture) {}

    private fun adjustPreviewAspect(videoW: Int, videoH: Int, rotation: Int) {
        if (previewWidth == 0 || previewHeight == 0 || videoW == 0 || videoH == 0) return

        val effW = if (rotation == 90 || rotation == 270) videoH else videoW
        val effH = if (rotation == 90 || rotation == 270) videoW else videoH

        val viewRatio = previewWidth.toFloat() / previewHeight.toFloat()
        val videoRatio = effW.toFloat() / effH.toFloat()

        val matrix = Matrix()
        if (viewRatio < videoRatio) {
            val scaleX = (previewHeight * videoRatio) / previewWidth
            matrix.setScale(scaleX, 1.0f, previewWidth / 2f, previewHeight / 2f)
        } else {
            val scaleY = (previewWidth / videoRatio) / previewHeight
            matrix.setScale(1.0f, scaleY, previewWidth / 2f, previewHeight / 2f)
        }
        previewTextureView.setTransform(matrix)
    }

    private fun playActiveVideoInPreview(item: WallpaperItem) {
        val surface = previewSurface ?: return
        try {
            stopPreviewPlayback()
            var rotation = 0

            previewPlayer = MediaPlayer().apply {
                setSurface(surface)

                if (item.isPreset) {
                    val afd = resources.openRawResourceFd(item.rawResId)
                    setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                    afd.close()
                } else {
                    val file = File(item.filePath)
                    if (file.exists() && file.length() > 0) {
                        setDataSource(file.absolutePath)
                        val retriever = MediaMetadataRetriever()
                        try {
                            retriever.setDataSource(file.absolutePath)
                            rotation = retriever.extractMetadata(
                                MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION
                            )?.toIntOrNull() ?: 0
                        } catch (_: Exception) {} finally {
                            try { retriever.release() } catch (_: Exception) {}
                        }
                    } else {
                        // Fallback to preset
                        val afd = resources.openRawResourceFd(R.raw.lock_wake_video)
                        setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                        afd.close()
                    }
                }

                isLooping = true
                val isMute = prefs.getBoolean(VideoWallpaperService.KEY_MUTE, true)
                val vol = if (isMute) 0f else 1f
                setVolume(vol, vol)

                setOnVideoSizeChangedListener { _, w, h ->
                    if (w > 0 && h > 0) {
                        adjustPreviewAspect(w, h, rotation)
                    }
                }

                setOnPreparedListener { mp ->
                    adjustPreviewAspect(mp.videoWidth, mp.videoHeight, rotation)
                    mp.start()
                }

                setOnErrorListener { _, what, extra ->
                    Log.e("MainActivity", "Preview error $what, $extra")
                    true
                }

                prepareAsync()
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Error playing preview", e)
        }
    }

    private fun stopPreviewPlayback() {
        try {
            previewPlayer?.let {
                if (it.isPlaying) it.stop()
                it.release()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            previewPlayer = null
        }
    }

    override fun onResume() {
        super.onResume()
        if (previewSurface != null && (previewPlayer == null || !previewPlayer!!.isPlaying)) {
            playActiveVideoInPreview(repository.getActiveWallpaper())
        }
    }

    override fun onPause() {
        stopPreviewPlayback()
        super.onPause()
    }

    override fun onDestroy() {
        stopPreviewPlayback()
        super.onDestroy()
    }
}
