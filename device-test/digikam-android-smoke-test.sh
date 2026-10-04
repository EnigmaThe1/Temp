#!/usr/bin/env bash
set -Eeuo pipefail

PACKAGE="org.kde.digikam"
ACTIVITY="org.qtproject.qt5.android.bindings.QtActivity"
APK="${1:-}"
OUT_DIR="${DIGIKAM_DEVICE_TEST_OUT:-./digikam-device-smoke-$(date +%Y%m%d-%H%M%S)}"

usage() {
    cat <<'EOF'
Usage:
  device-test/digikam-android-smoke-test.sh /path/to/digikam-9.1.0-arm64-v8a-test-signed.apk

Environment:
  ANDROID_SERIAL            Select a specific adb device when more than one is connected.
  DIGIKAM_DEVICE_TEST_OUT   Directory for logcat/dumpsys evidence.
  DIGIKAM_TEST_WAIT         Seconds to wait after launch (default: 12).

The script installs the test APK with runtime permissions granted, launches
digiKam, checks that the process remains alive, captures Android evidence and
fails on common native/Qt/SQLite startup errors. It leaves the app running so
the UI can be inspected manually afterwards.
EOF
}

if [[ -z "${APK}" || "${APK}" == "-h" || "${APK}" == "--help" ]]; then
    usage
    [[ -n "${APK}" ]] && exit 0 || exit 2
fi

command -v adb >/dev/null 2>&1 || {
    echo "adb is required but was not found in PATH." >&2
    exit 3
}

[[ -f "${APK}" ]] || {
    echo "APK not found: ${APK}" >&2
    exit 4
}

mkdir -p "${OUT_DIR}"
APK="$(realpath "${APK}")"

adb start-server >/dev/null

mapfile -t DEVICES < <(adb devices | awk 'NR>1 && $2=="device" {print $1}')

if [[ -n "${ANDROID_SERIAL:-}" ]]; then
    ADB=(adb -s "${ANDROID_SERIAL}")
else
    if [[ ${#DEVICES[@]} -ne 1 ]]; then
        echo "Expected exactly one authorised adb device; found ${#DEVICES[@]}." >&2
        adb devices -l >&2
        echo "Set ANDROID_SERIAL when more than one device is connected." >&2
        exit 5
    fi
    ADB=(adb -s "${DEVICES[0]}")
fi

SERIAL="$("${ADB[@]}" get-serialno | tr -d '\r')"
ABI="$("${ADB[@]}" shell getprop ro.product.cpu.abi | tr -d '\r')"
SDK="$("${ADB[@]}" shell getprop ro.build.version.sdk | tr -d '\r')"
MODEL="$("${ADB[@]}" shell getprop ro.product.model | tr -d '\r')"

{
    echo "serial=${SERIAL}"
    echo "model=${MODEL}"
    echo "abi=${ABI}"
    echo "sdk=${SDK}"
    echo "apk=${APK}"
} | tee "${OUT_DIR}/device.txt"

case "${ABI}" in
    arm64-v8a|arm64*)
        ;;
    *)
        echo "This APK is ARM64-only; connected device ABI is ${ABI}." >&2
        exit 6
        ;;
esac

echo "Installing test APK..."
"${ADB[@]}" install -r -t -g "${APK}" | tee "${OUT_DIR}/install.txt"

"${ADB[@]}" shell am force-stop "${PACKAGE}" || true
"${ADB[@]}" logcat -c || true

echo "Launching digiKam..."
set +e
"${ADB[@]}" shell am start -W -n "${PACKAGE}/${ACTIVITY}"     | tee "${OUT_DIR}/launch.txt"
LAUNCH_RC=${PIPESTATUS[0]}
set -e

if [[ ${LAUNCH_RC} -ne 0 ]]; then
    echo "Android activity launch failed." >&2
    exit 7
fi

WAIT="${DIGIKAM_TEST_WAIT:-12}"
sleep "${WAIT}"

PID="$("${ADB[@]}" shell pidof "${PACKAGE}" 2>/dev/null | tr -d '\r' | awk '{print $1}')"

"${ADB[@]}" shell dumpsys package "${PACKAGE}" > "${OUT_DIR}/package.txt" || true
"${ADB[@]}" shell dumpsys activity activities > "${OUT_DIR}/activities.txt" || true
"${ADB[@]}" shell dumpsys window windows > "${OUT_DIR}/windows.txt" || true

if [[ -n "${PID}" ]]; then
    "${ADB[@]}" logcat --pid="${PID}" -d -v threadtime         > "${OUT_DIR}/logcat-process.txt" 2>/dev/null || true
fi

"${ADB[@]}" logcat -d -v threadtime > "${OUT_DIR}/logcat-all.txt" || true

if [[ -z "${PID}" ]]; then
    echo "digiKam process is not alive after ${WAIT}s." >&2
    grep -Ei         'org\.kde\.digikam|FATAL EXCEPTION|Fatal signal|UnsatisfiedLinkError|dlopen failed|linker'         "${OUT_DIR}/logcat-all.txt" | tail -n 200 >&2 || true
    exit 8
fi

echo "digiKam PID: ${PID}" | tee "${OUT_DIR}/pid.txt"

if ! grep -Fq "${PACKAGE}" "${OUT_DIR}/activities.txt"; then
    echo "Warning: digiKam is alive but is not visible in the activity dump."         | tee "${OUT_DIR}/activity-warning.txt"
fi

ERROR_RE='FATAL EXCEPTION|Fatal signal|UnsatisfiedLinkError|dlopen failed|cannot locate symbol|library .* not found|Could not load the Qt platform plugin|no Qt platform plugin could be initialized|QSqlDatabase: QSQLITE driver not loaded|Driver not loaded|database driver not loaded'

if grep -Eiq "${ERROR_RE}" "${OUT_DIR}/logcat-process.txt" "${OUT_DIR}/logcat-all.txt" 2>/dev/null; then
    echo "Startup log contains a fatal native/Qt/SQLite error:" >&2
    grep -Ein "${ERROR_RE}"         "${OUT_DIR}/logcat-process.txt" "${OUT_DIR}/logcat-all.txt"         | tail -n 160 >&2 || true
    exit 9
fi

echo
echo "ADB smoke test passed:"
echo "  device: ${MODEL} (${ABI}, Android API ${SDK})"
echo "  process: alive (PID ${PID})"
echo "  fatal native/Qt/SQLite startup errors: none detected"
echo "  evidence: ${OUT_DIR}"
echo
echo "Leave digiKam open and manually verify first-run setup, Browse, Albums,"
echo "People, Search, More -> Tags/Dates/Timeline/Similarity/Map, image opening,"
echo "drawer sizing/back behaviour and access to the device photo collection."
