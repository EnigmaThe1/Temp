#!/usr/bin/env python3
from __future__ import annotations

import sys
from pathlib import Path

if len(sys.argv) != 2:
    raise SystemExit("usage: patch-libusb-blueprint.py <libusb.py>")

bp = Path(sys.argv[1]).resolve()
text = bp.read_text(encoding="utf-8")

needle = '''            super().__init__(**kwargs)
            # self.subinfo.options.useShadowBuild = False
'''
replacement = '''            super().__init__(**kwargs)
            if CraftCore.compiler.isAndroid:
                # libudev is a Linux userspace facility and is not available
                # on Android. libusb itself supports Android without udev.
                self.subinfo.options.configure.args += ["--disable-udev"]
            # self.subinfo.options.useShadowBuild = False
'''

if "--disable-udev" not in text:
    if needle not in text:
        raise SystemExit("Could not locate libusb AutoTools package block")
    text = text.replace(needle, replacement, 1)

bp.write_text(text, encoding="utf-8")
print(f"Patched libusb Craft blueprint: {bp}")
