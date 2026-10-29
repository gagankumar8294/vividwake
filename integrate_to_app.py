#!/usr/bin/env python3
"""
Integrates the cleaned scraped live wallpapers into the VideoLockScreen Android app.
Updates:
1. VideoLockScreen/wallpapers.json (remote repo file for GitHub/jsDelivr)
2. VideoLockScreen/app/src/main/assets/wallpapers.json (local offline fallback)
"""

import json
import os
import time

APP_ROOT = r"C:\Users\Administrator\Desktop\VideoLockScreen"
REPO_JSON = os.path.join(APP_ROOT, "wallpapers.json")
ASSETS_JSON = os.path.join(APP_ROOT, "app", "src", "main", "assets", "wallpapers.json")
SCRAPED_CLEAN = r"C:\Users\Administrator\Desktop\wallpaper_scraper\wallpapers_clean.json"

def main():
    # 1. Load existing app wallpapers (preserve original 4 items)
    with open(REPO_JSON, "r", encoding="utf-8") as f:
        existing_items = json.load(f)

    existing_ids = {it["id"] for it in existing_items}
    existing_urls = {it.get("videoUrl") for it in existing_items}

    # 2. Load clean scraped items
    with open(SCRAPED_CLEAN, "r", encoding="utf-8") as f:
        clean_scraped = json.load(f)

    # 3. Format and merge
    merged_catalog = list(existing_items)
    added_count = 0
    now_ms = int(time.time() * 1000)

    for item in clean_scraped:
        v_url = item.get("video_url")
        if not v_url or v_url in existing_urls:
            continue

        raw_id = item.get("id", "").replace("post-", "")
        wp_id = f"wp_{item.get('category_slug', 'gen')}_{raw_id}"
        if wp_id in existing_ids:
            wp_id = f"{wp_id}_{added_count}"

        formatted_item = {
            "id": wp_id,
            "title": item.get("title") or "Live Wallpaper",
            "category": item.get("category") or "General",
            "description": f"Seamless {item.get('category', 'mobile')} animated live lock screen wallpaper loop.",
            "tags": item.get("tags") or [],
            "status": "published",
            "isFeatured": False,
            "isVipOnly": False,
            "videoUrl": v_url,
            "thumbUrl": item.get("thumbnail_url") or "",
            "sizeBytes": 5242880,
            "sizeDisplay": "5.0 MB",
            "durationMs": 8000,
            "width": 1080,
            "height": 1920,
            "version": 1,
            "addedAt": now_ms
        }

        merged_catalog.append(formatted_item)
        existing_ids.add(wp_id)
        existing_urls.add(v_url)
        added_count += 1

    print(f"Total wallpapers in catalog: {len(merged_catalog)} (Existing: {len(existing_items)}, Newly Added: {added_count})")

    # 4. Save to VideoLockScreen/wallpapers.json
    with open(REPO_JSON, "w", encoding="utf-8") as f:
        json.dump(merged_catalog, f, indent=2, ensure_ascii=False)
    print(f"[+] Updated: {REPO_JSON}")

    # 5. Save to VideoLockScreen/app/src/main/assets/wallpapers.json
    os.makedirs(os.path.dirname(ASSETS_JSON), exist_ok=True)
    with open(ASSETS_JSON, "w", encoding="utf-8") as f:
        json.dump(merged_catalog, f, indent=2, ensure_ascii=False)
    print(f"[+] Updated: {ASSETS_JSON}")

if __name__ == "__main__":
    main()
