#!/usr/bin/env bash
# Build the debug APK (and run unit tests when RUN_TESTS=1).
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

# This development sandbox keeps Gradle caches / SDK inside the workspace.
if [[ -z "${GRADLE_USER_HOME:-}" && -d "$ROOT/../.gradle-home" ]]; then
  export GRADLE_USER_HOME="$ROOT/../.gradle-home"
fi
if [[ -z "${ANDROID_HOME:-}" && -d "$ROOT/../.android-sdk" ]]; then
  export ANDROID_HOME="$ROOT/../.android-sdk"
  export ANDROID_SDK_ROOT="$ANDROID_HOME"
fi

if [[ -x "$ROOT/gradlew" ]]; then
  GRADLE="$ROOT/gradlew"
elif [[ -x "$ROOT/../.toolchain/gradle-8.7/bin/gradle" ]]; then
  GRADLE="$ROOT/../.toolchain/gradle-8.7/bin/gradle"
elif command -v gradle >/dev/null 2>&1; then
  GRADLE="$(command -v gradle)"
else
  echo "No Gradle found (expected ./gradlew or gradle on PATH)" >&2
  exit 1
fi

if [[ "${RUN_TESTS:-0}" == "1" ]]; then
  "$GRADLE" --no-daemon :app:testDebugUnitTest "$@"
fi
"$GRADLE" --no-daemon :app:assembleDebug "$@"

APK="$ROOT/app/build/outputs/apk/debug/app-debug.apk"
if [[ -f "$APK" ]]; then
  mkdir -p "$ROOT/dist"
  cp "$APK" "$ROOT/dist/BiliUVC-Live-debug.apk"
  echo "APK: $ROOT/dist/BiliUVC-Live-debug.apk ($(du -h "$APK" | cut -f1))"
fi
