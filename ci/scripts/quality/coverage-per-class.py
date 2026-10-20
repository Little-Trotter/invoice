#!/usr/bin/env python3
#
# Project: Little Trotter X-Invoice
# Copyright (c) 2026 Eifel42 Stefan Zils
# See LICENSE and README.md for license terms and documentation.
#
# Disclaimer: This software is provided as-is, without warranty of any kind. Use at your own risk.
"""Per-source-file line/branch coverage from one or more JaCoCo XML reports.

Usage: coverage-per-class.py [--threshold 0.90] <jacoco.xml> [<jacoco.xml> ...]
Prints the totals (line, branch, Sonar-style combined) and every file below the line threshold,
sorted by missed lines. Exit code 1 when any file is below the threshold.
"""
import argparse
import sys
import xml.etree.ElementTree as ET


def rows_of(report):
    root = ET.parse(report).getroot()
    for package in root.iter("package"):
        for source in package.findall("sourcefile"):
            counters = {c.get("type"): (int(c.get("missed")), int(c.get("covered"))) for c in source.findall("counter")}
            line_missed, line_covered = counters.get("LINE", (0, 0))
            branch_missed, branch_covered = counters.get("BRANCH", (0, 0))
            if line_missed + line_covered:
                yield package.get("name") + "/" + source.get("name"), line_missed, line_covered, branch_missed, branch_covered


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--threshold", type=float, default=0.90)
    parser.add_argument("reports", nargs="+")
    args = parser.parse_args()
    rows = [row for report in args.reports for row in rows_of(report)]
    if not rows:
        print("ERROR: no coverage rows found in the given report(s); an empty report cannot pass the gate", file=sys.stderr)
        return 2
    lines = sum(r[1] + r[2] for r in rows)
    lines_covered = sum(r[2] for r in rows)
    branches = sum(r[3] + r[4] for r in rows)
    branches_covered = sum(r[4] for r in rows)
    print(
        f"TOTAL line {100 * lines_covered / lines:.1f}%  branch {100 * branches_covered / max(branches, 1):.1f}%  "
        f"combined {100 * (lines_covered + branches_covered) / (lines + branches):.1f}%  files={len(rows)}"
    )
    below = sorted((r for r in rows if r[2] / (r[1] + r[2]) < args.threshold), key=lambda r: -r[1])
    print(f"files below {args.threshold:.0%} line: {len(below)}  missed lines there: {sum(r[1] for r in below)}")
    for path, lm, lc, bm, bc in below:
        branch = 100 * bc / (bm + bc) if bm + bc else 100.0
        print(f"{100 * lc / (lm + lc):5.1f}%  missed={lm:4d}/{lm + lc:4d}  branch={branch:5.1f}%  {path}")
    return 1 if below else 0


if __name__ == "__main__":
    sys.exit(main())
