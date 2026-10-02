#!/usr/bin/env python3
from __future__ import annotations

import sys
from pathlib import Path

if len(sys.argv) != 2:
    raise SystemExit("usage: patch-marble-blueprint.py <marble.py>")

bp = Path(sys.argv[1]).resolve()
text = bp.read_text(encoding="utf-8")

needle = '''        self.subinfo.options.configure.args += ["-DBUILD_MARBLE_TESTS=OFF"]
'''

legacy_host = Path("/workspace/.digikam-android-work/host-protoc-3.20.3/bin/protoc")
modern_host = Path("/workspace/.digikam-android-work/host-protoc-33.0/bin/protoc")
host_protoc = legacy_host if legacy_host.exists() else modern_host

replacement = f'''        self.subinfo.options.configure.args += ["-DBUILD_MARBLE_TESTS=OFF"]
        if CraftCore.compiler.isAndroid:
            # digiKam consumes Marble as a library/plugin dependency. Do not
            # build Marble's standalone Android applications.
            self.subinfo.options.configure.args += [
                "-DBUILD_MARBLE_APPS=OFF",
                "-DProtobuf_PROTOC_EXECUTABLE={host_protoc}",
            ]
'''

if "-DBUILD_MARBLE_APPS=OFF" not in text:
    if needle not in text:
        raise SystemExit("Could not locate Marble configure argument block")
    text = text.replace(needle, replacement, 1)

bp.write_text(text, encoding="utf-8")
print(f"Patched Marble Craft blueprint: {bp}")
