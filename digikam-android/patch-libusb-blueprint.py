#!/usr/bin/env python3
from __future__ import annotations

import re
import sys
from pathlib import Path

if len(sys.argv) != 2:
    raise SystemExit("usage: patch-libusb-blueprint.py <libusb.py>")

bp = Path(sys.argv[1]).resolve()
text = bp.read_text(encoding="utf-8")

# Remove the exact downstream block from any earlier cached iteration. An
# earlier port attempt inserted it into the MSVC Package class because that was
# the first __init__ in this blueprint. Android uses the AutoTools branch.
text = re.sub(
    r'''\n\s*if CraftCore\.compiler\.isAndroid:\n'''
    r'''\s*# Android has no Linux udev/libudev userspace service\. libusb\n'''
    r'''\s*# itself supports Android without udev, so keep libusb enabled\n'''
    r'''\s*# while disabling only the unavailable Linux discovery backend\.\n'''
    r'''\s*self\.subinfo\.options\.configure\.args \+= \["--disable-udev"\]\n''',
    "\n",
    text,
)

anchor = '''else:

    class Package(AutoToolsPackageBase):
        def __init__(self, **kwargs):
            super().__init__(**kwargs)
'''
replacement = '''else:

    class Package(AutoToolsPackageBase):
        def __init__(self, **kwargs):
            super().__init__(**kwargs)
            if CraftCore.compiler.isAndroid:
                # Android has no Linux udev/libudev userspace service. libusb
                # supports Android without udev; disable only that Linux-only
                # device-discovery backend while retaining libusb itself.
                self.subinfo.options.configure.args += ["--disable-udev"]
'''

if anchor not in text:
    # Accept a blueprint that already has the correct Android setting.
    auto_idx = text.find("class Package(AutoToolsPackageBase):")
    disable_idx = text.find("--disable-udev", auto_idx if auto_idx >= 0 else 0)
    if auto_idx < 0 or disable_idx < auto_idx:
        raise SystemExit("Could not locate libusb AutoTools package initialiser")
else:
    text = text.replace(anchor, replacement, 1)

bp.write_text(text, encoding="utf-8")
print(f"Patched libusb Craft blueprint: {bp}")
