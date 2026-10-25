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
        "id": "wp_hogwarts_trio",
        "title": "Hogwarts Trio (Wake)",
        "category": "Harry Potter",
        "description": "Harry, Ron, and Hermione animated magic wand wake sequence.",
        "status": "published",
        "isFeatured": true,
        "isVipOnly": false,
        "videoUrl": "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/ForBiggerBlazes.mp4",
        "thumbUrl": "",
        "sizeBytes": 15600000,
        "sizeDisplay": "14.9 MB",
        "durationMs": 15000,
        "width": 1080,
        "height": 1920,
        "version": 1
      },
      {
        "id": "wp_elder_wand",
        "title": "Elder Wand Lumos",
        "category": "Harry Potter",
        "description": "High-speed spell casting animation with gold particle trailing.",
        "status": "published",
        "isFeatured": false,
        "isVipOnly": false,
        "videoUrl": "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/ForBiggerEscapes.mp4",
        "thumbUrl": "",
        "sizeBytes": 14900000,
        "sizeDisplay": "14.2 MB",
        "durationMs": 15000,
        "width": 1080,
        "height": 1920,
        "version": 1
      },
      {
        "id": "wp_gta_vice_city",
        "title": "GTA Vice City Sunset",
        "category": "GTA",
        "description": "Retro 80s aesthetic neon palm trees and sports car cruising animation.",
        "status": "published",
        "isFeatured": false,
        "isVipOnly": false,
        "videoUrl": "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/ForBiggerJoyBlazes.mp4",
        "thumbUrl": "",
        "sizeBytes": 15800000,
        "sizeDisplay": "15.1 MB",
        "durationMs": 15000,
        "width": 1080,
        "height": 1920,
        "version": 1
      },
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
      },
      {
        "id": "wp_cyberpunk_neon",
        "title": "Neon Cyberpunk Rain (Draft)",
        "category": "Cyberpunk",
        "description": "Futuristic neon city with rain animation and puddles.",
        "status": "hidden",
        "isFeatured": false,
        "isVipOnly": false,
        "videoUrl": "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/ForBiggerFun.mp4",
        "thumbUrl": "",
        "sizeBytes": 13800000,
        "sizeDisplay": "13.1 MB",
        "durationMs": 12000,
        "width": 1080,
        "height": 1920,
        "version": 1
      }
    ]
    """.trimIndent()
}
