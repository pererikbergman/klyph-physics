#!/usr/bin/env python3
"""Checks every page in docs/explained/ against the template in its README.

Each page needs, in order: a title with an emoji, the ⚡ line, a picture that exists, 🧠 How it works
with at most three 👉 bullets, 🤓 the details, 🎛️ The knobs as a table with the template's columns,
💥 Gotchas, 🧪 Try it, and the 📸 command that made its picture. The index must list every page.
Run from the repo root; it exits 1 if anything is off.
"""
import glob
import os
import re
import sys

DOCS = "docs/explained"
# the template's table, or, for knobs that aren't turned up or down, the one its rules allow
KNOB_HEADERS = ["| knob | default | turn it up 🔼 | turn it down 🔽 |", "| knob | default | what it does |"]
SECTIONS = ["## 🧠 How it works", "<summary>🤓 the details</summary>", "## 🎛️ The knobs", "## 💥 Gotchas", "## 🧪 Try it"]

problems = []


def problem(page, text):
    problems.append(f"{page}: {text}")


pages = sorted(p for p in glob.glob(f"{DOCS}/*.md") if not p.endswith("README.md"))
index = open(f"{DOCS}/README.md").read()
for path in pages:
    name = os.path.basename(path)
    text = open(path).read()
    lines = text.split("\n")
    if not re.match(r"# \S+ \S", lines[0]):
        problem(name, "the first line isn't a title with an emoji")
    if not any(line.startswith("⚡ **") for line in lines[:4]):
        problem(name, "no ⚡ line under the title")
    picture = re.search(r'<img src="\.\./img/([^"]+)"', text)
    if not picture:
        problem(name, "no picture")
    elif not os.path.exists(f"docs/img/{picture.group(1)}"):
        problem(name, f"its picture docs/img/{picture.group(1)} doesn't exist")
    at = 0
    for section in SECTIONS:
        found = text.find(section, at)
        if found < 0:
            problem(name, f"no '{section}', or it's out of order")
        else:
            at = found
    how = text.split("## 🧠 How it works", 1)[-1].split("<details>", 1)[0]
    bullets = [line for line in how.split("\n") if line.startswith("- ")]
    if not 1 <= len(bullets) <= 3 or not all(b.startswith("- 👉") for b in bullets):
        problem(name, f"🧠 How it works has {len(bullets)} bullets, or one isn't a 👉")
    knobs = text.split("## 🎛️ The knobs", 1)[-1]
    if not any(header in knobs.split("## ", 1)[0] for header in KNOB_HEADERS):
        problem(name, "🎛️ The knobs has no table with the template's columns")
    gotchas = text.split("## 💥 Gotchas", 1)[-1].split("## ", 1)[0]
    if not any(line.startswith("- 💥") for line in gotchas.split("\n")):
        problem(name, "💥 Gotchas has no 💥 bullet")
    if not re.search(r"^📸 `scripts/capture\.sh [^`]+`", text, re.M):
        problem(name, "no 📸 line with the capture command")
    if f"]({name})" not in index:
        problem(name, "not in the index")

for p in problems:
    print(p)
print(f"{len(pages)} pages, {len(problems)} problems", file=sys.stderr)
sys.exit(1 if problems else 0)
