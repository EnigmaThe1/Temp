#!/usr/bin/env python3
from __future__ import annotations

import re
import sys
from pathlib import Path

if len(sys.argv) != 2:
    raise SystemExit("usage: patch-libusb-blueprint.py <libusb.py>")

bp = Path(sys.argv[1]).resolve()
text = bp.read_text(encoding="utf-8")

if "--disable-udev" not in text:
    # Insert immediately after the AutoTools Package constructor. This handles
    # both current Craft (**kwargs) and the retired qt5-lts blueprint (**args).
    pattern = re.compile(
        r"(class\s+Package\s*\(AutoToolsPackageBase\):\s*"
        r"def\s+__init__\s*\([^\n]+\):\s*"
        r"super\(\)\.__init__\([^\n]*\)\s*)",
        re.MULTILINE,
    )
    match = pattern.search(text)
    if match is None:
        raise SystemExit("Could not locate libusb AutoTools package constructor")

    android = (
        match.group(1)
        + "\n        if CraftCore.compiler.isAndroid:\n"
        + "            # Android does not provide Linux libudev.\n"
        + "            self.subinfo.options.configure.args += [\"--disable-udev\"]\n"
    )
    text = text[:match.start()] + android + text[match.end():]

bp.write_text(text, encoding="utf-8")
print(f"Patched libusb Craft blueprint: {bp}")
