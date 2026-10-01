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
                "-DENABLE_DBUS=OFF",
                "-DENABLE_QWEBENGINE=OFF",
                "-DENABLE_MYSQLSUPPORT=OFF",
                "-DENABLE_INTERNALMYSQL=OFF",
                "-DENABLE_DIGIKAM_MODELTEST=OFF",
                "-DDIGIKAMSC_COMPILE_PO=OFF",
                "-DDIGIKAMSC_COMPILE_DOC=OFF",
                "-DDIGIKAMSC_COMPILE_DIGIKAM=ON",
                "-DBUILD_TESTING=OFF",
            ]

'''

needle = "        if CraftCore.compiler.isLinux:\n"
if android_block.strip() not in text:
    if needle not in text:
        raise SystemExit("Could not locate configure section in digiKam Craft blueprint")
    text = text.replace(needle, android_block + needle, 1)

bp.write_text(text, encoding="utf-8")
print(f"Patched Craft blueprint: {bp}")
