#!/usr/bin/env bash
# Run JVM unit tests and print a short summary.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

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

"$GRADLE" --no-daemon :app:testDebugUnitTest "$@"

REPORT="$ROOT/app/build/reports/tests/testDebugUnitTest/index.html"
if [[ -f "$REPORT" ]]; then
  echo "HTML report: $REPORT"
  grep -oE '[0-9]+ tests?|failures?|errors?' "$REPORT" 2>/dev/null | head -5 || true
fi
