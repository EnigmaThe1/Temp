#!/usr/bin/env bash
set -Eeuo pipefail

VERSION="${DIGIKAM_VERSION:-9.1.0}"
CRAFT_ROOT="${CRAFT_ROOT:-/home/user/CraftRoot}"
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
curl -fL \
    --retry 12 \
    --retry-all-errors \
    --retry-delay 3 \
    --retry-max-time 900 \
    --connect-timeout 30 \
    --continue-at - \
    "${SOURCE_URL}" -o "${SRC_ARCHIVE}"
tar -xJf "${SRC_ARCHIVE}" -C "${WORK_ROOT}/source"
SRC_DIR="$(find "${WORK_ROOT}/source" -mindepth 1 -maxdepth 1 -type d | head -n1)"
if [[ -z "${SRC_DIR}" || ! -d "${SRC_DIR}/core" ]]; then
    echo "Could not locate extracted digiKam source tree" >&2
    find "${WORK_ROOT}/source" -maxdepth 2 -type d -print
    exit 2
fi
echo "Source: ${SRC_DIR}"

# Preserve key upstream files before any downstream patching so failures are inspectable.
mkdir -p "${OUT_ROOT}/evidence/upstream"
cp "${SRC_DIR}/core/app/main/main.cpp" "${OUT_ROOT}/evidence/upstream/main.cpp"
cp "${SRC_DIR}/core/app/DigikamTarget.cmake" "${OUT_ROOT}/evidence/upstream/DigikamTarget.cmake"

stage "Apply Android source compatibility layer"
python3 /workspace/digikam-android/patch-source.py "${SRC_DIR}" | tee "${LOG_ROOT}/patch-source.log"

stage "Bootstrap KDE Craft for Android"
if [[ ! -f "${CRAFT_ROOT}/craft/craftenv.sh" ]]; then
    rm -rf "${CRAFT_ROOT:?}"/*
    curl -fL --retry 8 --retry-all-errors --retry-delay 2 \
      https://raw.githubusercontent.com/KDE/craft/master/setup/CraftBootstrap.py \
      -o /tmp/CraftBootstrap.py
    python3 /tmp/CraftBootstrap.py --prefix "${CRAFT_ROOT}" --branch master --use-defaults 2>&1 | tee "${LOG_ROOT}/craft-bootstrap.log"
fi

# Craft's environment script intentionally probes variables that can be unset.
# Disable Bash nounset only for the source operation, then restore strict mode.
# shellcheck disable=SC1091
set +u
source "${CRAFT_ROOT}/craft/craftenv.sh"
set -u

stage "Patch retired Craft Android compatibility"
MESON_BUILD_SYSTEM="$(find "${CRAFT_SEARCH_ROOTS[@]:-${CRAFT_ROOT}}" "${CRAFT_HOME:-$(dirname "${CRAFT_ROOT}")}" \
    -type f -path '*/bin/BuildSystem/MesonBuildSystem.py' -print -quit 2>/dev/null || true)"
if [[ -z "${MESON_BUILD_SYSTEM}" ]]; then
    MESON_BUILD_SYSTEM="$(find "$(dirname "${CRAFT_ROOT}")" -type f -path '*/bin/BuildSystem/MesonBuildSystem.py' -print -quit 2>/dev/null || true)"
fi
if [[ -z "${MESON_BUILD_SYSTEM}" ]]; then
    echo "Could not locate retired Craft MesonBuildSystem.py" >&2
    exit 11
fi
python3 /workspace/digikam-android/patch-craft-meson.py "${MESON_BUILD_SYSTEM}" | tee "${LOG_ROOT}/patch-craft-meson.log"

stage "Prepare Craft blueprints"

# Current Craft keeps blueprints below CRAFT_ROOT. KDE's retired qt5-lts
# Android workspace keeps them as siblings under /home/user. Search both
# layouts so the same port scripts work with the pinned Qt5 toolchain.
CRAFT_HOME="$(dirname "${CRAFT_ROOT}")"
CRAFT_SEARCH_ROOTS=("${CRAFT_ROOT}")
for candidate in "${CRAFT_HOME}/blueprints" "${CRAFT_HOME}/craft-clone"; do
    if [[ -e "${candidate}" ]]; then
        CRAFT_SEARCH_ROOTS+=("${candidate}")
    fi
