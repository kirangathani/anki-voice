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
  # Pre-grant All-files-access so AnkiDroid's onboarding permission screen is
  # already satisfied (the "Continue" button is disabled until it is).
  adb shell appops set com.ichi2.anki MANAGE_EXTERNAL_STORAGE allow || true
  # Taps the on-screen element whose exact text is $1, by parsing its bounds
  # from a uiautomator dump (so it works regardless of screen resolution).
  tap_by_text() {
    adb shell uiautomator dump /sdcard/ad.xml >/dev/null 2>&1 || true
    adb pull /sdcard/ad.xml /tmp/ad.xml >/dev/null 2>&1 || true
    local nums
    nums=$(tr '>' '\n' < /tmp/ad.xml 2>/dev/null \
      | grep -F "text=\"$1\"" \
      | grep -oE 'bounds="\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]"' | head -1 \
      | grep -oE '[0-9]+')
    [ -z "$nums" ] && return 1
    set -- $nums
    adb shell input tap $(( ($1 + $3) / 2 )) $(( ($2 + $4) / 2 ))
  }

  # AnkiDroid's first-run shows a "Get Started" onboarding screen; the collection
  # (and the Default deck the ContentProvider returns) is not created until it is
  # tapped. Complete onboarding so the provider has a deck.
  adb shell monkey -p com.ichi2.anki -c android.intent.category.LAUNCHER 1 || true
  sleep 12
  # Onboarding: Get Started -> permissions (pre-granted) Continue -> DeckPicker.
  # Tap a few times in case there are extra steps; harmless if a button is absent.
  if tap_by_text "Get Started"; then echo "tapped Get Started"; else echo "no Get Started button"; fi
  sleep 6
  if tap_by_text "Continue"; then echo "tapped Continue"; else echo "no Continue button"; fi
  sleep 6
  tap_by_text "Continue" >/dev/null 2>&1 || true
  tap_by_text "Got it" >/dev/null 2>&1 || true
  sleep 8
  adb exec-out screencap -p > ankidroid.png 2>/dev/null || true
  adb shell uiautomator dump /sdcard/ad2.xml >/dev/null 2>&1 || true
  adb pull /sdcard/ad2.xml ankidroid_ui.xml >/dev/null 2>&1 || true
  adb shell input keyevent KEYCODE_HOME || true
  echo "AnkiDroid installed + onboarding completed"
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
