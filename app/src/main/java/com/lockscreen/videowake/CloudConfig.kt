package com.lockscreen.videowake

/**
 * CloudConfig — Central configuration for Cloud Wallpapers, Analytics, and Admin settings.
 */
object CloudConfig {

    var CATALOG_URL: String = "https://cdn.jsdelivr.net/gh/gagankumar8294/vividwake@main/wallpapers.json"
    var ANALYTICS_BASE_URL: String = "https://vividwake-backend.onrender.com"

    const val FOLDER_WALLPAPERS: String = "wallpapers"
    const val FOLDER_THUMBS: String = "thumbs"
    const val FOLDER_CLOUD_WALLPAPERS: String = "cloud_wallpapers"
    const val FOLDER_CLOUD_THUMBS: String = "cloud_thumbs"

    /**
     * Emergency fallback only used if assets/wallpapers.json cannot be read from APK.
     * The actual catalog is dynamically loaded from MongoDB / GitHub wallpapers.json / assets.
     */
    fun getBuiltinCatalogJson(): String = "[]"
}
