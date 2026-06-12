#!/usr/bin/env bash
# Tier-3 CI UI check: install the app on the booted emulator, launch it, capture
# the live UI (screenshot + accessibility-tree dump), and assert expected
# elements render. Run from repo root inside reactivecircus/android-emulator-runner.
#
# Kept as a committed script (not inline YAML) so it runs under a real bash and
# is not subject to the action's dash/inline-script quirks.
set -uo pipefail

PKG=dev.kiran.ankivoice

echo "waiting for emulator boot..."
adb wait-for-device
until [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; do
  sleep 2
done
echo "boot complete"

./gradlew installDebug
adb shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 || true

# adb can hiccup right after launch and a single dump may grab an empty/stale
# file, so retry until the UI is actually rendered (anchored on the title).
captured=0
for i in $(seq 1 12); do
  sleep 6
  adb exec-out screencap -p > screenshot.png 2>/dev/null || true
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1 || true
  adb pull /sdcard/ui.xml ui.xml >/dev/null 2>&1 || true
  if [ -s ui.xml ] && grep -qF "anki-voice spike" ui.xml; then
    echo "UI captured on attempt $i"
    captured=1
    break
  fi
  echo "attempt $i: UI not ready yet"
done
if [ "$captured" -ne 1 ]; then
  echo "ERROR: app UI never rendered; see screenshot.png/ui.xml artifact"
  exit 1
fi

# Compose text lands in the accessibility tree uiautomator reads. Fail if any
# expected element is gone (a UI regression).
echo "=== asserting expected UI elements ==="
fail=0
for label in "anki-voice spike" "List decks" "Test math card" "Grant mic"; do
  if grep -qF "$label" ui.xml; then
    echo "OK: found '$label'"
  else
    echo "MISSING: '$label'"
    fail=1
  fi
done
if [ "$fail" -ne 0 ]; then
  echo "UI assertion failed; see ui.xml artifact"
  exit 1
fi
echo "UI assertions passed"
