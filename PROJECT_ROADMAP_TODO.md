# 📋 VideoLockScreen: Master Project Roadmap & Task Tracker

> **Architecture Standard:** Principal Mobile & Distributed Systems Engineer  
> **Target Android Versions:** Android 5.0 (API 21) – Android 15+ (API 35)  
> **Total Infrastructure Cost:** $0.00 Forever (No Credit Card Required)  
> **Status:** Ready for Task-by-Task Execution

---

## 🚦 Phase Status Overview

| Phase | Description | Status | Progress |
|---|---|:---:|:---:|
| **Phase 1** | Storage CDN, Releases & Dynamic Catalog Schema | ⏳ Pending | 0% |
| **Phase 2** | Android App-Private Sandbox & Chunked Downloader | ⏳ Pending | 0% |
| **Phase 3** | Dual-Tab Store UI, Streaming Video Preview & State Sync | ⏳ Pending | 0% |
| **Phase 4** | Admin Visibility Engine (Published vs Hidden/Draft Mode) | ⏳ Pending | 0% |
| **Phase 5** | Native Google Sign-In & User Identity Stitching | ⏳ Pending | 0% |
| **Phase 6** | Telemetry & SessionFlow Journey Analytics Pipeline | ⏳ Pending | 0% |
| **Phase 7** | Push Notifications Broadcast (Firebase FCM) | ⏳ Pending | 0% |
| **Phase 8** | Multi-Brand Hardening, Edge Cases & Production QA | ⏳ Pending | 0% |

---

## 📝 Detailed Phase-by-Phase Task List

---

### 🟢 Phase 1: Storage CDN, Releases & Dynamic Catalog Schema
- [ ] **Task 1.1:** Set up GitHub Releases asset storage for 10–15 MB video binaries (up to 2 GB per file, zero egress fee).
- [ ] **Task 1.2:** Create the production `wallpapers.json` schema with fields:
  - `id`: Unique identifier (e.g. `wp_cyberpunk_01`)
  - `title`: Display name
  - `description`: Subtitle/details
  - `status`: `"published"` (public) | `"hidden"` (admin draft)
  - `isFeatured`: Boolean badge
  - `isVipOnly`: Boolean hook for future monetization
  - `videoUrl`: Direct HTTPS download link from CDN
  - `thumbUrl`: Direct HTTPS thumbnail link
  - `sizeBytes` & `sizeDisplay`: Accurate file size (e.g. `14.2 MB`)
  - `durationMs`, `width`, `height`: Video metadata
- [ ] **Task 1.3:** Host `wallpapers.json` on raw GitHub / GitHub Pages for single-request instant loading.

---

### 🟢 Phase 2: Android App-Private Sandbox & Chunked Downloader
- [ ] **Task 2.1:** Create `context.filesDir/wallpapers/` private sandbox storage.
  - Add `.nomedia` file to make videos 100% invisible to Google Photos/Gallery/cleaner apps.
  - Enforce Linux sandbox permissions (works universally on Samsung, Xiaomi, OnePlus, Motorola, Pixel).
- [ ] **Task 2.2:** Implement `VideoDownloadManager` using OkHttp streaming chunks:
  - 8 KB chunk buffer for smooth memory consumption.
  - Real-time progress callback: `progress = (downloaded * 100) / total`.
  - Download to temporary `.tmp` file; atomic rename to `.mp4` upon EOF verification.
  - Fail-safe: Auto-delete corrupted or partial files on network drops.
- [ ] **Task 2.3:** Storage pre-flight check (`StatFs`):
  - Verify device has at least `sizeBytes + 20 MB` before allowing download to begin.

---

### 🟢 Phase 3: Dual-Tab Store UI, Streaming Video Preview & State Sync
- [ ] **Task 3.1:** Update `activity_main.xml` layout to add top navigation tabs:
  - Tab 1: **"🎨 My Wallpapers"** (Built-in presets + user imports + downloaded videos).
  - Tab 2: **"🌐 Explore Store"** (Cloud-streamed wallpapers catalog).
- [ ] **Task 3.2:** Build zero-disk Streaming Preview in top `previewTextureView`:
  - When user taps a store card, stream the first 2–3 seconds over HTTP via `MediaPlayer`.
  - User watches live animated video without saving full 15 MB to disk.
- [ ] **Task 3.3:** Implement Store item card states:
  - **Not Downloaded:** `[ ⬇ Download to Phone (14.2 MB) ]`
  - **Downloading:** `[ ⏳ Downloading... 45% ]` with horizontal progress bar.
  - **Downloaded:** `[ ✨ Apply to Lock Screen ]` + `[ 🗑️ Free Storage ]`.
