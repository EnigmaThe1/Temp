#!/usr/bin/env python3
from __future__ import annotations

import sys
from pathlib import Path

if len(sys.argv) != 2:
    raise SystemExit("usage: patch-blueprint.py <digikam.py>")

bp = Path(sys.argv[1]).resolve()
text = bp.read_text(encoding="utf-8")

# Qt WebEngine is not an Android dependency for this port.
text = text.replace(
    '        else:\n            self.runtimeDependencies["libs/qt/qtwebengine"] = None',
    '        elif not CraftCore.compiler.isAndroid:\n            self.runtimeDependencies["libs/qt/qtwebengine"] = None',
)

# The upstream digiKam Craft recipe adds the Plasma Breeze desktop style
# unconditionally. That brings desktop KCM dependencies into Android and
# fails at KF5KCMUtils. Keep Breeze on desktop and omit it only on Android.
breeze_dep = '        self.runtimeDependencies["kde/plasma/breeze"] = None\n'
breeze_guard = '''        if not CraftCore.compiler.isAndroid:
            # Plasma Breeze is a desktop Qt Widgets style. Android uses the
            # platform/mobile presentation layer instead.
            self.runtimeDependencies["kde/plasma/breeze"] = None
'''
if breeze_guard not in text:
    if breeze_dep not in text:
        raise SystemExit("Could not locate digiKam Plasma Breeze dependency")
    text = text.replace(breeze_dep, breeze_guard, 1)

# digiKam's history graph uses Boost.Graph templates. The Android build only
# needs the complete Boost header tree; the retired Craft boost-graph package
# unnecessarily pulls compiled boost-regex and its broken legacy bjam path.
boost_headers_guard = '''        if CraftCore.compiler.isAndroid:
            self.buildDependencies["libs/boost/boost-headers"] = None
'''
if boost_headers_guard not in text:
    opencv_dep = '        self.runtimeDependencies["libs/opencv/opencv"] = None\n'
    if opencv_dep not in text:
        raise SystemExit("Could not locate digiKam OpenCV dependency")
    text = text.replace(opencv_dep, opencv_dep + boost_headers_guard, 1)

# The first Android build uses SQLite only.
text = text.replace(
    '        self.runtimeDependencies["binary/mysql"] = None',
    '        if not CraftCore.compiler.isAndroid:\n            self.runtimeDependencies["binary/mysql"] = None',
)

# FFmpeg is already excluded by upstream's blueprint on Android. Its codec-side
# helpers are unnecessary while the media player is disabled.
for dep in (
    '        self.runtimeDependencies["libs/x265"] = None\n',
    '        self.runtimeDependencies["libs/libass"] = None\n',
):
    text = text.replace(dep, f'        if not CraftCore.compiler.isAndroid:\n    {dep}')

android_block = '''
        if CraftCore.compiler.isAndroid:
            self.subinfo.options.configure.args = [
                "-DENABLE_KFILEMETADATASUPPORT=OFF",
                "-DENABLE_AKONADICONTACTSUPPORT=OFF",
                "-DENABLE_MEDIAPLAYER=OFF",
                "-DCMAKE_DISABLE_FIND_PACKAGE_FFmpeg=ON",
                "-DCMAKE_DISABLE_FIND_PACKAGE_QtAV=ON",
                "-DENABLE_DBUS=OFF",
                "-DENABLE_QWEBENGINE=OFF",
                "-DENABLE_KIO=OFF",
                "-DENABLE_MYSQLSUPPORT=OFF",
                "-DENABLE_INTERNALMYSQL=OFF",
                "-DENABLE_DIGIKAM_MODELTEST=OFF",
                "-DDIGIKAMSC_COMPILE_PO=OFF",
                "-DDIGIKAMSC_COMPILE_DOC=OFF",
                "-DDIGIKAMSC_COMPILE_DIGIKAM=ON",
                "-DBUILD_TESTING=OFF",
                f"-DOpenCV_DIR={CraftCore.standardDirs.craftRoot() / 'sdk/native/jni'}",
            ]

'''

needle = "        if CraftCore.compiler.isLinux:\n"
if android_block.strip() not in text:
    if needle not in text:
        raise SystemExit("Could not locate configure section in digiKam Craft blueprint")
    text = text.replace(needle, android_block + needle, 1)

bp.write_text(text, encoding="utf-8")
print(f"Patched Craft blueprint: {bp}")
