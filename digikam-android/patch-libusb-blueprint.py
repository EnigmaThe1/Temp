#!/usr/bin/env python3
from __future__ import annotations

import sys
from pathlib import Path

if len(sys.argv) != 2:
    raise SystemExit("usage: patch-libusb-blueprint.py <libusb.py>")

bp = Path(sys.argv[1]).resolve()
text = bp.read_text(encoding="utf-8")

needle = '''        def __init__(self, **kwargs):
            super().__init__(**kwargs)
'''
replacement = '''        def __init__(self, **kwargs):
            super().__init__(**kwargs)
            if CraftCore.compiler.isAndroid:
                # Android has no Linux udev/libudev userspace service. libusb
                # itself supports Android without udev, so keep libusb enabled
                # while disabling only the unavailable Linux discovery backend.
                self.subinfo.options.configure.args += ["--disable-udev"]
'''

if "--disable-udev" not in text:
    if needle not in text:
        raise SystemExit("Could not locate libusb AutoTools package initialiser")
    text = text.replace(needle, replacement, 1)

bp.write_text(text, encoding="utf-8")
print(f"Patched libusb Craft blueprint: {bp}")
