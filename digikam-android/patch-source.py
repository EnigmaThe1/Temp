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

# 1. Locate the existing digiKam target. APK packaging for this Qt5 port is
# provided by ECM's Android toolchain (ECMAndroidDeployQt5) through the
# QTANDROID_EXPORTED_TARGET/ANDROID_APK_DIR variables supplied by Craft.
# ECMAddAndroidApk is a Qt6 API and must not be injected into this Qt5 build.
target_file = src / "core" / "app" / "DigikamTarget.cmake"
if not target_file.exists():
    matches = list(src.rglob("DigikamTarget.cmake"))
    if len(matches) != 1:
        raise SystemExit(f"Could not uniquely locate DigikamTarget.cmake: {matches}")
    target_file = matches[0]

text = target_file.read_text(encoding="utf-8")

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

# 4. FFmpeg is only required by digiKam's optional media player, but the 9.1
# RulesFFmpeg.cmake split made the REQUIRED probe unconditional. This mirrors
# the downstream packaging fix used when ENABLE_MEDIAPLAYER is OFF: guard the
# complete rule file so neither the REQUIRED probe nor its version checks run.
ffmpeg_rules_file = src / "core" / "cmake" / "rules" / "RulesFFmpeg.cmake"
if not ffmpeg_rules_file.exists():
    raise SystemExit(f"Could not locate FFmpeg dependency rules: {ffmpeg_rules_file}")

ffmpeg_rules_text = ffmpeg_rules_file.read_text(encoding="utf-8")
ffmpeg_marker = "# DIGIKAM_ANDROID_OPTIONAL_FFMPEG"

if ffmpeg_marker not in ffmpeg_rules_text:
    if "find_package(FFmpeg" not in ffmpeg_rules_text:
        raise SystemExit(
            f"Could not locate FFmpeg dependency probe in {ffmpeg_rules_file}"
        )

    ffmpeg_rules_text = (
        "if(ENABLE_MEDIAPLAYER)\n"
        f"    {ffmpeg_marker}\n\n"
        + ffmpeg_rules_text.rstrip()
        + "\n\nendif(ENABLE_MEDIAPLAYER)\n"
    )
    ffmpeg_rules_file.write_text(ffmpeg_rules_text, encoding="utf-8")

# 5. Fix an Android-only Marble source path that normal desktop builds never
# compile. digiKam enables QT_NO_CAST_FROM_ASCII globally, so the raw C string
# used for the Android plugin filter is rejected by Qt5.
marble_plugin_manager = (
    src / "core" / "utilities" / "geolocation" / "engine" /
    "plugins" / "PluginManager.cpp"
)
if not marble_plugin_manager.exists():
    raise SystemExit(
        f"Could not locate bundled Marble PluginManager: {marble_plugin_manager}"
    )

marble_plugin_text = marble_plugin_manager.read_text(encoding="utf-8")
marble_android_filter_old = (
    'QStringList pluginNameFilter      = QStringList() << "lib*.so";'
)
marble_android_filter_new = (
    'QStringList pluginNameFilter      = '
    'QStringList() << QLatin1String("lib*.so");'
)

if marble_android_filter_old in marble_plugin_text:
    marble_plugin_text = marble_plugin_text.replace(
        marble_android_filter_old,
        marble_android_filter_new,
        1,
    )
elif marble_android_filter_new not in marble_plugin_text:
    raise SystemExit(
        "Could not locate Android Marble plugin-name filter for Qt string fix"
    )

marble_plugin_manager.write_text(marble_plugin_text, encoding="utf-8")

# Qt5 Android in the pinned toolchain exposes QtSvg but not the QSvgWidget
# widget implementation used only by Marble's optional Overview Map overlay.
# Keep the full geolocation engine and all other render/runner plugins, but
# skip this one unsupported mini-overview plugin on Android.
marble_render_cmake = (
    src / "core" / "utilities" / "geolocation" / "engine" /
    "plugins" / "render" / "CMakeLists.txt"
)
marble_render_text = marble_render_cmake.read_text(encoding="utf-8")
overview_line = "add_subdirectory(overviewmap)"

if overview_line in marble_render_text:
    marble_render_text = marble_render_text.replace(
        overview_line,
        "# Android Qt5 port: OverviewMap requires unavailable QSvgWidget.",
        1,
    )
elif "OverviewMap requires unavailable QSvgWidget" not in marble_render_text:
    raise SystemExit(
        f"Could not locate Marble overviewmap plugin entry in {marble_render_cmake}"
    )

marble_render_cmake.write_text(marble_render_text, encoding="utf-8")

