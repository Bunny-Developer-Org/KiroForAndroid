#!/usr/bin/env bash
# Install and run KiroForAndroid on a USB-connected device, with the bridge
# reachable over the cable rather than over the network.
#
# The bridge refuses a non-loopback bind without TLS (AUTHENTICATION §4), which
# is correct and which `adb reverse` sidesteps entirely: the phone's own
# 127.0.0.1:8765 is forwarded down the USB cable to this machine's loopback.
# Nothing is exposed to the LAN and no certificate is needed.
#
# It is also the one shape that wants no --public-url: the bridge falls back to
# advertising its own loopback address, and here that is exactly right, because
# the phone really does reach it at 127.0.0.1. A tunnelled bridge is the case
# that needs to be told (docs/HOSTING.md §1).
set -euo pipefail

PORT="${KIRO_BRIDGE_PORT:-8765}"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
export PATH="$ANDROID_HOME/platform-tools:$HOME/.local/bin:$PATH"

if ! adb get-state >/dev/null 2>&1; then
  echo "No device. Check: a data cable (not charge-only), USB debugging enabled," >&2
  echo "USB mode set to File transfer, and the debugging prompt accepted." >&2
  exit 1
fi
echo "device: $(adb devices -l | sed -n '2p')"

echo "==> building"
"$ROOT/gradlew" -p "$ROOT" :app:assembleDebug :bridge:installDist -q

echo "==> installing"
adb install -r "$ROOT/app/build/outputs/apk/debug/app-debug.apk"

# The one line that makes loopback-only work from the phone.
echo "==> forwarding phone:$PORT -> host:$PORT over USB"
adb reverse --remove-all >/dev/null 2>&1 || true
adb reverse "tcp:$PORT" "tcp:$PORT"

echo "==> starting bridge (Ctrl-C to stop)"
echo
echo "    In the app, enter:"
echo "      Bridge address : ws://127.0.0.1:$PORT/acp"
echo "      Pairing code   : printed below"
echo
adb shell am start -n dev.kiro.android.debug/dev.kiro.android.MainActivity >/dev/null

exec "$ROOT/bridge/build/install/bridge/bin/bridge" --port "$PORT"
