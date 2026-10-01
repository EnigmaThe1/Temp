#!/usr/bin/env bash
set -Eeuo pipefail

VERSION="${DIGIKAM_VERSION:-9.1.0}"
CRAFT_ROOT="/home/user/CraftRoot"
WORK_ROOT="/workspace/.digikam-android-work"
OUT_ROOT="/workspace/digikam-android-out"
LOG_ROOT="${OUT_ROOT}/logs"
SRC_ARCHIVE="${WORK_ROOT}/digiKam-${VERSION}.tar.xz"
SOURCE_URL="https://download.kde.org/stable/digikam/${VERSION}/digiKam-${VERSION}.tar.xz"

mkdir -p "${WORK_ROOT}" "${OUT_ROOT}" "${LOG_ROOT}"
rm -f "${OUT_ROOT}/build-status.txt"

stage() {
    echo
    echo "===================================================================="
    echo "== $*"
    echo "===================================================================="
}

run_logged() {
    local name="$1"
    shift
    stage "$name"
    set +e
    "$@" 2>&1 | tee "${LOG_ROOT}/${name}.log"
    local rc=${PIPESTATUS[0]}
    set -e
    echo "${rc}" > "${LOG_ROOT}/${name}.exit"
    if [[ ${rc} -ne 0 ]]; then
        echo "FAILED_STAGE=${name}" | tee "${OUT_ROOT}/build-status.txt"
        exit "${rc}"
    fi
}

stage "Environment"
echo "User: $(id)"
echo "Android SDK: ${ANDROID_SDK_ROOT:-${ANDROID_HOME:-unset}}"
echo "Android NDK: ${ANDROID_NDK:-${ANDROID_NDK_ROOT:-unset}}"
echo "PATH=${PATH}"
df -h || true

stage "Fetch digiKam ${VERSION}"
rm -rf "${WORK_ROOT}/source"
mkdir -p "${WORK_ROOT}/source"
curl -fL --retry 4 --retry-delay 5 "${SOURCE_URL}" -o "${SRC_ARCHIVE}"
tar -xJf "${SRC_ARCHIVE}" -C "${WORK_ROOT}/source"
SRC_DIR="$(find "${WORK_ROOT}/source" -mindepth 1 -maxdepth 1 -type d | head -n1)"
if [[ -z "${SRC_DIR}" || ! -d "${SRC_DIR}/core" ]]; then
    echo "Could not locate extracted digiKam source tree" >&2
    find "${WORK_ROOT}/source" -maxdepth 2 -type d -print
    exit 2
fi
echo "Source: ${SRC_DIR}"

stage "Apply Android source compatibility layer"
python3 /workspace/digikam-android/patch-source.py "${SRC_DIR}" | tee "${LOG_ROOT}/patch-source.log"

stage "Bootstrap KDE Craft for Android"
if [[ ! -f "${CRAFT_ROOT}/craft/craftenv.sh" ]]; then
    rm -rf "${CRAFT_ROOT:?}"/*
    curl -fL --retry 4       https://raw.githubusercontent.com/KDE/craft/master/setup/CraftBootstrap.py       -o /tmp/CraftBootstrap.py
    python3 /tmp/CraftBootstrap.py --prefix "${CRAFT_ROOT}" --branch master --use-defaults 2>&1 | tee "${LOG_ROOT}/craft-bootstrap.log"
fi

# shellcheck disable=SC1091
source "${CRAFT_ROOT}/craft/craftenv.sh"

stage "Refresh Craft blueprints"
craft -i craft-blueprints-kde 2>&1 | tee "${LOG_ROOT}/craft-blueprints-update.log"

DIGIKAM_BP="$(find "${CRAFT_ROOT}" -type f -path '*/extragear/digikam/digikam.py' -print -quit)"
if [[ -z "${DIGIKAM_BP}" ]]; then
    echo "Could not locate digiKam Craft blueprint" >&2
    find "${CRAFT_ROOT}" -maxdepth 6 -type f -name 'digikam.py' -print || true
    exit 3
fi
python3 /workspace/digikam-android/patch-blueprint.py "${DIGIKAM_BP}" | tee "${LOG_ROOT}/patch-blueprint.log"

