package com.lockscreen.videowake

import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

sealed class DeviceVerificationResult {
    data class Success(val message: String) : DeviceVerificationResult()
    data class Blocked(val reason: String, val title: String) : DeviceVerificationResult()
    data class Error(val errorMessage: String) : DeviceVerificationResult()
}

/**
 * DeviceLockManager
 *
 * Enforces the strict 1-Phone = 1-Email rule by verifying with the backend:
 * - Phone already locked to someone else -> BLOCKED
 * - Email already locked on another phone -> BLOCKED
 * - Legitimate owner or first-time binding -> ALLOWED
 */
class DeviceLockManager(private val context: Context) {

    companion object {
        private const val TAG = "DeviceLockManager"
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .build()

    suspend fun verifyBinding(email: String, userId: String?): DeviceVerificationResult = withContext(Dispatchers.IO) {
        try {
            val hwFingerprint = HardwareFingerprintHelper.getDeviceFingerprint(context)
            val url = "${CloudConfig.ANALYTICS_BASE_URL}/api/auth/verify-device"

            val payload = JSONObject().apply {
                put("hardwareFingerprint", hwFingerprint)
                put("email", email)
                put("userId", userId ?: "")
                put("deviceBrand", Build.BRAND)
                put("deviceModel", Build.MODEL)
                put("deviceManufacturer", Build.MANUFACTURER)
            }

            val request = Request.Builder()
                .url(url)
                .post(payload.toString().toRequestBody(JSON_MEDIA))
                .header("Content-Type", "application/json")
                .build()

            val response = httpClient.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""

            if (response.isSuccessful) {
                val json = try { JSONObject(responseBody) } catch (_: Exception) { JSONObject() }
                val msg = json.optString("message", "Device identity verified.")
                Log.d(TAG, "Device lock check passed: $msg")
                return@withContext DeviceVerificationResult.Success(msg)
            }

            if (response.code == 403) {
                val json = try { JSONObject(responseBody) } catch (_: Exception) { JSONObject() }
                val status = json.optString("status", "")
                val errorMsg = json.optString("error", "This device is locked to another account.")

                val title = if (status == "EMAIL_LOCKED_TO_ANOTHER_PHONE") {
                    "Account Locked to Another Device"
                } else {
                    "Device Locked to Another User"
                }

                Log.w(TAG, "Device lock check BLOCKED (403): $errorMsg")
                return@withContext DeviceVerificationResult.Blocked(errorMsg, title)
            }

            Log.e(TAG, "Unexpected HTTP ${response.code}: $responseBody")
            return@withContext DeviceVerificationResult.Error("Security verification failed (HTTP ${response.code})")
        } catch (e: Exception) {
            Log.e(TAG, "Device lock network exception", e)
            // In case of complete network outage during login, return error message gracefully
            return@withContext DeviceVerificationResult.Error("Could not reach device security server: ${e.localizedMessage}")
        }
    }
}
