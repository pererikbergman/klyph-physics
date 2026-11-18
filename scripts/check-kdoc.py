#!/usr/bin/env python3
"""Lists every public declaration in the engine and the bridge with no KDoc right above it.

A declaration is documented when the nearest line above it that isn't an annotation ends a
`/** ... */` block. Run from the repo root; it exits 1 if anything is missing.
"""
import glob
import re
import sys

ROOTS = ["klyph-physics/src/commonMain", "klyph-physics-bridge/src/commonMain"]
DECLARATION = re.compile(r"^\s*public\b")

missing = []
for root in ROOTS:
    for path in sorted(glob.glob(f"{root}/**/*.kt", recursive=True)):
        lines = open(path).read().split("\n")
        for i, line in enumerate(lines):
            # an unnamed companion object is only a place for its members, each documented
            if not DECLARATION.match(line) or "override " in line or re.match(r"\s*public companion object\b(?! \w)", line):
                continue
            j = i - 1
            while j >= 0 and lines[j].strip().startswith("@"):
                j -= 1
            above = lines[j].strip() if j >= 0 else ""
            if not above.endswith("*/"):
                missing.append(f"{path}:{i + 1}: {line.strip()[:110]}")
for m in missing:
    print(m)
print(f"{len(missing)} public declarations without KDoc", file=sys.stderr)
sys.exit(1 if missing else 0)
