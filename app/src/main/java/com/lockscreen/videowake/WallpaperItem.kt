package com.lockscreen.videowake

import java.io.Serializable

data class WallpaperItem(
    val id: String,
    val title: String,
    val isPreset: Boolean,
    val rawResId: Int = 0,
    val rawResName: String = "",
    val filePath: String = "",
    val thumbPath: String = "",
    val durationMs: Long = 0L,
    val width: Int = 0,
    val height: Int = 0,
    // Cloud Store and Admin Metadata
    val isCloud: Boolean = false,
    val cloudId: String = "",
    val videoUrl: String = "",
    val thumbUrl: String = "",
    val sizeBytes: Long = 0L,
    val sizeDisplay: String = "",
    val isDownloaded: Boolean = false,
    val description: String = "",
    val status: String = "published", // "published" | "hidden" | "draft"
    val isFeatured: Boolean = false,
    val isVipOnly: Boolean = false,
    val category: String = "General"
) : Serializable
