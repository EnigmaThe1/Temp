#!/usr/bin/env python3
from __future__ import annotations

import sys
from pathlib import Path

if len(sys.argv) != 2:
    raise SystemExit("usage: patch-libffi-blueprint.py <libffi.py>")

bp = Path(sys.argv[1]).resolve()
text = bp.read_text(encoding="utf-8")

# The retired qt5-lts blueprint still uses FTP for libffi 3.3. Modern curl
# runners reject that legacy FTPS path. Keep the exact same Sourceware release
# and digest, but fetch it over HTTPS.
text = text.replace(
    "ftp://sourceware.org/pub/libffi/libffi-{ver}.tar.gz",
    "https://sourceware.org/pub/libffi/libffi-{ver}.tar.gz",
)

needle = '''        self.shell.useMSVCCompatEnv = True
'''
android = '''        self.shell.useMSVCCompatEnv = True
        if CraftCore.compiler.isAndroid:
            # libffi release tarballs already contain a generated configure
            # script. Re-running autoreconf with the newer host libtool fails
            # on the obsolete LT_SYS_SYMBOL_USCORE macro before cross-build
            # configuration even starts, so use the shipped configure script.
            self.subinfo.options.configure.autoreconf = False
'''

if "self.subinfo.options.configure.autoreconf = False" not in text:
    if needle not in text:
        raise SystemExit("Could not locate libffi package initialisation block")
    text = text.replace(needle, android, 1)

bp.write_text(text, encoding="utf-8")
print(f"Patched libffi Craft blueprint: {bp}")
