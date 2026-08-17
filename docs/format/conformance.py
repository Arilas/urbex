#!/usr/bin/env python3
"""Regenerates palette/conformance.md from the specification documents.

Run from docs/format:  python3 conformance.py [--check]

--check exits non-zero if the checked-in index differs from what the documents say, which is what
ConformanceIndexTest asserts once the Java harness exists. Until then this script is the harness.

It also performs the checks docs/format/README.md §4.2 requires of FormatFixtureTest, since both
read the same documents and there is no reason to parse them twice:

  * every cited rule identifier is defined somewhere
  * every REJECT rule cites a DIAG identifier that the catalogue defines
  * every fixture is well-formed JSON
  * every ACCEPT/REJECT/DEFAULT/EQUIV rule has a fixture, is [NO-FIXTURE], or is in a [DRAFT] document
"""

import collections
import glob
import io
import json
import re
import sys

AREAS = ("MODEL", "TRAIT", "REF", "MERGE", "WEIGHT", "CHAR", "LOAD", "DIAG", "VER")
# DEMO is reserved for README examples; it defines nothing and is ignored everywhere. See README §3.1.
RESERVED = "DEMO"
NEEDS_FIXTURE = {"ACCEPT", "REJECT", "DEFAULT", "EQUIV"}
INDEX = "palette/conformance.md"

RULE = re.compile(
    r"^>\s*\*\*([A-Z]+\.\d{3})\*\*\s*·\s*`([A-Z ]+)`"
    r"(?:\s*\(`(DIAG\.\d{3})`\))?"
    r"(?:\s*`\[NO-FIXTURE: ([^\]]+)\]`)?"
)
DIAG = re.compile(r"^\|\s*`(DIAG\.\d{3})`\s*\|")
FIXTURE = re.compile(r"^```json fixture:([A-Z]+\.\d{3})\s+(accept|reject=DIAG\.\d{3}|equiv=[\w-]+|fragment)")
CITE = re.compile(r"\b([A-Z]+\.\d{3})\b")


def read():
    files = sorted(glob.glob("palette/*.md")) + ["README.md"]
    files = [f for f in files if f != INDEX]
    rules, order, fixtures = {}, [], collections.defaultdict(list)
    cited, problems = collections.defaultdict(set), []
    draft = set()

    for f in files:
        lines = open(f).read().split("\n")
        if any(l.startswith("`[DRAFT]`") for l in lines[:6]):
            draft.add(f)
        for i, line in enumerate(lines):
            m = RULE.match(line)
            if m and not m.group(1).startswith(RESERVED + "."):
                rid, cls, diag, nofix = m.groups()
                if rid in rules:
                    problems.append(f"{f}: {rid} is defined twice")
                rules[rid] = dict(file=f, cls=cls.strip(), diag=diag, nofix=nofix)
                order.append(rid)
                if cls.strip() == "REJECT" and not diag:
                    problems.append(f"{f}: {rid} is REJECT but cites no DIAG")
            d = DIAG.match(line)
            if d:
                rules[d.group(1)] = dict(file=f, cls="DIAG", diag=None, nofix=None)
                order.append(d.group(1))
            x = FIXTURE.match(line)
            if x:
                fixtures[x.group(1)].append(x.group(2))
                body, j = [], i + 1
                while j < len(lines) and not lines[j].startswith("```"):
                    body.append(lines[j])
                    j += 1
                try:
                    json.loads("\n".join(body))
                except ValueError as e:
                    problems.append(f"{f}: fixture for {x.group(1)} is not valid JSON — {e}")
            for r in CITE.findall(line):
                if r.split(".")[0] in AREAS:
                    cited[r].add(f)

    for r in sorted(set(cited) - set(rules)):
        problems.append(f"{sorted(cited[r])[0]}: cites {r}, which no document defines")
    for rid, d in rules.items():
        if d["cls"] == "REJECT" and d["diag"] and d["diag"] not in rules:
            problems.append(f"{d['file']}: {rid} cites {d['diag']}, which the catalogue does not define")
        if d["cls"] in NEEDS_FIXTURE and not fixtures[rid] and not d["nofix"] and d["file"] not in draft:
            problems.append(f"{d['file']}: {rid} is {d['cls']} with no fixture and no [NO-FIXTURE]")
    return files, rules, order, fixtures, draft, problems


