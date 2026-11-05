package com.lockscreen.videowake

import android.content.Context
import android.content.SharedPreferences
import android.media.MediaDataSource
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.io.RandomAccessFile

/**
 * VaultSecurityManager
 *
 * Implements:
 * 1. 1KB MP4 Header Scramble / Encryption (prevents VLC / MX Player / external scraping).
 * 2. ScrambledMediaDataSource for zero-temp-file in-memory playback via MediaPlayer.
 * 3. 48-Hour Monotonic Hardware Clock License Lease (prevents date-rollback exploits).
 * 4. Selective Logout Purge (preserves active lock screen, purges other VIP assets).
 */
object VaultSecurityManager {

    private const val TAG = "VaultSecurityManager"
    private const val PREFS_SECURITY = "vividwake_vault_security"

    // 1024-byte header scrambling mask
    private const val SCRAMBLE_BYTE_COUNT = 1024
    private val SCRAMBLE_KEY = byteArrayOf(
        0x56.toByte(), 0x69.toByte(), 0x76.toByte(), 0x69.toByte(), // 'V','i','v','i'
        0x64.toByte(), 0x57.toByte(), 0x61.toByte(), 0x6B.toByte(), // 'd','W','a','k'
        0x65.toByte(), 0x5F.toByte(), 0x56.toByte(), 0x61.toByte(), // 'e','_','V','a'
        0x75.toByte(), 0x6C.toByte(), 0x74.toByte(), 0x32.toByte(), // 'u','l','t','2'
        0x30.toByte(), 0x32.toByte(), 0x36.toByte(), 0x5F.toByte(), // '0','2','6','_'
        0x53.toByte(), 0x65.toByte(), 0x63.toByte(), 0x75.toByte(), // 'S','e','c','u'
        0x72.toByte(), 0x65.toByte(), 0x4D.toByte(), 0x61.toByte(), // 'r','e','M','a'
        0x73.toByte(), 0x6B.toByte(), 0x21.toByte(), 0x24.toByte()  // 's','k','!','$'
    )

    // Security preferences keys
    private const val KEY_LEASE_GRANTED_UTC = "lease_granted_utc"
    private const val KEY_LEASE_ELAPSED_REALTIME = "lease_elapsed_realtime"
    private const val KEY_USER_TIER = "user_tier"
    private const val KEY_CUSTOM_LIMIT = "user_custom_limit"
    private const val KEY_TIER_EXPIRES_AT = "tier_expires_at"
    private const val KEY_IS_BANNED = "is_device_banned"
    private const val KEY_BAN_REASON = "device_ban_reason"

