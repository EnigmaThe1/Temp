#!/usr/bin/env python3
from __future__ import annotations

import sys
from pathlib import Path

if len(sys.argv) != 2:
    raise SystemExit("usage: patch-lensfun-blueprint.py <lensfun.py>")

bp = Path(sys.argv[1]).resolve()
text = bp.read_text(encoding="utf-8")

old = (
    "        disableSSE = CraftBool(CraftCore.compiler.isMacOS and "
    "CraftCore.compiler.architecture == CraftCompiler.Architecture.arm64)"
)
new = (
    "        disableSSE = CraftBool(\n"
    "            (CraftCore.compiler.isMacOS or CraftCore.compiler.isAndroid)\n"
    "            and CraftCore.compiler.architecture == CraftCompiler.Architecture.arm64\n"
    "        )"
)

if old in text:
    text = text.replace(old, new, 1)
elif "CraftCore.compiler.isAndroid" not in text:
    raise SystemExit("Could not locate Lensfun SSE-selection expression")

bp.write_text(text, encoding="utf-8")
print(f"Patched Lensfun Craft blueprint: {bp}")
