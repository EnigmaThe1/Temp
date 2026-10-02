#!/usr/bin/env python3
from __future__ import annotations

import sys
from pathlib import Path

if len(sys.argv) != 2:
    raise SystemExit("usage: patch-lensfun-blueprint.py <lensfun.py>")

bp = Path(sys.argv[1]).resolve()
text = bp.read_text(encoding="utf-8")

modern = (
    "        disableSSE = CraftBool(CraftCore.compiler.isMacOS and "
    "CraftCore.compiler.architecture == CraftCompiler.Architecture.arm64)"
)
modern_replacement = (
    "        disableSSE = CraftBool(\n"
    "            (CraftCore.compiler.isMacOS or CraftCore.compiler.isAndroid)\n"
    "            and CraftCore.compiler.architecture == CraftCompiler.Architecture.arm64\n"
    "        )"
)

legacy = (
    "        disableSSE = CraftCore.compiler.isMacOS and "
    "CraftCore.compiler.architecture == CraftCompiler.Architecture.arm64"
)
legacy_replacement = (
    "        disableSSE = (CraftCore.compiler.isMacOS or CraftCore.compiler.isAndroid) "
    "and CraftCore.compiler.architecture == CraftCompiler.Architecture.arm64"
)

if modern in text:
    text = text.replace(modern, modern_replacement, 1)
elif legacy in text:
    text = text.replace(legacy, legacy_replacement, 1)
elif "CraftCore.compiler.isAndroid" not in text:
    raise SystemExit("Could not locate Lensfun SSE-selection expression")

bp.write_text(text, encoding="utf-8")
print(f"Patched Lensfun Craft blueprint: {bp}")
