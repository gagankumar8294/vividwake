package com.lockscreen.videowake

import android.app.Activity
import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Surface
import android.view.TextureView
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

class MainActivity : AppCompatActivity(), TextureView.SurfaceTextureListener {

    private lateinit var tvDeviceBrand: TextView
    private lateinit var tvPrimaryInstruction: TextView
    private lateinit var previewTextureView: TextureView
    private lateinit var btnSetWallpaper: MaterialButton
    private lateinit var btnDirectGalleryHandOff: MaterialButton
    private lateinit var btnClearLockOverride: MaterialButton
    private lateinit var btnExportVideo: MaterialButton
    private lateinit var rgPlaybackMode: RadioGroup
    private lateinit var rbFreezeLastFrame: RadioButton
    private lateinit var rbLoop: RadioButton
    private lateinit var switchMute: SwitchMaterial

    private lateinit var prefs: SharedPreferences
    private var previewPlayer: MediaPlayer? = null
    private var previewSurface: Surface? = null

    private var isSamsung = false
    private var isXiaomi = false
    private var isColorOS = false

    private val saveVideoLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val uri: Uri? = result.data?.data
            if (uri != null) {
                saveVideoToUri(uri)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences(VideoWallpaperService.PREFS_NAME, Context.MODE_PRIVATE)

        tvDeviceBrand = findViewById(R.id.tvDeviceBrand)
        tvPrimaryInstruction = findViewById(R.id.tvPrimaryInstruction)
        previewTextureView = findViewById(R.id.previewTextureView)
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
            when {
                isSamsung -> {
                    val uri = getCachedVideoUri()
                    if (uri != null) launchSamsungVideoWallpaper(uri) else openLiveWallpaperChooser()
                }
                isXiaomi -> {
                    val uri = getCachedVideoUri()
                    if (uri != null) launchXiaomiVideoWallpaper(uri) else openLiveWallpaperChooser()
                }
                else -> {
                    clearLockScreenOverrideSilently()
                    openLiveWallpaperChooser()
                }
            }
        }

        btnDirectGalleryHandOff.setOnClickListener {
            val uri = getCachedVideoUri()
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

    private fun getCachedVideoUri(): Uri? {
        return try {
            val videoDir = File(cacheDir, "videos").apply { mkdirs() }
            val videoFile = File(videoDir, "HarryPotter_LockScreen_Wake.mp4")
            if (!videoFile.exists() || videoFile.length() == 0L) {
                resources.openRawResource(R.raw.lock_wake_video).use { input ->
                    FileOutputStream(videoFile).use { output ->
                        input.copyTo(output)
                    }
                }
            }
            FileProvider.getUriForFile(this, "${packageName}.fileprovider", videoFile)
        } catch (e: Exception) {
            Log.e("MainActivity", "Error creating cached video file", e)
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
            val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "video/mp4"
                putExtra(Intent.EXTRA_TITLE, "HarryPotter_LockScreen_Wake.mp4")
            }
            saveVideoLauncher.launch(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Could not open folder picker: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun saveVideoToUri(uri: Uri) {
        try {
            contentResolver.openOutputStream(uri)?.use { output ->
                resources.openRawResource(R.raw.lock_wake_video).use { input ->
                    input.copyTo(output)
                }
            }
            Toast.makeText(
                this,
                "Video saved to chosen folder! Now in Themes -> My live wallpapers, tap '+' and select it.",
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
        } catch (e: Exception) {
            // Ignore if OEM blocks clearing
        }
    }

    private fun openLiveWallpaperChooser() {
        val component = ComponentName(this, VideoWallpaperService::class.java)

        // Attempt 1: Direct live wallpaper preview intent
        try {
            val intent = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).apply {
                putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, component)
                putExtra("SET_LOCKSCREEN_WALLPAPER", true)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
            return
        } catch (e: Exception) {
            // Log and fallback
        }

        // Attempt 2: Live wallpaper chooser picker
        try {
            val fallbackIntent = Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(fallbackIntent)
            return
        } catch (e: Exception) {
            // Log and fallback
        }

        // Attempt 3: General system wallpaper picker
        try {
            val fallbackIntent = Intent(Intent.ACTION_SET_WALLPAPER).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(fallbackIntent)
            return
        } catch (e: Exception) {
            // Log and fallback
        }

        // Attempt 4: System display settings
        try {
            val settingsIntent = Intent(android.provider.Settings.ACTION_DISPLAY_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(settingsIntent)
        } catch (e: Exception) {
            Toast.makeText(this, "Please open Settings -> Wallpaper -> Live Wallpapers", Toast.LENGTH_LONG).show()
        }
    }

    override fun onSurfaceTextureAvailable(surfaceTexture: SurfaceTexture, width: Int, height: Int) {
        previewSurface = Surface(surfaceTexture)
        adjustPreviewAspect(width, height)
        startPreviewPlayback()
    }

    override fun onSurfaceTextureSizeChanged(surfaceTexture: SurfaceTexture, width: Int, height: Int) {
        adjustPreviewAspect(width, height)
    }

    override fun onSurfaceTextureDestroyed(surfaceTexture: SurfaceTexture): Boolean {
        stopPreviewPlayback()
        previewSurface?.release()
        previewSurface = null
        return true
    }

    override fun onSurfaceTextureUpdated(surfaceTexture: SurfaceTexture) {}

    private fun adjustPreviewAspect(viewW: Int, viewH: Int) {
        if (viewW == 0 || viewH == 0) return
        val videoW = 720f
        val videoH = 1280f
        val viewRatio = viewW.toFloat() / viewH
        val videoRatio = videoW / videoH

        val matrix = Matrix()
        if (viewRatio < videoRatio) {
            val scaleX = (viewH * videoRatio) / viewW
            matrix.setScale(scaleX, 1.0f, viewW / 2f, viewH / 2f)
        } else {
            val scaleY = (viewW / videoRatio) / viewH
            matrix.setScale(1.0f, scaleY, viewW / 2f, viewH / 2f)
        }
        previewTextureView.setTransform(matrix)
    }

    private fun startPreviewPlayback() {
        if (previewSurface == null) return
        try {
            stopPreviewPlayback()
            previewPlayer = MediaPlayer().apply {
                setSurface(this@MainActivity.previewSurface)
                val afd = resources.openRawResourceFd(R.raw.lock_wake_video)
                setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                afd.close()
                isLooping = true
                val isMute = prefs.getBoolean(VideoWallpaperService.KEY_MUTE, true)
                val vol = if (isMute) 0f else 1f
                setVolume(vol, vol)
                setOnPreparedListener { mp ->
                    mp.start()
                }
                prepareAsync()
            }
        } catch (e: Exception) {
            e.printStackTrace()
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
            startPreviewPlayback()
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
