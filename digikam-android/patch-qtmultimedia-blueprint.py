#!/usr/bin/env python3
from __future__ import annotations

import sys
from pathlib import Path

if len(sys.argv) != 2:
    raise SystemExit("usage: patch-qtmultimedia-blueprint.py <qtmultimedia.py>")

bp = Path(sys.argv[1]).resolve()
text = bp.read_text(encoding="utf-8")

# KDE's retired Qt5 recipe applies this patch to AVFoundation/macOS sources.
# It is irrelevant to Android and no longer applies cleanly to the pinned
# kde/5.15 source snapshot, so keep it for desktop builds and skip it on Android.
patch_line = '        self.patchToApply["kde/5.15"] = [("0002-fix-c++17-build.patch", 1)]\n'
guarded = '''        if not CraftCore.compiler.isAndroid:
            self.patchToApply["kde/5.15"] = [("0002-fix-c++17-build.patch", 1)]
'''

if guarded not in text:
    if patch_line not in text:
        raise SystemExit("Could not locate Qt5 qtmultimedia C++17 patch assignment")
    text = text.replace(patch_line, guarded, 1)

bp.write_text(text, encoding="utf-8")
print(f"Patched Qt5 qtmultimedia blueprint: {bp}")
