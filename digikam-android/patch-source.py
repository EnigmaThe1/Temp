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
mobile_template = port_root / "mobile"

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

# Android builds add a thin adaptive mobile shell while keeping digiKam's
# existing data/model/image pipeline intact.
text = target_file.read_text(encoding="utf-8")
mobile_sources_marker = "main/mobileuiadapter.cpp"
if mobile_sources_marker not in text:
    text += """
if(ANDROID)
    target_sources(digikam PRIVATE
        main/mobileuiadapter.cpp
        main/mobileuiadapter.h
    )
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

# Compile-time Android hook for the adaptive mobile shell.
mobile_include = '#ifdef Q_OS_ANDROID\n#   include "mobileuiadapter.h"\n#endif\n'
if "mobileuiadapter.h" not in main_text:
    include_anchor = '#include "digikamapp.h"'
    if include_anchor not in main_text:
        raise SystemExit("Could not locate digikamapp.h include for mobile UI hook")
    main_text = main_text.replace(
        include_anchor,
        include_anchor + "\n" + mobile_include,
        1,
    )

# Apply mobile sizing to first-run/configuration dialogs immediately after the
# QApplication exists, before digiKam creates any desktop-oriented dialogs.
if "MobileUiAdapter::prepareApplication" not in main_text:
    app_match = re.search(
        r"(QApplication\s+\w+\s*\(\s*argc\s*,\s*argv\s*\)\s*;)",
        main_text,
    )
    if app_match is None:
        raise SystemExit("Could not locate QApplication construction for mobile UI hook")
    app_var_match = re.search(r"QApplication\s+(\w+)\s*\(", app_match.group(1))
    app_var = app_var_match.group(1)
    prepare = (
        app_match.group(1)
        + "\n\n#ifdef Q_OS_ANDROID\n"
        + f"    Digikam::MobileUiAdapter::prepareApplication(&{app_var});\n"
        + "#endif"
    )
    main_text = (
        main_text[:app_match.start()]
        + prepare
        + main_text[app_match.end():]
    )

# Replace the desktop chrome only after the real digiKam window has completed
# its normal setup. This leaves the core application logic untouched.
if "MobileUiAdapter::install" not in main_text:
    app_window_match = re.search(
        r"DigikamApp\s*\*\s*(?:const\s+)?(\w+)\s*=\s*new\s+DigikamApp\s*\(\s*\)\s*;",
        main_text,
    )
    if app_window_match is None:
        raise SystemExit("Could not locate the DigikamApp main-window construction")
    window_var = app_window_match.group(1)
    show_match = re.search(
        rf"(\b{re.escape(window_var)}\s*->\s*show\s*\(\s*\)\s*;)",
        main_text,
    )
    if show_match is None:
        raise SystemExit(
            f"Could not locate {window_var}->show() for mobile UI hook"
        )
    install = (
        show_match.group(1)
        + "\n#ifdef Q_OS_ANDROID\n"
        + f"    Digikam::MobileUiAdapter::install({window_var});\n"
        + "#endif"
    )
    main_text = (
        main_text[:show_match.start()]
        + install
        + main_text[show_match.end():]
    )

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

main_file.write_text(main_text, encoding="utf-8")

# 3. digiKam 9.1 still discovers Qt WebEngine Widgets unconditionally in
# RulesQtFramework.cmake, before ENABLE_QWEBENGINE=OFF can take effect.
# Qt WebEngine is intentionally absent from this Android Qt5 port, so make the
# required WebEngineWidgets lookup obey digiKam's existing feature option.
qt_rules_file = src / "core" / "cmake" / "rules" / "RulesQtFramework.cmake"
if not qt_rules_file.exists():
    raise SystemExit(f"Could not locate Qt dependency rules: {qt_rules_file}")

qt_rules_text = qt_rules_file.read_text(encoding="utf-8")
qwebengine_marker = "# DIGIKAM_ANDROID_OPTIONAL_WEBENGINE"

if qwebengine_marker not in qt_rules_text:
    component_pos = qt_rules_text.find("WebEngineWidgets")
    if component_pos < 0:
        raise SystemExit(
            f"Could not locate WebEngineWidgets dependency in {qt_rules_file}"
        )

    find_start = qt_rules_text.rfind("find_package(", 0, component_pos)
    find_end = qt_rules_text.find(")", component_pos)

    if find_start < 0 or find_end < 0:
        raise SystemExit(
            f"Could not isolate WebEngineWidgets find_package block in {qt_rules_file}"
        )

    find_end += 1
    webengine_find = qt_rules_text[find_start:find_end]

    if "WebEngineWidgets" not in webengine_find:
        raise SystemExit(
            f"Wrong Qt dependency block selected in {qt_rules_file}"
        )

    indented_find = "\n".join(
        ("    " + line) if line.strip() else line
        for line in webengine_find.splitlines()
    )
    guarded_find = (
        "if(ENABLE_QWEBENGINE)\n"
        f"    {qwebengine_marker}\n"
        f"{indented_find}\n"
        "endif()"
    )
    qt_rules_text = (
        qt_rules_text[:find_start]
        + guarded_find
        + qt_rules_text[find_end:]
    )
    qt_rules_file.write_text(qt_rules_text, encoding="utf-8")

# 4. Install Android manifest, splash, icon, and mobile UI source next to the target.
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

mobile_dst = target_file.parent / "main"
for name in ("mobileuiadapter.cpp", "mobileuiadapter.h"):
    source = mobile_template / name
    if not source.exists():
        raise SystemExit(f"Missing Android mobile UI source: {source}")
    shutil.copy2(source, mobile_dst / name)

print(f"Patched source tree: {src}")
print(f"  target: {target_file}")
print(f"  main:   {main_file}")
print(f"  qtrules:{qt_rules_file}")
print(f"  android:{android_dir}")
print(f"  mobile: {mobile_dst / 'mobileuiadapter.cpp'}")
