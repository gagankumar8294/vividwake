package com.lockscreen.videowake

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaPlayer
import android.service.wallpaper.WallpaperService
import android.util.Log
import android.view.SurfaceHolder

class VideoWallpaperService : WallpaperService() {

    companion object {
        private const val TAG = "VideoWallpaperService"
        const val PREFS_NAME = "video_lock_prefs"
        const val KEY_LOOP = "pref_loop"
        const val KEY_MUTE = "pref_mute"
    }

    override fun onCreateEngine(): Engine = VideoEngine()

    inner class VideoEngine : Engine(), SharedPreferences.OnSharedPreferenceChangeListener {

        private var mediaPlayer: MediaPlayer? = null
        private var isVisibleState = false
        private var isPrepared = false
        private var surfaceWidth = 0
        private var surfaceHeight = 0
        private lateinit var prefs: SharedPreferences

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.registerOnSharedPreferenceChangeListener(this)
        }

        override fun onSurfaceCreated(holder: SurfaceHolder) {
            super.onSurfaceCreated(holder)
            Log.d(TAG, "onSurfaceCreated")
            initMediaPlayer(holder)
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            surfaceWidth = width
            surfaceHeight = height
            Log.d(TAG, "onSurfaceChanged: ${width}x${height}")

            // Adjust surface aspect ratio to center-crop if needed
            if (width > 0 && height > 0) {
                val videoRatio = 720f / 1280f
                val screenRatio = width.toFloat() / height.toFloat()
                if (screenRatio < videoRatio) {
                    // Screen is taller than video -> widen surface to preserve aspect ratio
                    val targetWidth = (height * videoRatio).toInt()
                    holder.setFixedSize(targetWidth, height)
                } else {
                    val targetHeight = (width / videoRatio).toInt()
                    holder.setFixedSize(width, targetHeight)
                }
            }

            if (mediaPlayer == null) {
                initMediaPlayer(holder)
            }
        }

        private fun initMediaPlayer(holder: SurfaceHolder) {
            try {
                releaseMediaPlayer()
                mediaPlayer = MediaPlayer().apply {
                    setDisplay(holder)
                    val afd = resources.openRawResourceFd(R.raw.lock_wake_video)
                    setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                    afd.close()

                    val isMuted = prefs.getBoolean(KEY_MUTE, true)
                    val volume = if (isMuted) 0f else 1f
                    setVolume(volume, volume)

                    val isLoopingMode = prefs.getBoolean(KEY_LOOP, false)
                    isLooping = isLoopingMode

                    setOnPreparedListener { mp ->
                        isPrepared = true
                        Log.d(TAG, "MediaPlayer prepared, visible=$isVisibleState")
                        if (isVisibleState) {
                            mp.seekTo(0)
                            mp.start()
                        }
                    }

                    setOnCompletionListener {
                        // Freeze on final frame
                        Log.d(TAG, "Playback completed, holding final frame statically")
                    }

                    prepareAsync()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error initializing MediaPlayer", e)
            }
        }

        override fun onVisibilityChanged(visible: Boolean) {
            this.isVisibleState = visible
            Log.d(TAG, "onVisibilityChanged: $visible, prepared=$isPrepared")
            val mp = mediaPlayer
            if (mp != null) {
                if (visible) {
                    if (isPrepared) {
                        try {
                            mp.seekTo(0)
                            mp.start()
                        } catch (e: Exception) {
                            Log.e(TAG, "Error starting on visibility changed", e)
                        }
                    }
                } else {
                    try {
                        if (mp.isPlaying) {
                            mp.pause()
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error pausing on visibility changed", e)
                    }
                }
            }
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            super.onSurfaceDestroyed(holder)
            Log.d(TAG, "onSurfaceDestroyed")
            releaseMediaPlayer()
        }

        private fun releaseMediaPlayer() {
            try {
                mediaPlayer?.let {
                    if (it.isPlaying) it.stop()
                    it.release()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error releasing MediaPlayer", e)
            } finally {
                mediaPlayer = null
                isPrepared = false
            }
        }

        override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
            mediaPlayer?.let { mp ->
                when (key) {
                    KEY_MUTE -> {
                        val isMuted = prefs.getBoolean(KEY_MUTE, true)
                        val vol = if (isMuted) 0f else 1f
                        mp.setVolume(vol, vol)
                    }
                    KEY_LOOP -> {
                        mp.isLooping = prefs.getBoolean(KEY_LOOP, false)
                    }
                }
            }
        }

        override fun onDestroy() {
            prefs.unregisterOnSharedPreferenceChangeListener(this)
            releaseMediaPlayer()
            super.onDestroy()
        }
    }
}
