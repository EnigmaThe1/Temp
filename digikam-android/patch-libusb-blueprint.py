#!/usr/bin/env python3
from __future__ import annotations

import ast
import re
import sys
from pathlib import Path

if len(sys.argv) != 2:
    raise SystemExit("usage: patch-libusb-blueprint.py <libusb.py>")

bp = Path(sys.argv[1]).resolve()
text = bp.read_text(encoding="utf-8")

if "--disable-udev" not in text:
    # Match through the constructor's super() line, but deliberately do not
    # consume indentation belonging to the next statement. This supports both
    # current Craft and KDE's retired qt5-lts libusb blueprint.
    pattern = re.compile(
        r"(class\s+Package\s*\(AutoToolsPackageBase\):\n"
        r"    def\s+__init__\s*\([^\n]+\):\n"
        r"        super\(\)\.__init__\([^\n]*\)\n)",
        re.MULTILINE,
    )
    match = pattern.search(text)

    if match is None:
        raise SystemExit("Could not locate libusb AutoTools package constructor")

    android = (
        match.group(1)
        + "        if CraftCore.compiler.isAndroid:\n"
        + "            # Android does not provide Linux libudev.\n"
        + "            self.subinfo.options.configure.args += [\"--disable-udev\"]\n"
    )
    text = text[:match.start()] + android + text[match.end():]

# Never write a syntactically broken Craft blueprint.
try:
    ast.parse(text, filename=str(bp))
except SyntaxError as exc:
    raise SystemExit(f"Patched libusb blueprint is invalid Python: {exc}") from exc

bp.write_text(text, encoding="utf-8")
print(f"Patched libusb Craft blueprint: {bp}")
