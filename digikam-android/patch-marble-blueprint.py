#!/usr/bin/env python3
from __future__ import annotations

import re
import sys
from pathlib import Path

if len(sys.argv) != 2:
    raise SystemExit("usage: patch-marble-blueprint.py <marble.py>")

bp = Path(sys.argv[1]).resolve()
text = bp.read_text(encoding="utf-8")

legacy_host = Path("/workspace/.digikam-android-work/host-protoc-3.20.3/bin/protoc")
modern_host = Path("/workspace/.digikam-android-work/host-protoc-33.0/bin/protoc")
host_protoc = legacy_host if legacy_host.exists() else modern_host

if "-DBUILD_MARBLE_APPS=OFF" not in text:
    pattern = re.compile(
        r'(?m)^(\s*self\.subinfo\.options\.configure\.args\s*\+=\s*\[[^\n]*'
        r'-DBUILD_MARBLE_TESTS=OFF[^\n]*\]\s*)$'
    )
    match = pattern.search(text)
    if match is None:
        raise SystemExit("Could not locate Marble configure argument block")

    indent = re.match(r"\s*", match.group(1)).group(0)
    android = (
        match.group(1)
        + "\n"
        + indent
        + "if CraftCore.compiler.isAndroid:\n"
        + indent
        + "    # digiKam needs Marble libraries/plugins, not Marble apps.\n"
        + indent
        + "    self.subinfo.options.configure.args += [\n"
        + indent
        + '        "-DBUILD_MARBLE_APPS=OFF",\n'
        + indent
        + f'        "-DProtobuf_PROTOC_EXECUTABLE={host_protoc}",\n'
        + indent
        + "    ]"
    )
    text = text[:match.start()] + android + text[match.end():]

# The package-level compatibility hooks below need pathlib inside the patched
# Craft blueprint itself (not just in this patching helper).
if "from pathlib import Path" not in text:
    if "import info\n" not in text:
        raise SystemExit("Could not locate Marble blueprint import block")
    text = text.replace("import info\n", "import info\nfrom pathlib import Path\n", 1)

# Craft's Android CMake packager scans every AndroidManifest.xml below the
# source tree before CMake evaluates BUILD_MARBLE_APPS. Marble's disabled app
# sources therefore still look like APK targets (marble-maps/MarbleBehaim),
# and ECM later fails because those targets were intentionally not created.
# This package is only a digiKam library dependency on Android, so report no
# Marble APK targets while preserving Craft's normal behaviour elsewhere.
#
# Marble 24.02.1 also builds its optional OverviewMap render plugin
# unconditionally. The plugin uses QSvgWidget, which is absent from this
# pinned Android Qt5Svg build, while digiKam does not depend on that decoration.
# Remove just that subdirectory after unpacking on Android.
if "DIGIKAM_ANDROID_NO_MARBLE_APK_TARGETS" not in text:
    class_match = re.search(
        r"(?m)^(class\s+Package\([^\n]+\):\s*\n)",
        text,
    )
    if class_match is None:
        raise SystemExit("Could not locate Marble Package class")

    override = (
        class_match.group(1)
        + "    # DIGIKAM_ANDROID_NO_MARBLE_APK_TARGETS\n"
        + "    @property\n"
        + "    def androidApkTargets(self):\n"
        + "        if CraftCore.compiler.isAndroid:\n"
        + "            return set()\n"
        + "        return super().androidApkTargets\n\n"
        + "    def unpack(self):\n"
        + "        if not super().unpack():\n"
        + "            return False\n"
        + "        if CraftCore.compiler.isAndroid:\n"
        + "            render_cmake = Path(self.sourceDir()) / \"src/plugins/render/CMakeLists.txt\"\n"
        + "            source = render_cmake.read_text(encoding=\"utf-8\")\n"
        + "            marker = \"# DIGIKAM_ANDROID_NO_OVERVIEWMAP\"\n"
        + "            needle = \"add_subdirectory( overviewmap )\"\n"
        + "            if needle in source:\n"
        + "                source = source.replace(needle, marker, 1)\n"
        + "                render_cmake.write_text(source, encoding=\"utf-8\")\n"
        + "            elif marker not in source:\n"
        + "                raise RuntimeError(\"Could not locate Marble OverviewMap subdirectory\")\n"
        + "        return True\n\n"
    )
    text = text[:class_match.start()] + override + text[class_match.end():]
elif "DIGIKAM_ANDROID_NO_OVERVIEWMAP" not in text:
    raise SystemExit("Marble Android package override exists without OverviewMap suppression")

bp.write_text(text, encoding="utf-8")
print(f"Patched Marble Craft blueprint: {bp}")
