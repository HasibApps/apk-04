#!/data/data/com.termux/files/usr/bin/bash
# Termux build helper:  bash build.sh
cd "$(dirname "$0")" || exit 1
gradle assembleDebug --no-daemon "$@" && \
  echo "✅ APK: app/build/outputs/apk/debug/app-debug.apk" && \
  cp app/build/outputs/apk/debug/app-debug.apk /sdcard/Download/YTDownloader-v3.0.0.apk 2>/dev/null && \
  echo "📲 Copied to /sdcard/Download/YTDownloader-v3.0.0.apk"
