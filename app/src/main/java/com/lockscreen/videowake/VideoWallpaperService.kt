package com.lockscreen.videowake

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.graphics.SurfaceTexture
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.Matrix
import android.os.Handler
import android.os.HandlerThread
import android.service.wallpaper.WallpaperService
import android.util.Log
import android.view.Surface
import android.view.SurfaceHolder
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

class VideoWallpaperService : WallpaperService() {

    companion object {
        private const val TAG = "VideoWallpaperService"
        const val PREFS_NAME = "video_lock_prefs"
        const val KEY_LOOP = "pref_loop"
        const val KEY_MUTE = "pref_mute"

        // Playback Modes
        const val KEY_PLAYBACK_MODE = "pref_playback_mode"
        const val MODE_WAKE_FREEZE = 0       // Play on wake (5s), freeze on final frame (Default & Recommended)
        const val MODE_LOOP_LOCK_ONLY = 1     // Loop on Lock Screen, freeze on Home Screen (Zero Lag & Battery Saver)
        const val MODE_LOOP_ALWAYS = 2        // Loop on both Lock Screen and Home Screen

        const val KEY_ACTIVE_ID = "pref_active_id"
        const val KEY_ACTIVE_TYPE = "pref_active_type"         // "preset" or "file"
        const val KEY_ACTIVE_RES_NAME = "pref_active_res_name" // e.g. "lock_wake_video", "preset_wand_wake"
        const val KEY_ACTIVE_FILE_PATH = "pref_active_file_path"
        const val KEY_ACTIVE_TIMESTAMP = "pref_active_timestamp"
    }

    override fun onCreateEngine(): Engine = GLVideoEngine()

    inner class GLVideoEngine : Engine(), SharedPreferences.OnSharedPreferenceChangeListener {

        private lateinit var prefs: SharedPreferences
        private var glThread: HandlerThread? = null
        private var glHandler: Handler? = null

        // System Services
        private var keyguardManager: KeyguardManager? = null
        private var systemReceiver: BroadcastReceiver? = null

        // Dimensions
        private var surfaceWidth = 0
        private var surfaceHeight = 0
        private var videoWidth = 720
        private var videoHeight = 1280
        private var videoRotation = 0

        // EGL
        private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
        private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
        private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE

        // GL & Video
        private var program = 0
        private var uMVPMatrixHandle = 0
        private var uSTMatrixHandle = 0
        private var aPositionHandle = 0
        private var aTextureCoordHandle = 0
        private var textureId = 0

        private var surfaceTexture: SurfaceTexture? = null
        private var videoSurface: Surface? = null
        private var mediaPlayer: MediaPlayer? = null

        private val mvpMatrix = FloatArray(16)
        private val stMatrix = FloatArray(16)

        private var isVisibleState = false
        private var isPrepared = false

        // Frame pacing & backlog prevention (guarantees zero lag and smooth home screen scrolling)
        @Volatile
        private var isDrawPending = false

        private val drawRunnable = Runnable {
            isDrawPending = false
            drawFrame()
        }

        // Quad coordinates (X, Y, Z, U, V)
        private val verticesData = floatArrayOf(
            -1.0f, -1.0f, 0f, 0f, 0f,
             1.0f, -1.0f, 0f, 1f, 0f,
            -1.0f,  1.0f, 0f, 0f, 1f,
             1.0f,  1.0f, 0f, 1f, 1f
        )
        private val verticesBuffer: FloatBuffer = ByteBuffer.allocateDirect(verticesData.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply {
                put(verticesData)
                position(0)
            }

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)

            // CRITICAL FIX FOR LAUNCHER SWIPING:
            // 1. Disable offset notifications so Launcher never waits for or synchronizes horizontal page swipes over IPC
            setOffsetNotificationsEnabled(false)
            // 2. Disable touch events so wallpaper never intercepts or interferes with home screen touch gestures
            setTouchEventsEnabled(false)

            prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.registerOnSharedPreferenceChangeListener(this)

            keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager

            registerSystemReceiver()

            glThread = HandlerThread("GLVideoWallpaperThread").apply { start() }
            glHandler = Handler(glThread!!.looper)
        }

        override fun onOffsetsChanged(
            xOffset: Float,
            yOffset: Float,
            xOffsetStep: Float,
            yOffsetStep: Float,
            xPixelOffset: Int,
            yPixelOffset: Int
        ) {
            // Explicit NO-OP. Do not perform any work or GL redraw on home screen page scroll
        }