    const val MAX_OFFLINE_LEASE_MS = 48L * 60L * 60L * 1000L // 48 Hours

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_SECURITY, Context.MODE_PRIVATE)
    }

    // ==========================================
    // 1. FILE SCRAMBLE & DESCRAMBLE (1KB HEADER)
    // ==========================================

    /**
     * Scrambles the first 1KB of the file in-place using XOR.
     * Involution: Calling this twice on the file restores it completely.
     */
    fun scrambleFile(file: File): Boolean {
        if (!file.exists() || file.length() < 16) return false
        return try {
            RandomAccessFile(file, "rw").use { raf ->
                val bytesToScramble = Math.min(SCRAMBLE_BYTE_COUNT.toLong(), raf.length()).toInt()
                val buffer = ByteArray(bytesToScramble)
                raf.seek(0)
                raf.readFully(buffer)

                // XOR scramble
                for (i in 0 until bytesToScramble) {
                    val keyByte = SCRAMBLE_KEY[i % SCRAMBLE_KEY.size]
                    buffer[i] = (buffer[i].toInt() xor keyByte.toInt()).toByte()
                }

                raf.seek(0)
                raf.write(buffer)
            }
            Log.d(TAG, "Scrambled header (${file.name}) to protect from external players")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error scrambling file: ${file.name}", e)
            false
        }
    }

    /**
     * Checks if the file header looks like a raw MP4 (starts with 'ftyp' at offset 4).
     * If not, it is considered scrambled/encrypted in our sandbox.
     */
    fun isScrambled(file: File): Boolean {
        if (!file.exists() || file.length() < 12) return false
        return try {
            RandomAccessFile(file, "r").use { raf ->
                raf.seek(4)
                val signature = ByteArray(4)
                raf.readFully(signature)
                val type = String(signature, Charsets.US_ASCII)
                // If it doesn't match standard MP4 ftyp container, it's scrambled
                type != "ftyp"
            }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Creates an in-memory streaming MediaDataSource for MediaPlayer / MediaMetadataRetriever.
     * Reads directly from disk and descrambles the 1KB header on-the-fly with 0 disk writes!
     */
    fun createMediaDataSource(file: File): MediaDataSource {
        return object : MediaDataSource() {
            private val raf = RandomAccessFile(file, "r")

            override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
                if (position >= raf.length()) return -1

                raf.seek(position)
                val bytesRead = raf.read(buffer, offset, size)
                if (bytesRead <= 0) return bytesRead

                // Descramble any bytes that fall within the first 1KB
                for (i in 0 until bytesRead) {
                    val filePos = position + i
                    if (filePos < SCRAMBLE_BYTE_COUNT) {
                        val keyByte = SCRAMBLE_KEY[(filePos % SCRAMBLE_KEY.size).toInt()]
                        val bufIdx = offset + i
                        buffer[bufIdx] = (buffer[bufIdx].toInt() xor keyByte.toInt()).toByte()
                    }
                }
                return bytesRead
            }

            override fun getSize(): Long = file.length()

            override fun close() {
                try {
                    raf.close()
                } catch (_: Exception) {}
            }
        }
    }

    // ==========================================
    // 2. 48-HOUR CRYPTOGRAPHIC LICENSE LEASE
    // ==========================================

    /**
     * Record a fresh verified lease from the server heartbeat.
     */
    fun recordServerLease(
        context: Context,
        tier: String,
        customLimit: Int = 0,
        tierExpiresAt: Long? = null
    ) {
        getPrefs(context).edit()
            .putLong(KEY_LEASE_GRANTED_UTC, System.currentTimeMillis())
            .putLong(KEY_LEASE_ELAPSED_REALTIME, SystemClock.elapsedRealtime())
            .putString(KEY_USER_TIER, tier)
            .putInt(KEY_CUSTOM_LIMIT, customLimit)
            .putLong(KEY_TIER_EXPIRES_AT, tierExpiresAt ?: 0L)
            .putBoolean(KEY_IS_BANNED, false)
            .putString(KEY_BAN_REASON, "")
            .apply()
        Log.d(TAG, "Recorded license lease: tier=$tier, customLimit=$customLimit")
    }

    /**
     * Mark device as banned via Admin Remote Kill Switch.
     */
    fun setBanned(context: Context, banned: Boolean, reason: String = "") {
        getPrefs(context).edit()
            .putBoolean(KEY_IS_BANNED, banned)
            .putString(KEY_BAN_REASON, reason)
            .apply()
    }

    fun isDeviceBanned(context: Context): Pair<Boolean, String> {
        val prefs = getPrefs(context)
        return Pair(
            prefs.getBoolean(KEY_IS_BANNED, false),
            prefs.getString(KEY_BAN_REASON, "Administrative restriction") ?: ""
        )
    }

    fun getUserTier(context: Context): String {
        return getPrefs(context).getString(KEY_USER_TIER, "FREE") ?: "FREE"
    }

    fun getCustomLimit(context: Context): Int {
        return getPrefs(context).getInt(KEY_CUSTOM_LIMIT, 0)
    }

    /**
     * Validates whether the device has a valid offline license lease.
     * Prevents calendar rollback exploits via SystemClock.elapsedRealtime().
     */
    fun isOfflineLeaseValid(context: Context): Boolean {
        val prefs = getPrefs(context)
        val leaseUtc = prefs.getLong(KEY_LEASE_GRANTED_UTC, 0L)
        val leaseUptime = prefs.getLong(KEY_LEASE_ELAPSED_REALTIME, 0L)
        val tier = prefs.getString(KEY_USER_TIER, "FREE")

        // Free tier doesn't require VIP lease
        if (tier == "FREE") return true

        if (leaseUtc == 0L || leaseUptime == 0L) return false

        val currentUtc = System.currentTimeMillis()
        val currentUptime = SystemClock.elapsedRealtime()

        // 🛡️ ANTI-TAMPER CHECK 1: Detect Calendar Rollback
        if (currentUtc < leaseUtc) {
            Log.w(TAG, "Calendar rollback exploit detected! Device clock ($currentUtc) is behind lease ($leaseUtc)")
            return false
        }

        // 🛡️ ANTI-TAMPER CHECK 2: Monotonic Uptime Check
        // If device has not rebooted (currentUptime >= leaseUptime), check uptime duration
        if (currentUptime >= leaseUptime) {
            val uptimeDuration = currentUptime - leaseUptime
            if (uptimeDuration > MAX_OFFLINE_LEASE_MS) {
                Log.w(TAG, "Monotonic uptime lease expired: ${uptimeDuration / 3600000}h > 48h")
                return false
            }
        }

        // 🛡️ ANTI-TAMPER CHECK 3: Total Wall-clock Duration
        val wallDuration = currentUtc - leaseUtc
        if (wallDuration > MAX_OFFLINE_LEASE_MS) {
            Log.w(TAG, "Wall clock lease expired: ${wallDuration / 3600000}h > 48h")
            return false
        }

        // 🛡️ ANTI-TAMPER CHECK 4: Tier Expiration (if set by admin)
        val expiresAt = prefs.getLong(KEY_TIER_EXPIRES_AT, 0L)
        if (expiresAt > 0L && currentUtc > expiresAt) {
            Log.w(TAG, "User tier expired at $expiresAt")
            return false
        }

        return true
    }

    // ==========================================
    // 3. SECURE SELECTIVE LOGOUT PURGE
    // ==========================================

    /**
     * Purges all downloaded VIP cloud wallpapers from the private sandbox EXCEPT
     * the one currently running as the active lock screen wallpaper.
     */
    fun performSelectiveLogoutPurge(context: Context) {
        try {
            val prefs = context.getSharedPreferences(VideoWallpaperService.PREFS_NAME, Context.MODE_PRIVATE)
            val activeFilePath = prefs.getString(VideoWallpaperService.KEY_ACTIVE_FILE_PATH, "") ?: ""
            val activeFile = if (activeFilePath.isNotEmpty()) File(activeFilePath) else null

            val downloadManager = VideoDownloadManager(context)
            val cloudDir = downloadManager.cloudWallpapersDir

            var purgedCount = 0
            cloudDir.listFiles()?.forEach { file ->
                // Never delete the currently active wallpaper!
                val isActive = activeFile != null && file.absolutePath == activeFile.absolutePath
                if (!isActive && file.isFile && file.name.startsWith("cloud_")) {
                    val deleted = file.delete()
                    if (deleted) purgedCount++
                }
            }

            // Clear local cached download IDs in preferences except active
            val activeId = prefs.getString(VideoWallpaperService.KEY_ACTIVE_ID, "") ?: ""
            val activeCloudId = if (activeId.startsWith("cloud_")) activeId.removePrefix("cloud_") else null

            val downloadPrefs = context.getSharedPreferences("cloud_downloads_meta", Context.MODE_PRIVATE)
            if (activeCloudId != null) {
                downloadPrefs.edit().putString("downloaded_cloud_ids", "[\"$activeCloudId\"]").apply()
            } else {
                downloadPrefs.edit().putString("downloaded_cloud_ids", "[]").apply()
            }

            // Reset VIP tier to FREE
            getPrefs(context).edit()
                .putString(KEY_USER_TIER, "FREE")
                .putInt(KEY_CUSTOM_LIMIT, 0)
                .putLong(KEY_LEASE_GRANTED_UTC, 0L)
                .apply()

            Log.d(TAG, "Selective logout purge completed: $purgedCount VIP files removed, active wallpaper preserved")
        } catch (e: Exception) {
            Log.e(TAG, "Error performing logout purge", e)
        }
    }
}
