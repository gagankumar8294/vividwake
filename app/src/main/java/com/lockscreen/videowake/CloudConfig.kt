package com.lockscreen.videowake

/**
 * CloudConfig — Central configuration for Cloud Wallpapers, Analytics, and Admin settings.
 *
 * When you publish your GitHub repository releases and wallpapers.json,
 * simply update [CATALOG_URL] to your raw URL:
 * e.g., "https://raw.githubusercontent.com/<username>/<repo>/main/wallpapers.json"
 */
object CloudConfig {

    /**
     * Remote URL for wallpapers.json.
     * Default points to a placeholder or raw GitHub repository URL.
     */
    var CATALOG_URL: String = "https://raw.githubusercontent.com/gagankumar8294/vividwake/main/wallpapers.json"

    /**
     * Base URL for the SessionFlow Analytics Ingestion endpoint.
     * Android app will POST telemetry batches to: "$ANALYTICS_BASE_URL/api/analytics/sync"
     */
    var ANALYTICS_BASE_URL: String = "https://api.yourdomain.com"

    /**
     * Default Admin Passcode to unlock Hidden/Draft wallpapers directly in the app.
     * Can be changed anytime.
     */
    const val ADMIN_SECRET_PIN: String = "7788"

    /**
     * Folder names within context.filesDir (Private App Sandbox)
     */
    const val FOLDER_WALLPAPERS: String = "wallpapers"
    const val FOLDER_THUMBS: String = "thumbs"
    const val FOLDER_CLOUD_WALLPAPERS: String = "cloud_wallpapers"
    const val FOLDER_CLOUD_THUMBS: String = "cloud_thumbs"

    /**
     * Built-in fallback catalog to display in the Store tab immediately,
     * even before remote network credentials or URLs are plugged in.
     */
    fun getBuiltinCatalogJson(): String = """
    [
      {
        "id": "cloud_wp_hogwarts_trio",
        "title": "Hogwarts Trio (Wake)",
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
        "id": "cloud_wp_wand_magic",
        "title": "Elder Wand Lumos",
        "description": "High-speed spell casting animation with gold particle trailing.",
        "status": "published",
        "isFeatured": true,
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
        "id": "cloud_wp_cyberpunk_neon",
        "title": "Neon Cyberpunk Rain (Draft)",
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
