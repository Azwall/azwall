#!/usr/bin/env python3
"""Refuses errors swallowed in silence: each one must say why it may be dropped.

An error the app carries on without is either harmless (a comment says why), or reported to the diagnostic log
(DiagnosticLog on the apps, log() in the PXE host), or shown to the user. This flags the constructs that drop an
error (Swift `try?`, Kotlin `runCatching`, TypeScript `catch {` and `.catch(() =>`) without a `//` comment on
their line or on one of the two lines above. Tests, generated code and build output are skipped.

Usage: scripts/check-swallowed.py [--list]   (exit code 1 when one is found; --list prints them all and exits 0)
"""
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SOURCES = {
    "ios": (".swift", re.compile(r"\btry\?")),
    "android": (".kt", re.compile(r"\brunCatching\b")),
    "pxe/host/src": (".ts", re.compile(r"\bcatch\s*\{|\.catch\(\s*\(\)\s*=>")),
    "pxe/shared/src": (".ts", re.compile(r"\bcatch\s*\{|\.catch\(\s*\(\)\s*=>")),
}
SKIP_PARTS = {"test", "Tests", "build", ".build", "generated", "node_modules", "dist"}


def silent(lines, i):
    """No comment on the line or the two above it."""
    return not any("//" in lines[j] for j in range(max(0, i - 2), i + 1))


def findings():
    for folder, (ext, pattern) in SOURCES.items():
        for path in sorted((ROOT / folder).rglob(f"*{ext}")):
            if SKIP_PARTS.intersection(path.relative_to(ROOT).parts):
                continue
            lines = path.read_text(encoding="utf-8").splitlines()
            for i, line in enumerate(lines):
                if pattern.search(line) and silent(lines, i):
                    yield f"{path.relative_to(ROOT)}:{i + 1}: {line.strip()}"


def main():
    found = list(findings())
    for f in found:
        print(f)
    if "--list" in sys.argv:
        return 0
    if found:
        print(f"\n{len(found)} error(s) dropped without a word: say why in a comment, report them, or show them.", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
