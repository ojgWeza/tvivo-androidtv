#!/usr/bin/env bash
# Boot the Android TV emulator for this project.
#
# Two flags here are not optional on this machine:
#   -gpu swiftshader_indirect  the host GPU path paints a black window; the guest is
#                              rendering fine, but you cannot see it
#   -memory 1536               16 GB total, and the Gradle daemon (1536m) plus the Kotlin
#                              daemon (768m) plus the emulator do not fit at once. The
#                              emulator gets OOM-killed mid-session when they overlap
#
# The Gradle daemons are stopped first for the same reason. Run a build, then run this;
# the next build restarts the daemons on its own.

set -euo pipefail

SDK="${ANDROID_SDK_ROOT:-D:\dev\android-sdk}"
SDK_UNIX="/d/dev/android-sdk"
AVD="${1:-tvivo_tv34}"

export ANDROID_SDK_ROOT="$SDK"
export ANDROID_HOME="$SDK"
export ANDROID_AVD_HOME="D:\dev\android-sdk\avd"
export JAVA_HOME="${JAVA_HOME:-/c/Program Files/Android/openjdk/jdk-21.0.8}"
export GRADLE_USER_HOME="${GRADLE_USER_HOME:-D:\dev\gradle_home}"

echo "==> stopping Gradle daemons to free memory"
(cd "$(dirname "$0")/.." && ./gradlew --stop >/dev/null 2>&1) || true

# Force "Send keyboard shortcuts to = Emulator controls" before every boot.
#
# Without this the host keyboard stops driving the emulator: Esc arrives as
# KEYCODE_ESCAPE (111) instead of KEYCODE_BACK (4) and the arrow keys stop acting as the
# D-pad, which makes a D-pad-only app untestable from the host. Typing into text fields
# keeps working either way, so there is no trade here.
#
# It is set here rather than once by hand because the emulator persists its UI settings
# on *clean exit* only — and on this machine the emulator is routinely OOM-killed or hard
# killed (see -memory below), which loses the value and silently reverts the setting.
# Writing it every launch is idempotent and immune to that.
# MSYS_NO_PATHCONV=1 is required: without it Git Bash rewrites the `HKCU\...` key into a
# Windows path and reg.exe fails with "Invalid syntax".
if command -v reg.exe >/dev/null 2>&1; then
  MSYS_NO_PATHCONV=1 reg.exe add 'HKCU\Software\Android Open Source Project\Emulator\set' \
    /v forwardShortcutsToDevice /t REG_SZ /d false /f >/dev/null 2>&1 || true
  echo "==> keyboard shortcuts routed to emulator controls"
fi

echo "==> booting $AVD"
"$SDK_UNIX/emulator/emulator.exe" \
  -avd "$AVD" \
  -no-snapshot-load \
  -no-boot-anim \
  -memory 1536 \
  -gpu swiftshader_indirect &

echo "==> waiting for boot to complete (cold boot takes a few minutes)"
"$SDK_UNIX/platform-tools/adb.exe" wait-for-device
for _ in $(seq 1 90); do
  if [ "$("$SDK_UNIX/platform-tools/adb.exe" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r\n ')" = "1" ]; then
    echo "==> booted"
    exit 0
  fi
  sleep 5
done

echo "==> timed out waiting for boot" >&2
exit 1
