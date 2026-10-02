#!/usr/bin/env python3
from __future__ import annotations

import sys
from pathlib import Path

if len(sys.argv) != 2:
    raise SystemExit("usage: patch-craft-meson.py <MesonBuildSystem.py>")

path = Path(sys.argv[1]).resolve()
text = path.read_text(encoding="utf-8")

broken = '''            if CraftCore.compiler.isAndroid:
                extra_options = ["--cross-file", self.craftCrossFile()]
'''
fixed = '''            if CraftCore.compiler.isAndroid:
                # craftCrossFile() already returns ["--cross-file", path].
                # Wrapping it again produces:
                #   meson setup --cross-file --cross-file <path>
                # which modern Meson rejects before configuration begins.
                extra_options = self.craftCrossFile()
'''

if broken in text:
    text = text.replace(broken, fixed, 1)
elif 'extra_options = self.craftCrossFile()' not in text:
    raise SystemExit("Could not locate retired Craft Android Meson cross-file bug")

path.write_text(text, encoding="utf-8")
print(f"Patched retired Craft Meson Android cross-file handling: {path}")
