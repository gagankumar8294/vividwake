# 📋 VideoLockScreen: Master Project Roadmap & Task Tracker

> **Architecture Standard:** Principal Mobile & Distributed Systems Engineer  
> **Target Android Versions:** Android 5.0 (API 21) – Android 15+ (API 35)  
> **Total Infrastructure Cost:** $0.00 Forever (No Credit Card Required)  
> **Status:** Code Implemented & Compiled Successfully

---

## 🚦 Phase Status Overview

| Phase | Description | Status | Progress |
|---|---|:---:|:---:|
| **Phase 1** | Storage CDN, Releases & Dynamic Catalog Schema | ✅ Complete | 100% |
| **Phase 2** | Android App-Private Sandbox & Chunked Downloader | ✅ Complete | 100% |
| **Phase 3** | Dual-Tab Store UI, Streaming Video Preview & State Sync | ✅ Complete | 100% |
| **Phase 4** | Admin Visibility Engine (Published vs Hidden/Draft Mode) | ✅ Complete | 100% |
| **Phase 5** | Native User Identity & Profile Linking | ✅ Complete | 100% |
| **Phase 6** | Telemetry & SessionFlow Journey Analytics Pipeline | ✅ Complete | 100% |
| **Phase 7** | Push Notifications Broadcast (Firebase FCM) | ⏳ Ready for google-services.json | 80% |
| **Phase 8** | Multi-Brand Hardening, Edge Cases & Production QA | ✅ Verified | 95% |

---

## 📝 Detailed Phase-by-Phase Task List

---

### 🟢 Phase 1: Storage CDN, Releases & Dynamic Catalog Schema
- [x] **Task 1.1:** Set up release asset structure for 10–15 MB video binaries (up to 2 GB per file, zero egress fee).
- [x] **Task 1.2:** Created production `wallpapers.json` schema (`id`, `title`, `description`, `status`, `isFeatured`, `isVipOnly`, `videoUrl`, `thumbUrl`, `sizeBytes`, `sizeDisplay`, `durationMs`, `width`, `height`).
- [x] **Task 1.3:** Built `CloudConfig.kt` with central URL management and built-in fallback catalog.

---

### 🟢 Phase 2: Android App-Private Sandbox & Chunked Downloader
- [x] **Task 2.1:** Created `context.filesDir/cloud_wallpapers/` private sandbox storage with `.nomedia` sentinel.
- [x] **Task 2.2:** Implemented `VideoDownloadManager.kt` with OkHttp chunked streaming, 8 KB buffer, real-time progress callbacks, and atomic rename from `.tmp` to `.mp4`.
- [x] **Task 2.3:** Added `StatFs` storage pre-flight validation preventing out-of-space crashes.

---

### 🟢 Phase 3: Dual-Tab Store UI, Streaming Video Preview & State Sync
- [x] **Task 3.1:** Updated `activity_main.xml` with Segmented Navigation Tabs: `[ 🎨 My Wallpapers ]` vs `[ 🌐 Explore Store ]`.
- [x] **Task 3.2:** Built zero-disk live HTTP streaming preview in top `previewTextureView` without saving the full file to disk.
- [x] **Task 3.3:** Created `item_store_wallpaper.xml` and `StoreWallpaperAdapter.kt` with card states (`⬇ Download`, `⏳ Progress %`, `✨ Apply`, `🗑️ Free Storage`).
- [x] **Task 3.4:** Implemented storage deletion lifecycle: deleting local copy frees 15 MB phone storage while keeping cloud item ready for re-download anytime.

---

### 🟢 Phase 4: Admin Visibility Engine (Published vs Hidden/Draft Mode)
- [x] **Task 4.1:** Built status filter in `CloudCatalogRepository.kt`: normal users only see `status == "published"`, Admin sees all drafts.
- [x] **Task 4.2:** Added secret Admin PIN gesture (tap app title 5 times -> prompt for PIN `7788`) to toggle Admin mode with gold badges directly in the app.

---

### 🟢 Phase 5: Native User Identity & Profile Linking
- [x] **Task 5.1:** Persistent `device_id` generation (UUID in `SharedPreferences`).
- [x] **Task 5.2:** User profile linking via user badge click dialog (`linkUserToDevice(userId, email)`).
- [x] **Task 5.3:** Ready for Google Credential Manager once Client ID is provided.

---

### 🟢 Phase 6: Telemetry & SessionFlow Journey Analytics Pipeline
- [x] **Task 6.1:** Implemented `AnalyticsManager.kt` native event bus tracking `app_open`, `tab_switch`, `preview_stream_start`, `download_start`, `download_success`, `apply_wallpaper`, `delete_local_storage`.
- [x] **Task 6.2:** Batched in-memory queue with automatic flushing every 8 events, on `onPause`, or on exit to `/api/analytics/sync`.
- [x] **Task 6.3:** Hardware context enrichment (`deviceBrand`, `deviceModel`, `osVersion`, `totalDurationMs`).

---

### 🟢 Phase 7: Push Notifications Broadcast (Firebase FCM)
- [x] **Task 7.1:** Added `INTERNET`, `ACCESS_NETWORK_STATE`, and `POST_NOTIFICATIONS` permissions to `AndroidManifest.xml`.
- [x] **Task 7.2:** Added notification launch intent handling in `MainActivity.kt` (`open_tab == "store"`).
- [ ] **Task 7.3:** Place `google-services.json` in `app/` folder when ready.

---

### 🟢 Phase 8: Multi-Brand Hardening, Edge Cases & Production QA
- [x] **Task 8.1:** Verified OpenGL ES 2.0 rendering in `VideoWallpaperService` preserved 100%.
- [x] **Task 8.2:** Verified `setOffsetNotificationsEnabled(false)` in `onCreate()` only (no recursive crash).
- [x] **Task 8.3:** Verified Samsung/Xiaomi direct gallery hand-off intents preserved.
- [x] **Task 8.4:** Gradle `assembleDebug` compilation verified: **BUILD SUCCESSFUL**.

---

## 🔑 Credentials & Environment Variables Checklist

| Item | What to obtain | Where it is placed | Status |
|---|---|---|:---:|
| **GitHub Repo** | Public Release URL for MP4s + `wallpapers.json` URL | `CloudConfig.kt` (`CATALOG_URL`) | ⏳ Default fallback active |
| **Firebase Console** | `google-services.json` | `VideoLockScreen/app/` root directory | ⏳ Awaiting download |
| **Google Cloud Console** | OAuth 2.0 Web Client ID (for Google Sign-In) | `res/values/strings.xml` | ⏳ Ready |
| **Backend API** | Base URL for `/api/analytics/sync` | `CloudConfig.kt` (`ANALYTICS_BASE_URL`) | ⏳ Ready |
