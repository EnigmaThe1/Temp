#!/usr/bin/env python3
from __future__ import annotations

import sys
from pathlib import Path

if len(sys.argv) != 2:
    raise SystemExit("usage: patch-qtbase-blueprint.py <qtbase.py>")

bp = Path(sys.argv[1]).resolve()
text = bp.read_text(encoding="utf-8")

old = '''            elif ver in ["kde/5.15", "kde/before-5.15.11-rebase"]:
                self.patchToApply[ver] = [
                    (".qt-kde-5.15", 1),
                ]
'''
new = '''            elif ver in ["kde/5.15", "kde/before-5.15.11-rebase"]:
                if CraftCore.compiler.isAndroid:
                    # The .qt-kde-5.15 directory is a desktop compatibility
                    # bundle: macOS QStandardPaths/style/linker patches,
                    # Windows ANGLE/MIME fixes, a QDBus shutdown patch, a
                    # testlib-only warning-limit patch, and an obsolete
                    # bundled-HarfBuzz patch. Android uses neither those
                    # desktop paths nor bundled HarfBuzz here, and the KDE
                    # 5.15 branch already contains overlapping changes.
                    # Applying the whole directory therefore fails during
                    # unpack without providing Android functionality.
                    self.patchToApply[ver] = []
                else:
                    self.patchToApply[ver] = [
                        (".qt-kde-5.15", 1),
                    ]
'''

if old in text:
    text = text.replace(old, new, 1)
elif 'if CraftCore.compiler.isAndroid:' not in text or 'self.patchToApply[ver] = []' not in text:
    raise SystemExit("Could not locate KDE 5.15 qtbase patch bundle selection")

bp.write_text(text, encoding="utf-8")
print(f"Patched Qt5 qtbase Android patch policy: {bp}")