done
echo "Craft search roots: ${CRAFT_SEARCH_ROOTS[*]}"

if [[ "${DIGIKAM_SKIP_CRAFT_REFRESH:-0}" == "1" ]]; then
    echo "Using the pinned Craft/blueprint revisions already present in the toolchain image." | tee "${LOG_ROOT}/craft-blueprints-update.log"
else
    craft -i craft-blueprints-kde 2>&1 | tee "${LOG_ROOT}/craft-blueprints-update.log"
fi

# The Actions cache intentionally preserves Craft's compiled package state.
# It also preserves our locally modified blueprint working trees, so reset only
# the files this Android port patches before reapplying those patches. This
# makes every iteration deterministic without throwing away built dependencies.
KDE_BP_ROOT="$(find "${CRAFT_SEARCH_ROOTS[@]}" -type d -path '*/craft-blueprints-kde/.git' -printf '%h\n' -quit 2>/dev/null || true)"
if [[ -n "${KDE_BP_ROOT}" && -d "${KDE_BP_ROOT}/.git" ]]; then
    echo "Resetting patched KDE blueprints from: ${KDE_BP_ROOT}"
    for rel in \
        extragear/digikam/digikam.py \
        libs/opencv/opencv/opencv.py \
        libs/lensfun/lensfun.py \
        libs/libusb/libusb.py \
        libs/glib/glib.py \
        libs/qt5/qtbase/qtbase.py \
        libs/qt5/qtmultimedia/qtmultimedia.py \
        kde/applications/marble/marble.py
    do
        if [[ -e "${KDE_BP_ROOT}/${rel}" ]]; then
            git -C "${KDE_BP_ROOT}" checkout -- "${rel}"
        fi
    done
else
    echo "craft-blueprints-kde Git checkout is not exposed in this Craft layout; continuing with idempotent patchers."
fi

LIBFFI_RESET_BP="$(find "${CRAFT_SEARCH_ROOTS[@]}" -type f -path '*/blueprints/libs/libffi/libffi.py' -print -quit 2>/dev/null || true)"
if [[ -n "${LIBFFI_RESET_BP}" ]]; then
    LIBFFI_GIT_ROOT="$(git -C "$(dirname "${LIBFFI_RESET_BP}")" rev-parse --show-toplevel 2>/dev/null || true)"
    if [[ -n "${LIBFFI_GIT_ROOT}" ]]; then
        LIBFFI_REL="${LIBFFI_RESET_BP#"${LIBFFI_GIT_ROOT}/"}"
        git -C "${LIBFFI_GIT_ROOT}" checkout -- "${LIBFFI_REL}" || true
    fi
fi

DIGIKAM_BP="$(find "${CRAFT_SEARCH_ROOTS[@]}" -type f -path '*/extragear/digikam/digikam.py' -print -quit 2>/dev/null || true)"
if [[ -z "${DIGIKAM_BP}" ]]; then
    echo "Could not locate digiKam Craft blueprint" >&2
    find "${CRAFT_SEARCH_ROOTS[@]}" -maxdepth 8 -type f -name 'digikam.py' -print 2>/dev/null || true
    exit 3
fi
python3 /workspace/digikam-android/patch-blueprint.py "${DIGIKAM_BP}" | tee "${LOG_ROOT}/patch-blueprint.log"

OPENCV_BP="$(find "${CRAFT_SEARCH_ROOTS[@]}" -type f -path '*/libs/opencv/opencv/opencv.py' -print -quit 2>/dev/null || true)"
if [[ -z "${OPENCV_BP}" ]]; then
    echo "Could not locate OpenCV Craft blueprint" >&2
    exit 5
fi
python3 /workspace/digikam-android/patch-opencv-blueprint.py "${OPENCV_BP}" | tee "${LOG_ROOT}/patch-opencv-blueprint.log"

LIBFFI_BP="$(find "${CRAFT_SEARCH_ROOTS[@]}" -type f -path '*/blueprints/libs/libffi/libffi.py' -print -quit 2>/dev/null || true)"
if [[ -z "${LIBFFI_BP}" ]]; then
    echo "Could not locate libffi Craft blueprint" >&2
    exit 6
