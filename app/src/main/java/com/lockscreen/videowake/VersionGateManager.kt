package com.lockscreen.videowake

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class VersionGateManager(private val context: Context) {

    companion object {
        private const val TAG = "VersionGateManager"
    }

    data class VersionGateResult(
        val isForceUpdateRequired: Boolean,
        val isOptionalUpdateAvailable: Boolean,
        val title: String,
        val message: String,
        val downloadUrl: String
    )

    suspend fun checkVersion(): VersionGateResult? = withContext(Dispatchers.IO) {
        val currentVersionCode = try {
            val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                pInfo.longVersionCode.toInt()
            } else {
                @Suppress("DEPRECATION")
                pInfo.versionCode
            }
        } catch (_: Exception) { 1 }

        val endpoints = listOf(
            "http://127.0.0.1:5000/api/app/config",
            "${CloudConfig.ANALYTICS_BASE_URL}/api/app/config"
        )

        for (endpoint in endpoints) {
            try {
                val url = URL(endpoint)
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 4000
                    readTimeout = 4000
                }
                if (conn.responseCode == 200) {
                    val body = conn.inputStream.bufferedReader().use { it.readText() }
                    val json = JSONObject(body)
                    if (json.optBoolean("success", false)) {
                        val config = json.getJSONObject("config")
                        val minCode = config.optInt("minSupportedVersionCode", 1)
                        val latestCode = config.optInt("latestVersionCode", 1)
                        val forceEnabled = config.optBoolean("forceUpdateEnabled", false)
                        val title = config.optString("updateTitle", "Update Required")
                        val message = config.optString("updateMessage", "A new version of VividWake is available.")
                        val downloadUrl = config.optString("updateDownloadUrl", "https://github.com/gagankumar8294/vividwake/releases")

                        val forceRequired = forceEnabled && (currentVersionCode < minCode)
                        val optionalAvailable = !forceRequired && (currentVersionCode < latestCode)

                        return@withContext VersionGateResult(
                            isForceUpdateRequired = forceRequired,
                            isOptionalUpdateAvailable = optionalAvailable,
                            title = title,
                            message = message,
                            downloadUrl = downloadUrl
                        )
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to check version config against $endpoint: ${e.message}")
            }
        }
        null
    }
}
