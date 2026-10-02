#!/usr/bin/env python3
from __future__ import annotations

import sys
from pathlib import Path

if len(sys.argv) != 2:
    raise SystemExit("usage: patch-glib-blueprint.py <glib.py>")

bp = Path(sys.argv[1]).resolve()
text = bp.read_text(encoding="utf-8")

old = '        self.runtimeDependencies["libs/gettext"] = None\n'
new = '''        if CraftCore.compiler.isAndroid:
            # GNU gettext is intentionally disabled by retired Craft on
            # Android. KDE uses libintl-lite there; it installs libintl.a,
            # satisfying GLib's existing -lintl link flag.
            self.runtimeDependencies["libs/libintl-lite"] = None
        else:
            self.runtimeDependencies["libs/gettext"] = None
'''

if old in text:
    text = text.replace(old, new, 1)
elif 'self.runtimeDependencies["libs/libintl-lite"]' not in text:
    raise SystemExit("Could not locate GLib gettext dependency")

bp.write_text(text, encoding="utf-8")
print(f"Patched GLib Android intl dependency: {bp}")
