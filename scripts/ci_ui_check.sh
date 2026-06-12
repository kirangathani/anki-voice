#!/usr/bin/env bash
# Tier-3 CI: run instrumented UIAutomator tests on the booted emulator, then
# capture a screenshot + UI dump for visibility regardless of the result.
# Run from repo root inside reactivecircus/android-emulator-runner.
#
# Committed script (not inline YAML) so it runs under real bash, free of the
# action's dash/inline-script quirks.
set -uo pipefail

echo "waiting for emulator boot..."
adb wait-for-device
until [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; do
  sleep 2
done
echo "boot complete"

# Install AnkiDroid so the app's ContentProvider integration can be exercised.
# Use the x86_64 ABI variant (matches the emulator, smaller than universal).
# Best-effort: if it fails, the ContentProvider test skips (assumeTrue) and the
# rest still run.
echo "=== installing AnkiDroid ==="
ANKI_URL=$(curl -sL https://api.github.com/repos/ankidroid/Anki-Android/releases/latest \
  | grep -oE '"browser_download_url": *"[^"]+x86_64\.apk"' | grep -oE 'https[^"]+' | head -1)
echo "AnkiDroid APK: ${ANKI_URL:-<none found>}"
if [ -n "${ANKI_URL:-}" ] && curl -fsSL -o ankidroid.apk "$ANKI_URL"; then
  adb install -r -g ankidroid.apk || echo "WARN: adb install AnkiDroid failed"
  # Launch once to create the default collection, then return home.
  adb shell monkey -p com.ichi2.anki -c android.intent.category.LAUNCHER 1 || true
  sleep 25
  adb shell input keyevent KEYCODE_HOME || true
  echo "AnkiDroid installed + initialized"
else
  echo "WARN: could not fetch AnkiDroid APK; ContentProvider test will skip"
fi

# Instrumented Tier-3 UI tests (UIAutomator drives the real app).
./gradlew connectedDebugAndroidTest
rc=$?

# Capture final UI state + the app's log for the artifact, regardless of result.
adb exec-out screencap -p > screenshot.png 2>/dev/null || true
adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1 || true
adb pull /sdcard/ui.xml ui.xml >/dev/null 2>&1 || true
adb logcat -d -s SpikeLog:I > spikelog.txt 2>/dev/null || true

if [ "$rc" -eq 0 ]; then
  echo "instrumented tests passed"
else
  echo "instrumented tests FAILED (rc=$rc); see app-ui artifact + connected report"
fi
exit "$rc"
