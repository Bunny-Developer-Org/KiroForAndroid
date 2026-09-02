#!/usr/bin/env bash
# Install (if needed) and boot a headless Android emulator matching the app's
# target SDK, so the app can be tested without a physical device attached.
#
# Idempotent: safe to re-run. Installs the emulator + system-image packages
# and creates the AVD only if they're missing, then boots and waits for
# sys.boot_completed. Leaves the emulator running in the background; use
# `adb -s emulator-5554 ...` (or the serial this script prints) once other
# devices are also connected, since adb is ambiguous with more than one.
set -euo pipefail

export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
export PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"

# Matches gradle/libs.versions.toml's compileSdk/targetSdk (37) — the closest
# stable (non-beta) image, since compileSdk moves ahead of stable emulator
# images now and then.
API_LEVEL="37.0"
IMAGE_TYPE="google_apis"
ARCH="x86_64"
SYSTEM_IMAGE="system-images;android-${API_LEVEL};${IMAGE_TYPE};${ARCH}"
AVD_NAME="${KIRO_AVD_NAME:-kiro-dev}"

SDKMANAGER="$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager"
AVDMANAGER="$ANDROID_HOME/cmdline-tools/latest/bin/avdmanager"

if [ ! -x "$ANDROID_HOME/emulator/emulator" ] || [ ! -d "$ANDROID_HOME/system-images/android-${API_LEVEL}/${IMAGE_TYPE}/${ARCH}" ]; then
  echo "==> installing emulator + ${SYSTEM_IMAGE} (first run only, a few GB)"
  yes | "$SDKMANAGER" --licenses >/dev/null 2>&1 || true
  "$SDKMANAGER" --install "emulator" "$SYSTEM_IMAGE"
fi

if ! "$AVDMANAGER" list avd 2>/dev/null | grep -q "Name: ${AVD_NAME}$"; then
  echo "==> creating AVD '${AVD_NAME}'"
  echo "no" | "$AVDMANAGER" create avd \
    --name "$AVD_NAME" \
    --package "$SYSTEM_IMAGE" \
    --device "pixel_6"
fi

echo "==> booting '${AVD_NAME}' headless (KVM-accelerated)"
"$ANDROID_HOME/emulator/emulator" \
  -avd "$AVD_NAME" \
  -no-window -no-audio -no-boot-anim \
  -gpu swiftshader_indirect \
  >/tmp/kiro-emulator.log 2>&1 &
EMULATOR_PID=$!
echo "    emulator pid: $EMULATOR_PID (log: /tmp/kiro-emulator.log)"

echo "==> waiting for device"
adb wait-for-device

echo "==> waiting for boot to complete"
until [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; do
  sleep 2
done

SERIAL="$(adb devices | awk '/emulator-/{print $1; exit}')"
echo "==> ready: ${SERIAL}"
