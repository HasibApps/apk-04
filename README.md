# 📱 YT Downloader v3.0.0

**yt-dlp + FFmpeg** ইঞ্জিন দিয়ে বানানো Android ভিডিও ডাউনলোডার — YouTube ও Facebook থেকে ভিডিও/অডিও ডাউনলোড করুন। সম্পূর্ণ অ্যাপটি **Kotlin + Jetpack Compose**-এ লেখা।

[![Build APK](https://github.com/HasibApps/apk-04/actions/workflows/build-apk.yml/badge.svg)](https://github.com/HasibApps/apk-04/actions/workflows/build-apk.yml)
[![Release](https://img.shields.io/github/v/release/HasibApps/apk-04?label=Release&color=brightgreen)](https://github.com/HasibApps/apk-04/releases/latest)

---

## ⬇️ ডাউনলোড

| ফাইল | লিংক |
|---|---|
| **YTDownloader-v3.0.0.apk** | [GitHub Release থেকে ডাউনলোড করুন](https://github.com/HasibApps/apk-04/releases/latest) |

> APK টি debug key দিয়ে signed — সরাসরি ইনস্টল করা যায়, আলাদা কিছু করতে হয় না।
> ইনস্টলের সময় **"Install unknown apps"** পারমিশন চাইতে পারে।

---

## 📋 APK ডিটেইলস

| বিষয় | তথ্য |
|---|---|
| **অ্যাপের নাম** | YT Downloader |
| **Package** | `com.example.ytdl` |
| **Version** | 3.0.0 (versionCode 4) |
| **Min Android** | 7.0 (API 24) |
| **Target** | Android 14 (API 34) |
| **Supported ABI** | `arm64-v8a` (64-bit ফোন) |
| **Engine** | yt-dlp + FFmpeg (youtubedl-android 0.17.2) |
| **UI** | Jetpack Compose + Material 3 |
| **Build system** | Gradle 8.7, AGP 8.5.0, JDK 17 |

### 🔐 প্রয়োজনীয় পারমিশন
| পারমিশন | কেন লাগে |
|---|---|
| `INTERNET` / `ACCESS_NETWORK_STATE` | ভিডিও ডাউনলোড ও Wi-Fi only মোড |
| `POST_NOTIFICATIONS` | ডাউনলোড progress নোটিফিকেশন |
| `FOREGROUND_SERVICE` (+ `DATA_SYNC`) | ব্যাকগ্রাউন্ডে ডাউনলোড চালু রাখা |

---

## ✨ ফিচারসমূহ

- 🎬 **ভিডিও ফরম্যাট:** 1080p / 720p / 480p / 360p / MP4 (best quality)
- 🎵 **অডিও:** MP3 ও M4A এক্সট্রাক্ট (FFmpeg দিয়ে)
- 📥 **Downloads ট্যাব:** Active Queue — Pause / Resume / Retry / Cancel, লাইভ progress, speed ও ETA
- 🕘 **History:** search, play, share, delete কনফার্মেশন, swipe-to-delete
- 🔗 **Share মেনু ইন্টিগ্রেশন:** যেকোনো অ্যাপ থেকে লিংক Share → **YT Downloader** সিলেক্ট করলেই bottom-sheet popup — অ্যাপ খোলার দরকার নেই
- 🍪 **Facebook কুকি সাপোর্ট:** অ্যাপের ভেতরেই Facebook login করে cookies save, `cookies.txt` import বা paste — private ফেসবুক ভিডিওও ডাউনলোড হয়
- ⚙️ **তিন ধরনের Engine mode:** Hybrid (auto fallback) / Flask API (pytubefix) / Native yt-dlp + FFmpeg
- 🔄 **yt-dlp আপডেটার:** অ্যাপের Settings থেকেই এক ট্যাপে yt-dlp engine update
- 📚 **একসাথে ৩টি ডাউনলোড** (concurrent 1–3 সেট করা যায়)
- 🖼️ **Embed thumbnail ও metadata**, 📝 subtitle অপশন
- 📶 **Wi-Fi only মোড** — মোবাইল ডাটা বাঁচায়
- 🎨 **৪টি থিম:** System / Light / Dark / AMOLED Black
- 👷 **Crash handler:** ক্র্যাশ হলে আলাদা স্ক্রিনে এরর দেখায়
- Header credit: **亗𝐷𝑒𝑣. 𝐻𝑎𝑠𝑖𝑏 𝐵𝑟𝑜亗**

---

## 🛠️ বিল্ড

### GitHub Actions দিয়ে (অটোমেটিক)
প্রতিটি push-এ অটোমেটিক APK বিল্ড হয় এবং **Release `v3.0.0`**-এ আপলোড হয়।
ম্যানুয়ালি চালাতে: **Actions → Build APK → Run workflow**। বিল্ড শেষে APK পাবেন —
- ✅ Release assets-এ: `YTDownloader-v3.0.0.apk`
- 📦 Workflow run-এর Artifacts-এও

### লোকালি (Termux)
```bash
pkg install openjdk-17 gradle aapt2   # প্রথমবার
cd YTDownloader
bash build.sh
```
APK: `YTDownloader/app/build/outputs/apk/debug/app-debug.apk`

---

## 📁 রিপো স্ট্রাকচার

```
├── .github/workflows/build-apk.yml   # GitHub Actions: build + release
├── YTDownloader/                     # v3.0.0 সোর্স কোড (Android/Kotlin)
├── YTDownloader_v3.zip               # v3 অরিজিনাল zip
└── YTDownloader_v2.zip               # পুরনো v2 zip
```

## 💡 টিপ

- Facebook/YouTube হঠাৎ কাজ না করলে **Settings → Update yt-dlp Engine** চাপুন।
- `"This video is only available for registered users"` এলে **Settings → 🍪 Add / Manage Cookies** থেকে Facebook login করুন — cookies ফোনের private storage-এই থাকে।

---

<sub>Made with ❤️ by 亗𝐷𝑒𝑣. 𝐻𝑎𝑠𝑖𝑏 𝐵𝑟𝑜亗 • Powered by yt-dlp & FFmpeg</sub>
