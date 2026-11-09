package com.lockscreen.videowake

import android.Manifest
import android.app.Activity
import android.app.WallpaperManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
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
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
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
    private lateinit var ivUserAuthPhoto: ImageView
    private lateinit var tvUserAuthInitial: TextView

    private lateinit var tvDeviceBrand: TextView
    private lateinit var tvPrimaryInstruction: TextView
    private lateinit var tvActiveWallpaperSubtitle: TextView
    private lateinit var previewTextureView: TextureView
    private var tvMockNotificationText: TextView? = null

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
    private lateinit var nestedScrollViewMain: androidx.core.widget.NestedScrollView
    private lateinit var pbStoreLoadMore: ProgressBar

    // Concept 1: Command Bar, Search & Gifted Filter
    private lateinit var layoutStoreSearchBar: LinearLayout
    private lateinit var etStoreSearch: EditText
    private lateinit var btnClearStoreSearch: ImageButton
    private lateinit var btnFilterGifted: MaterialButton
    private lateinit var btnStoreFilterCategories: View
    private lateinit var layoutActiveFilterBanner: LinearLayout
    private lateinit var ivActiveFilterIcon: ImageView
    private lateinit var tvActiveFilterText: TextView
    private lateinit var btnResetActiveFilter: MaterialButton
    private lateinit var layoutStoreEmptyState: LinearLayout
    private lateinit var tvStoreEmptyTitle: TextView
    private lateinit var tvStoreEmptySubtitle: TextView
    private lateinit var btnResetStoreFilters: MaterialButton

    // Notification Drop Focus Banner
    private lateinit var layoutNotificationFocusBanner: LinearLayout
    private lateinit var tvNotificationFocusText: TextView
    private lateinit var btnDismissNotificationFocus: MaterialButton
    private var notificationFocusJob: Job? = null

    private var isGiftedFilterActive: Boolean = false
    private var isSearchExpanded: Boolean = false

    // Explore Store Pagination & Filter State
    private var fullCatalogList: List<WallpaperItem> = emptyList()
    private var filteredCatalogList: List<WallpaperItem> = emptyList()
    private var currentStoreCategory: String = "All"
    private var currentStoreSubTag: String = "All"
    private var currentStoreSearchQuery: String = ""
    private var currentStorePage: Int = 0
    private val STORE_PAGE_SIZE: Int = 20
    private var isStoreLoadingMore: Boolean = false
    private var searchJob: Job? = null

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
    private lateinit var deviceLockManager: DeviceLockManager
    private lateinit var inAppNotificationManager: InAppNotificationManager
    private lateinit var previewCooldownManager: PreviewCooldownManager

    private var previewPlayer: MediaPlayer? = null
    private var previewSurface: Surface? = null
    private var previewWidth = 0
    private var previewHeight = 0

    private var isSamsung = false
    private var isXiaomi = false
    private var isColorOS = false
    private var isVivo = false
    private var isHuawei = false
    private var isTranssion = false

    private var isWallpaperSelectedForApply = false
    private var selectedWallpaperItem: WallpaperItem? = null
    private var isOptionalUpdatePrompted = false

    private val notifReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            updateNotificationBadge()
        }
    }

    private var authLoadingDialog: AlertDialog? = null

    private fun showAuthLoading(message: String = "Connecting to Google...") {
        if (isFinishing || isDestroyed) return
        try {
            val titleText = when {
                message.contains("Opening", ignoreCase = true) -> "Connecting to Google"
                message.contains("Authenticating", ignoreCase = true) -> "Authenticating Account"
                message.contains("Verifying", ignoreCase = true) -> "Verifying Device Security"
                else -> message
            }
            val subtitleText = when {
                message.contains("Opening", ignoreCase = true) -> "Opening secure account chooser..."
                message.contains("Authenticating", ignoreCase = true) -> "Syncing Google profile & security tokens..."
                message.contains("Verifying", ignoreCase = true) -> "Checking 1-to-1 device authorization..."
                else -> "Securing your session, please wait..."
            }

            if (authLoadingDialog == null) {
                val view = layoutInflater.inflate(R.layout.dialog_auth_loading, null)
                val tvMsg = view.findViewById<TextView>(R.id.tvLoadingMessage)
                val tvSub = view.findViewById<TextView>(R.id.tvLoadingSubtitle)
                tvMsg.text = titleText
                tvSub?.text = subtitleText
                authLoadingDialog = AlertDialog.Builder(this)
                    .setView(view)
                    .setCancelable(false)
                    .create().apply {
                        window?.setBackgroundDrawableResource(android.R.color.transparent)
                        window?.setGravity(android.view.Gravity.CENTER)
                    }
            } else {
                val tvMsg = authLoadingDialog?.findViewById<TextView>(R.id.tvLoadingMessage)
                val tvSub = authLoadingDialog?.findViewById<TextView>(R.id.tvLoadingSubtitle)
                tvMsg?.text = titleText
                tvSub?.text = subtitleText
            }
            authLoadingDialog?.show()
        } catch (_: Exception) {}
    }

    private fun hideAuthLoading() {
        try {
            authLoadingDialog?.dismiss()
        } catch (_: Exception) {}
        authLoadingDialog = null
    }

    private fun showAuthErrorDialog(title: String, message: String) {
        if (isFinishing || isDestroyed) return
        try {
            AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton("OK", null)
                .show()
        } catch (_: Exception) {}
    }

    private val requestNotificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            Log.d("MainActivity", "POST_NOTIFICATIONS permission granted")
            syncInAppNotifications()
        }
    }

    private fun checkNotificationPermission() {
        // Mobile system status bar notifications are disabled (pure in-app notification center).
        // No runtime POST_NOTIFICATIONS permission prompt required.
    }

    private fun syncInAppNotifications() {
        val user = authManager.currentUser
        lifecycleScope.launch {
            try {
                val newCount = inAppNotificationManager.syncWithServer(
                    userEmail = user?.email,
                    userId = user?.uid,
                    showSystemAlertOnNew = false
                )
                withContext(Dispatchers.Main) {
                    updateNotificationBadge()
                }
            } catch (e: Exception) {
                Log.d("MainActivity", "Notification sync error: ${e.message}")
            }
        }
    }

    private fun registerPushTokenWithBackend() {
        FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
            if (task.isSuccessful && !task.result.isNullOrBlank()) {
                val token = task.result
                val user = authManager.currentUser
                lifecycleScope.launch(Dispatchers.IO) {
                    try {
                        val payload = JSONObject().apply {
                            put("fcmToken", token)
                            put("userEmail", user?.email ?: "")
                            put("userId", user?.uid ?: "")
                            put("deviceModel", "${Build.MANUFACTURER} ${Build.MODEL}")
                        }
                        val client = OkHttpClient()
                        val mediaType = "application/json; charset=utf-8".toMediaType()
                        val req = Request.Builder()
                            .url("${CloudConfig.ANALYTICS_BASE_URL}/api/notifications/register-token")
                            .post(payload.toString().toRequestBody(mediaType))
                            .build()
                        client.newCall(req).execute().close()
                    } catch (_: Exception) {}
                }
            }
        }
    }

    private val googleSignInLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val start = System.currentTimeMillis()
        showAuthLoading("Authenticating with Google...")
        lifecycleScope.launch {
            try {
                val res = authManager.handleSignInResult(result.data)
                val duration = System.currentTimeMillis() - start
                if (res.isSuccess) {
                    val user = res.getOrNull()
                    val userEmail = user?.email ?: ""
                    val userId = user?.uid ?: ""

                    // 🔒 Enforce Strict 1-Phone = 1-Email Device Security Check
                    showAuthLoading("Verifying device security...")
                    val verifyResult = deviceLockManager.verifyBinding(userEmail, userId)
                    hideAuthLoading()

                    when (verifyResult) {
                        is DeviceVerificationResult.Banned -> {
                            authManager.signOut {}
                            analytics.logAction("device_banned_blocked", "FAILED", duration, mapOf(
                                "reason" to verifyResult.reason
                            ))
                            analytics.flushSync()
                            updateUserAuthUI()
                            showBannedDeviceDialog(verifyResult.reason, userEmail)
                            return@launch
                        }
                        is DeviceVerificationResult.MismatchedDevice -> {
                            authManager.signOut {}
                            analytics.logAction("device_mismatch_blocked", "FAILED", duration, mapOf(
                                "boundDevice" to verifyResult.boundDevice,
                                "transferStatus" to verifyResult.transferRequestStatus
                            ))
                            analytics.flushSync()
                            updateUserAuthUI()
                            showTransferRequestDialog(userEmail, verifyResult.boundDevice, verifyResult.transferRequestStatus)
                            return@launch
                        }
                        is DeviceVerificationResult.DeviceLockedToAnotherEmail -> {
                            authManager.signOut {}
                            analytics.logAction("device_lock_blocked", "FAILED", duration, mapOf(
                                "attemptedEmail" to userEmail,
                                "boundEmail" to verifyResult.boundEmail,
                                "error" to verifyResult.errorMessage
                            ))
                            analytics.flushSync()
                            updateUserAuthUI()
                            showDeviceLockedToAuthorizedEmailDialog(verifyResult.boundEmail)
                            return@launch
                        }
                        is DeviceVerificationResult.Blocked -> {
                            // Device or Email is bound to someone else!
                            // Immediately sign out to prevent unauthorized access
                            authManager.signOut {}
                            analytics.logAction("device_lock_blocked", "FAILED", duration, mapOf(
                                "email" to userEmail,
                                "reason" to verifyResult.reason
                            ))
                            analytics.flushSync()
                            updateUserAuthUI()

                            val emailMatch = Regex("[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}").find(verifyResult.reason)
                            if (emailMatch != null) {
                                showDeviceLockedToAuthorizedEmailDialog(emailMatch.value)
                            } else {
                                showAuthErrorDialog(verifyResult.title, verifyResult.reason)
                            }
                            return@launch
                        }
                        is DeviceVerificationResult.Error -> {
                            // Non-blocking warning (e.g. temporary offline)
                            Log.w("MainActivity", "Device lock non-fatal verification note: ${verifyResult.errorMessage}")
                        }
                        is DeviceVerificationResult.Success -> {
                            Log.d("MainActivity", "Device lock verified: ${verifyResult.message} [Tier: ${verifyResult.tier}]")
                        }
                    }

                    if (user != null) {
                        analytics.linkUserToDevice(
                            user.uid,
                            user.email ?: "",
                            user.displayName ?: "",
                            user.photoUrl?.toString() ?: ""
                        )
                    }
                    val name = user?.displayName ?: "User"
                    analytics.logAction("google_signin_completed", "SUCCESS", duration, mapOf(
                        "uid" to (user?.uid ?: ""),
                        "email" to (user?.email ?: "")
                    ))
                    analytics.flushSync()
                    showGlassToast("Welcome, $name! • Google Account Linked", isSuccess = true)
                    updateUserAuthUI()
                } else {
                    hideAuthLoading()
                    val err = res.exceptionOrNull()
                    val errMsg = err?.message ?: "Sign-in could not be completed"
                    analytics.logAction("google_signin_completed", "FAILED", duration, mapOf(
                        "error" to errMsg
                    ), err)
                    analytics.flushSync()
                    if (errMsg.contains("Code 12501")) {
                        Toast.makeText(this@MainActivity, "Sign-in cancelled", Toast.LENGTH_SHORT).show()
                    } else {
                        showAuthErrorDialog("Google Sign-In Alert", errMsg)
                    }
                }
            } catch (e: Throwable) {
                hideAuthLoading()
                val duration = System.currentTimeMillis() - start
                analytics.logAction("google_signin_completed", "FAILED", duration, mapOf("error" to (e.message ?: "unknown")), e)
                analytics.flushSync()
                showAuthErrorDialog("Authentication Error", e.localizedMessage ?: "Unexpected error")
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
        ivUserAuthPhoto = findViewById(R.id.ivUserAuthPhoto)
        tvUserAuthInitial = findViewById(R.id.tvUserAuthInitial)

        authManager = AuthManager(this)
        deviceLockManager = DeviceLockManager(this)
        inAppNotificationManager = InAppNotificationManager(this)
        previewCooldownManager = PreviewCooldownManager(this)
        tvDeviceBrand = findViewById(R.id.tvDeviceBrand)
        tvPrimaryInstruction = findViewById(R.id.tvPrimaryInstruction)
        tvActiveWallpaperSubtitle = findViewById(R.id.tvActiveWallpaperSubtitle)
        previewTextureView = findViewById(R.id.previewTextureView)
        tvMockNotificationText = findViewById(R.id.tvMockNotificationText)

        btnTabMyWallpapers = findViewById(R.id.btnTabMyWallpapers)
        btnTabExploreStore = findViewById(R.id.btnTabExploreStore)
        layoutMyWallpapers = findViewById(R.id.layoutMyWallpapers)
        layoutExploreStore = findViewById(R.id.layoutExploreStore)

        rvWallpapers = findViewById(R.id.rvWallpapers)
        layoutImporting = findViewById(R.id.layoutImporting)
        tvImportingStatus = findViewById(R.id.tvImportingStatus)

        rvStoreWallpapers = findViewById(R.id.rvStoreWallpapers)
        layoutStoreSkeleton = findViewById(R.id.layoutStoreSkeleton)
        nestedScrollViewMain = findViewById(R.id.nestedScrollViewMain)
        pbStoreLoadMore = findViewById(R.id.pbStoreLoadMore)

        layoutStoreSearchBar = findViewById(R.id.layoutStoreSearchBar)
        etStoreSearch = findViewById(R.id.etStoreSearch)
        btnClearStoreSearch = findViewById(R.id.btnClearStoreSearch)
        btnFilterGifted = findViewById(R.id.btnFilterGifted)
        btnStoreFilterCategories = findViewById(R.id.btnStoreFilterCategories)
        layoutActiveFilterBanner = findViewById(R.id.layoutActiveFilterBanner)
        ivActiveFilterIcon = findViewById(R.id.ivActiveFilterIcon)
        tvActiveFilterText = findViewById(R.id.tvActiveFilterText)
        btnResetActiveFilter = findViewById(R.id.btnResetActiveFilter)
        layoutStoreEmptyState = findViewById(R.id.layoutStoreEmptyState)
        tvStoreEmptyTitle = findViewById(R.id.tvStoreEmptyTitle)
        tvStoreEmptySubtitle = findViewById(R.id.tvStoreEmptySubtitle)
        btnResetStoreFilters = findViewById(R.id.btnResetStoreFilters)

        layoutNotificationFocusBanner = findViewById(R.id.layoutNotificationFocusBanner)
        tvNotificationFocusText = findViewById(R.id.tvNotificationFocusText)
        btnDismissNotificationFocus = findViewById(R.id.btnDismissNotificationFocus)
        btnDismissNotificationFocus.setOnClickListener {
            notificationFocusJob?.cancel()
            storeAdapter.clearFocusMode()
            layoutNotificationFocusBanner.visibility = View.GONE
        }

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
        setupHeaderControls()

        checkNotificationPermission()
        syncInAppNotifications()
        registerPushTokenWithBackend()

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
        isVivo = manufacturer.contains("vivo") || manufacturer.contains("iqoo") ||
                brand.contains("vivo") || brand.contains("iqoo")
        isHuawei = manufacturer.contains("huawei") || manufacturer.contains("honor") ||
                brand.contains("huawei") || brand.contains("honor")
        isTranssion = manufacturer.contains("transsion") || manufacturer.contains("infinix") ||
                manufacturer.contains("tecno") || manufacturer.contains("itel")

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
                tvDeviceBrand.text = "Detected: $brandName (OxygenOS / ColorOS / Realme UI)"
                tvPrimaryInstruction.text = "Select 'Home and Lock screen'. Note: turn off 'Lock Screen Magazine' if it reverts."
            }
            isVivo -> {
                val brandName = Build.MANUFACTURER.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }
                tvDeviceBrand.text = "Detected: $brandName (Funtouch OS / OriginOS)"
                tvPrimaryInstruction.text = "Select 'Home and Lock screen'. Note: turn off 'Lock Screen Poster' if it reverts."
            }
            isHuawei -> {
                val brandName = Build.MANUFACTURER.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }
                tvDeviceBrand.text = "Detected: $brandName (EMUI / MagicOS)"
                tvPrimaryInstruction.text = "Choose 'Set as Lock screen and Home screen'. Turn off 'Magazine Unlock' in Settings if enabled."
            }
            isTranssion -> {
                val brandName = Build.MANUFACTURER.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }
                tvDeviceBrand.text = "Detected: $brandName (XOS / HiOS)"
                tvPrimaryInstruction.text = "Tap 'Set wallpaper' ➔ choose 'Both Home & Lock screen'"
            }
            else -> {
                val brandName = Build.MANUFACTURER.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }
                tvDeviceBrand.text = "Detected: $brandName (Native AOSP / Clean UI)"
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

    private fun showGlassToast(message: String, isSuccess: Boolean = true, iconRes: Int = R.drawable.ic_clock) {
        try {
            val inflater = LayoutInflater.from(this)
            val view = inflater.inflate(R.layout.view_glass_toast, null)
            val ivIcon: ImageView = view.findViewById(R.id.ivToastIcon)
            val tvMsg: TextView = view.findViewById(R.id.tvToastMessage)

            tvMsg.text = message
            ivIcon.setImageResource(iconRes)
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

        // Fetch on scroll: when scrolling near bottom of NestedScrollView, load next page chunk
        nestedScrollViewMain.setOnScrollChangeListener(androidx.core.widget.NestedScrollView.OnScrollChangeListener { v, _, scrollY, _, _ ->
            if (layoutExploreStore.visibility == View.VISIBLE && !isStoreLoadingMore && (currentStorePage * STORE_PAGE_SIZE < filteredCatalogList.size)) {
                val child = v.getChildAt(0)
                if (child != null) {
                    val diff = child.bottom - (v.height + scrollY)
                    if (diff <= 1000) {
                        loadNextStorePage()
                    }
                }
            }
        })
    }

    private fun loadStoreCatalog() {
        val start = System.currentTimeMillis()
        val pulseAnim = android.view.animation.AnimationUtils.loadAnimation(this, R.anim.pulse_skeleton)
        layoutStoreSkeleton.visibility = View.VISIBLE
        layoutStoreSkeleton.startAnimation(pulseAnim)
        rvStoreWallpapers.visibility = View.GONE
        lifecycleScope.launch {
            try {
                val currentUser = authManager.currentUser
                val userEmail = currentUser?.email
                val userId = currentUser?.uid
                val assignedIds = prefs.getStringSet("assigned_wallpaper_ids", emptySet()) ?: emptySet()
                fullCatalogList = catalogRepo.fetchCatalog(userEmail, userId, assignedIds)
                layoutStoreSkeleton.clearAnimation()
                layoutStoreSkeleton.visibility = View.GONE
                rvStoreWallpapers.visibility = View.VISIBLE

                updateGiftedFilterButtonUI()
                applyCategoryFilter(currentStoreCategory)

                val duration = System.currentTimeMillis() - start
                analytics.logAction("store_catalog_fetch", "SUCCESS", duration, mapOf(
                    "count" to fullCatalogList.size
                ))
            } catch (e: Exception) {
                layoutStoreSkeleton.clearAnimation()
                layoutStoreSkeleton.visibility = View.GONE
                rvStoreWallpapers.visibility = View.VISIBLE
                val duration = System.currentTimeMillis() - start
                analytics.logAction("store_catalog_fetch", "FAILED", duration, emptyMap(), e)
            }
        }
    }

    private fun setupStoreCategoryChips() {
        // Concept 1: Categories are accessed via the dedicated Filter Modal Button
    }

    private fun setupStoreSubTags() {
        // Concept 1: Cluttered horizontal scrollbars removed
    }

    private fun applyCategoryFilter(category: String) {
        currentStoreCategory = category
        currentStoreSubTag = "All"
        recomputeFilteredCatalog()
    }

    private fun applySubTagFilter(subTag: String) {
        currentStoreSubTag = subTag
        recomputeFilteredCatalog()
    }

    private fun formatTagName(tag: String): String {
        return tag.split("-", "_", " ")
            .filter { it.isNotBlank() }
            .joinToString(" ") { word ->
                when (word.lowercase()) {
                    "pubg", "gta", "fps", "lol", "cod", "ai", "4k", "hd" -> word.uppercase()
                    else -> word.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }
                }
            }
    }

    private fun recomputeFilteredCatalog() {
        var result = fullCatalogList

        // 0. 🎁 Gifted Filter (Concept 1)
        if (isGiftedFilterActive) {
            result = result.filter { it.isGifted }
        }

        // 1. Category filter
        if (currentStoreCategory != "All") {
            result = result.filter { it.category.equals(currentStoreCategory, ignoreCase = true) }
        }

        // 2. Sub-Tag filter
        if (currentStoreSubTag.isNotBlank() && currentStoreSubTag != "All") {
            val qTag = currentStoreSubTag.lowercase()
            result = result.filter { item ->
                item.tags.any { it.lowercase() == qTag || it.lowercase().contains(qTag) } ||
                item.title.lowercase().contains(qTag) ||
                item.description.lowercase().contains(qTag)
            }
        }

        // 3. Search query filter
        if (currentStoreSearchQuery.isNotBlank()) {
            val tokens = currentStoreSearchQuery.lowercase().trim().split(" ").filter { it.isNotBlank() }
            result = result.filter { item ->
                tokens.all { t ->
                    item.title.lowercase().contains(t) ||
                    item.description.lowercase().contains(t) ||
                    item.category.lowercase().contains(t) ||
                    item.tags.any { it.lowercase().contains(t) }
                }
            }
        }

        filteredCatalogList = result
        currentStorePage = 0
        val firstBatch = filteredCatalogList.take(STORE_PAGE_SIZE)
        storeAdapter.submitInitialList(firstBatch)
        currentStorePage = 1

        // Empty state visibility
        if (filteredCatalogList.isEmpty()) {
            rvStoreWallpapers.visibility = View.GONE
            layoutStoreEmptyState.visibility = View.VISIBLE
            if (isGiftedFilterActive) {
                tvStoreEmptyTitle.text = "No Gifted Wallpapers Yet"
                tvStoreEmptySubtitle.text = "Wallpapers specially assigned to you by the admin will appear here."
            } else {
                tvStoreEmptyTitle.text = "No wallpapers found"
                tvStoreEmptySubtitle.text = "No wallpapers found for '$currentStoreSearchQuery'."
            }
        } else {
            rvStoreWallpapers.visibility = View.VISIBLE
            layoutStoreEmptyState.visibility = View.GONE
        }

        updateActiveFilterBanner()
    }

    private fun updateGiftedFilterButtonUI() {
        val giftedCount = fullCatalogList.count { it.isGifted }
        val label = if (giftedCount > 0) "Gifted ($giftedCount)" else "Gifted"
        btnFilterGifted.text = if (isSearchExpanded) "" else label

        if (isGiftedFilterActive) {
            btnFilterGifted.setBackgroundColor(Color.parseColor("#D4AF37"))
            btnFilterGifted.setTextColor(Color.parseColor("#120E1F"))
            btnFilterGifted.strokeColor = ColorStateList.valueOf(Color.parseColor("#D4AF37"))
            btnFilterGifted.iconTint = ColorStateList.valueOf(Color.parseColor("#120E1F"))
        } else {
            btnFilterGifted.setBackgroundColor(Color.parseColor("#261E38"))
            btnFilterGifted.setTextColor(Color.parseColor("#D4AF37"))
            btnFilterGifted.strokeColor = ColorStateList.valueOf(Color.parseColor("#50D4AF37"))
            btnFilterGifted.iconTint = ColorStateList.valueOf(Color.parseColor("#D4AF37"))
        }
    }

    private fun updateCommandBarState(expanded: Boolean) {
        isSearchExpanded = expanded
        val density = resources.displayMetrics.density
        if (expanded) {
            btnFilterGifted.text = ""
            btnFilterGifted.iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START
            btnFilterGifted.iconPadding = 0
            val params = btnFilterGifted.layoutParams
            params.width = (44 * density).toInt()
            btnFilterGifted.layoutParams = params
            btnFilterGifted.setPadding(0, 0, 0, 0)
        } else {
            val params = btnFilterGifted.layoutParams
            params.width = ViewGroup.LayoutParams.WRAP_CONTENT
            btnFilterGifted.layoutParams = params
            val padHoriz = (12 * density).toInt()
            btnFilterGifted.setPadding(padHoriz, 0, padHoriz, 0)
            btnFilterGifted.iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START
            btnFilterGifted.iconPadding = (6 * density).toInt()
        }
        updateGiftedFilterButtonUI()
    }

    private fun updateActiveFilterBanner() {
        val hasGifted = isGiftedFilterActive
        val hasCategory = currentStoreCategory != "All"
        val hasSubTag = currentStoreSubTag.isNotBlank() && currentStoreSubTag != "All"
        val hasSearch = currentStoreSearchQuery.isNotBlank()

        if (hasGifted || hasCategory || hasSubTag || hasSearch) {
            layoutActiveFilterBanner.visibility = View.VISIBLE
            val count = filteredCatalogList.size
            val itemText = if (count == 1) "1 wallpaper" else "$count wallpapers"

            when {
                hasGifted && hasCategory -> {
                    ivActiveFilterIcon.setImageResource(R.drawable.ic_gift)
                    tvActiveFilterText.text = "Gifted • $currentStoreCategory ($itemText)"
                }
                hasGifted -> {
                    ivActiveFilterIcon.setImageResource(R.drawable.ic_gift)
                    tvActiveFilterText.text = "Gifted Wallpapers ($itemText)"
                }
                hasSearch -> {
                    ivActiveFilterIcon.setImageResource(R.drawable.ic_search)
                    tvActiveFilterText.text = "Search: \"$currentStoreSearchQuery\" ($itemText)"
                }
                hasCategory -> {
                    ivActiveFilterIcon.setImageResource(R.drawable.ic_filter)
                    val title = if (hasSubTag) "$currentStoreCategory • ${formatTagName(currentStoreSubTag)}" else currentStoreCategory
                    tvActiveFilterText.text = "$title ($itemText)"
                }
                else -> {
                    ivActiveFilterIcon.setImageResource(R.drawable.ic_filter)
                    tvActiveFilterText.text = "Filtered ($itemText)"
                }
            }
        } else {
            layoutActiveFilterBanner.visibility = View.GONE
        }
    }

    private fun showCategoryBottomSheetDialog() {
        val bottomSheet = BottomSheetDialog(this, R.style.Theme_VideoLockScreen_BottomSheetDialog)
        val sheetView = layoutInflater.inflate(R.layout.bottom_sheet_categories, null)
        bottomSheet.setContentView(sheetView)

        val btnClose = sheetView.findViewById<ImageButton>(R.id.btnCloseCategorySheet)
        val rvCategories = sheetView.findViewById<RecyclerView>(R.id.rvCategorySheetGrid)

        btnClose.setOnClickListener { bottomSheet.dismiss() }

        val distinctCategories = fullCatalogList.map { it.category }.filter { it.isNotBlank() }.distinct()
        val allCats = listOf("All") + distinctCategories

        rvCategories.layoutManager = LinearLayoutManager(this)
        rvCategories.adapter = object : RecyclerView.Adapter<CategorySheetViewHolder>() {
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CategorySheetViewHolder {
                val v = layoutInflater.inflate(R.layout.item_category_sheet_card, parent, false)
                return CategorySheetViewHolder(v)
            }

            override fun getItemCount(): Int = allCats.size

            override fun onBindViewHolder(holder: CategorySheetViewHolder, position: Int) {
                val cat = allCats[position]
                val count = if (cat == "All") fullCatalogList.size else fullCatalogList.count { it.category.equals(cat, ignoreCase = true) }

                // Get top 4 tags for this category
                val catItems = if (cat == "All") fullCatalogList else fullCatalogList.filter { it.category.equals(cat, ignoreCase = true) }
                val tagFreq = mutableMapOf<String, Int>()
                val skipTags = setOf("wallpaper", "video", "animated", "lockscreen", "screen", "live", "4k", "hd", "loop")
                catItems.take(150).forEach { item ->
                    item.tags.forEach { t ->
                        val clean = t.lowercase().trim()
                        if (clean.length > 2 && clean !in skipTags) {
                            tagFreq[clean] = (tagFreq[clean] ?: 0) + 1
                        }
                    }
                }
                val top4 = tagFreq.entries.sortedByDescending { it.value }.take(4).map { formatTagName(it.key) }
                val tagSummary = if (top4.isNotEmpty()) top4.joinToString(" • ") else "Popular 4K lock screen wallpapers"

                holder.bind(cat, count, tagSummary, cat.equals(currentStoreCategory, ignoreCase = true)) {
                    applyCategoryFilter(cat)
                    setupStoreCategoryChips()
                    bottomSheet.dismiss()
                }
            }
        }

        bottomSheet.show()
    }

    private fun loadNextStorePage() {
        if (isStoreLoadingMore) return
        val startIndex = currentStorePage * STORE_PAGE_SIZE
        if (startIndex >= filteredCatalogList.size) return

        isStoreLoadingMore = true
        pbStoreLoadMore.visibility = View.VISIBLE
        lifecycleScope.launch {
            kotlinx.coroutines.delay(100) // gentle debounce
            val nextBatch = filteredCatalogList.drop(startIndex).take(STORE_PAGE_SIZE)
            storeAdapter.appendList(nextBatch)
            currentStorePage++
            isStoreLoadingMore = false
            pbStoreLoadMore.visibility = View.GONE
        }
    }

    private fun downloadCloudWallpaper(item: WallpaperItem) {
        if (!authManager.isUserSignedIn) {
            showAuthRequiredDialog()
            return
        }
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
                val thumbFile = File(repository.downloadManager.cloudThumbsDir, "thumb_${item.id}.jpg")
                if (!thumbFile.exists() || thumbFile.length() == 0L) {
                    try {
                        if (item.thumbUrl.isNotBlank()) {
                            val client = OkHttpClient()
                            val req = Request.Builder().url(item.thumbUrl).build()
                            client.newCall(req).execute().use { resp ->
                                if (resp.isSuccessful) {
                                    val b = resp.body?.bytes()
                                    if (b != null && b.isNotEmpty()) {
                                        thumbFile.parentFile?.mkdirs()
                                        thumbFile.writeBytes(b)
                                    }
                                }
                            }
                        }
                    } catch (_: Exception) {}

                    if (!thumbFile.exists() || thumbFile.length() == 0L) {
                        try {
                            val mds = VaultSecurityManager.createMediaDataSource(downloadedFile)
                            val retriever = MediaMetadataRetriever()
                            try {
                                retriever.setDataSource(mds)
                                val bmp = retriever.getFrameAtTime(1_000_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                                    ?: retriever.frameAtTime
                                if (bmp != null) {
                                    thumbFile.parentFile?.mkdirs()
                                    FileOutputStream(thumbFile).use { out ->
                                        bmp.compress(Bitmap.CompressFormat.JPEG, 85, out)
                                    }
                                }
                            } finally {
                                try { retriever.release() } catch (_: Exception) {}
                                try { mds.close() } catch (_: Exception) {}
                            }
                        } catch (_: Exception) {}
                    }
                }

                val finalThumbPath = if (thumbFile.exists() && thumbFile.length() > 0) thumbFile.absolutePath else item.thumbPath
                val updated = item.copy(
                    filePath = downloadedFile.absolutePath,
                    thumbPath = finalThumbPath,
                    isDownloaded = true
                )
                fullCatalogList = fullCatalogList.map { if (it.id == updated.id) updated else it }
                filteredCatalogList = filteredCatalogList.map { if (it.id == updated.id) updated else it }
                storeAdapter.finishDownload(updated)
                refreshGallery()
                selectWallpaper(updated)
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
                    fullCatalogList = fullCatalogList.map { if (it.id == updated.id) updated else it }
                    filteredCatalogList = filteredCatalogList.map { if (it.id == updated.id) updated else it }
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

    private fun setupHeaderControls() {
        btnUserAuth.setOnClickListener {
            showUserAuthDialog()
        }

        btnNotificationCenter.setOnClickListener {
            showInAppNotificationsDialog()
        }
    }

    private fun loadProfileAvatar(
        photoUrl: String?,
        displayName: String?,
        imageView: ImageView,
        fallbackInitial: TextView
    ) {
        val initial = (displayName?.takeIf { it.isNotBlank() } ?: "U").first().uppercase()
        fallbackInitial.text = initial

        if (photoUrl.isNullOrBlank()) {
            imageView.visibility = View.GONE
            fallbackInitial.visibility = View.VISIBLE
            return
        }

        // Show fallback letter initially while image loads in background
        imageView.visibility = View.GONE
        fallbackInitial.visibility = View.VISIBLE

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val client = OkHttpClient()
                val request = Request.Builder().url(photoUrl).build()
                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val bytes = response.body?.bytes()
                        if (bytes != null && bytes.isNotEmpty()) {
                            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                            withContext(Dispatchers.Main) {
                                if (!isFinishing && !isDestroyed) {
                                    imageView.setImageBitmap(bitmap)
                                    imageView.visibility = View.VISIBLE
                                    fallbackInitial.visibility = View.GONE
                                }
                            }
                            return@launch
                        }
                    }
                }
            } catch (e: Exception) {
                // If offline or network issue, fallback initial remains visible
            }
            withContext(Dispatchers.Main) {
                if (!isFinishing && !isDestroyed && imageView.drawable == null) {
                    imageView.visibility = View.GONE
                    fallbackInitial.visibility = View.VISIBLE
                }
            }
        }
    }

    private fun showAccountProfileDialog() {
        val user = authManager.currentUser ?: return
        val dialog = BottomSheetDialog(this, R.style.Theme_VideoLockScreen_BottomSheetDialog)
        val view = layoutInflater.inflate(R.layout.dialog_account_profile, null)
        dialog.setContentView(view)

        val ivAvatar = view.findViewById<ImageView>(R.id.dialogIvUserAvatar)
        val tvInitial = view.findViewById<TextView>(R.id.dialogTvAvatarInitial)
        val tvName = view.findViewById<TextView>(R.id.dialogTvUserName)
        val tvEmail = view.findViewById<TextView>(R.id.dialogTvUserEmail)
        val tvAssigned = view.findViewById<TextView>(R.id.dialogTvAssignedCount)
        val btnClose = view.findViewById<View>(R.id.btnCloseProfileDialog)
        val btnSwitch = view.findViewById<MaterialButton>(R.id.dialogBtnSwitchAccount)
        val btnSignOut = view.findViewById<MaterialButton>(R.id.dialogBtnSignOut)

        val name = user.displayName ?: "User"
        val email = user.email ?: ""
        tvName.text = name
        tvEmail.text = email

        loadProfileAvatar(user.photoUrl?.toString(), name, ivAvatar, tvInitial)

        val assignedCount = prefs.getStringSet("assigned_wallpaper_ids", emptySet())?.size ?: 0
        tvAssigned.text = if (assignedCount > 0) "$assignedCount Assigned" else "0 Assigned (Public Catalog)"

        btnClose.setOnClickListener {
            dialog.dismiss()
        }

        btnSwitch.setOnClickListener {
            dialog.dismiss()
            val start = System.currentTimeMillis()
            VaultSecurityManager.performSelectiveLogoutPurge(this@MainActivity)
            authManager.signOut {
                analytics.linkUserToDevice("", "")
                analytics.logAction("switch_account_clicked", "SUCCESS", System.currentTimeMillis() - start, emptyMap())
                analytics.flushSync()
                runOnUiThread {
                    updateUserAuthUI()
                    refreshGallery()
                    loadStoreCatalog()
                    try {
                        showAuthLoading("Opening Google Sign-In...")
                        googleSignInLauncher.launch(authManager.getSignInIntent())
                    } catch (e: Exception) {
                        hideAuthLoading()
                        showAuthErrorDialog("Error", "Could not launch Google Sign-In: ${e.localizedMessage}")
                    }
                }
            }
        }

        btnSignOut.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Sign Out")
                .setMessage("Are you sure you want to sign out of your Google account?")
                .setPositiveButton("Sign Out") { _, _ ->
                    dialog.dismiss()
                    val start = System.currentTimeMillis()
                    VaultSecurityManager.performSelectiveLogoutPurge(this@MainActivity)
                    authManager.signOut {
                        analytics.linkUserToDevice("", "")
                        analytics.logAction("sign_out_clicked", "SUCCESS", System.currentTimeMillis() - start, emptyMap())
                        analytics.flushSync()
                        runOnUiThread {
                            updateUserAuthUI()
                            refreshGallery()
                            loadStoreCatalog()
                            Toast.makeText(this, "Signed out. Active wallpaper preserved.", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        dialog.show()
    }

    private fun showUserAuthDialog() {
        val user = authManager.currentUser
        if (user != null) {
            showAccountProfileDialog()
        } else {
            val start = System.currentTimeMillis()
            try {
                showAuthLoading("Opening Google Sign-In...")
                analytics.logAction("google_signin_button_clicked", "SUCCESS", System.currentTimeMillis() - start, emptyMap())
                googleSignInLauncher.launch(authManager.getSignInIntent())
            } catch (e: Exception) {
                hideAuthLoading()
                analytics.logAction("google_signin_button_clicked", "FAILED", System.currentTimeMillis() - start, emptyMap(), e)
                showAuthErrorDialog("Error", "Could not open Google Sign-In: ${e.localizedMessage}")
            }
        }
    }

    private fun updateUserAuthUI() {
        val user = authManager.currentUser
        if (user != null) {
            val displayName = user.displayName ?: user.email?.substringBefore("@") ?: "User"
            val email = user.email ?: ""
            val firstName = displayName.split(" ").firstOrNull() ?: displayName

            // Header Pill Button
            tvUserAuthText.text = firstName
            ivUserAuthIcon.visibility = View.GONE
            loadProfileAvatar(user.photoUrl?.toString(), displayName, ivUserAuthPhoto, tvUserAuthInitial)

            analytics.linkUserToDevice(
                user.uid,
                email,
                displayName,
                user.photoUrl?.toString() ?: ""
            )
            // Listen to real-time wallpaper assignments from admin
            authManager.listenToAssignedWallpapers { assignedList ->
                prefs.edit().putStringSet("assigned_wallpaper_ids", assignedList.toSet()).apply()
                runOnUiThread {
                    loadStoreCatalog()
                    syncInAppNotifications()
                }
            }
        } else {
            // Header Pill Button
            tvUserAuthText.text = "Sign In"
            ivUserAuthIcon.visibility = View.VISIBLE
            ivUserAuthPhoto.visibility = View.GONE
            tvUserAuthInitial.visibility = View.GONE
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
        val bellStart = System.currentTimeMillis()
        val count = inAppNotificationManager.getUnreadCount()
        val totalCount = inAppNotificationManager.getNotifications().size
        analytics.logAction("notification_modal_opened", "SUCCESS", System.currentTimeMillis() - bellStart, mapOf(
            "unreadCount" to count,
            "totalCount" to totalCount,
            "action" to "opened"
        ))
        analytics.flushSync()

        val dialog = BottomSheetDialog(this, R.style.Theme_VideoLockScreen_BottomSheetDialog)
        val view = layoutInflater.inflate(R.layout.dialog_inapp_notifications, null)
        dialog.setContentView(view)

        val rv = view.findViewById<RecyclerView>(R.id.rvInAppNotifs)
        val tvEmpty = view.findViewById<TextView>(R.id.tvEmptyNotifs)
        val btnMarkAll = view.findViewById<TextView>(R.id.btnMarkAllRead)

        var notifAdapter: InAppNotificationAdapter? = null

        fun renderList() {
            val list = inAppNotificationManager.getNotifications()
            if (list.isEmpty()) {
                tvEmpty.visibility = View.VISIBLE
                rv.visibility = View.GONE
            } else {
                tvEmpty.visibility = View.GONE
                rv.visibility = View.VISIBLE
                if (notifAdapter == null) {
                    rv.layoutManager = LinearLayoutManager(this)
                    notifAdapter = InAppNotificationAdapter(list) { notif ->
                        val clickStart = System.currentTimeMillis()
                        // 1. Mark this specific notification as read in persistent storage
                        inAppNotificationManager.markAsRead(notif.id)
                        notif.isRead = true

                        // 2. Immediately update unread badge on top bar
                        updateNotificationBadge()

                        // 3. Log real-time analytics event for Admin Dashboard
                        analytics.logAction("notification_item_clicked", "SUCCESS", System.currentTimeMillis() - clickStart, mapOf(
                            "notifId" to notif.id,
                            "title" to notif.title,
                            "wallpaperId" to (notif.wallpaperId ?: ""),
                            "target" to notif.title
                        ))
                        analytics.flushSync()

                        dialog.dismiss()
                        navigateToWallpaper(notif.wallpaperId)
                    }
                    rv.adapter = notifAdapter
                } else {
                    notifAdapter?.updateNotifications(list)
                }
            }
        }

        renderList()

        // Background sync to ensure freshest notifications from server
        val user = authManager.currentUser
        lifecycleScope.launch {
            try {
                val newCount = inAppNotificationManager.syncWithServer(
                    userEmail = user?.email,
                    userId = user?.uid,
                    showSystemAlertOnNew = false
                )
                if (newCount > 0 && dialog.isShowing) {
                    withContext(Dispatchers.Main) {
                        renderList()
                        updateNotificationBadge()
                    }
                }
            } catch (_: Exception) {}
        }

        btnMarkAll?.setOnClickListener {
            inAppNotificationManager.markAllAsRead()
            updateNotificationBadge()
            renderList()
            analytics.logAction("notification_mark_all_read", "SUCCESS", 0)
            analytics.flushSync()
        }

        updateNotificationBadge()
        dialog.show()
    }

    private fun navigateToWallpaper(wallpaperId: String?) {
        if (wallpaperId.isNullOrBlank()) {
            switchToStoreTab()
            return
        }

        // 1. Check local wallpapers first (presets and user-downloaded)
        val allLocal = repository.getAllWallpapers()
        val localItem = allLocal.find {
            it.id.equals(wallpaperId, ignoreCase = true) ||
            it.title.equals(wallpaperId, ignoreCase = true) ||
            it.id.contains(wallpaperId, ignoreCase = true)
        }

        if (localItem != null) {
            switchToMyWallpapersTab()
            selectWallpaper(localItem)
            val pos = allLocal.indexOfFirst { it.id == localItem.id }
            if (pos >= 0) {
                rvWallpapers.smoothScrollToPosition(pos)
            }
            showGlassToast("Playing '${localItem.title}'", isSuccess = true)
            return
        }

        // 2. Otherwise navigate to Explore Store
        switchToStoreTab()
        lifecycleScope.launch {
            // Wait for cloud catalog to finish loading if currently empty or skeleton is visible
            var attempts = 0
            while ((fullCatalogList.isEmpty() || layoutStoreSkeleton.visibility == View.VISIBLE) && attempts < 30) {
                kotlinx.coroutines.delay(150)
                attempts++
            }

            // Find matching item in full catalog
            val targetItem = fullCatalogList.find {
                it.id.equals(wallpaperId, ignoreCase = true) ||
                it.title.contains(wallpaperId, ignoreCase = true) ||
                it.id.contains(wallpaperId, ignoreCase = true)
            }

            if (targetItem != null) {
                withContext(Dispatchers.Main) {
                    // Reset any filters that might hide the target item
                    if (isGiftedFilterActive || currentStoreCategory != "All" || currentStoreSearchQuery.isNotBlank() || currentStoreSubTag != "All") {
                        isGiftedFilterActive = false
                        currentStoreCategory = "All"
                        currentStoreSubTag = "All"
                        currentStoreSearchQuery = ""
                        etStoreSearch.setText("")
                        btnClearStoreSearch.visibility = View.GONE
                        recomputeFilteredCatalog()
                        updateActiveFilterBanner()
                    }

                    val targetIndex = filteredCatalogList.indexOfFirst { it.id == targetItem.id }
                    if (targetIndex >= 0) {
                        // Ensure adapter contains items up to this index
                        val neededCount = maxOf(STORE_PAGE_SIZE, targetIndex + 5)
                        if (neededCount > currentStorePage * STORE_PAGE_SIZE) {
                            val batch = filteredCatalogList.take(neededCount)
                            storeAdapter.submitInitialList(batch)
                            currentStorePage = (neededCount / STORE_PAGE_SIZE) + 1
                        }

                        // Prominently highlight this card and dim other store cards in 9-second focus mode
                        storeAdapter.setNotificationDropFocus(targetItem.id)

                        layoutNotificationFocusBanner.visibility = View.VISIBLE
                        tvNotificationFocusText.text = "✨ Viewing Notification Drop: ${targetItem.title}"

                        // Auto-revert dimming after 9 seconds so user can freely move on
                        notificationFocusJob?.cancel()
                        notificationFocusJob = lifecycleScope.launch {
                            kotlinx.coroutines.delay(9000)
                            withContext(Dispatchers.Main) {
                                storeAdapter.clearFocusMode()
                                layoutNotificationFocusBanner.visibility = View.GONE
                            }
                        }

                        // Immediately begin live video streaming in the preview mockup
                        playStreamingVideoInPreview(targetItem)

                        // Smoothly scroll NestedScrollView directly down to the wallpaper card
                        nestedScrollViewMain.postDelayed({
                            val lm = rvStoreWallpapers.layoutManager
                            val child = lm?.findViewByPosition(targetIndex)
                            if (child != null) {
                                val locChild = IntArray(2)
                                val locScroll = IntArray(2)
                                child.getLocationInWindow(locChild)
                                nestedScrollViewMain.getLocationInWindow(locScroll)
                                val targetY = nestedScrollViewMain.scrollY + (locChild[1] - locScroll[1]) - 160
                                nestedScrollViewMain.smoothScrollTo(0, maxOf(0, targetY))

                                // Visual pulse highlight on the card
                                child.alpha = 0.35f
                                child.animate().alpha(1.0f).setDuration(600).start()
                            } else {
                                // Fallback estimation: store section top + card offset
                                val approxCardHeight = (280 * resources.displayMetrics.density).toInt()
                                val targetY = layoutExploreStore.top + (targetIndex * approxCardHeight)
                                nestedScrollViewMain.smoothScrollTo(0, targetY)
                            }
                            showGlassToast("Previewing '${targetItem.title}'", isSuccess = true)
                        }, 350)
                    }
                }
                return@launch
            }

            // Fallback: if not found, scroll to top of Explore Store
            withContext(Dispatchers.Main) {
                nestedScrollViewMain.smoothScrollTo(0, layoutExploreStore.top)
            }
        }
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
            tvMockNotificationText?.text = "${item.title} • Lock Screen Active"
            showGlassToast("Selected '${item.title}' • Tap 'Set wallpaper' below", iconRes = R.drawable.ic_clock)
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
                val start = System.currentTimeMillis()
                repository.deleteWallpaper(item)

                // Sync state with Explore Store so it shows the Download icon again
                val cloudId = item.cloudId.ifBlank { item.id.removePrefix("cloud_") }
                val updatedStoreItem = item.copy(id = cloudId, filePath = "", isDownloaded = false)
                storeAdapter.updateItem(updatedStoreItem)

                refreshGallery()
                if (selectedWallpaperItem?.id == item.id) {
                    setApplyButtonState(isSelected = false, item = null)
                }
                val current = repository.getActiveWallpaper()
                playActiveVideoInPreview(current)

                val duration = System.currentTimeMillis() - start
                analytics.logAction("delete_wallpaper", "SUCCESS", duration, mapOf(
                    "id" to item.id,
                    "title" to item.title,
                    "isCloud" to item.isCloud
                ))
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
            showGlassToast("Applying '${activeItem.title}' • Choose 'Home and lock screen'", iconRes = R.drawable.ic_clock)
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
            val start = System.currentTimeMillis()
            val activeItem = repository.getActiveWallpaper()
            try {
                val uri = getActiveVideoUri(activeItem)
                if (uri != null) {
                    launchGalleryVideo(uri)
                    val duration = System.currentTimeMillis() - start
                    analytics.logAction("direct_gallery_handoff_click", "SUCCESS", duration, mapOf("title" to activeItem.title))
                } else {
                    val duration = System.currentTimeMillis() - start
                    analytics.logAction("direct_gallery_handoff_click", "FAILED", duration, mapOf("reason" to "video_uri_null"))
                    Toast.makeText(this, "Could not prepare video file", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                val duration = System.currentTimeMillis() - start
                analytics.logAction("direct_gallery_handoff_click", "FAILED", duration, mapOf("title" to activeItem.title), e)
            }
        }

        btnClearLockOverride.setOnClickListener {
            val start = System.currentTimeMillis()
            try {
                clearLockScreenOverrideSilently()
                openLiveWallpaperChooser()
                val duration = System.currentTimeMillis() - start
                analytics.logAction("clear_lock_override_click", "SUCCESS", duration, emptyMap())
                Toast.makeText(this, "Cleared lock photo override! Select 'Home screen and lock screen' in preview", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                val duration = System.currentTimeMillis() - start
                analytics.logAction("clear_lock_override_click", "FAILED", duration, emptyMap(), e)
            }
        }

        btnExportVideo.setOnClickListener {
            val start = System.currentTimeMillis()
            try {
                promptSaveVideoLocation()
                val duration = System.currentTimeMillis() - start
                analytics.logAction("export_video_click", "SUCCESS", duration, emptyMap())
            } catch (e: Exception) {
                val duration = System.currentTimeMillis() - start
                analytics.logAction("export_video_click", "FAILED", duration, emptyMap(), e)
            }
        }

        rgPlaybackMode.setOnCheckedChangeListener { _, checkedId ->
            val start = System.currentTimeMillis()
            try {
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
                val duration = System.currentTimeMillis() - start
                analytics.logAction("change_playback_mode", "SUCCESS", duration, mapOf("mode" to mode, "isLoop" to isLoop))

                val msg = when (mode) {
                    VideoWallpaperService.MODE_LOOP_LOCK_ONLY -> "Mode: Loop on Lock Screen (Pauses on Home Screen - 100% Smooth)"
                    VideoWallpaperService.MODE_LOOP_ALWAYS -> "Mode: Loop Always (Lock & Home Screen)"
                    else -> "Mode: Wake Animation (Freeze on End Frame - 0% Battery)"
                }
                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                val duration = System.currentTimeMillis() - start
                analytics.logAction("change_playback_mode", "FAILED", duration, emptyMap(), e)
            }
        }

        switchMute.setOnCheckedChangeListener { _, isChecked ->
            val start = System.currentTimeMillis()
            try {
                prefs.edit().putBoolean(VideoWallpaperService.KEY_MUTE, isChecked).apply()
                previewPlayer?.let {
                    val vol = if (isChecked) 0f else 1f
                    it.setVolume(vol, vol)
                }
                val duration = System.currentTimeMillis() - start
                analytics.logAction("toggle_audio_mute", "SUCCESS", duration, mapOf("isMuted" to isChecked))
            } catch (e: Exception) {
                val duration = System.currentTimeMillis() - start
                analytics.logAction("toggle_audio_mute", "FAILED", duration, mapOf("isMuted" to isChecked), e)
            }
        }

        etStoreSearch.setOnFocusChangeListener { _, hasFocus ->
            updateCommandBarState(hasFocus)
            val q = etStoreSearch.text?.toString()?.trim() ?: ""
            btnClearStoreSearch.visibility = if (hasFocus && q.isNotEmpty()) View.VISIBLE else View.GONE
        }

        etStoreSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val q = s?.toString()?.trim() ?: ""
                btnClearStoreSearch.visibility = if (q.isNotEmpty()) View.VISIBLE else View.GONE
                searchJob?.cancel()
                searchJob = lifecycleScope.launch {
                    delay(200)
                    currentStoreSearchQuery = q
                    recomputeFilteredCatalog()
                }
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        btnClearStoreSearch.setOnClickListener {
            etStoreSearch.text.clear()
            currentStoreSearchQuery = ""
            btnClearStoreSearch.visibility = View.GONE
            etStoreSearch.clearFocus()
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.hideSoftInputFromWindow(etStoreSearch.windowToken, 0)
            updateCommandBarState(false)
            recomputeFilteredCatalog()
        }

        btnFilterGifted.setOnClickListener {
            isGiftedFilterActive = !isGiftedFilterActive
            updateGiftedFilterButtonUI()
            recomputeFilteredCatalog()
        }

        btnStoreFilterCategories.setOnClickListener {
            showCategoryBottomSheetDialog()
        }

        btnResetActiveFilter.setOnClickListener {
            isGiftedFilterActive = false
            currentStoreCategory = "All"
            currentStoreSubTag = "All"
            currentStoreSearchQuery = ""
            etStoreSearch.text.clear()
            updateGiftedFilterButtonUI()
            recomputeFilteredCatalog()
        }

        btnResetStoreFilters.setOnClickListener {
            isGiftedFilterActive = false
            currentStoreCategory = "All"
            currentStoreSubTag = "All"
            currentStoreSearchQuery = ""
            etStoreSearch.text.clear()
            updateGiftedFilterButtonUI()
            recomputeFilteredCatalog()
        }
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.action == MotionEvent.ACTION_DOWN) {
            val v = currentFocus
            if (v is EditText && v.id == R.id.etStoreSearch) {
                val outRect = Rect()
                layoutStoreSearchBar.getGlobalVisibleRect(outRect)
                if (!outRect.contains(ev.rawX.toInt(), ev.rawY.toInt())) {
                    v.clearFocus()
                    val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                    imm?.hideSoftInputFromWindow(v.windowToken, 0)
                    updateCommandBarState(false)
                }
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    inner class CategorySheetViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val card: com.google.android.material.card.MaterialCardView = itemView.findViewById(R.id.cardCategoryItem)
        private val tvTitle: TextView = itemView.findViewById(R.id.tvSheetCategoryTitle)
        private val tvCount: TextView = itemView.findViewById(R.id.tvSheetCategoryCount)
        private val tvTags: TextView = itemView.findViewById(R.id.tvSheetCategoryTags)

        fun bind(title: String, count: Int, tags: String, isSelected: Boolean, onClick: () -> Unit) {
            tvTitle.text = title
            tvCount.text = "$count"
            tvTags.text = tags

            if (isSelected) {
                card.strokeColor = Color.parseColor("#D4AF37")
                card.strokeWidth = dpToPx(2f)
                card.setCardBackgroundColor(Color.parseColor("#2A1E3E"))
            } else {
                card.strokeColor = Color.parseColor("#382C4E")
                card.strokeWidth = dpToPx(1f)
                card.setCardBackgroundColor(Color.parseColor("#1F172E"))
            }

            card.setOnClickListener { onClick() }
        }
    }

    private fun dpToPx(dp: Float): Int {
        return (dp * resources.displayMetrics.density).toInt()
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
                        if (VaultSecurityManager.isScrambled(f)) {
                            val tempExport = File(cacheDir, "temp_export_${f.name}")
                            VaultSecurityManager.descrambleToFile(f, tempExport)
                            FileInputStream(tempExport).use { input ->
                                input.copyTo(output)
                            }
                            tempExport.delete()
                        } else {
                            FileInputStream(f).use { input ->
                                input.copyTo(output)
                            }
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
                        if (VaultSecurityManager.isScrambled(file)) {
                            val retrieverMds = VaultSecurityManager.createMediaDataSource(file)
                            val retriever = MediaMetadataRetriever()
                            try {
                                retriever.setDataSource(retrieverMds)
                                rotation = retriever.extractMetadata(
                                    MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION
                                )?.toIntOrNull() ?: 0
                            } catch (_: Exception) {} finally {
                                try { retriever.release() } catch (_: Exception) {}
                                try { retrieverMds.close() } catch (_: Exception) {}
                            }

                            val playerMds = VaultSecurityManager.createMediaDataSource(file)
                            setDataSource(playerMds)
                        } else {
                            val retriever = MediaMetadataRetriever()
                            try {
                                retriever.setDataSource(file.absolutePath)
                                rotation = retriever.extractMetadata(
                                    MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION
                                )?.toIntOrNull() ?: 0
                            } catch (_: Exception) {} finally {
                                try { retriever.release() } catch (_: Exception) {}
                            }
                            setDataSource(file.absolutePath)
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

        val isVip = VaultSecurityManager.getUserTier(this) == "VIP"
        if (!isVip && !previewCooldownManager.consumePreview()) {
            val status = previewCooldownManager.getStatus()
            val minutesLeft = (status.cooldownRemainingMs / 60000L) + 1
            showPreviewCooldownDialog(minutesLeft)
            return
        }

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

    private fun showPreviewCooldownDialog(minutesLeft: Long) {
        AlertDialog.Builder(this)
            .setTitle("Preview Cooldown Active ⏳")
            .setMessage("You have used your preview credits for this session.\n\n5 fresh previews will unlock automatically in ~$minutesLeft minutes. You'll receive a notification as soon as they're ready!")
            .setPositiveButton("Got It", null)
            .show()
    }

    private fun showDeviceLockedToAuthorizedEmailDialog(boundEmail: String) {
        if (isFinishing || isDestroyed) return
        try {
            val view = layoutInflater.inflate(R.layout.dialog_device_locked, null)
            val dialog = AlertDialog.Builder(this)
                .setView(view)
                .setCancelable(true)
                .create()

            val tvAuthorizedEmail = view.findViewById<TextView>(R.id.tvAuthorizedEmail)
            val btnSignInWithAuthorized = view.findViewById<View>(R.id.btnSignInWithAuthorized)
            val btnClose = view.findViewById<View>(R.id.btnCloseDeviceLock)

            tvAuthorizedEmail.text = boundEmail

            btnSignInWithAuthorized.setOnClickListener {
                dialog.dismiss()
                try {
                    showAuthLoading("Connecting to Google...")
                    googleSignInLauncher.launch(authManager.getSignInIntent())
                } catch (e: Exception) {
                    hideAuthLoading()
                    showAuthErrorDialog("Error", "Could not open Google Sign-In: ${e.localizedMessage}")
                }
            }

            btnClose.setOnClickListener {
                dialog.dismiss()
            }

            dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
            dialog.window?.setGravity(Gravity.CENTER)
            dialog.show()
            dialog.window?.setLayout(
                (resources.displayMetrics.density * 340).toInt(),
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        } catch (e: Exception) {
            Log.e("MainActivity", "Failed to show device locked dialog", e)
            showAuthErrorDialog("Device Locked", "Please use $boundEmail to log in on this device.")
        }
    }

    private fun showBannedDeviceDialog(reason: String, userEmail: String) {
        AlertDialog.Builder(this)
            .setTitle("Access Restricted")
            .setMessage("This device has been restricted by the administrator.\n\nReason: $reason\n\nIf you believe this is an error, you can submit an appeal by sending an email from your registered Google account.")
            .setCancelable(false)
            .setPositiveButton("Contact Support") { _, _ ->
                val intent = Intent(Intent.ACTION_SENDTO).apply {
                    data = Uri.parse("mailto:vividwake.support@gmail.com")
                    putExtra(Intent.EXTRA_SUBJECT, "VividWake Account Appeal")
                    putExtra(Intent.EXTRA_TEXT, "Hello Support Team,\n\nI am requesting a review of the restriction on my account.\n\nLinked Email: $userEmail\n\nThank you.")
                }
                try {
                    startActivity(intent)
                } catch (_: Exception) {
                    Toast.makeText(this, "Please email vividwake.support@gmail.com from your registered address", Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun showTransferRequestDialog(userEmail: String, boundDevice: String, transferStatus: String) {
        if (transferStatus == "PENDING") {
            AlertDialog.Builder(this)
                .setTitle("Transfer Request Pending ⏳")
                .setMessage("A device transfer request for $userEmail is already submitted and awaiting administrator approval.\n\nOnce approved, you will be able to sign in on this phone.")
                .setPositiveButton("OK", null)
                .setNeutralButton("Contact Support") { _, _ ->
                    val intent = Intent(Intent.ACTION_SENDTO).apply {
                        data = Uri.parse("mailto:vividwake.support@gmail.com")
                        putExtra(Intent.EXTRA_SUBJECT, "VividWake Transfer Status")
                        putExtra(Intent.EXTRA_TEXT, "Hello Support Team,\n\nI submitted a device transfer request for my account ($userEmail). Please review when possible.\n\nThank you.")
                    }
                    try { startActivity(intent) } catch (_: Exception) {}
                }
                .show()
            return
        }

        AlertDialog.Builder(this)
            .setTitle("Device Transfer Required 🔒")
            .setMessage("Your Google account is currently registered on another device ($boundDevice).\n\nUnder our 1-to-1 device security policy, your account can only be active on one phone at a time.\n\nWould you like to request an authorized transfer to this phone?")
            .setPositiveButton("Request Transfer") { _, _ ->
                lifecycleScope.launch {
                    showAuthLoading("Submitting transfer request...")
                    val result = deviceLockManager.requestRebind(userEmail, "User switched to new phone")
                    hideAuthLoading()
                    if (result.isSuccess) {
                        AlertDialog.Builder(this@MainActivity)
                            .setTitle("Request Submitted")
                            .setMessage("Your transfer request has been submitted to the administrator. You will be able to log in once approved.")
                            .setPositiveButton("OK", null)
                            .show()
                    } else {
                        Toast.makeText(this@MainActivity, "Failed: ${result.exceptionOrNull()?.localizedMessage}", Toast.LENGTH_LONG).show()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun stopPreviewPlayback() {
        val player = previewPlayer ?: return
        previewPlayer = null
        try {
            player.setOnPreparedListener(null)
            player.setOnVideoSizeChangedListener(null)
            player.setOnErrorListener(null)
            player.reset()
            player.release()
        } catch (e: Exception) {
            Log.w("MainActivity", "Error stopping preview playback", e)
        }
    }

    private fun showAuthRequiredDialog() {
        AlertDialog.Builder(this)
            .setTitle("Account Required 🔒")
            .setMessage("Sign in with your Google account to download 4K live wallpapers and bind your device security license.")
            .setPositiveButton("Sign In") { _, _ ->
                try {
                    showAuthLoading("Opening Google Sign-In...")
                    googleSignInLauncher.launch(authManager.getSignInIntent())
                } catch (e: Exception) {
                    hideAuthLoading()
                    showAuthErrorDialog("Error", "Could not open Google Sign-In: ${e.localizedMessage}")
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun checkVersionGate() {
        val gateManager = VersionGateManager(this)
        lifecycleScope.launch {
            val result = gateManager.checkVersion() ?: return@launch
            if (result.isForceUpdateRequired) {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle(result.title)
                    .setMessage(result.message)
                    .setCancelable(false)
                    .setPositiveButton("Update Now") { _, _ ->
                        try {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(result.downloadUrl))
                            startActivity(intent)
                        } catch (_: Exception) {}
                        finish()
                    }
                    .show()
            } else if (result.isOptionalUpdateAvailable && !isOptionalUpdatePrompted) {
                isOptionalUpdatePrompted = true
                AlertDialog.Builder(this@MainActivity)
                    .setTitle(result.title)
                    .setMessage(result.message)
                    .setPositiveButton("Update") { _, _ ->
                        try {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(result.downloadUrl))
                            startActivity(intent)
                        } catch (_: Exception) {}
                    }
                    .setNegativeButton("Later", null)
                    .show()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        updateNotificationBadge()
        syncInAppNotifications()
        updateUserAuthUI()
        checkVersionGate()

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
