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
