#!/usr/bin/env bash
set -euo pipefail
# Never install or instrument the owner's normal app package.
serial=${1:?Usage: scripts/run-audit-tests.sh emulator-SERIAL}
[[ "$serial" == emulator-* ]] || { echo 'Only an explicit emulator serial is supported.' >&2; exit 1; }
task_sdk=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}
[[ -n "$task_sdk" ]] || { echo 'Set ANDROID_HOME to the Android SDK.' >&2; exit 1; }
adb="$task_sdk/platform-tools/adb"
[[ "$("$adb" -s "$serial" shell getprop ro.kernel.qemu | tr -d '\r')" == 1 ]] || { echo 'Target is not an emulator.' >&2; exit 1; }
cd "$(dirname "$0")/.."
./gradlew -PauditInstall=true -PownerPro=true :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest :terminal-emulator:testDebugUnitTest --console=plain
# Assert the variant before any installation. The manifest package is fixed by this property.
grep -q '"applicationId": "dev.wissam.sshterm.audit"' app/build/outputs/apk/debug/output-metadata.json
"$adb" -s "$serial" install -r app/build/outputs/apk/debug/app-debug.apk
"$adb" -s "$serial" install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
# Keep startup-provider tests in a fresh process; legacy tests alter providers.
run_instrumentation() {
  local result
  result=$(mktemp)
  "$adb" -s "$serial" shell am instrument -w \
    -e securityAuditDisposable true \
    -e securityAuditTargetPackage dev.wissam.sshterm.audit "$@" \
    dev.wissam.sshterm.audit.test/androidx.test.runner.AndroidJUnitRunner | tee "$result"
  if ! grep -Eq '^OK \([0-9]+ tests?\)' "$result" || grep -Eq 'FAILURES|INSTRUMENTATION_FAILED|Process crashed' "$result"; then
    rm -f "$result"
    return 1
  fi
  rm -f "$result"
}
run_instrumentation -e package dev.wissam.sshterm.security -e notPackage dev.wissam.sshterm.security.keystore
run_instrumentation -e notPackage dev.wissam.sshterm.security
