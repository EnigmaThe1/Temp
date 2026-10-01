#!/usr/bin/env python3
from __future__ import annotations

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

# 2. Qt/Android needs the application entry point exported from the shared
# library that backs the APK. digiKam 9.1 uses MAIN_EXPORT/MAIN_FN macros
# rather than spelling the function as a literal int main(...).
main_file = src / "core" / "app" / "main" / "main.cpp"
if not main_file.exists():
    candidates = [p for p in src.rglob("main.cpp") if "core/app" in p.as_posix()]
    if not candidates:
        raise SystemExit("Could not locate digiKam main.cpp")
    main_file = candidates[0]

main_text = main_file.read_text(encoding="utf-8")
macro_entry = re.search(r"\bMAIN_EXPORT\s+int\s+MAIN_FN\s*\(", main_text)

if macro_entry:
    # In digiKam 9.1 the non-Windows branch defines MAIN_EXPORT as empty.
    # For this Android-only patched source tree, make that definition visible
    # to Qt's Android loader while retaining digiKam's own MAIN_FN abstraction.
    empty_export = re.compile(
        r"(?m)^(\s*#\s*define\s+MAIN_EXPORT)\s*$"
    )
    main_text, count = empty_export.subn(
        r"\1 Q_DECL_EXPORT",
        main_text,
        count=1,
    )
    if count != 1 and "MAIN_EXPORT Q_DECL_EXPORT" not in main_text:
        candidates = [
            f"{i + 1}: {line}"
            for i, line in enumerate(main_text.splitlines())
            if "MAIN_EXPORT" in line or "MAIN_FN" in line
        ][:60]
        raise SystemExit(
            f"Could not patch MAIN_EXPORT in {main_file}:\n"
            + "\n".join(candidates)
        )
    main_file.write_text(main_text, encoding="utf-8")
else:
    # Compatibility fallback for older/newer upstream layouts using literal main().
    already_exported = re.search(
        r"Q_DECL_EXPORT\s*(?:\n\s*)*int\s+main\s*\(",
        main_text,
        flags=re.MULTILINE,
    )
    if not already_exported:
        match = re.search(r"\bint\s+main\s*\(", main_text, flags=re.MULTILINE)
        if match is None:
            candidates = [
                f"{i + 1}: {line}"
                for i, line in enumerate(main_text.splitlines())
                if "main" in line.lower()
            ][:60]
            raise SystemExit(
                f"Could not locate digiKam entry point in {main_file}; "
                "main-like lines:\n" + "\n".join(candidates)
            )

        export_block = (
            "#ifdef Q_OS_ANDROID\n"
            "// DIGIKAM_ANDROID_MAIN_EXPORT\n"
            "Q_DECL_EXPORT\n"
            "#endif\n"
        )
        main_text = (
            main_text[: match.start()]
            + export_block
            + main_text[match.start():]
        )
        main_file.write_text(main_text, encoding="utf-8")

# 3. Install Android manifest, splash and icon next to the digiKam target.
android_dir = target_file.parent / "android"
(android_dir / "res" / "drawable").mkdir(parents=True, exist_ok=True)
shutil.copy2(android_template / "AndroidManifest.xml", android_dir / "AndroidManifest.xml")
shutil.copy2(
    android_template / "res" / "drawable" / "splash.xml",
    android_dir / "res" / "drawable" / "splash.xml",
)

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
