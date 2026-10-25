package com.lockscreen.videowake

/**
 * CloudConfig — Central configuration for Cloud Wallpapers, Analytics, and Admin settings.
 */
object CloudConfig {

    var CATALOG_URL: String = "https://raw.githubusercontent.com/gagankumar8294/vividwake/main/wallpapers.json"
    var ANALYTICS_BASE_URL: String = "https://vividwake-backend.onrender.com"
    const val ADMIN_SECRET_PIN: String = "7788"

    const val FOLDER_WALLPAPERS: String = "wallpapers"
    const val FOLDER_THUMBS: String = "thumbs"
    const val FOLDER_CLOUD_WALLPAPERS: String = "cloud_wallpapers"
    const val FOLDER_CLOUD_THUMBS: String = "cloud_thumbs"

    fun getBuiltinCatalogJson(): String = """
    [
      {
        "id": "wp_game_character_01",
        "title": "Game Character Wake",
        "category": "Gaming",
        "description": "High-energy animated game character live lock screen wallpaper.",
        "status": "published",
        "isFeatured": true,
        "isVipOnly": false,
        "videoUrl": "https://github.com/gagankumar8294/vividwake/releases/download/v1.0.0/Animating_game_character_wallpaper_20261024075106.mp4",
        "thumbUrl": "",
        "sizeBytes": 1380501,
        "sizeDisplay": "1.38 MB",
        "durationMs": 5000,
        "width": 1080,
        "height": 1920,
        "version": 1
      }
    ]
    """.trimIndent()
}