fi
python3 /workspace/digikam-android/patch-libffi-blueprint.py "${LIBFFI_BP}" | tee "${LOG_ROOT}/patch-libffi-blueprint.log"

LENSFUN_BP="$(find "${CRAFT_SEARCH_ROOTS[@]}" -type f -path '*/libs/lensfun/lensfun.py' -print -quit 2>/dev/null || true)"
if [[ -z "${LENSFUN_BP}" ]]; then
    echo "Could not locate Lensfun Craft blueprint" >&2
    exit 7
fi
python3 /workspace/digikam-android/patch-lensfun-blueprint.py "${LENSFUN_BP}" | tee "${LOG_ROOT}/patch-lensfun-blueprint.log"

LIBUSB_BP="$(find "${CRAFT_SEARCH_ROOTS[@]}" -type f -path '*/libs/libusb/libusb.py' -print -quit 2>/dev/null || true)"
if [[ -z "${LIBUSB_BP}" ]]; then
    echo "Could not locate libusb Craft blueprint" >&2
    exit 8
fi
python3 /workspace/digikam-android/patch-libusb-blueprint.py "${LIBUSB_BP}" | tee "${LOG_ROOT}/patch-libusb-blueprint.log"

GLIB_BP="$(find "${CRAFT_SEARCH_ROOTS[@]}" -type f -path '*/libs/glib/glib.py' -print -quit 2>/dev/null || true)"
if [[ -z "${GLIB_BP}" ]]; then
    echo "Could not locate GLib Craft blueprint" >&2
    exit 12
fi
python3 /workspace/digikam-android/patch-glib-blueprint.py "${GLIB_BP}" | tee "${LOG_ROOT}/patch-glib-blueprint.log"

QTBASE_BP="$(find "${CRAFT_SEARCH_ROOTS[@]}" -type f -path '*/libs/qt5/qtbase/qtbase.py' -print -quit 2>/dev/null || true)"
if [[ -z "${QTBASE_BP}" ]]; then
    echo "Could not locate Qt5 qtbase Craft blueprint" >&2
    exit 21
fi
python3 /workspace/digikam-android/patch-qtbase-blueprint.py "${QTBASE_BP}" | tee "${LOG_ROOT}/patch-qtbase-blueprint.log"

QTMULTIMEDIA_BP="$(find "${CRAFT_SEARCH_ROOTS[@]}" -type f -path '*/libs/qt5/qtmultimedia/qtmultimedia.py' -print -quit 2>/dev/null || true)"
if [[ -z "${QTMULTIMEDIA_BP}" ]]; then
    echo "Could not locate Qt5 qtmultimedia Craft blueprint" >&2
    exit 22
fi
python3 /workspace/digikam-android/patch-qtmultimedia-blueprint.py "${QTMULTIMEDIA_BP}" | tee "${LOG_ROOT}/patch-qtmultimedia-blueprint.log"

MARBLE_BP="$(find "${CRAFT_SEARCH_ROOTS[@]}" -type f -path '*/kde/applications/marble/marble.py' -print -quit 2>/dev/null || true)"
if [[ -z "${MARBLE_BP}" ]]; then
    echo "Could not locate Marble Craft blueprint" >&2
    exit 10
fi
python3 /workspace/digikam-android/patch-marble-blueprint.py "${MARBLE_BP}" | tee "${LOG_ROOT}/patch-marble-blueprint.log"

# Preserve exactly what was used for this build.
mkdir -p "${OUT_ROOT}/evidence"
cp "${DIGIKAM_BP}" "${OUT_ROOT}/evidence/digikam.android.blueprint.py"
cp "${OPENCV_BP}" "${OUT_ROOT}/evidence/opencv.android.blueprint.py"
cp "${LIBFFI_BP}" "${OUT_ROOT}/evidence/libffi.android.blueprint.py"
cp "${LENSFUN_BP}" "${OUT_ROOT}/evidence/lensfun.android.blueprint.py"
cp "${LIBUSB_BP}" "${OUT_ROOT}/evidence/libusb.android.blueprint.py"
cp "${GLIB_BP}" "${OUT_ROOT}/evidence/glib.android.blueprint.py"
cp "${QTBASE_BP}" "${OUT_ROOT}/evidence/qtbase.android.blueprint.py"
cp "${QTMULTIMEDIA_BP}" "${OUT_ROOT}/evidence/qtmultimedia.android.blueprint.py"
cp "${MARBLE_BP}" "${OUT_ROOT}/evidence/marble.android.blueprint.py"
cp "${SRC_DIR}/core/app/DigikamTarget.cmake" "${OUT_ROOT}/evidence/DigikamTarget.cmake"
cp "${SRC_DIR}/core/app/main/main.cpp" "${OUT_ROOT}/evidence/main.cpp"
cp -R "${SRC_DIR}/core/app/android" "${OUT_ROOT}/evidence/android"

