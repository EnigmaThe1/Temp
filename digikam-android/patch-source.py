#!/usr/bin/env python3
from __future__ import annotations

import os
import re
import shutil
import sys
from pathlib import Path

if len(sys.argv) != 2:
    raise SystemExit("usage: patch-source.py <digikam-source-dir>")

src = Path(sys.argv[1]).resolve()
port_root = Path(__file__).resolve().parent
android_template = port_root / "android"

if not (src / "core").is_dir():
    raise SystemExit(f"Not a digiKam source tree: {src}")

# 1. Add KDE/ECM Android APK packaging to the existing digiKam executable.
target_file = src / "core" / "app" / "DigikamTarget.cmake"
if not target_file.exists():
    matches = list(src.rglob("DigikamTarget.cmake"))
    if len(matches) != 1:
        raise SystemExit(f"Could not uniquely locate DigikamTarget.cmake: {matches}")
    target_file = matches[0]

text = target_file.read_text(encoding="utf-8")
marker = "ecm_add_android_apk(digikam"
if marker not in text:
    text += """
# Android packaging layer (temporary downstream port).
if(ANDROID)
    include(ECMAddAndroidApk)
    ecm_add_android_apk(digikam ANDROID_DIR ${CMAKE_CURRENT_SOURCE_DIR}/android)
endif()
"""
    target_file.write_text(text, encoding="utf-8")

# 2. Qt/Android needs main() exported from the shared library that backs the APK.
main_file = src / "core" / "app" / "main" / "main.cpp"
if not main_file.exists():
    candidates = [p for p in src.rglob("main.cpp") if "core/app" in p.as_posix()]
    if not candidates:
        raise SystemExit("Could not locate digiKam main.cpp")
    main_file = candidates[0]

main_text = main_file.read_text(encoding="utf-8")
if "Q_DECL_EXPORT" not in main_text:
    main_text, count = re.subn(
        r"(?m)^int\s+main\s*\(",
        "#ifdef Q_OS_ANDROID\nQ_DECL_EXPORT\n#endif\nint main(",
        main_text,
        count=1,
    )
    if count != 1:
        raise SystemExit(f"Could not patch main() export in {main_file}")
    main_file.write_text(main_text, encoding="utf-8")

# 3. Install Android manifest, splash and icon next to the digiKam target.
android_dir = target_file.parent / "android"
(android_dir / "res" / "drawable").mkdir(parents=True, exist_ok=True)
shutil.copy2(android_template / "AndroidManifest.xml", android_dir / "AndroidManifest.xml")
shutil.copy2(android_template / "res" / "drawable" / "splash.xml", android_dir / "res" / "drawable" / "splash.xml")

icon_candidates = [
    src / "core" / "data" / "icons" / "apps" / "128-apps-digikam.png",
    src / "core" / "data" / "icons" / "apps" / "256-apps-digikam.png",
    src / "core" / "data" / "icons" / "apps" / "64-apps-digikam.png",
]
icon = next((p for p in icon_candidates if p.exists()), None)
if icon is None:
    found = list(src.rglob("*apps-digikam.png"))
    if not found:
        raise SystemExit("Could not find a digiKam PNG icon for Android packaging")
    icon = sorted(found, key=lambda p: p.stat().st_size, reverse=True)[0]
shutil.copy2(icon, android_dir / "res" / "drawable" / "digikam.png")

print(f"Patched source tree: {src}")
print(f"  target: {target_file}")
print(f"  main:   {main_file}")
print(f"  android:{android_dir}")
