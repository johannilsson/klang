#!/usr/bin/env python3
"""Summarise a Klang export ZIP and re-analyse every clap recording with the current analyser.

Usage:  scripts/analyse_export.py EXPORT.zip OUT_DIR

OUT_DIR must not exist yet (the ZIP is unpacked into it; nothing is ever deleted).
Needs the CLI: ./gradlew -p core :cli:installDist
Prints (1) what the app stored for each attempt and (2) a fresh analysis of the original WAVs with the
analyser in this checkout, so results recorded by an older app build can be compared.
Levels are dBFS (relative to digital full scale), not SPL.
"""
import json
import subprocess
import sys
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
CLI = ROOT / "core/cli/build/install/klang-analyse/bin/klang-analyse"


def main() -> int:
    if len(sys.argv) != 3:
        print(__doc__)
        return 2
    zip_path, out = Path(sys.argv[1]), Path(sys.argv[2])
    if out.exists():
        print(f"{out} already exists; pass a new directory", file=sys.stderr)
        return 2
    if not CLI.exists():
        print("CLI missing: run ./gradlew -p core :cli:installDist", file=sys.stderr)
        return 2
    out.mkdir(parents=True)
    with zipfile.ZipFile(zip_path) as z:
        z.extractall(out)
    doc = json.loads((out / "results.json").read_text())
    positions = {p["id"]: p["name"] for p in doc["room"]["positions"]}
    print(f"Room: {doc['room']['name']}  positions: {list(positions.values())}")

    print("\n== As stored by the app ==")
    for s in doc["sessions"]:
        se = s["session"]
        print(f"\nSESSION {se['name']} [{se['kind']}] {se.get('intervention') or ''}")
        for a in s["attempts"]:
            an = a["analyses"][-1] if a["analyses"] else None
            cfg = a.get("audioConfiguration") or {}
            line = f"  {positions.get(a['positionId'], '?')} #{a['sequence']} {a['kind'][:3]} src={cfg.get('audioSource')}"
            line += f" events={[e['type'] for e in a['captureEvents']]}"
            if an:
                line += f" | v{an['algorithmVersion']} {an['state']} pk={an['peakDbfs']:.1f} bg={(an['backgroundRmsDbfs'] or 0):.1f} "
                line += " ".join(
                    f"{b['centerHz']}:{b['state'][0]}{('%.2f' % b['t20']['seconds']) if b['t20'] else '-'}" for b in an["bands"]
                )
            print(line)

    print("\n== Re-analysed with this checkout (original WAVs) ==")
    for s in doc["sessions"]:
        for a in s["attempts"]:
            if a["kind"] != "IMPULSE":
                continue
            wav = out / "recordings" / s["session"]["id"] / f"{a['id']}.wav"
            res = subprocess.run([str(CLI), "--json", str(wav)], capture_output=True, text=True)
            text = res.stdout[res.stdout.index("{"):] if "{" in res.stdout else ""
            if not text:
                print(f"  {s['session']['name']} #{a['sequence']}: CLI failed: {res.stderr[-200:]}")
                continue
            r = json.loads(text)
            codes = sorted(
                {(x["code"], x["severity"][0]) for x in r["reasons"]}
                | {(x["code"], x["severity"][0]) for b in r["bands"] if b["centerHz"] in (500, 1000, 2000) for x in b["reasons"]}
            )
            print(f"  {s['session']['name']} / {positions.get(a['positionId'], '?')} #{a['sequence']}: v{r['algorithmVersion']} {r['state']} "
                  f"pk={r['peakDbfs']:.1f} bg={(r['backgroundRmsDbfs'] or 0):.1f}")
            print("     " + " ".join(f"{b['centerHz']}:{b['state'][0]}{('%.2f' % b['t20']['seconds']) if b['t20'] else '-'}" for b in r["bands"]))
            print("     reasons: " + ", ".join(f"{c}({sv})" for c, sv in codes))
    return 0


if __name__ == "__main__":
    sys.exit(main())
