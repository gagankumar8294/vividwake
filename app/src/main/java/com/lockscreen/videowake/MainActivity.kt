package com.lockscreen.videowake

import android.app.Activity
import android.app.WallpaperManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
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

    private lateinit var tvAppTitle: TextView
    private lateinit var btnNotificationCenter: FrameLayout
    private lateinit var tvNotificationBadge: TextView
    private lateinit var btnUserAuth: LinearLayout
    private lateinit var ivUserAuthIcon: ImageView
    private lateinit var tvUserAuthText: TextView

    private lateinit var tvDeviceBrand: TextView
    private lateinit var tvPrimaryInstruction: TextView
    private lateinit var tvActiveWallpaperSubtitle: TextView
    private lateinit var previewTextureView: TextureView

    // Tabs & Containers
    private lateinit var btnTabMyWallpapers: MaterialButton
    private lateinit var btnTabExploreStore: MaterialButton
    private lateinit var layoutMyWallpapers: LinearLayout
    private lateinit var layoutExploreStore: LinearLayout

    // My Gallery
    private lateinit var rvWallpapers: RecyclerView
    private lateinit var layoutImporting: LinearLayout
    private lateinit var tvImportingStatus: TextView

    // Store Section
    private lateinit var rvStoreWallpapers: RecyclerView
    private lateinit var layoutStoreSkeleton: View
    private lateinit var btnRefreshStore: MaterialButton
    private lateinit var tvStoreCatalogHeader: TextView

    // Action Buttons
    private lateinit var btnSetWallpaper: MaterialButton
    private lateinit var btnDirectGalleryHandOff: MaterialButton
    private lateinit var btnClearLockOverride: MaterialButton
    private lateinit var btnExportVideo: MaterialButton
    private lateinit var rgPlaybackMode: RadioGroup
    private lateinit var rbFreezeLastFrame: RadioButton
    private lateinit var rbLoopLockOnly: RadioButton
    private lateinit var rbLoopAlways: RadioButton
    private lateinit var switchMute: SwitchMaterial

    private lateinit var prefs: SharedPreferences
    private lateinit var repository: WallpaperRepository
    private lateinit var adapter: WallpaperAdapter
    private lateinit var storeAdapter: StoreWallpaperAdapter
    private lateinit var catalogRepo: CloudCatalogRepository
    private lateinit var analytics: AnalyticsManager
    private lateinit var authManager: AuthManager
    private lateinit var inAppNotificationManager: InAppNotificationManager

    private var previewPlayer: MediaPlayer? = null
    private var previewSurface: Surface? = null
    private var previewWidth = 0
    private var previewHeight = 0

    private var isSamsung = false
    private var isXiaomi = false
    private var isColorOS = false
    private var isAdminMode = false
    private var titleClickCount = 0
    private var lastTitleClickTime = 0L

    private var isWallpaperSelectedForApply = false
    private var selectedWallpaperItem: WallpaperItem? = null

    private val notifReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            updateNotificationBadge()
        }
    }

    private val googleSignInLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            lifecycleScope.launch {
                val res = authManager.handleSignInResult(result.data)
                if (res.isSuccess) {
                    val user = res.getOrNull()
                    if (user != null) {
                        analytics.linkUserToDevice(user.uid, user.email ?: "")
                    }
                    val name = user?.displayName ?: "User"
                    Toast.makeText(this@MainActivity, "Welcome, $name!", Toast.LENGTH_SHORT).show()
                    updateUserAuthUI()
                } else {
                    val err = res.exceptionOrNull()?.message ?: "Unknown error"
                    Toast.makeText(this@MainActivity, "Sign-in note: $err", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

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
        catalogRepo = CloudCatalogRepository(this, repository.downloadManager)
        analytics = AnalyticsManager.getInstance(this)

        tvAppTitle = findViewById(R.id.tvAppTitle)
        btnNotificationCenter = findViewById(R.id.btnNotificationCenter)
        tvNotificationBadge = findViewById(R.id.tvNotificationBadge)
        btnUserAuth = findViewById(R.id.btnUserAuth)
        ivUserAuthIcon = findViewById(R.id.ivUserAuthIcon)
        tvUserAuthText = findViewById(R.id.tvUserAuthText)

        authManager = AuthManager(this)
        inAppNotificationManager = InAppNotificationManager(this)
        tvDeviceBrand = findViewById(R.id.tvDeviceBrand)
        tvPrimaryInstruction = findViewById(R.id.tvPrimaryInstruction)
        tvActiveWallpaperSubtitle = findViewById(R.id.tvActiveWallpaperSubtitle)
        previewTextureView = findViewById(R.id.previewTextureView)

        btnTabMyWallpapers = findViewById(R.id.btnTabMyWallpapers)
        btnTabExploreStore = findViewById(R.id.btnTabExploreStore)
        layoutMyWallpapers = findViewById(R.id.layoutMyWallpapers)
        layoutExploreStore = findViewById(R.id.layoutExploreStore)

        rvWallpapers = findViewById(R.id.rvWallpapers)
        layoutImporting = findViewById(R.id.layoutImporting)
        tvImportingStatus = findViewById(R.id.tvImportingStatus)

        rvStoreWallpapers = findViewById(R.id.rvStoreWallpapers)
        layoutStoreSkeleton = findViewById(R.id.layoutStoreSkeleton)
        btnRefreshStore = findViewById(R.id.btnRefreshStore)
        tvStoreCatalogHeader = findViewById(R.id.tvStoreCatalogHeader)

        btnSetWallpaper = findViewById(R.id.btnSetWallpaper)
        btnDirectGalleryHandOff = findViewById(R.id.btnDirectGalleryHandOff)
        btnClearLockOverride = findViewById(R.id.btnClearLockOverride)
        btnExportVideo = findViewById(R.id.btnExportVideo)
        rgPlaybackMode = findViewById(R.id.rgPlaybackMode)
        rbFreezeLastFrame = findViewById(R.id.rbFreezeLastFrame)
        rbLoopLockOnly = findViewById(R.id.rbLoopLockOnly)
        rbLoopAlways = findViewById(R.id.rbLoopAlways)
        switchMute = findViewById(R.id.switchMute)

        previewTextureView.surfaceTextureListener = this

        detectDeviceBrand()
        setupGalleryRecyclerView()
        setupStoreRecyclerView()
        loadSavedSettings()
        setupTabSwitching()
        setupListeners()
        setupAdminSecretGesture()

        // Check if opened from notification
        if (intent?.getStringExtra("open_tab") == "store") {
            switchToStoreTab()
        }
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
                tvDeviceBrand.text = "Detected: Samsung Galaxy (One UI)"
                tvPrimaryInstruction.text = "Opens Samsung One UI lock screen video setup directly"
            }
            isXiaomi -> {
                tvDeviceBrand.text = "Detected: Xiaomi / Redmi / Poco (HyperOS/MIUI)"
                tvPrimaryInstruction.text = "Opens video player: tap '...' ➔ 'Set video wallpaper' ➔ 'Lock screen'"
            }
            isColorOS -> {
                val brandName = Build.MANUFACTURER.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }
                tvDeviceBrand.text = "Detected: $brandName (OxygenOS / ColorOS)"
                tvPrimaryInstruction.text = "Select 'Home and Lock screen'. Note: turn off 'Lock Screen Magazine' if it reverts."
            }
            else -> {
                val brandName = Build.MANUFACTURER.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }
                tvDeviceBrand.text = "Detected: $brandName (Native AOSP)"
                tvPrimaryInstruction.text = "Tap 'Set wallpaper' ➔ choose 'Home screen and lock screen'"
            }
        }
        setApplyButtonState(isSelected = false, item = null)
    }

    private fun setApplyButtonState(isSelected: Boolean, item: WallpaperItem? = null) {
        isWallpaperSelectedForApply = isSelected
        selectedWallpaperItem = if (isSelected) item else null

        if (!isSelected || item == null) {
            btnSetWallpaper.text = "Select wallpaper to apply lock screen"
            btnSetWallpaper.alpha = 0.40f
            btnSetWallpaper.isEnabled = false
            btnSetWallpaper.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#261E38"))
            btnSetWallpaper.setTextColor(Color.parseColor("#8E879E"))
            btnSetWallpaper.iconTint = ColorStateList.valueOf(Color.parseColor("#8E879E"))
        } else {
            val title = item.title.ifBlank { "Wallpaper" }
            val actionText = when {
                isSamsung -> "Apply '$title' to Samsung Lock Screen"
                isXiaomi -> "Apply '$title' in Xiaomi Video Wallpaper"
                else -> "Apply '$title' to Lock Screen"
            }
            btnSetWallpaper.text = actionText
            btnSetWallpaper.alpha = 1.0f
            btnSetWallpaper.isEnabled = true
            btnSetWallpaper.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#D4AF37"))
            btnSetWallpaper.setTextColor(Color.parseColor("#0E0B16"))
            btnSetWallpaper.iconTint = ColorStateList.valueOf(Color.parseColor("#0E0B16"))
        }
    }

    private fun showGlassToast(message: String, isSuccess: Boolean = true) {
        try {
            val inflater = LayoutInflater.from(this)
            val view = inflater.inflate(R.layout.view_glass_toast, null)
            val ivIcon: ImageView = view.findViewById(R.id.ivToastIcon)
            val tvMsg: TextView = view.findViewById(R.id.tvToastMessage)

            tvMsg.text = message
            ivIcon.setImageResource(if (isSuccess) R.drawable.ic_sparkle else R.drawable.ic_check)
            ivIcon.imageTintList = ColorStateList.valueOf(Color.parseColor("#D4AF37"))

            val toast = Toast(this)
            toast.duration = Toast.LENGTH_LONG
            toast.setGravity(Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL, 0, 200)
            toast.view = view
            toast.show()
        } catch (_: Exception) {
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        }
    }

    private fun setupTabSwitching() {
        btnTabMyWallpapers.setOnClickListener {
            switchToMyWallpapersTab()
        }

        btnTabExploreStore.setOnClickListener {
            switchToStoreTab()
        }

        btnRefreshStore.setOnClickListener {
            loadStoreCatalog()
        }
    }

    private fun switchToMyWallpapersTab() {
        val start = System.currentTimeMillis()
        try {
            layoutMyWallpapers.visibility = View.VISIBLE
            layoutExploreStore.visibility = View.GONE

            // Active Tab (Yellow background -> Black text & Black icon)
            btnTabMyWallpapers.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#D4AF37"))
            btnTabMyWallpapers.setTextColor(Color.parseColor("#0E0B16"))
            btnTabMyWallpapers.iconTint = ColorStateList.valueOf(Color.parseColor("#0E0B16"))

            // Inactive Tab (Transparent/Black background -> Yellow text & Yellow icon)
            btnTabExploreStore.backgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
            btnTabExploreStore.setTextColor(Color.parseColor("#D4AF37"))
            btnTabExploreStore.iconTint = ColorStateList.valueOf(Color.parseColor("#D4AF37"))

            refreshGallery()
            val duration = System.currentTimeMillis() - start
            analytics.logAction("my_wallpapers_tab_clicked", "SUCCESS", duration, mapOf("tab" to "my_wallpapers"))
        } catch (e: Exception) {
            val duration = System.currentTimeMillis() - start
            analytics.logAction("my_wallpapers_tab_clicked", "FAILED", duration, mapOf("tab" to "my_wallpapers"), e)
        }
    }

    private fun switchToStoreTab() {
        val start = System.currentTimeMillis()
        try {
            layoutMyWallpapers.visibility = View.GONE
            layoutExploreStore.visibility = View.VISIBLE

            // Active Tab (Yellow background -> Black text & Black icon)
            btnTabExploreStore.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#D4AF37"))
            btnTabExploreStore.setTextColor(Color.parseColor("#0E0B16"))
            btnTabExploreStore.iconTint = ColorStateList.valueOf(Color.parseColor("#0E0B16"))

            // Inactive Tab (Transparent/Black background -> Yellow text & Yellow icon)
            btnTabMyWallpapers.backgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
            btnTabMyWallpapers.setTextColor(Color.parseColor("#D4AF37"))
            btnTabMyWallpapers.iconTint = ColorStateList.valueOf(Color.parseColor("#D4AF37"))

            // Dim and disable apply button until user selects a wallpaper
            setApplyButtonState(isSelected = false, item = null)

            val duration = System.currentTimeMillis() - start
            analytics.logAction("explore_store_tab_clicked", "SUCCESS", duration, mapOf("tab" to "explore_store"))
            loadStoreCatalog()
        } catch (e: Exception) {
            val duration = System.currentTimeMillis() - start
            analytics.logAction("explore_store_tab_clicked", "FAILED", duration, mapOf("tab" to "explore_store"), e)
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
        adapter.isAdminMode = isAdminMode
        rvWallpapers.adapter = adapter
        refreshGallery()
    }

    private fun setupStoreRecyclerView() {
        storeAdapter = StoreWallpaperAdapter(
            context = this,
            scope = lifecycleScope,
            onPreview = { item ->
                playStreamingVideoInPreview(item)
            },
            onDownload = { item ->
                downloadCloudWallpaper(item)
            },
            onApply = { item ->
                selectWallpaper(item)
            },
            onDeleteLocal = { item ->
                confirmDeleteCloudDownload(item)
            }
        )

        rvStoreWallpapers.layoutManager = LinearLayoutManager(this, LinearLayoutManager.VERTICAL, false)
        rvStoreWallpapers.adapter = storeAdapter
    }

    private fun loadStoreCatalog() {
        val start = System.currentTimeMillis()
        val pulseAnim = android.view.animation.AnimationUtils.loadAnimation(this, R.anim.pulse_skeleton)
        layoutStoreSkeleton.visibility = View.VISIBLE
        layoutStoreSkeleton.startAnimation(pulseAnim)
        rvStoreWallpapers.visibility = View.GONE
        lifecycleScope.launch {
            try {
                val list = catalogRepo.fetchCatalog(isAdminMode)
                layoutStoreSkeleton.clearAnimation()
                layoutStoreSkeleton.visibility = View.GONE
                rvStoreWallpapers.visibility = View.VISIBLE
                storeAdapter.submitList(list)
                if (isAdminMode) {
                    tvStoreCatalogHeader.text = "👑 ADMIN CATALOG (${list.size} wallpapers, drafts visible)"
                } else {
                    tvStoreCatalogHeader.text = "Tap card to stream preview. Download to set as wallpaper."
                }
                val duration = System.currentTimeMillis() - start
                analytics.logAction("store_catalog_fetch", "SUCCESS", duration, mapOf(
                    "count" to list.size,
                    "isAdminMode" to isAdminMode
                ))
            } catch (e: Exception) {
                layoutStoreSkeleton.clearAnimation()
                layoutStoreSkeleton.visibility = View.GONE
                rvStoreWallpapers.visibility = View.VISIBLE
                val duration = System.currentTimeMillis() - start
                analytics.logAction("store_catalog_fetch", "FAILED", duration, mapOf(
                    "isAdminMode" to isAdminMode
                ), e)
            }
        }
    }

    private fun downloadCloudWallpaper(item: WallpaperItem) {
        val start = System.currentTimeMillis()
        analytics.logAction("download_start", "SUCCESS", 0, mapOf(
            "id" to item.id,
            "title" to item.title,
            "sizeDisplay" to item.sizeDisplay
        ))
        storeAdapter.setDownloadingProgress(item.id, 0)

        lifecycleScope.launch {
            val result = repository.downloadManager.downloadVideo(item) { progress ->
                runOnUiThread {
                    storeAdapter.setDownloadingProgress(item.id, progress)
                }
            }

            val duration = System.currentTimeMillis() - start
            result.onSuccess { downloadedFile ->
                val updated = item.copy(
                    filePath = downloadedFile.absolutePath,
                    isDownloaded = true
                )
                storeAdapter.finishDownload(updated)
                refreshGallery()
                // Silent download - no toast alert as requested
                analytics.logAction("download_success", "SUCCESS", duration, mapOf(
                    "id" to item.id,
                    "title" to item.title,
                    "fileBytes" to downloadedFile.length(),
                    "speedKBps" to if (duration > 0) (downloadedFile.length() / duration) else 0
                ))
                analytics.flushSync()
            }.onFailure { err ->
                storeAdapter.setDownloadingProgress(item.id, 100) // resets downloading state
                analytics.logAction("download_failed", "FAILED", duration, mapOf(
                    "id" to item.id,
                    "title" to item.title
                ), err)
                analytics.flushSync()
                showGlassToast("Download failed: ${err.message}", isSuccess = false)
            }
        }
    }

    private fun confirmDeleteCloudDownload(item: WallpaperItem) {
        AlertDialog.Builder(this)
            .setTitle("Free Phone Storage")
            .setMessage("Delete '${item.title}' from your phone?\n\n(It will remain in the Cloud Store to download again anytime).")
            .setPositiveButton("Free Storage") { _, _ ->
                val start = System.currentTimeMillis()
                try {
                    repository.downloadManager.deleteLocalDownload(item.id)
                    val updated = item.copy(filePath = "", isDownloaded = false)
                    storeAdapter.updateItem(updated)
                    refreshGallery()

                    val current = repository.getActiveWallpaper()
                    if (current.id == item.id || selectedWallpaperItem?.id == item.id) {
                        stopPreviewPlayback()
                        tvActiveWallpaperSubtitle.text = "Video deleted from phone. Select another wallpaper below."
                        setApplyButtonState(isSelected = false, item = null)
                    } else {
                        playActiveVideoInPreview(current)
                    }
                    val duration = System.currentTimeMillis() - start
                    analytics.logAction("delete_local_storage", "SUCCESS", duration, mapOf(
                        "id" to item.id,
                        "title" to item.title
                    ))
                    // Silent delete - no toast alert as requested
                } catch (e: Exception) {
                    val duration = System.currentTimeMillis() - start
                    analytics.logAction("delete_local_storage", "FAILED", duration, mapOf(
                        "id" to item.id,
                        "title" to item.title
                    ), e)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun setupAdminSecretGesture() {
        tvAppTitle.setOnClickListener {
            val now = System.currentTimeMillis()
            if (now - lastTitleClickTime < 1000) {
                titleClickCount++
            } else {
                titleClickCount = 1
            }
            lastTitleClickTime = now

            if (titleClickCount >= 5) {
                titleClickCount = 0
                showAdminPinDialog()
            }
        }

        btnUserAuth.setOnClickListener {
            showUserAuthDialog()
        }

        btnNotificationCenter.setOnClickListener {
            showInAppNotificationsDialog()
        }
    }

    private fun showAdminPinDialog() {
        val input = EditText(this).apply {
            hint = "Enter Admin PIN"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
        }

        AlertDialog.Builder(this)
            .setTitle("👑 Admin Secret Mode")
            .setMessage("Enter Admin PIN to view unpublished/draft wallpapers:")
            .setView(input)
            .setPositiveButton("Unlock") { _, _ ->
                val entered = input.text.toString().trim()
                if (entered == CloudConfig.ADMIN_SECRET_PIN) {
                    isAdminMode = !isAdminMode
                    val modeText = if (isAdminMode) "👑 Admin Mode ENABLED" else "Guest Mode Restored"
                    tvAppTitle.text = if (isAdminMode) "👑 VividWake (Admin)" else "Lock Screen Video"
                    Toast.makeText(this, modeText, Toast.LENGTH_SHORT).show()
                    adapter.isAdminMode = isAdminMode
                    adapter.notifyDataSetChanged()
                    loadStoreCatalog()
                } else {
                    Toast.makeText(this, "Incorrect Admin PIN", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showUserAuthDialog() {
        val user = authManager.currentUser
        if (user != null) {
            val name = user.displayName ?: "User"
            val email = user.email ?: ""
            AlertDialog.Builder(this)
                .setTitle("👤 Google Account")
                .setMessage("Signed in as:\n$name\n($email)")
                .setPositiveButton("Sign Out") { _, _ ->
                    authManager.signOut {
                        analytics.linkUserToDevice("", "")
                        runOnUiThread {
                            updateUserAuthUI()
                            Toast.makeText(this, "Signed out successfully", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                .setNegativeButton("Close", null)
                .show()
        } else {
            googleSignInLauncher.launch(authManager.getSignInIntent())
        }
    }

    private fun updateUserAuthUI() {
        val user = authManager.currentUser
        if (user != null) {
            val firstName = user.displayName?.split(" ")?.firstOrNull() ?: user.email?.substringBefore("@") ?: "User"
            tvUserAuthText.text = firstName
            ivUserAuthIcon.setImageResource(R.drawable.ic_check)
            analytics.linkUserToDevice(user.uid, user.email ?: "")
            // Listen to real-time wallpaper assignments from admin
            authManager.listenToAssignedWallpapers { assignedList ->
                prefs.edit().putStringSet("assigned_wallpaper_ids", assignedList.toSet()).apply()
            }
        } else {
            tvUserAuthText.text = "Sign In"
            ivUserAuthIcon.setImageResource(R.drawable.ic_google)
        }
    }

    private fun updateNotificationBadge() {
        val count = inAppNotificationManager.getUnreadCount()
        if (count > 0) {
            tvNotificationBadge.visibility = View.VISIBLE
            tvNotificationBadge.text = if (count > 9) "9+" else count.toString()
        } else {
            tvNotificationBadge.visibility = View.GONE
        }
    }

    private fun showInAppNotificationsDialog() {
        val dialog = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.dialog_inapp_notifications, null)
        dialog.setContentView(view)

        val rv = view.findViewById<RecyclerView>(R.id.rvInAppNotifs)
        val tvEmpty = view.findViewById<TextView>(R.id.tvEmptyNotifs)
        val btnClear = view.findViewById<TextView>(R.id.btnClearAllNotifs)

        val list = inAppNotificationManager.getNotifications()
        if (list.isEmpty()) {
            tvEmpty.visibility = View.VISIBLE
            rv.visibility = View.GONE
        } else {
            tvEmpty.visibility = View.GONE
            rv.visibility = View.VISIBLE
            rv.layoutManager = LinearLayoutManager(this)
            val notifAdapter = InAppNotificationAdapter(list) { notif ->
                dialog.dismiss()
                if (!notif.wallpaperId.isNullOrBlank()) {
                    switchToStoreTab()
                }
            }
            rv.adapter = notifAdapter
        }

        btnClear.setOnClickListener {
            inAppNotificationManager.clearAll()
            updateNotificationBadge()
            dialog.dismiss()
        }

        inAppNotificationManager.markAllAsRead()
        updateNotificationBadge()
        dialog.show()
    }

    private fun refreshGallery() {
        val allWallpapers = repository.getAllWallpapers()
        val activeItem = repository.getActiveWallpaper()
        adapter.submitList(allWallpapers, activeItem.id)
        updateSubtitle(activeItem)
    }

    private fun updateSubtitle(item: WallpaperItem) {
        val mode = if (prefs.contains(VideoWallpaperService.KEY_PLAYBACK_MODE)) {
            prefs.getInt(VideoWallpaperService.KEY_PLAYBACK_MODE, VideoWallpaperService.MODE_WAKE_FREEZE)
        } else {
            if (prefs.getBoolean(VideoWallpaperService.KEY_LOOP, false)) VideoWallpaperService.MODE_LOOP_LOCK_ONLY else VideoWallpaperService.MODE_WAKE_FREEZE
        }
        val modeText = when (mode) {
            VideoWallpaperService.MODE_LOOP_LOCK_ONLY -> "Loop on Lock Screen (Fast Home Screen)"
            VideoWallpaperService.MODE_LOOP_ALWAYS -> "Continuous Loop Always"
            else -> "Plays on wake, freezes on final frame"
        }
        tvActiveWallpaperSubtitle.text = "Active: ${item.title} • $modeText"
    }

    private fun selectWallpaper(item: WallpaperItem) {
        val start = System.currentTimeMillis()
        try {
            repository.setActiveWallpaper(item)
            adapter.setActiveId(item.id)
            updateSubtitle(item)
            playActiveVideoInPreview(item)
            // Brighten up the apply button with vibrant gold and bold text
            setApplyButtonState(isSelected = true, item = item)
            val duration = System.currentTimeMillis() - start
            analytics.logAction("wallpaper_selected", "SUCCESS", duration, mapOf(
                "id" to item.id,
                "title" to item.title,
                "category" to item.category
            ))
            showGlassToast("Selected '${item.title}'! Tap 'Apply to Lock Screen' below to apply to phone")
        } catch (e: Exception) {
            val duration = System.currentTimeMillis() - start
            analytics.logAction("wallpaper_selected", "FAILED", duration, mapOf(
                "id" to item.id,
                "title" to item.title
            ), e)
        }
    }

    private fun confirmDeleteWallpaper(item: WallpaperItem) {
        AlertDialog.Builder(this)
            .setTitle("Delete Wallpaper")
            .setMessage("Remove '${item.title}' from your gallery?")
            .setPositiveButton("Delete") { _, _ ->
                repository.deleteWallpaper(item)
                refreshGallery()
                if (selectedWallpaperItem?.id == item.id) {
                    setApplyButtonState(isSelected = false, item = null)
                }
                val current = repository.getActiveWallpaper()
                playActiveVideoInPreview(current)
                // Silent delete - no toast alert as requested
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
                analytics.logEvent("local_video_imported", mapOf("title" to newItem.title))
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
        val mode = if (prefs.contains(VideoWallpaperService.KEY_PLAYBACK_MODE)) {
            prefs.getInt(VideoWallpaperService.KEY_PLAYBACK_MODE, VideoWallpaperService.MODE_WAKE_FREEZE)
        } else {
            if (prefs.getBoolean(VideoWallpaperService.KEY_LOOP, false)) {
                VideoWallpaperService.MODE_LOOP_LOCK_ONLY
            } else {
                VideoWallpaperService.MODE_WAKE_FREEZE
            }
        }

        when (mode) {
            VideoWallpaperService.MODE_LOOP_LOCK_ONLY -> rbLoopLockOnly.isChecked = true
            VideoWallpaperService.MODE_LOOP_ALWAYS -> rbLoopAlways.isChecked = true
            else -> rbFreezeLastFrame.isChecked = true
        }

        val isMute = prefs.getBoolean(VideoWallpaperService.KEY_MUTE, true)
        switchMute.isChecked = isMute
    }

    private fun setupListeners() {
        btnSetWallpaper.setOnClickListener {
            if (!isWallpaperSelectedForApply || selectedWallpaperItem == null) {
                return@setOnClickListener
            }
            val start = System.currentTimeMillis()
            val activeItem = selectedWallpaperItem ?: repository.getActiveWallpaper()
            try {
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
                val duration = System.currentTimeMillis() - start
                analytics.logAction("set_wallpaper_button_click", "SUCCESS", duration, mapOf(
                    "brand" to Build.MANUFACTURER,
                    "model" to Build.MODEL,
                    "wallpaperId" to activeItem.id,
                    "title" to activeItem.title
                ))
            } catch (e: Exception) {
                val duration = System.currentTimeMillis() - start
                analytics.logAction("set_wallpaper_button_click", "FAILED", duration, mapOf(
                    "brand" to Build.MANUFACTURER,
                    "model" to Build.MODEL,
                    "wallpaperId" to activeItem.id,
                    "title" to activeItem.title
                ), e)
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
            val mode = when (checkedId) {
                R.id.rbLoopLockOnly -> VideoWallpaperService.MODE_LOOP_LOCK_ONLY
                R.id.rbLoopAlways -> VideoWallpaperService.MODE_LOOP_ALWAYS
                else -> VideoWallpaperService.MODE_WAKE_FREEZE
            }
            val isLoop = (mode != VideoWallpaperService.MODE_WAKE_FREEZE)

            prefs.edit()
                .putInt(VideoWallpaperService.KEY_PLAYBACK_MODE, mode)
                .putBoolean(VideoWallpaperService.KEY_LOOP, isLoop)
                .apply()

            updateSubtitle(repository.getActiveWallpaper())
            analytics.logEvent("change_playback_mode", mapOf("mode" to mode))

            val msg = when (mode) {
                VideoWallpaperService.MODE_LOOP_LOCK_ONLY -> "Mode: Loop on Lock Screen (Pauses on Home Screen - 100% Smooth)"
                VideoWallpaperService.MODE_LOOP_ALWAYS -> "Mode: Loop Always (Lock & Home Screen)"
                else -> "Mode: Wake Animation (Freeze on End Frame - 0% Battery)"
            }
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
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
            Toast.makeText(this, "Video saved to chosen folder!", Toast.LENGTH_LONG).show()
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

    /**
     * Plays a local active wallpaper item in the top preview player.
     */
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

    /**
     * Zero-disk live streaming preview:
     * Streams video directly from remote URL over HTTP without saving full 15MB to disk.
     */
    private fun playStreamingVideoInPreview(item: WallpaperItem) {
        val surface = previewSurface ?: return
        if (item.videoUrl.isBlank()) return

        val start = System.currentTimeMillis()
        try {
            stopPreviewPlayback()
            Toast.makeText(this, "Streaming '${item.title}' preview...", Toast.LENGTH_SHORT).show()

            previewPlayer = MediaPlayer().apply {
                setSurface(surface)
                setDataSource(this@MainActivity, Uri.parse(item.videoUrl))
                isLooping = true
                val isMute = prefs.getBoolean(VideoWallpaperService.KEY_MUTE, true)
                val vol = if (isMute) 0f else 1f
                setVolume(vol, vol)

                setOnVideoSizeChangedListener { _, w, h ->
                    if (w > 0 && h > 0) {
                        adjustPreviewAspect(w, h, 0)
                    }
                }

                setOnPreparedListener { mp ->
                    adjustPreviewAspect(mp.videoWidth, mp.videoHeight, 0)
                    mp.start()
                    val duration = System.currentTimeMillis() - start
                    analytics.logAction("preview_stream_ready", "SUCCESS", duration, mapOf(
                        "id" to item.id,
                        "title" to item.title,
                        "category" to item.category
                    ))
                }

                setOnErrorListener { _, what, extra ->
                    val duration = System.currentTimeMillis() - start
                    analytics.logAction("preview_stream_error", "FAILED", duration, mapOf(
                        "id" to item.id,
                        "title" to item.title,
                        "what" to what,
                        "extra" to extra
                    ))
                    analytics.flushSync()
                    playActiveVideoInPreview(repository.getActiveWallpaper())
                    true
                }

                prepareAsync()
            }
        } catch (e: Exception) {
            val duration = System.currentTimeMillis() - start
            analytics.logAction("preview_stream_failed", "FAILED", duration, mapOf(
                "id" to item.id,
                "title" to item.title
            ), e)
            analytics.flushSync()
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
        updateNotificationBadge()
        updateUserAuthUI()

        val filter = IntentFilter(AppFirebaseMessagingService.ACTION_NEW_INAPP_NOTIFICATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(notifReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(notifReceiver, filter)
        }

        if (previewSurface != null && (previewPlayer == null || !previewPlayer!!.isPlaying)) {
            playActiveVideoInPreview(repository.getActiveWallpaper())
        }
    }

    override fun onPause() {
        try {
            unregisterReceiver(notifReceiver)
        } catch (_: Exception) {}
        stopPreviewPlayback()
        analytics.flushSync()
        super.onPause()
    }

    override fun onDestroy() {
        stopPreviewPlayback()
        analytics.flushSync()
        super.onDestroy()
    }
}
