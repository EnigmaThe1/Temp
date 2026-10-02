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
replacement = '''        self.subinfo.options.configure.args += ["-DBUILD_MARBLE_TESTS=OFF"]
        if CraftCore.compiler.isAndroid:
            # Marble generates C++ from OSM .proto files while cross-compiling.
            # Craft's target protoc is Android/arm64 and cannot execute on the
            # x86_64 CI host. Reuse the host protoc installed for OpenCV.
            self.subinfo.options.configure.args += [
                "-DProtobuf_PROTOC_EXECUTABLE=/workspace/.digikam-android-work/host-protoc/bin/protoc"
            ]
'''

if "Protobuf_PROTOC_EXECUTABLE=/workspace/.digikam-android-work/host-protoc/bin/protoc" not in text:
    if needle not in text:
        raise SystemExit("Could not locate Marble configure argument block")
    text = text.replace(needle, replacement, 1)

bp.write_text(text, encoding="utf-8")
print(f"Patched Marble Craft blueprint: {bp}")
