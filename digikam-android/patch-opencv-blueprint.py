#!/usr/bin/env python3
from __future__ import annotations

import os
import subprocess
import sys
import urllib.request
import zipfile
from pathlib import Path

if len(sys.argv) != 2:
    raise SystemExit("usage: patch-opencv-blueprint.py <opencv.py>")

bp = Path(sys.argv[1]).resolve()
text = bp.read_text(encoding="utf-8")

# Upstream blueprint currently concatenates BUILD_TESTS and BUILD_DOCS into one
# Python string. Split them so CMake receives two valid options.
text = text.replace(
    'f"-DBUILD_TESTS={self.subinfo.options.dynamic.buildTests.asOnOff}" "-DBUILD_DOCS=OFF",',
    'f"-DBUILD_TESTS={self.subinfo.options.dynamic.buildTests.asOnOff}",\n'
    '            "-DBUILD_DOCS=OFF",',
)

# Cross-compiling OpenCV still requires a HOST protoc executable to generate
# C++ sources. Craft's Android protobuf package provides an arm64-v8a protoc,
# which cannot execute on the x86_64 CI host. Use the matching official host
# protoc while continuing to link Craft's Android protobuf libraries.
host_root = Path("/workspace/.digikam-android-work/host-protoc")
host_protoc = host_root / "bin" / "protoc"
if not host_protoc.exists():
    host_root.mkdir(parents=True, exist_ok=True)
    archive = host_root / "protoc-33.0-linux-x86_64.zip"
    url = (
        "https://github.com/protocolbuffers/protobuf/releases/download/"
        "v33.0/protoc-33.0-linux-x86_64.zip"
    )
    print(f"Downloading host protoc: {url}")
    urllib.request.urlretrieve(url, archive)
    with zipfile.ZipFile(archive) as zf:
        zf.extractall(host_root)
    archive.unlink(missing_ok=True)

host_protoc.chmod(0o755)
probe = subprocess.run(
    [str(host_protoc), "--version"],
    check=True,
    capture_output=True,
    text=True,
)
print(f"Host protoc: {probe.stdout.strip()} ({host_protoc})")

android_block = f'''
        if CraftCore.compiler.isAndroid:
            # OpenCV 4.12 currently creates Android sample helper targets even
            # when Java wrappers are unavailable in this Craft configuration.
            # digiKam needs the OpenCV libraries, not OpenCV's demo APKs.
            #
            # OpenCV's DNN protobuf generation must run on the build host.
            # Keep Android protobuf libraries, but force the host protoc binary.
            self.subinfo.options.configure.args += [
                "-DBUILD_ANDROID_PROJECTS=OFF",
                "-DBUILD_ANDROID_EXAMPLES=OFF",
                "-DINSTALL_ANDROID_EXAMPLES=OFF",
                "-DBUILD_EXAMPLES=OFF",
                "-DBUILD_TESTS=OFF",
                "-DBUILD_PERF_TESTS=OFF",
                "-DBUILD_opencv_apps=OFF",
                "-DProtobuf_PROTOC_EXECUTABLE={host_protoc}",
            ]

'''

needle = "        if CraftCore.compiler.architecture & CraftCompiler.Architecture.x86:\n"
if "Protobuf_PROTOC_EXECUTABLE=/workspace/.digikam-android-work/host-protoc/bin/protoc" not in text:
    old_start = text.find("        if CraftCore.compiler.isAndroid:\n")
    old_end = text.find(needle)
    if old_start >= 0 and old_end > old_start:
        text = text[:old_start] + android_block + text[old_end:]
    else:
        if needle not in text:
            raise SystemExit("Could not locate OpenCV architecture block")
        text = text.replace(needle, android_block + needle, 1)

bp.write_text(text, encoding="utf-8")
print(f"Patched OpenCV Craft blueprint: {bp}")