CRAFT_OPT="digikam.srcDir=${SRC_DIR}"

stage "Ensure Android iconv/libintl runtime prerequisites"
if ! compgen -G "${CRAFT_ROOT}/lib/libiconv.*" >/dev/null; then
    echo "libiconv is missing from the Android prefix; cross-building KDE's pinned GNU libiconv 1.15."

    ICONV_VER="1.15"
    ICONV_SHA256="ccf536620a45458d26ba83887a983b96827001e92a13847b45e4925cc8913178"
    ICONV_ARCHIVE="${WORK_ROOT}/libiconv-${ICONV_VER}.tar.gz"
    ICONV_SRC="${WORK_ROOT}/libiconv-${ICONV_VER}"
    ICONV_URL="https://ftp.gnu.org/pub/gnu/libiconv/libiconv-${ICONV_VER}.tar.gz"

    curl -fL --retry 10 --retry-all-errors --retry-delay 2 \
        "${ICONV_URL}" -o "${ICONV_ARCHIVE}"
    echo "${ICONV_SHA256}  ${ICONV_ARCHIVE}" | sha256sum -c -

    rm -rf "${ICONV_SRC}"
    tar -xzf "${ICONV_ARCHIVE}" -C "${WORK_ROOT}"

    NDK_ROOT="${ANDROID_NDK:-${ANDROID_NDK_ROOT:-}}"
    if [[ -z "${NDK_ROOT}" ]]; then
        echo "ANDROID_NDK/ANDROID_NDK_ROOT is not set" >&2
        exit 13
    fi

    NDK_HOST="${ANDROID_NDK_HOST:-linux-x86_64}"
    NDK_BIN="${NDK_ROOT}/toolchains/llvm/prebuilt/${NDK_HOST}/bin"
    API_LEVEL="${ANDROID_API_LEVEL:-21}"

    stage "00-build-iconv"
    (
        cd "${ICONV_SRC}"
        export CC="${NDK_BIN}/aarch64-linux-android${API_LEVEL}-clang"
        export CXX="${NDK_BIN}/aarch64-linux-android${API_LEVEL}-clang++"
        export AR="${NDK_BIN}/llvm-ar"
        export RANLIB="${NDK_BIN}/llvm-ranlib"
        export STRIP="${NDK_BIN}/llvm-strip"
        export LD="${NDK_BIN}/ld.lld"

        ./configure \
            --host=aarch64-linux-android \
            --prefix="${CRAFT_ROOT}" \
            --disable-static \
            --enable-shared
        make -j2
        make install
    ) 2>&1 | tee "${LOG_ROOT}/00-build-iconv.log"
    ICONV_RC=${PIPESTATUS[0]}

    if [[ ${ICONV_RC} -ne 0 ]]; then
        echo "Direct Android libiconv build failed." >&2
        exit "${ICONV_RC}"
    fi

    if ! compgen -G "${CRAFT_ROOT}/lib/libiconv.*" >/dev/null; then
        echo "libiconv build completed but no linkable library was installed." >&2
        find "${CRAFT_ROOT}" -maxdepth 4 \( -name 'libiconv*' -o -name 'iconv.h' \) -print || true
        exit 14
    fi

    echo "Installed Android libiconv:"
    ls -l "${CRAFT_ROOT}"/lib/libiconv.* "${CRAFT_ROOT}"/include/iconv.h || true
else
    echo "libiconv already present in ${CRAFT_ROOT}/lib"
fi

