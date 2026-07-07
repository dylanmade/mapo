#!/usr/bin/env bash
# Redeploys Mappo to the connected device and re-grants its special permissions
# (accessibility service binding + overlay) without user interaction.
#
# Why this exists: Android Studio's "Run" cannot terminate Mappo when the
# InputAccessibilityService is bound to system_server -- the OS auto-restarts
# protected accessibility services immediately after force-stop, racing the APK
# swap, and `pm install -r` then refuses to replace a live APK. We work around
# it by clearing the enabled-services list (which cleanly unbinds the service)
# before the install, then restoring it afterward.
#
# Usage:
#   scripts/redeploy.sh                  # default connected device
#   scripts/redeploy.sh -s <serial>      # specific device (forwarded to adb)
#
# App data is PRESERVED across runs (uses `installDebug`, not uninstall).

set -euo pipefail

PKG=com.mappo
SERVICE="${PKG}/${PKG}.service.InputAccessibilityService"
ACTIVITY="${PKG}/.MainActivity"

# Forward an optional `-s <serial>` through to every adb invocation AND to
# Gradle. Gradle's `installDebug` picks its target via ANDROID_SERIAL, not adb
# args, so both need to be set for multi-device setups.
ADB_TARGET=()
if [[ "${1:-}" == "-s" && -n "${2:-}" ]]; then
  ADB_TARGET=(-s "$2")
  export ANDROID_SERIAL="$2"
  shift 2
fi
adb_() { adb ${ADB_TARGET[@]+"${ADB_TARGET[@]}"} "$@"; }

# Fail early with a useful device list if multiple devices are attached and the
# caller didn't pick one — otherwise adb / gradle bail mid-run with "more than
# one device".
if [[ ${#ADB_TARGET[@]} -eq 0 ]]; then
  device_count=$(adb devices | awk 'NR>1 && $2=="device"' | wc -l | tr -d ' ')
  if [[ "$device_count" -gt 1 ]]; then
    echo "Multiple devices connected. Re-run with: scripts/redeploy.sh -s <serial>"
    echo
    echo "Available devices:"
    adb devices | awk 'NR>1 && $2=="device" {print $1}' | while read -r serial; do
      # `</dev/null` keeps adb shell from slurping the rest of this loop's stdin.
      model=$(adb -s "$serial" shell </dev/null getprop ro.product.model 2>/dev/null | tr -d '\r')
      printf "  %-52s %s\n" "$serial" "$model"
    done
    exit 1
  fi
fi

cd "$(dirname "$0")/.."

echo "==> Unbinding accessibility service so the install can replace the APK"
adb_ shell settings delete secure enabled_accessibility_services >/dev/null
adb_ shell settings put secure accessibility_enabled 0 >/dev/null
adb_ shell am force-stop "$PKG" >/dev/null 2>&1 || true

echo "==> Building + installing debug APK"
./gradlew :app:installDebug

echo "==> Re-granting accessibility binding + overlay permission"
adb_ shell settings put secure enabled_accessibility_services "$SERVICE" >/dev/null
adb_ shell settings put secure accessibility_enabled 1 >/dev/null
adb_ shell appops set "$PKG" SYSTEM_ALERT_WINDOW allow >/dev/null

echo "==> Launching $ACTIVITY"
adb_ shell am start -n "$ACTIVITY" >/dev/null

echo "==> Done."