# 6. Replace desktop-only embedded-web surfaces with Android-safe adapters.
# Qt WebEngine is not available in the pinned Qt5 Android toolchain. Keep
# digiKam's core model/database/photo functionality while routing browser
# actions to Android and preserving geolocation APIs with a placeholder widget.

webengine_include_files = [
    src / "core" / "libs" / "dialogs" / "CMakeLists.txt",
    src / "core" / "libs" / "dplugins" / "CMakeLists.txt",
    src / "core" / "utilities" / "geolocation" / "geoiface" / "CMakeLists.txt",
    src / "core" / "app" / "CMakeLists.txt",
]

for cmake_file in webengine_include_files:
    if not cmake_file.exists():
        raise SystemExit(f"Missing WebEngine CMake file: {cmake_file}")

    cmake_text = cmake_file.read_text(encoding="utf-8")
    filtered = "\n".join(
        line
        for line in cmake_text.splitlines()
        if "WebEngineWidgets,INTERFACE_INCLUDE_DIRECTORIES" not in line
    )

    if filtered == cmake_text.rstrip("\n"):
        raise SystemExit(
            f"Could not locate WebEngine include target in {cmake_file}"
        )

    cmake_file.write_text(filtered + "\n", encoding="utf-8")

for rel, target_name in (
    ("core/app/DigikamCoreTarget.cmake", "digikamcore"),
    ("core/app/DigikamGuiTarget.cmake", "digikamgui"),
):
    cmake_file = src / rel
    cmake_text = cmake_file.read_text(encoding="utf-8")
    cmake_lines = cmake_text.splitlines()
    filtered_lines = [
        line
        for line in cmake_lines
        if "::WebEngineWidgets" not in line
    ]

    if len(filtered_lines) == len(cmake_lines):
        raise SystemExit(
            f"Could not locate WebEngine link entry for {target_name} in {cmake_file}"
        )

    cmake_text = "\n".join(filtered_lines) + "\n"

    # If WebEngine was the only item in a dedicated link block, removing its
    # line leaves an empty target_link_libraries call. Drop only that empty
    # call; larger link blocks retain every other dependency unchanged.
    cmake_text = re.sub(
        r"target_link_libraries\(\s*"
        + re.escape(target_name)
        + r"\s+PRIVATE\s*\)\s*",
        "",
        cmake_text,
        flags=re.MULTILINE,
    )

    cmake_file.write_text(cmake_text, encoding="utf-8")

# digiKam 9.1's digikamcore aggregates networking object libraries but does
# not link Qt Network directly. Desktop builds can acquire it transitively;
# Android links with --no-undefined, so make the dependency explicit.
digikam_core_target = src / "core" / "app" / "DigikamCoreTarget.cmake"
digikam_core_text = digikam_core_target.read_text(encoding="utf-8")
qt_network_link = "Qt${QT_VERSION_MAJOR}::Network"

if qt_network_link not in digikam_core_text:
    qt_gui_link = "Qt${QT_VERSION_MAJOR}::Gui"

    if qt_gui_link not in digikam_core_text:
        raise SystemExit(
            f"Could not locate Qt GUI link entry in {digikam_core_target}"
        )

    digikam_core_text = digikam_core_text.replace(
        qt_gui_link,
        qt_gui_link + "\n                      " + qt_network_link,
        1,
    )
    digikam_core_target.write_text(digikam_core_text, encoding="utf-8")

# The Android welcome page is a compact mobile replacement. digiKam 9.1 splits
# the desktop text content into About, Features, and Credits translation units;
# those depend on private methods intentionally absent from the mobile class.
# Keep welcomepageview_p.cpp: it also implements GradientWidget,
# InvertedGradientWidget, ResizableBackgroundWidget and TitleEffect, whose
# Q_OBJECT-generated vtables are still part of the GUI object library.
digikam_gui_target = src / "core" / "app" / "DigikamGuiTarget.cmake"
digikam_gui_text = digikam_gui_target.read_text(encoding="utf-8")
desktop_welcome_sources = (
    "views/stack/welcomepageview_about.cpp",
    "views/stack/welcomepageview_features.cpp",
    "views/stack/welcomepageview_credits.cpp",
)

removed_welcome_sources = 0
digikam_gui_lines = []

for line in digikam_gui_text.splitlines():
    if any(source_name in line for source_name in desktop_welcome_sources):
        removed_welcome_sources += 1
        continue

    digikam_gui_lines.append(line)

if removed_welcome_sources != len(desktop_welcome_sources):
    raise SystemExit(
        "Could not remove all desktop welcome-page companion sources from "
        f"{digikam_gui_target}: removed {removed_welcome_sources}/"
        f"{len(desktop_welcome_sources)}"
    )