        private fun registerSystemReceiver() {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_USER_PRESENT)
                addAction(Intent.ACTION_SCREEN_OFF)
            }
            systemReceiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    when (intent?.action) {
                        Intent.ACTION_USER_PRESENT -> {
                            // User unlocked device and is now on Home Screen
                            handleUserUnlocked()
                        }
                        Intent.ACTION_SCREEN_OFF -> {
                            // Screen turned off: reset to frame 0 for next wake
                            handleScreenOff()
                        }
                    }
                }
            }
            try {
                registerReceiver(systemReceiver, filter)
            } catch (e: Exception) {
                Log.w(TAG, "Error registering receiver", e)
            }
        }

        private fun handleUserUnlocked() {
            glHandler?.post {
                val mode = getEffectivePlaybackMode()
                if (mode == MODE_LOOP_LOCK_ONLY) {
                    // Pause playback on Home Screen to give 100% CPU/GPU and 120Hz refresh to Launcher
                    mediaPlayer?.let { mp ->
                        try {
                            if (mp.isPlaying) {
                                mp.pause()
                                Log.d(TAG, "Device unlocked: paused video on Home Screen for maximum launcher smoothness")
                            }
                        } catch (_: Exception) {}
                    }
                }
            }
        }

        private fun handleScreenOff() {
            glHandler?.post {
                mediaPlayer?.let { mp ->
                    try {
                        if (isPrepared) {
                            mp.seekTo(0)
                            if (mp.isPlaying) mp.pause()
                        }
                    } catch (_: Exception) {}
                }
            }
        }

        private fun getEffectivePlaybackMode(): Int {
            if (prefs.contains(KEY_PLAYBACK_MODE)) {
                return prefs.getInt(KEY_PLAYBACK_MODE, MODE_WAKE_FREEZE)
            }
            // Fallback to legacy KEY_LOOP boolean
            val isLoop = prefs.getBoolean(KEY_LOOP, false)
            return if (isLoop) MODE_LOOP_LOCK_ONLY else MODE_WAKE_FREEZE
        }

        override fun onSurfaceCreated(holder: SurfaceHolder) {
            super.onSurfaceCreated(holder)
            Log.d(TAG, "onSurfaceCreated")
            glHandler?.post {
                initGL(holder)
                initMediaPlayer()
            }
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            Log.d(TAG, "onSurfaceChanged: ${width}x${height}")
            surfaceWidth = width
            surfaceHeight = height
            glHandler?.post {
                updateViewportAndMatrix()
            }
        }

        override fun onVisibilityChanged(visible: Boolean) {
            this.isVisibleState = visible
            Log.d(TAG, "onVisibilityChanged: $visible, prepared=$isPrepared")
            glHandler?.post {
                val mp = mediaPlayer ?: return@post
                if (visible) {
                    if (isPrepared) {
                        try {
                            val mode = getEffectivePlaybackMode()
                            val isLocked = keyguardManager?.isKeyguardLocked ?: true

                            if (mode == MODE_LOOP_LOCK_ONLY && !isLocked) {
                                // Already on Home Screen: keep static frame to preserve butter-smooth launcher performance
                                if (mp.isPlaying) mp.pause()
                            } else {
                                mp.seekTo(0)
                                mp.start()
                            }
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
            glHandler?.post {
                releaseMediaPlayer()
                releaseGL()
            }
        }

        override fun onDestroy() {
            prefs.unregisterOnSharedPreferenceChangeListener(this)
            systemReceiver?.let {
                try { unregisterReceiver(it) } catch (_: Exception) {}
                systemReceiver = null
            }
            glHandler?.post {
                releaseMediaPlayer()
                releaseGL()
            }
            glThread?.quitSafely()
            glThread = null
            glHandler = null
            super.onDestroy()
        }

        override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
            glHandler?.post {
                when (key) {
                    KEY_MUTE -> {
                        mediaPlayer?.let { mp ->
                            val isMuted = prefs.getBoolean(KEY_MUTE, true)
                            val vol = if (isMuted) 0f else 1f
                            mp.setVolume(vol, vol)
                        }
                    }
                    KEY_LOOP, KEY_PLAYBACK_MODE -> {
                        mediaPlayer?.let { mp ->
                            val mode = getEffectivePlaybackMode()
                            mp.isLooping = (mode == MODE_LOOP_LOCK_ONLY || mode == MODE_LOOP_ALWAYS)
                        }
                    }
                    KEY_ACTIVE_TIMESTAMP, KEY_ACTIVE_ID, KEY_ACTIVE_TYPE, KEY_ACTIVE_FILE_PATH, KEY_ACTIVE_RES_NAME -> {
                        Log.d(TAG, "Active wallpaper changed! Re-initializing MediaPlayer...")
                        initMediaPlayer()
                    }
                }
            }
        }

        private fun chooseEglConfig(display: EGLDisplay): EGLConfig? {
            val configs = arrayOfNulls<EGLConfig>(1)
            val numConfigs = IntArray(1)

            // Priority 1: Standard 32-bit (RGBA 8888) with ES2
            val attribList32 = intArrayOf(
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_NONE
            )
            if (EGL14.eglChooseConfig(display, attribList32, 0, configs, 0, 1, numConfigs, 0) && numConfigs[0] > 0 && configs[0] != null) {
                return configs[0]
            }

            // Priority 2: 16-bit (RGB 565) with ES2
            val attribList16 = intArrayOf(
                EGL14.EGL_RED_SIZE, 5,
                EGL14.EGL_GREEN_SIZE, 6,
                EGL14.EGL_BLUE_SIZE, 5,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_NONE
            )
            if (EGL14.eglChooseConfig(display, attribList16, 0, configs, 0, 1, numConfigs, 0) && numConfigs[0] > 0 && configs[0] != null) {
                return configs[0]
            }

            // Priority 3: Fallback any available config with ES2
            val attribListAny = intArrayOf(
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_NONE
            )
            if (EGL14.eglChooseConfig(display, attribListAny, 0, configs, 0, 1, numConfigs, 0) && numConfigs[0] > 0 && configs[0] != null) {
                return configs[0]
            }

            return null
        }

        private fun initGL(holder: SurfaceHolder) {
            releaseGL()

            eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            val version = IntArray(2)
            EGL14.eglInitialize(eglDisplay, version, 0, version, 1)

            val config = chooseEglConfig(eglDisplay) ?: run {
                Log.e(TAG, "No suitable EGLConfig found on this GPU")
                return
            }

            val contextAttribs = intArrayOf(
                EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
                EGL14.EGL_NONE
            )
            eglContext = EGL14.eglCreateContext(eglDisplay, config, EGL14.EGL_NO_CONTEXT, contextAttribs, 0)

            val surfaceAttribs = intArrayOf(EGL14.EGL_NONE)
            eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, config, holder.surface, surfaceAttribs, 0)

            if (eglSurface == EGL14.EGL_NO_SURFACE) {
                Log.e(TAG, "Failed to create EGL window surface")
                return
            }

            EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)
            // Sync with display VSYNC to avoid tearing or buffer queue lock contention
            EGL14.eglSwapInterval(eglDisplay, 1)

            program = createProgram(VERTEX_SHADER, FRAGMENT_SHADER)
            if (program == 0) return

            aPositionHandle = GLES20.glGetAttribLocation(program, "aPosition")
            aTextureCoordHandle = GLES20.glGetAttribLocation(program, "aTextureCoord")
            uMVPMatrixHandle = GLES20.glGetUniformLocation(program, "uMVPMatrix")
            uSTMatrixHandle = GLES20.glGetUniformLocation(program, "uSTMatrix")

            val textures = IntArray(1)
            GLES20.glGenTextures(1, textures, 0)
            textureId = textures[0]
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

            // CRITICAL ZERO-LAG FRAME LISTENER:
            // Dispatches directly on glHandler with atomic isDrawPending guard to prevent message backlog
            surfaceTexture = SurfaceTexture(textureId).apply {
                setOnFrameAvailableListener({
                    if (!isDrawPending) {
                        isDrawPending = true
                        glHandler?.post(drawRunnable)
                    }
                }, glHandler)
            }
            videoSurface = Surface(surfaceTexture)
            updateViewportAndMatrix()
        }

        private fun updateViewportAndMatrix() {
            if (surfaceWidth <= 0 || surfaceHeight <= 0) return
            GLES20.glViewport(0, 0, surfaceWidth, surfaceHeight)

            val surfaceRatio = surfaceWidth.toFloat() / surfaceHeight.toFloat()
            val videoRatio = videoWidth.toFloat() / videoHeight.toFloat()

            Matrix.setIdentityM(mvpMatrix, 0)
            if (surfaceRatio > videoRatio) {
                val scaleY = surfaceRatio / videoRatio
                Matrix.scaleM(mvpMatrix, 0, 1.0f, scaleY, 1.0f)
            } else {
                val scaleX = videoRatio / surfaceRatio
                Matrix.scaleM(mvpMatrix, 0, scaleX, 1.0f, 1.0f)
            }
        }

        private fun drawFrame() {
            if (eglDisplay == EGL14.EGL_NO_DISPLAY || eglSurface == EGL14.EGL_NO_SURFACE) return
            try {
                surfaceTexture?.updateTexImage()
                surfaceTexture?.getTransformMatrix(stMatrix)

                GLES20.glUseProgram(program)

                GLES20.glUniformMatrix4fv(uMVPMatrixHandle, 1, false, mvpMatrix, 0)
                GLES20.glUniformMatrix4fv(uSTMatrixHandle, 1, false, stMatrix, 0)

                verticesBuffer.position(0)
                GLES20.glVertexAttribPointer(aPositionHandle, 3, GLES20.GL_FLOAT, false, 5 * 4, verticesBuffer)
                GLES20.glEnableVertexAttribArray(aPositionHandle)

                verticesBuffer.position(3)
                GLES20.glVertexAttribPointer(aTextureCoordHandle, 2, GLES20.GL_FLOAT, false, 5 * 4, verticesBuffer)
                GLES20.glEnableVertexAttribArray(aTextureCoordHandle)

                GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
                GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)

                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

                EGL14.eglSwapBuffers(eglDisplay, eglSurface)
            } catch (e: Exception) {
                Log.e(TAG, "Error drawing frame", e)
            }
        }

        private fun initMediaPlayer() {
            val vSurface = videoSurface ?: return
            try {
                releaseMediaPlayer()

                val activeType = prefs.getString(KEY_ACTIVE_TYPE, "preset") ?: "preset"
                val activeResName = prefs.getString(KEY_ACTIVE_RES_NAME, "lock_wake_video") ?: "lock_wake_video"
                val activeFilePath = prefs.getString(KEY_ACTIVE_FILE_PATH, "") ?: ""

                videoRotation = 0

                mediaPlayer = MediaPlayer().apply {
                    setSurface(vSurface)

                    var dataSourceLoaded = false
                    if (activeType == "file" && activeFilePath.isNotEmpty()) {
                        val file = File(activeFilePath)
                        if (file.exists() && file.length() > 0) {
                            try {
                                setDataSource(file.absolutePath)
                                dataSourceLoaded = true

                                val retriever = MediaMetadataRetriever()
                                try {
                                    retriever.setDataSource(file.absolutePath)
                                    videoRotation = retriever.extractMetadata(
                                        MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION
                                    )?.toIntOrNull() ?: 0
                                } catch (_: Exception) {} finally {
                                    try { retriever.release() } catch (_: Exception) {}
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed to load custom video data source: $activeFilePath", e)
                            }
                        }
                    }

                    if (!dataSourceLoaded) {
                        val resId = when (activeResName) {
                            "preset_wand_wake" -> R.raw.preset_wand_wake
                            else -> R.raw.lock_wake_video
                        }
                        val afd = resources.openRawResourceFd(resId)
                        setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                        afd.close()
                    }

                    val isMuted = prefs.getBoolean(KEY_MUTE, true)
                    val volume = if (isMuted) 0f else 1f
                    setVolume(volume, volume)

                    val mode = getEffectivePlaybackMode()
                    isLooping = (mode == MODE_LOOP_LOCK_ONLY || mode == MODE_LOOP_ALWAYS)

                    setOnVideoSizeChangedListener { _, w, h ->
                        if (w > 0 && h > 0) {
                            if (videoRotation == 90 || videoRotation == 270) {
                                this@GLVideoEngine.videoWidth = h
                                this@GLVideoEngine.videoHeight = w
                            } else {
                                this@GLVideoEngine.videoWidth = w
                                this@GLVideoEngine.videoHeight = h
                            }
                            updateViewportAndMatrix()
                        }
                    }

                    setOnErrorListener { _, what, extra ->
                        Log.e(TAG, "MediaPlayer error: what=$what, extra=$extra. Gracefully falling back to default preset.")
                        glHandler?.post {
                            fallbackToDefault()
                        }
                        true
                    }

                    setOnPreparedListener { mp ->
                        isPrepared = true
                        Log.d(TAG, "MediaPlayer prepared, visible=$isVisibleState, isPreview=$isPreview")
                        if (isVisibleState || isPreview) {
                            val isLocked = keyguardManager?.isKeyguardLocked ?: true
                            if (mode == MODE_LOOP_LOCK_ONLY && !isLocked && !isPreview) {
                                // On Home Screen: hold still frame
                                mp.seekTo(0)
                            } else {
                                mp.seekTo(0)
                                mp.start()
                            }
                        }
                    }

                    setOnCompletionListener {
                        Log.d(TAG, "Playback completed, holding final frame statically")
                    }

                    prepareAsync()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error initializing MediaPlayer", e)
            }
        }

        private fun fallbackToDefault() {
            try {
                prefs.edit()
                    .putString(KEY_ACTIVE_ID, WallpaperRepository.ID_PRESET_TRIO)
                    .putString(KEY_ACTIVE_TYPE, "preset")
                    .putString(KEY_ACTIVE_RES_NAME, "lock_wake_video")
                    .remove(KEY_ACTIVE_FILE_PATH)
                    .putLong(KEY_ACTIVE_TIMESTAMP, System.currentTimeMillis())
                    .apply()
            } catch (e: Exception) {
                Log.e(TAG, "Error during fallback", e)
            }
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

        private fun releaseGL() {
            if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                if (eglSurface != EGL14.EGL_NO_SURFACE) {
                    EGL14.eglDestroySurface(eglDisplay, eglSurface)
                    eglSurface = EGL14.EGL_NO_SURFACE
                }
                if (eglContext != EGL14.EGL_NO_CONTEXT) {
                    EGL14.eglDestroyContext(eglDisplay, eglContext)
                    eglContext = EGL14.EGL_NO_CONTEXT
                }
                EGL14.eglTerminate(eglDisplay)
                eglDisplay = EGL14.EGL_NO_DISPLAY
            }

            videoSurface?.release()
            videoSurface = null
            surfaceTexture?.release()
            surfaceTexture = null

            if (textureId != 0) {
                val textures = intArrayOf(textureId)
                GLES20.glDeleteTextures(1, textures, 0)
                textureId = 0
            }
            if (program != 0) {
                GLES20.glDeleteProgram(program)
                program = 0
            }
        }

        private fun createProgram(vertexSource: String, fragmentSource: String): Int {
            val vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexSource)
            if (vertexShader == 0) return 0
            val pixelShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
            if (pixelShader == 0) return 0

            var prog = GLES20.glCreateProgram()
            if (prog != 0) {
                GLES20.glAttachShader(prog, vertexShader)
                GLES20.glAttachShader(prog, pixelShader)
                GLES20.glLinkProgram(prog)
                val linkStatus = IntArray(1)
                GLES20.glGetProgramiv(prog, GLES20.GL_LINK_STATUS, linkStatus, 0)
                if (linkStatus[0] != GLES20.GL_TRUE) {
                    Log.e(TAG, "Could not link program: " + GLES20.glGetProgramInfoLog(prog))
                    GLES20.glDeleteProgram(prog)
                    prog = 0
                }
            }
            return prog
        }

        private fun loadShader(shaderType: Int, source: String): Int {
            var shader = GLES20.glCreateShader(shaderType)
            if (shader != 0) {
                GLES20.glShaderSource(shader, source)
                GLES20.glCompileShader(shader)
                val compiled = IntArray(1)
                GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
                if (compiled[0] == 0) {
                    Log.e(TAG, "Could not compile shader $shaderType: " + GLES20.glGetShaderInfoLog(shader))
                    GLES20.glDeleteShader(shader)
                    shader = 0
                }
            }
            return shader
        }
    }
}

private const val VERTEX_SHADER = """
    uniform mat4 uMVPMatrix;
    uniform mat4 uSTMatrix;
    attribute vec4 aPosition;
    attribute vec4 aTextureCoord;
    varying vec2 vTextureCoord;
    void main() {
        gl_Position = uMVPMatrix * aPosition;
        vTextureCoord = (uSTMatrix * aTextureCoord).xy;
    }
"""

private const val FRAGMENT_SHADER = """
    #extension GL_OES_EGL_image_external : require
    precision mediump float;
    varying vec2 vTextureCoord;
    uniform samplerExternalOES sTexture;
    void main() {
        gl_FragColor = texture2D(sTexture, vTextureCoord);
    }
"""