if ! compgen -G "${CRAFT_ROOT}/lib/libintl.*" >/dev/null; then
    echo "libintl is missing from the cached Android prefix; building libintl-lite."
    run_logged "00-install-libintl" craft -i libs/libintl-lite
else
    echo "libintl already present in ${CRAFT_ROOT}/lib"
fi

stage "Ensure Android libintl-lite GNU API compatibility"
LIBINTL_HEADER="${CRAFT_ROOT}/include/libintl.h"
LIBINTL_ARCHIVE="${CRAFT_ROOT}/lib/libintl.a"
NDK_ROOT="${ANDROID_NDK:-${ANDROID_NDK_ROOT:-}}"
NDK_HOST="${ANDROID_NDK_HOST:-linux-x86_64}"
NDK_BIN="${NDK_ROOT}/toolchains/llvm/prebuilt/${NDK_HOST}/bin"
API_LEVEL="${ANDROID_API_LEVEL:-21}"

if [[ -z "${NDK_ROOT}" || ! -x "${NDK_BIN}/llvm-nm" ]]; then
    echo "Android NDK tools are unavailable for libintl compatibility shim." >&2
    exit 18
fi

if [[ ! -f "${LIBINTL_HEADER}" || ! -f "${LIBINTL_ARCHIVE}" ]]; then
    echo "Expected libintl-lite header/archive are missing after installation." >&2
    exit 19
fi

python3 - "${LIBINTL_HEADER}" <<'PY'
from pathlib import Path
import sys

p = Path(sys.argv[1])
text = p.read_text(encoding="utf-8")

# libintl-lite intentionally implements a small gettext subset. GLib expects
# the GNU gettext API surface and compiler format-argument annotations.
# Add only the missing compatibility declarations/metadata, idempotently.
if "LIBINTL_LITE_FORMAT_ARG" not in text:
    marker = "#ifdef __cplusplus\n"
    compat_macro = """#if defined(__GNUC__) || defined(__clang__)
#  define LIBINTL_LITE_FORMAT_ARG(n) __attribute__((format_arg(n)))
#else
#  define LIBINTL_LITE_FORMAT_ARG(n)
#endif

"""
    if marker not in text:
        raise SystemExit("Could not locate C++ linkage marker in libintl-lite header")
    text = text.replace(marker, compat_macro + marker, 1)

decl_replacements = {
    "LIBINTL_LITE_API const char* gettext(const char* origStr);":
        "LIBINTL_LITE_API const char* gettext(const char* origStr) LIBINTL_LITE_FORMAT_ARG(1);",
    "LIBINTL_LITE_API const char* dgettext(const char* domain, const char* origStr);":
        "LIBINTL_LITE_API const char* dgettext(const char* domain, const char* origStr) LIBINTL_LITE_FORMAT_ARG(2);",
}

for old, new in decl_replacements.items():
    if new not in text:
        if old not in text:
            raise SystemExit(f"Could not locate libintl-lite declaration: {old}")
        text = text.replace(old, new, 1)

if "const char* dcgettext(" not in text:
    anchor = "LIBINTL_LITE_API const char* dgettext(const char* domain, const char* origStr) LIBINTL_LITE_FORMAT_ARG(2);\n"
    addition = anchor + "LIBINTL_LITE_API const char* dcgettext(const char* domain, const char* origStr, int category) LIBINTL_LITE_FORMAT_ARG(2);\n"
    if anchor not in text:
        raise SystemExit("Could not locate annotated dgettext declaration")
    text = text.replace(anchor, addition, 1)
elif "dcgettext(const char* domain, const char* origStr, int category) LIBINTL_LITE_FORMAT_ARG(2)" not in text:
    old = "LIBINTL_LITE_API const char* dcgettext(const char* domain, const char* origStr, int category);"
    new = "LIBINTL_LITE_API const char* dcgettext(const char* domain, const char* origStr, int category) LIBINTL_LITE_FORMAT_ARG(2);"
    if old not in text:
        raise SystemExit("Could not annotate existing dcgettext declaration")
    text = text.replace(old, new, 1)