def render(files, rules, order, fixtures):
    out = io.StringIO()
    w = out.write
    w("# Conformance index\n\n")
    w("`[GENERATED]` — do not edit. Regenerate with `python3 docs/format/conformance.py`;\n")
    w("`ConformanceIndexTest` fails if the checked-in copy differs from what the documents say.\n\n")
    w("Every rule in this specification, its class, its fixtures, and the tests that cite it. See\n")
    w("[the specification system](../README.md#5-the-conformance-index) for what this file is for.\n\n")

    counts = collections.Counter(r.split(".")[0] for r in rules)
    w("## Totals\n\n| Area | Rules | Fixtures |\n|---|---:|---:|\n")
    for a in AREAS:
        fx = sum(len(fixtures[r]) for r in rules if r.startswith(a + "."))
        w(f"| `{a}` | {counts.get(a, 0)} | {fx} |\n")
    w(f"| **total** | **{len(rules)}** | **{sum(len(v) for v in fixtures.values())}** |\n\n")

    gaps = [r for r in order if rules[r]["cls"] in NEEDS_FIXTURE and not fixtures[r] and not rules[r]["nofix"]]
    nofix = [r for r in order if rules[r]["nofix"]]
    w("## Outstanding\n\n")
    w(f"**Rules relying on the draft suspension of fixture-completeness ({len(gaps)}):** ")
    w(", ".join(f"`{g}`" for g in gaps) if gaps
      else "none — this specification is ready to leave draft on this criterion.")
    w("\n\n")
    w(f"**Rules marked `[NO-FIXTURE]` ({len(nofix)}), which must each be covered by a citing test:**\n\n")
    w("| Rule | Reason |\n|---|---|\n")
    for r in nofix:
        w(f"| `{r}` | {rules[r]['nofix']} |\n")
    w("\n**Tests:** none yet. Every row below shows `—` in the Tests column until the harness lands;\n")
    w("`ConformanceIndexTest` will fail on any rule that still shows `—` once this document leaves draft.\n\n")

    w("## Rules\n\n")
    seen = set()
    for f in files:
        rs = [r for r in order if rules[r]["file"] == f and r not in seen]
        if not rs:
            continue
        seen.update(rs)
        w(f"### `{f}`\n\n| Rule | Class | Diagnostic | Fixtures | Tests |\n|---|---|---|---|---|\n")
        for r in rs:
            d = rules[r]
            fx = ", ".join(f"`{x}`" for x in fixtures[r])
            if not fx:
                fx = "*n/a*" if d["nofix"] else ("*—*" if d["cls"] in NEEDS_FIXTURE else "")
            diag = f"`{d['diag']}`" if d["diag"] else ""
            w(f"| `{r}` | `{d['cls']}` | {diag} | {fx} | — |\n")
        w("\n")
    return out.getvalue()


def main():
    files, rules, order, fixtures, draft, problems = read()
    for p in problems:
        print(f"FAIL {p}", file=sys.stderr)
    rendered = render(files, rules, order, fixtures)

    if "--check" in sys.argv:
        try:
            current = open(INDEX).read()
        except FileNotFoundError:
            current = None
        if current != rendered:
            print(f"FAIL {INDEX} is out of date; run python3 docs/format/conformance.py", file=sys.stderr)
            problems.append("index out of date")
    else:
        open(INDEX, "w").write(rendered)
        print(f"wrote {INDEX}: {len(rules)} identifiers, "
              f"{sum(len(v) for v in fixtures.values())} fixtures")

    if problems:
        print(f"\n{len(problems)} problem(s)", file=sys.stderr)
        return 1
    print("all checks passed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
