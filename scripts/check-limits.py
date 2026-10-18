#!/usr/bin/env python3
"""Enforces the code size rules: at most 600 lines per source file and 50 lines per function.

Covers Rust, Kotlin and Swift sources, skipping generated code and build output. A function is
measured from its signature line to its closing brace. Braces inside strings and comments are
ignored on a best-effort basis (good enough for this codebase's style).

Usage: scripts/check-limits.py   (exit code 1 when a limit is exceeded)
"""
import re
import sys
from pathlib import Path

MAX_FILE_LINES = 600
MAX_FUNCTION_LINES = 50
ROOT = Path(__file__).resolve().parent.parent
SKIP_PARTS = {"target", "build", ".build", ".gradle", "generated", "vendor", "node_modules"}
FUNCTION = {
    ".rs": re.compile(r"^\s*(pub(\([^)]*\))?\s+)?(const\s+)?(async\s+)?(unsafe\s+)?(extern\s+\"C\"\s+)?fn\s+\w+"),
    ".kt": re.compile(r"^\s*((private|internal|public|protected|override|inline|suspend|operator)\s+)*fun\s"),
    ".swift": re.compile(r"^\s*(@\w+\s+)*((private|fileprivate|public|internal|static|override|mutating|nonisolated|convenience)\s+)*(func\s|init[?(]|init<)"),
}
STRIP = re.compile(r'"(\\.|[^"\\])*"|\'(\\.|[^\'\\])\'|//.*$')


def sources():
    for ext in FUNCTION:
        for path in ROOT.rglob(f"*{ext}"):
            if not SKIP_PARTS.intersection(path.relative_to(ROOT).parts):
                yield path


def long_functions(path, lines):
    pattern = FUNCTION[path.suffix]
    i = 0
    while i < len(lines):
        if not pattern.match(lines[i]):
            i += 1
            continue
        start, depth, opened = i, 0, False
        while i < len(lines):
            code = STRIP.sub("", lines[i])
            depth += code.count("{") - code.count("}")
            opened = opened or "{" in code
            if opened and depth <= 0:
                break
            if not opened and (code.rstrip().endswith(";") or "=" in code.split("//")[0] and "{" not in code and "(" not in code):
                break  # declaration without a body (trait/protocol requirement, expression body)
            i += 1
        length = i - start + 1
        if opened and length > MAX_FUNCTION_LINES:
            yield start + 1, length
        i = start + 1


def main():
    failures = []
    for path in sorted(sources()):
        lines = path.read_text(encoding="utf-8").splitlines()
        rel = path.relative_to(ROOT)
        if len(lines) > MAX_FILE_LINES:
            failures.append(f"{rel}: {len(lines)} lines (max {MAX_FILE_LINES})")
        for line, length in long_functions(path, lines):
            failures.append(f"{rel}:{line}: function of {length} lines (max {MAX_FUNCTION_LINES})")
    for failure in failures:
        print(failure)
    if failures:
        return 1
    print("size limits ok")
    return 0


if __name__ == "__main__":
    sys.exit(main())