- [ ] **Task 3.4:** Storage Deletion Lifecycle:
  - Tapping `🗑️ Free Storage` deletes the 15 MB local copy from `filesDir`.
  - Active lock screen switches to preset if deleted item was active.
  - Item switches back to `[ ⬇ Download ]` in Store (cloud copy remains available forever).

---

### 🟢 Phase 4: Admin Visibility Engine (Published vs Hidden/Draft Mode)
- [ ] **Task 4.1:** Status filter logic in `WallpaperRepository`:
  - Regular users: `wallpapers.filter { it.status == "published" }`.
  - Admin mode: See all wallpapers including `hidden` and `draft`.
- [ ] **Task 4.2:** In-App Admin Secret Access:
  - Tap title bar 5 times consecutively -> prompt for Admin PIN.
  - Unlocks Admin Mode with yellow badges: `⚠️ HIDDEN FROM USERS`.
  - Admin can preview drafts and test lock screen fit before setting `status: "published"` in GitHub.

---

### 🟢 Phase 5: Native Google Sign-In & User Identity Stitching
- [ ] **Task 5.1:** Integrate Android Credential Manager (OAuth 2.0 / Google Identity Services):
  - 100% free for unlimited users.
  - 1-tap Google bottom sheet dialog.
  - Extract `email`, `displayName`, `googleUid`, and `avatarUrl`.
- [ ] **Task 5.2:** Anonymous to Authenticated Identity Stitching:
  - Generate persistent `device_id` (UUID) stored in `SharedPreferences`.
  - When user signs in, send payload to backend: `{ deviceId, googleUid, email }`.
  - Backend runs `updateMany({ deviceId }, { $set: { userId: googleUid } })` in MongoDB.

---

### 🟢 Phase 6: Telemetry & SessionFlow Journey Analytics Pipeline
- [ ] **Task 6.1:** Build native mobile `AnalyticsManager` event bus:
  - Track: `app_open`, `nav_tab`, `preview_start`, `preview_dwell`, `download_click`, `download_done`, `apply_wallpaper`, `delete_local`.
- [ ] **Task 6.2:** Batched, battery-efficient sync:
  - Buffer events in memory; flush to `POST /api/analytics/sync` every 30 seconds or on `onPause`.
  - Offline-first: If network is unavailable, store in local SQLite/disk and flush when back online.
- [ ] **Task 6.3:** Enrich with hardware metadata:
  - `brand`, `model`, `androidVersion`, `networkType` (5G/Wi-Fi/4G), server-side IP resolution.

---

### 🟢 Phase 7: Push Notifications Broadcast (Firebase FCM)
- [ ] **Task 7.1:** Add Firebase Cloud Messaging (`firebase-messaging-ktx`).
- [ ] **Task 7.2:** Auto-subscribe all devices on first run to topic `"all_wallpapers"`.
- [ ] **Task 7.3:** Implement `MyFirebaseMessagingService`:
  - System tray alert: *"New Wallpaper Added: Cyberpunk Neon! Tap to browse."*
  - Deep-link intent: Tapping alert opens app directly into the Store Tab focusing on that wallpaper.

---

### 🟢 Phase 8: Multi-Brand Hardening, Edge Cases & Production QA
- [ ] **Task 8.1:** Preserve existing engine stability:
  - Verify OpenGL ES 2.0 rendering in `VideoWallpaperService`.
  - Verify `setOffsetNotificationsEnabled(false)` in `onCreate()` only (no recursive crash).
  - Verify smart `KeyguardManager` home-screen pause (zero home-screen swipe lag).
  - Verify Samsung / Xiaomi direct gallery hand-off intents.
- [ ] **Task 8.2:** Stress-test network interruptions, rapid tap handling, and orientation changes.
- [ ] **Task 8.3:** End-to-end device testing on connected Motorola device (`ZA222RCMBP`).

---

## 🔑 Credentials & Environment Variables Checklist

| Item | What to obtain | Where it is placed |
|---|---|---|
| **GitHub Repo** | Public Release URL for MP4s + `wallpapers.json` URL | `app/.../CloudConfig.kt` |
| **Firebase Console** | `google-services.json` | `VideoLockScreen/app/` root directory |
| **Google Cloud Console** | OAuth 2.0 Web Client ID (for Google Sign-In) | `res/values/strings.xml` |
| **Backend API** | Base URL for `/api/analytics/sync` | `app/.../AnalyticsEngine.kt` |
