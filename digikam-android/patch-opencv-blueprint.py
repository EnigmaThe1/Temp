#!/usr/bin/env python3
from __future__ import annotations

import sys
from pathlib import Path

if len(sys.argv) != 2:
    raise SystemExit("usage: patch-opencv-blueprint.py <opencv.py>")

bp = Path(sys.argv[1]).resolve()
text = bp.read_text(encoding="utf-8")

# Upstream blueprint currently concatenates BUILD_TESTS and BUILD_DOCS into one
# Python string. Split them so CMake receives two valid options.
text = text.replace(
    'f"-DBUILD_TESTS={self.subinfo.options.dynamic.buildTests.asOnOff}" "-DBUILD_DOCS=OFF",',
    'f"-DBUILD_TESTS={self.subinfo.options.dynamic.buildTests.asOnOff}",\n'
    '            "-DBUILD_DOCS=OFF",',
)

android_block = '''
        if CraftCore.compiler.isAndroid:
            # OpenCV 4.12 currently creates Android sample helper targets even
            # when Java wrappers are unavailable in this Craft configuration.
            # digiKam needs the OpenCV libraries, not OpenCV's demo APKs.
            self.subinfo.options.configure.args += [
                "-DBUILD_ANDROID_PROJECTS=OFF",
                "-DBUILD_ANDROID_EXAMPLES=OFF",
                "-DINSTALL_ANDROID_EXAMPLES=OFF",
                "-DBUILD_EXAMPLES=OFF",
                "-DBUILD_TESTS=OFF",
                "-DBUILD_PERF_TESTS=OFF",
                "-DBUILD_opencv_apps=OFF",
            ]

'''

needle = "        if CraftCore.compiler.architecture & CraftCompiler.Architecture.x86:\n"
if android_block.strip() not in text:
    if needle not in text:
        raise SystemExit("Could not locate OpenCV architecture block")
    text = text.replace(needle, android_block + needle, 1)

bp.write_text(text, encoding="utf-8")
print(f"Patched OpenCV Craft blueprint: {bp}")
