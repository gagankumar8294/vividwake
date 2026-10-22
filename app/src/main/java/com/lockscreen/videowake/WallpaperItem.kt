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
    val height: Int = 0
) : Serializable