if "const char* dcngettext(" not in text:
    anchor = "LIBINTL_LITE_API const char* dngettext(const char* domain, const char* origStr, const char* origStrPlural, unsigned long n);\n"
    addition = anchor + "LIBINTL_LITE_API const char* dcngettext(const char* domain, const char* origStr, const char* origStrPlural, unsigned long n, int category);\n"
    if anchor not in text:
        raise SystemExit("Could not locate dngettext declaration")
    text = text.replace(anchor, addition, 1)

# This is a C declaration, not an old-style unspecified-arguments function.
text = text.replace(
    "LIBINTL_LITE_API void closeAllLoadedMessageCatalogs();",
    "LIBINTL_LITE_API void closeAllLoadedMessageCatalogs(void);",
)

p.write_text(text, encoding="utf-8")
print("Ensured GNU gettext declarations and format_arg annotations in:", p)
PY

if ! "${NDK_BIN}/llvm-nm" "${LIBINTL_ARCHIVE}" 2>/dev/null | grep -q ' T dcgettext$'; then
    INTL_COMPAT_C="${WORK_ROOT}/libintl-lite-gnu-compat.c"
    INTL_COMPAT_O="${WORK_ROOT}/libintl-lite-gnu-compat.o"

    cat > "${INTL_COMPAT_C}" <<'C'
#include <libintl.h>

const char* dcgettext(const char* domain, const char* msgid, int category)
{
    (void)category;
    return dgettext(domain, msgid);
}

const char* dcngettext(const char* domain,
                       const char* msgid,
                       const char* msgid_plural,
                       unsigned long n,
                       int category)
{
    (void)category;
    return dngettext(domain, msgid, msgid_plural, n);
}
C

    "${NDK_BIN}/aarch64-linux-android${API_LEVEL}-clang" \
        -I"${CRAFT_ROOT}/include" \
        -c "${INTL_COMPAT_C}" -o "${INTL_COMPAT_O}"
    "${NDK_BIN}/llvm-ar" r "${LIBINTL_ARCHIVE}" "${INTL_COMPAT_O}"
    "${NDK_BIN}/llvm-ranlib" "${LIBINTL_ARCHIVE}"
fi

if ! "${NDK_BIN}/llvm-nm" "${LIBINTL_ARCHIVE}" 2>/dev/null | grep -q ' T dcgettext$'; then
    echo "Failed to add dcgettext compatibility symbol to libintl-lite." >&2
    exit 20
fi

