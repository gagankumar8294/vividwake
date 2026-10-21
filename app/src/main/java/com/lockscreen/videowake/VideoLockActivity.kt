package com.lockscreen.videowake

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.graphics.drawable.ColorDrawable
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

class VideoLockActivity : AppCompatActivity(), TextureView.SurfaceTextureListener {

    private lateinit var ivPreview: ImageView
    private lateinit var textureView: TextureView
    private lateinit var tvUnlockHint: TextView

    private var mediaPlayer: MediaPlayer? = null
    private var surface: Surface? = null
    private var lockReceiver: BroadcastReceiver? = null
    private var startY = 0f

    companion object {
        @Volatile
        var isCoverVisible: Boolean = false
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        overridePendingTransition(0, 0)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        window.setBackgroundDrawable(ColorDrawable(Color.parseColor("#0E0B16")))
        WindowCompat.setDecorFitsSystemWindows(window, false)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // Prevent dismiss via back
            }
        })

        setContentView(R.layout.activity_video_lock)

        ivPreview = findViewById(R.id.ivFirstFramePreview)
        textureView = findViewById(R.id.videoLockTextureView)
        tvUnlockHint = findViewById(R.id.tvUnlockHint)

        textureView.surfaceTextureListener = this

        hideSystemBars()
        registerLockReceivers()
    }

    override fun onSurfaceTextureAvailable(surfaceTexture: SurfaceTexture, width: Int, height: Int) {
        surface = Surface(surfaceTexture)
        adjustTextureViewSize(width, height)
        startVideoPlayback()
    }

    override fun onSurfaceTextureSizeChanged(surfaceTexture: SurfaceTexture, width: Int, height: Int) {
        adjustTextureViewSize(width, height)
    }

    override fun onSurfaceTextureDestroyed(surfaceTexture: SurfaceTexture): Boolean {
        stopVideoPlayback()
        surface?.release()
        surface = null
        return true
    }

    override fun onSurfaceTextureUpdated(surfaceTexture: SurfaceTexture) {}

    private fun adjustTextureViewSize(viewW: Int, viewH: Int) {
        if (viewW == 0 || viewH == 0) return
        val videoW = 720f
        val videoH = 1280f

        val viewRatio = viewW.toFloat() / viewH
        val videoRatio = videoW / videoH

        val matrix = Matrix()
        if (viewRatio < videoRatio) {
            // Screen is taller than video -> crop left and right
            val scaleX = (viewH * videoRatio) / viewW
            matrix.setScale(scaleX, 1.0f, viewW / 2f, viewH / 2f)
        } else {
            val scaleY = (viewW / videoRatio) / viewH
            matrix.setScale(1.0f, scaleY, viewW / 2f, viewH / 2f)
        }
        textureView.setTransform(matrix)
    }

    private fun startVideoPlayback() {
        if (surface == null) return
        try {
            stopVideoPlayback()
            val prefs = getSharedPreferences(VideoWallpaperService.PREFS_NAME, Context.MODE_PRIVATE)
            val isLoop = prefs.getBoolean(VideoWallpaperService.KEY_LOOP, false)
            val isMute = prefs.getBoolean(VideoWallpaperService.KEY_MUTE, true)

            mediaPlayer = MediaPlayer().apply {
                setSurface(this@VideoLockActivity.surface)
                val afd = resources.openRawResourceFd(R.raw.lock_wake_video)
                setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                afd.close()

                val vol = if (isMute) 0f else 1f
                setVolume(vol, vol)
                isLooping = isLoop

                setOnPreparedListener { mp ->
                    mp.start()
                }

                setOnInfoListener { _, what, _ ->
                    if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) {
                        ivPreview.animate()
                            .alpha(0f)
                            .setDuration(150)
                            .withEndAction { ivPreview.visibility = View.GONE }
                    }
                    false
                }

                setOnCompletionListener {
                    // Holds the last frame statically
                }

                prepareAsync()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            ivPreview.visibility = View.VISIBLE
            ivPreview.setImageResource(R.drawable.last_frame)
        }
    }

    private fun stopVideoPlayback() {
        try {
            mediaPlayer?.let {
                if (it.isPlaying) {
                    it.stop()
                }
                it.release()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            mediaPlayer = null
        }
    }

    private fun registerLockReceivers() {
        if (lockReceiver != null) return
        lockReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    Intent.ACTION_USER_PRESENT -> goToHomeScreenAndFinish()
                    Intent.ACTION_SCREEN_OFF -> finish()
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        ContextCompat.registerReceiver(
            this,
            lockReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onResume() {
        super.onResume()
        isCoverVisible = true
        hideSystemBars()
        if (surface != null && (mediaPlayer == null || !mediaPlayer!!.isPlaying)) {
            ivPreview.alpha = 1f
            ivPreview.visibility = View.VISIBLE
            startVideoPlayback()
        }
    }

    override fun onPause() {
        isCoverVisible = false
        super.onPause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            hideSystemBars()
        }
    }

    private fun hideSystemBars() {
        try {
            WindowCompat.setDecorFitsSystemWindows(window, false)
            WindowInsetsControllerCompat(window, window.decorView).let { controller ->
                controller.hide(WindowInsetsCompat.Type.systemBars())
                controller.systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                startY = event.y
                return true
            }
            MotionEvent.ACTION_UP -> {
                val deltaY = startY - event.y
                if (deltaY > 50 || Math.abs(deltaY) < 30) { // Swipe up or tap
                    unlockDevice()
                }
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun unlockDevice() {
        val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            keyguardManager.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() {
                    goToHomeScreenAndFinish()
                }

                override fun onDismissCancelled() {
                    // Stay on lockscreen
                }

                override fun onDismissError() {
                    if (!keyguardManager.isKeyguardLocked) {
                        goToHomeScreenAndFinish()
                    }
                }
            })
        } else {
            goToHomeScreenAndFinish()
        }
    }

    private fun goToHomeScreenAndFinish() {
        try {
            val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            startActivity(homeIntent)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        finish()
    }

    override fun finish() {
        stopVideoPlayback()
        super.finish()
        overridePendingTransition(0, 0)
    }

    override fun onDestroy() {
        stopVideoPlayback()
        lockReceiver?.let {
            try {
                unregisterReceiver(it)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        super.onDestroy()
    }
}
