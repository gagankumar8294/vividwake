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

    fun getBuiltinCatalogJson(): String = """
    [
      {
        "id": "wp_gta6_lucia_editorial_portrait_01",
        "title": "GTA VI - Lucia Intense Editorial Portrait",
        "category": "Gaming",
        "subCategory": "Grand Theft Auto VI",
        "description": "The viral hyper-realistic portrait of GTA VI's Lucia. Featuring her intense hazel gaze, layered brunette hair, layered diamond necklaces, and atmospheric falling embers.",
        "tags": ["GTA 6", "Lucia", "Grand Theft Auto VI", "Vice City", "Rockstar Games", "Gaming", "Portrait", "Aesthetic", "Viral"],
        "status": "published",
        "isFeatured": true,
        "isVipOnly": false,
        "videoUrl": "https://github.com/gagankumar8294/vividwake/releases/download/v1.0.0/Lucia-video.mp4",
        "thumbUrl": "https://github.com/gagankumar8294/vividwake/releases/download/v1.0.0/Intense_Editorial_Portrait_with_Layered_2a6f7569.jpg",
        "sizeBytes": 7361495,
        "sizeDisplay": "7.02 MB",
        "durationMs": 9960,
        "width": 1080,
        "height": 1920,
        "version": 1
      },
      {
        "id": "wp_gta6_lucia_jason_motorcycle_wheelie_01",
        "title": "GTA VI - Jason & Lucia Motorcycle Wheelie",
        "category": "Gaming",
        "subCategory": "Grand Theft Auto VI",
        "description": "High-octane GTA VI Vice City action! Jason and Lucia executing an intense 12-o'clock motorcycle wheelie during a high-speed police chase with helicopters and squad cars.",
        "tags": ["GTA 6", "Lucia", "Jason", "Grand Theft Auto VI", "Vice City", "Motorcycle", "Wheelie", "Police Chase", "Rockstar Games", "Gaming", "Action"],
        "status": "published",
        "isFeatured": true,
        "isVipOnly": false,
        "videoUrl": "https://github.com/gagankumar8294/vividwake/releases/download/v1.0.0/Motorcycle_wheelie_lucia_gta.mp4",
        "thumbUrl": "https://github.com/gagankumar8294/vividwake/releases/download/v1.0.0/Motorcycle_wheelie_lucia_gta_walpaper.jpg",
        "sizeBytes": 10912515,
        "sizeDisplay": "10.41 MB",
        "durationMs": 7670,
        "width": 1080,
        "height": 1920,
        "version": 1
      },
      {
        "id": "wp_sabrina_carpenter_viral_kiss_01",
        "title": "Sabrina Carpenter - Taste Kiss Scene",
        "category": "Celebrity & Music",
        "subCategory": "Viral Pop & Music Videos",
        "description": "The viral internet sensation: pop star Sabrina Carpenter's iconic cinematic kiss scene from the global hit 'Taste' music video with Jenna Ortega.",
        "tags": ["Sabrina Carpenter", "Taste", "Viral Kiss", "Jenna Ortega", "Pop Music", "Celebrity", "Music Video", "Short n Sweet", "Trending", "Aesthetic"],
        "status": "published",
        "isFeatured": true,
        "isVipOnly": false,
        "videoUrl": "https://github.com/gagankumar8294/vividwake/releases/download/v1.0.0/sabrina-carpenter-video.mp4",
        "thumbUrl": "https://github.com/gagankumar8294/vividwake/releases/download/v1.0.0/sabrina-carpenter-kiss-image.jpg",
        "sizeBytes": 27493101,
        "sizeDisplay": "26.22 MB",
        "durationMs": 14100,
        "width": 1080,
        "height": 1920,
        "version": 2
      },
      {
        "id": "wp_game_character_01",
        "title": "Game Character Wake",
        "category": "Gaming",
        "description": "High-energy animated game character live lock screen wallpaper.",
        "tags": ["gaming", "action", "anime", "character", "cyberpunk"],
        "status": "draft",
        "isFeatured": true,
        "isVipOnly": false,
        "videoUrl": "https://github.com/gagankumar8294/vividwake/releases/download/v1.0.0/Animating_game_character_wallpaper_20261024075106.mp4",
        "thumbUrl": "https://github.com/gagankumar8294/vividwake/releases/download/v1.0.0/lucia.jpg",
        "sizeBytes": 1380501,
        "sizeDisplay": "1.38 MB",
        "durationMs": 5000,
        "width": 1080,
        "height": 1920,
        "version": 1
      },
      {
        "id": "wp_viking_warrior_01",
        "title": "Viking Warrior Ragnarok",
        "category": "Norse",
        "description": "8-second cinematic loop of a Viking warrior standing in cold mountain fjord breeze.",
        "tags": ["viking", "norse", "ragnarok", "ragnar", "valhalla", "warrior", "fjord", "nordic", "axe", "odin", "mythology"],
        "status": "draft",
        "isFeatured": true,
        "isVipOnly": false,
        "videoUrl": "https://github.com/gagankumar8294/vividwake/releases/download/v1.0.0/Create_Viking_live_wallpaper_20261026075151.mp4",
        "thumbUrl": "https://github.com/gagankumar8294/vividwake/releases/download/v1.0.0/Stormborn.Viking.Warrior.png",
        "sizeBytes": 5558076,
        "sizeDisplay": "5.30 MB",
        "durationMs": 8000,
        "width": 1080,
        "height": 1920,
        "version": 1
      },
      {
        "id": "wp_pubg_last_survivor_01",
        "title": "Last Survivor Battlefield",
        "category": "PUBG",
        "description": "Cinematic lone soldier standing on the battlefield amidst dense battle smoke.",
        "tags": ["pubg", "battleground", "soldier", "battlefield", "survivor", "military", "warzone", "fps", "action"],
        "status": "draft",
        "isFeatured": true,
        "isVipOnly": false,
        "videoUrl": "https://github.com/gagankumar8294/vividwake/releases/download/v1.0.0/Soldier_live_wallpaper_animation_20261027214920.mp4",
        "thumbUrl": "https://github.com/gagankumar8294/vividwake/releases/download/v1.0.0/Soldier_standing_on_battlefield_2K_20261027215040.jpg",
        "sizeBytes": 2946571,
        "sizeDisplay": "2.81 MB",
        "durationMs": 8000,
        "width": 1080,
        "height": 1920,
        "version": 1
      }
    ]
    """.trimIndent()
}