digikam_gui_target.write_text(
    "\n".join(digikam_gui_lines) + "\n",
    encoding="utf-8",
)

# CMake AUTOMOC automatically probes a same-base *_p.h when it sees
# welcomepageview.cpp. The desktop private header contains Q_OBJECT helper
# classes whose implementations were intentionally removed above. Replace the
# unused private header with an inert Android stub so AUTOMOC does not emit
# dead vtables for those desktop-only helpers.
welcome_private_header = (
    src / "core" / "app" / "views" / "stack" / "welcomepageview_p.h"
)
if not welcome_private_header.exists():
    raise SystemExit(
        f"Could not locate desktop welcome private header: {welcome_private_header}"
    )

welcome_private_header.write_text(
    "#pragma once\n"
    "\n"
    "// Android Qt5 port: desktop welcome-page private helpers are disabled.\n",
    encoding="utf-8",
)

generic_plugins = src / "core" / "dplugins" / "generic" / "CMakeLists.txt"
generic_text = generic_plugins.read_text(encoding="utf-8")
if "add_subdirectory(webservices)" not in generic_text:
    raise SystemExit("Could not locate generic webservices subdirectory")
generic_text = generic_text.replace(
    "add_subdirectory(webservices)",
    "# Android Qt5 port: embedded-WebEngine web-service plugins are disabled.",
    1,
)
generic_plugins.write_text(generic_text, encoding="utf-8")

libsinfo_candidates = [
    src / "core" / "libs" / "dialogs" / "libsinfodlg_p.h",
    src / "core" / "libs" / "dialogs" / "libsinfodlg.cpp",
]

web_version_include = re.compile(
    r"(?m)^#\s*include\s*<QtWeb(?:Engine|Kit)WidgetsVersion>\s*$"
)

libsinfo = None
libsinfo_text = None

for candidate in libsinfo_candidates:
    if not candidate.exists():
        continue

    candidate_text = candidate.read_text(encoding="utf-8")

    if web_version_include.search(candidate_text):
        libsinfo = candidate
        libsinfo_text = candidate_text
        break

if libsinfo is None or libsinfo_text is None:
    raise SystemExit(
        "Could not locate digiKam WebEngine/WebKit version header include "
        "in libsinfodlg_p.h or libsinfodlg.cpp"
    )

# digiKam 9.1 moved the common library-dialog includes into libsinfodlg_p.h;
# older source trees kept them in libsinfodlg.cpp. Remove the embedded-web
# version headers from whichever layout is present and provide the version
# strings consumed by the existing dialog code.
libsinfo_text, web_version_include_count = web_version_include.subn(
    "",
    libsinfo_text,
)

if web_version_include_count == 0:
    raise SystemExit(
        f"Could not remove digiKam WebEngine/WebKit version headers from {libsinfo}"
    )

web_version_compat = (
    '#define QTWEBENGINEWIDGETS_VERSION_STR "disabled on Android"\n'
    '#define QTWEBKITWIDGETS_VERSION_STR "disabled on Android"\n'
)

config_include = '#include "digikam_config.h"'
if config_include not in libsinfo_text:
    raise SystemExit(
        f"Could not locate digikam_config.h include in {libsinfo}"
    )

libsinfo_text = libsinfo_text.replace(
    config_include,
    config_include + "\n" + web_version_compat,
    1,
)
libsinfo.write_text(libsinfo_text, encoding="utf-8")

android_replacements = {
    "webbrowserdlg_android.cpp":
        src / "core" / "libs" / "dialogs" / "webbrowserdlg.cpp",
    "welcomepageview_android.h":
        src / "core" / "app" / "views" / "stack" / "welcomepageview.h",
    "welcomepageview_android.cpp":
        src / "core" / "app" / "views" / "stack" / "welcomepageview.cpp",
    "htmlwidget_android.h":
        src / "core" / "utilities" / "geolocation" / "geoiface" / "widgets" / "htmlwidget_qwebengine.h",
    "htmlwidget_android.cpp":
        src / "core" / "utilities" / "geolocation" / "geoiface" / "widgets" / "htmlwidget_qwebengine.cpp",
}

for template_name, destination in android_replacements.items():
    source = mobile_template / template_name

    if not source.exists():
        raise SystemExit(f"Missing Android compatibility source: {source}")

    shutil.copy2(source, destination)

# 7. Install Android manifest, splash, icon, and mobile UI source next to the target.
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
print(f"  ffrules:{ffmpeg_rules_file}")
print(f"  android:{android_dir}")
print(f"  mobile: {mobile_dst / 'mobileuiadapter.cpp'}")
