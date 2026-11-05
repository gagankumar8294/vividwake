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
    data class Success(
        val message: String,
        val tier: String = "FREE",
        val customLimit: Int = 0,
        val tierExpiresAt: Long? = null
    ) : DeviceVerificationResult()

    data class Banned(val reason: String) : DeviceVerificationResult()

    data class MismatchedDevice(
        val boundDevice: String,
        val transferRequestStatus: String,
        val errorMessage: String
    ) : DeviceVerificationResult()

    data class Blocked(val reason: String, val title: String) : DeviceVerificationResult()
    data class Error(val errorMessage: String) : DeviceVerificationResult()
}

/**
 * DeviceLockManager
 *
 * Enforces the strict 1-Phone = 1-Email rule by verifying with the backend:
 * - Phone banned by admin -> BAN GATE
 * - Email already locked on another phone -> TRANSFER REQUEST GATE
 * - Legitimate owner or first-time binding -> ALLOWED & LEASE RECORDED
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
                val dataObj = json.optJSONObject("data")
                val tier = dataObj?.optString("tier", "FREE") ?: "FREE"
                val customLimit = dataObj?.optInt("customDownloadLimit", 0) ?: 0
                val tierExpiresAt = if (dataObj != null && !dataObj.isNull("tierExpiresAt")) {
                    try {
                        val str = dataObj.getString("tierExpiresAt")
                        // Parse ISO or timestamp
                        java.time.Instant.parse(str).toEpochMilli()
                    } catch (_: Exception) { null }
                } else null

                // Record server lease
                VaultSecurityManager.recordServerLease(context, tier, customLimit, tierExpiresAt)

                Log.d(TAG, "Device lock check passed: $msg [Tier: $tier]")
                return@withContext DeviceVerificationResult.Success(msg, tier, customLimit, tierExpiresAt)
            }

            if (response.code == 403) {
                val json = try { JSONObject(responseBody) } catch (_: Exception) { JSONObject() }
                val status = json.optString("status", "")
                val errorMsg = json.optString("error", "Access restricted.")

                if (status == "DEVICE_BANNED") {
                    val banReason = json.optString("banReason", "Administrative restriction")
                    VaultSecurityManager.setBanned(context, true, banReason)
                    return@withContext DeviceVerificationResult.Banned(banReason)
                }

                if (status == "EMAIL_LOCKED_TO_ANOTHER_PHONE") {
                    val boundDevice = json.optString("boundDevice", "Another Android Phone")
                    val transferStatus = json.optString("transferRequestStatus", "NONE")
                    return@withContext DeviceVerificationResult.MismatchedDevice(boundDevice, transferStatus, errorMsg)
                }

                val title = "Device Access Restricted"
                return@withContext DeviceVerificationResult.Blocked(errorMsg, title)
            }

            Log.e(TAG, "Unexpected HTTP ${response.code}: $responseBody")
            return@withContext DeviceVerificationResult.Error("Security verification failed (HTTP ${response.code})")
        } catch (e: Exception) {
            Log.e(TAG, "Device lock network exception", e)
            return@withContext DeviceVerificationResult.Error("Could not reach device security server: ${e.localizedMessage}")
        }
    }

    /**
     * Mobile App: Submit Device Transfer / Rebind Request
     */
    suspend fun requestRebind(email: String, reason: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val hwFingerprint = HardwareFingerprintHelper.getDeviceFingerprint(context)
            val url = "${CloudConfig.ANALYTICS_BASE_URL}/api/auth/request-rebind"

            val payload = JSONObject().apply {
                put("email", email)
                put("newHardwareFingerprint", hwFingerprint)
                put("newDeviceBrand", Build.BRAND)
                put("newDeviceModel", Build.MODEL)
                put("reason", reason)
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
                val msg = json.optString("message", "Transfer request submitted successfully.")
                Result.success(msg)
            } else {
                val json = try { JSONObject(responseBody) } catch (_: Exception) { JSONObject() }
                val err = json.optString("error", "Failed to submit request (HTTP ${response.code})")
                Result.failure(Exception(err))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
