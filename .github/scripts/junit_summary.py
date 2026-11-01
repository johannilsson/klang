#!/usr/bin/env python3
"""Summarise JUnit XML results as Markdown for the GitHub job summary.

Usage: junit_summary.py GLOB [GLOB...]

Prints the summary and, when GITHUB_STEP_SUMMARY is set, appends it there. Always exits 0: the Gradle
step already fails the job on test failures; this step only makes the counts visible. Standard library only.
"""
import glob
import os
import sys
import xml.etree.ElementTree as ET


def main(patterns, title="Core tests"):
    paths = sorted({p for pattern in patterns for p in glob.glob(pattern)})
    totals = {"tests": 0, "failures": 0, "errors": 0, "skipped": 0}
    problems = []  # (suite, detail)

    for path in paths:
        try:
            root = ET.parse(path).getroot()
        except (ET.ParseError, OSError) as e:
            problems.append((path, f"unreadable: {e}"))
            continue
        suites = [root] if root.tag == "testsuite" else root.iter("testsuite")
        for suite in suites:
            for key in totals:
                totals[key] += int(suite.get(key, 0) or 0)
            for case in suite.iter("testcase"):
                for kind in ("failure", "error"):
                    node = case.find(kind)
                    if node is not None:
                        message = (node.get("message") or "").splitlines()
                        first = message[0][:200] if message else ""
                        problems.append((f"{case.get('classname', '?')}.{case.get('name', '?')}", f"{kind}: {first}"))

    lines = [f"## {title}", ""]
    if not paths:
        lines.append("No JUnit XML found (" + ", ".join(f"`{p}`" for p in patterns) + ").")
    else:
        failed = totals["failures"] + totals["errors"]
        status = "all passed" if failed == 0 and not problems else "FAILED"
        lines.append(
            f"**{totals['tests']} tests, {totals['failures']} failures, {totals['errors']} errors, "
            f"{totals['skipped']} skipped ({status})** from {len(paths)} result files."
        )
        if problems:
            lines += ["", "| Test | Problem |", "|---|---|"]
            for name, detail in problems:
                detail = detail.replace("|", "\\|")
                lines.append(f"| `{name}` | {detail} |")

    text = "\n".join(lines) + "\n"
    print(text)
    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary:
        with open(summary, "a", encoding="utf-8") as fh:
            fh.write(text)
    return 0


if __name__ == "__main__":
    args = sys.argv[1:]
    title = "Core tests"
    if args and args[0].startswith("--title="):
        title = args.pop(0).split("=", 1)[1]
    sys.exit(main(args or ["core/*/build/test-results/test/*.xml"], title))
