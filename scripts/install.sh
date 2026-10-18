#!/usr/bin/env bash
# Hogo Panel の debug APK を、シリアルを明示した 1 台の端末へ導入して起動する。
# 使い方: scripts/install.sh <serial> [apk-path]
set -euo pipefail

if [ "$#" -lt 1 ] || [ "$#" -gt 2 ] || [ -z "${1:-}" ]; then
  echo "usage: $0 <adb-serial> [apk-path]" >&2
  echo "serial is required. List candidates with: adb devices -l" >&2
  exit 2
fi

serial="$1"
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
apk="${2:-$root/app/build/outputs/apk/debug/app-debug.apk}"
package="io.github.ysogabe.hogo.panel"

command -v adb >/dev/null 2>&1 || { echo "adb not found in PATH" >&2; exit 1; }
[ -f "$apk" ] || { echo "APK not found: $apk (run ./gradlew assembleDebug first)" >&2; exit 1; }

state="$(adb -s "$serial" get-state 2>&1 || true)"
if [ "$state" != "device" ]; then
  echo "device '$serial' is not ready (state: $state)" >&2
  exit 1
fi

adb -s "$serial" install -r "$apk"
adb -s "$serial" shell am start -n "$package/.MainActivity"