# Preserve exactly what was used for this build.
mkdir -p "${OUT_ROOT}/evidence"
cp "${DIGIKAM_BP}" "${OUT_ROOT}/evidence/digikam.android.blueprint.py"
cp "${SRC_DIR}/core/app/DigikamTarget.cmake" "${OUT_ROOT}/evidence/DigikamTarget.cmake"
cp "${SRC_DIR}/core/app/main/main.cpp" "${OUT_ROOT}/evidence/main.cpp"
cp -R "${SRC_DIR}/core/app/android" "${OUT_ROOT}/evidence/android"

CRAFT_OPT="digikam.srcDir=${SRC_DIR}"

run_logged "01-install-deps" craft --options "${CRAFT_OPT}" --install-deps digikam
run_logged "02-configure" craft --options "${CRAFT_OPT}" --configure digikam
run_logged "03-compile" craft --options "${CRAFT_OPT}" --compile digikam
run_logged "04-install" craft --options "${CRAFT_OPT}" --install digikam

stage "Create APK target"
set +e
cb digikam
CB_RC=$?
set -e
if [[ ${CB_RC} -ne 0 ]]; then
    echo "Craft could not enter the digiKam build directory" >&2
    exit ${CB_RC}
fi
BUILD_DIR="$PWD"
echo "Build dir: ${BUILD_DIR}" | tee "${LOG_ROOT}/build-dir.log"

run_logged "05-create-apk" cmake --build . --target create-apk-digikam --parallel 2

stage "Collect APK artifacts"
mkdir -p "${OUT_ROOT}/apk"
mapfile -t APKS < <(find "${BUILD_DIR}" "${CRAFT_ROOT}/tmp" -type f -name '*.apk' 2>/dev/null | sort -u)
if [[ ${#APKS[@]} -eq 0 ]]; then
    echo "No APK was found after create-apk-digikam" >&2
    find "${BUILD_DIR}" -maxdepth 4 -type f | sort | tail -n 400 > "${LOG_ROOT}/build-files-tail.txt" || true
    exit 4
fi

for apk in "${APKS[@]}"; do
    base="$(basename "${apk}")"
    cp -f "${apk}" "${OUT_ROOT}/apk/${base}"
done

# KDE Craft/ECM commonly produces an unsigned APK. Sign a disposable test APK
# so the artifact can be installed directly on a device for validation.
SDK_ROOT="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
if [[ -n "${SDK_ROOT}" && -d "${SDK_ROOT}/build-tools" ]]; then
    BUILD_TOOLS="$(find "${SDK_ROOT}/build-tools" -mindepth 1 -maxdepth 1 -type d | sort -V | tail -n1)"
    ZIPALIGN="${BUILD_TOOLS}/zipalign"
    APKSIGNER="${BUILD_TOOLS}/apksigner"
    if [[ -x "${ZIPALIGN}" && -x "${APKSIGNER}" ]]; then
        KEYSTORE="${WORK_ROOT}/digikam-test.keystore"
        keytool -genkeypair -noprompt           -keystore "${KEYSTORE}"           -storepass android           -keypass android           -alias androiddebugkey           -keyalg RSA           -keysize 2048           -validity 10000           -dname "CN=digiKam Android Test,OU=Temp Build,O=Local,C=GB" >/dev/null 2>&1

        SRC_APK="${APKS[0]}"
        ALIGNED="${WORK_ROOT}/digikam-${VERSION}-arm64-v8a-aligned.apk"
        SIGNED="${OUT_ROOT}/apk/digikam-${VERSION}-arm64-v8a-test-signed.apk"
        "${ZIPALIGN}" -p -f 4 "${SRC_APK}" "${ALIGNED}"
        "${APKSIGNER}" sign           --ks "${KEYSTORE}"           --ks-key-alias androiddebugkey           --ks-pass pass:android           --key-pass pass:android           --out "${SIGNED}"           "${ALIGNED}"
        "${APKSIGNER}" verify --verbose "${SIGNED}" | tee "${LOG_ROOT}/apksigner-verify.log"
        rm -f "${KEYSTORE}" "${ALIGNED}"
    else
        echo "zipalign/apksigner not found; leaving unsigned APK only" | tee "${LOG_ROOT}/signing-warning.log"
    fi
fi

sha256sum "${OUT_ROOT}"/apk/*.apk | tee "${OUT_ROOT}/SHA256SUMS.txt"
echo "SUCCESS" | tee "${OUT_ROOT}/build-status.txt"
stage "Done"
find "${OUT_ROOT}" -maxdepth 3 -type f -printf '%p %k KB\n' | sort