stage "Ensure Android PCRE2 runtime prerequisite"
if ! compgen -G "${CRAFT_ROOT}/lib/libpcre2-8.*" >/dev/null || \
   [[ ! -f "${CRAFT_ROOT}/lib/pkgconfig/libpcre2-8.pc" ]]; then
    echo "PCRE2 is missing/incomplete in the Android prefix; cross-building KDE's pinned PCRE2 10.42."

    PCRE2_VER="10.42"
    PCRE2_SHA256="c33b418e3b936ee3153de2c61cc638e7e4fe3156022a5c77d0711bcbb9d64f1f"
    PCRE2_ARCHIVE="${WORK_ROOT}/pcre2-${PCRE2_VER}.tar.gz"
    PCRE2_SRC="${WORK_ROOT}/pcre2-${PCRE2_VER}"
    PCRE2_BUILD="${WORK_ROOT}/pcre2-build"
    PCRE2_URL="https://github.com/PCRE2Project/pcre2/releases/download/pcre2-${PCRE2_VER}/pcre2-${PCRE2_VER}.tar.gz"

    curl -fL --retry 10 --retry-all-errors --retry-delay 2 \
        "${PCRE2_URL}" -o "${PCRE2_ARCHIVE}"
    echo "${PCRE2_SHA256}  ${PCRE2_ARCHIVE}" | sha256sum -c -

    rm -rf "${PCRE2_SRC}" "${PCRE2_BUILD}"
    tar -xzf "${PCRE2_ARCHIVE}" -C "${WORK_ROOT}"

    NDK_ROOT="${ANDROID_NDK:-${ANDROID_NDK_ROOT:-}}"
    if [[ -z "${NDK_ROOT}" ]]; then
        echo "ANDROID_NDK/ANDROID_NDK_ROOT is not set" >&2
        exit 15
    fi

    stage "00-build-pcre2"
    cmake -S "${PCRE2_SRC}" -B "${PCRE2_BUILD}" -G Ninja \
        -DCMAKE_TOOLCHAIN_FILE="${NDK_ROOT}/build/cmake/android.toolchain.cmake" \
        -DANDROID_ABI=arm64-v8a \
        -DANDROID_PLATFORM=android-21 \
        -DCMAKE_BUILD_TYPE=MinSizeRel \
        -DCMAKE_INSTALL_PREFIX="${CRAFT_ROOT}" \
        -DBUILD_SHARED_LIBS=ON \
        -DBUILD_STATIC_LIBS=OFF \
        -DPCRE2_BUILD_PCRE2_8=ON \
        -DPCRE2_BUILD_PCRE2_16=ON \
        -DPCRE2_BUILD_PCRE2_32=ON \
        -DPCRE2_BUILD_PCRE2GREP=OFF \
        -DPCRE2_BUILD_TESTS=OFF \
        -DPCRE2_SUPPORT_LIBBZ2=OFF \
        -DPCRE2_SUPPORT_LIBZ=OFF
    cmake --build "${PCRE2_BUILD}" --parallel 2
    cmake --install "${PCRE2_BUILD}"

    if ! compgen -G "${CRAFT_ROOT}/lib/libpcre2-8.*" >/dev/null; then
        echo "PCRE2 direct build completed but libpcre2-8 is still missing." >&2
        find "${CRAFT_ROOT}" -maxdepth 5 -iname '*pcre2*' -print || true
        exit 16
    fi

    if [[ ! -f "${CRAFT_ROOT}/lib/pkgconfig/libpcre2-8.pc" ]]; then
        echo "PCRE2 library exists but libpcre2-8.pc is missing." >&2
        find "${CRAFT_ROOT}" -maxdepth 6 -name 'libpcre2-8.pc' -print || true
        exit 17
    fi

    echo "Installed Android PCRE2:"
    ls -l "${CRAFT_ROOT}"/lib/libpcre2-* "${CRAFT_ROOT}"/lib/pkgconfig/libpcre2-8.pc || true
else
    echo "PCRE2 already present and complete in ${CRAFT_ROOT}"
fi

stage "Ensure required Boost.Graph and OpenCV"
BOOST_GRAPH_HEADER="${CRAFT_ROOT}/include/boost/graph/adjacency_list.hpp"
if [[ ! -f "${BOOST_GRAPH_HEADER}" ]]; then
    echo "Boost.Graph headers are missing from the Android prefix; forcing the pinned Craft package to reinstall."
    run_logged "00-install-boost-graph" craft -i libs/boost/boost-graph
fi

if [[ ! -f "${BOOST_GRAPH_HEADER}" ]]; then
    echo "Boost.Graph reinstall completed but ${BOOST_GRAPH_HEADER} is still missing." >&2
    exit 25
fi

OPENCV_CONFIG="$(find "${CRAFT_ROOT}" -type f -name 'OpenCVConfig.cmake' -print -quit 2>/dev/null || true)"
if [[ -z "${OPENCV_CONFIG}" ]]; then
    echo "OpenCV CMake package metadata is missing from the Android prefix; forcing the patched Craft package to reinstall."
    run_logged "00-install-opencv" craft -i libs/opencv/opencv
    OPENCV_CONFIG="$(find "${CRAFT_ROOT}" -type f -name 'OpenCVConfig.cmake' -print -quit 2>/dev/null || true)"
fi

if [[ -z "${OPENCV_CONFIG}" ]]; then
    echo "OpenCV reinstall completed but OpenCVConfig.cmake is still missing." >&2
    exit 26
fi

echo "Required Android dependencies verified:"
echo "  Boost.Graph: ${BOOST_GRAPH_HEADER}"
echo "  OpenCV:      ${OPENCV_CONFIG}"

stage "Clear failed Qt5 unpack state"
# Failed Craft patch/unpack operations leave partially modified source
# checkouts in the persistent Actions cache. Remove only the disposable work
# trees whose Android patch policy is overridden by this port.
rm -rf "${CRAFT_ROOT}/build/libs/qt5/qtbase/work"
rm -rf "${CRAFT_ROOT}/build/libs/qt5/qtmultimedia/work"

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
