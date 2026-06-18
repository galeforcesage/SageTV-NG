"""
stv_analyzer.py — STV Diagnostic Tool
Run this before any optimization pass to get a full health report.

Usage:
    python stv_analyzer.py SageTV7.xml
    python stv_analyzer.py SageTV7.xml --json report.json
"""

import xml.etree.ElementTree as ET
import re
import os
import json
import sys
import argparse
from pathlib import Path
from collections import defaultdict, Counter

WIDGET_TAGS = {
    "Menu", "Panel", "Action", "Conditional", "Image", "Video",
    "Text", "Theme", "Hook", "Listener", "Table", "Shape",
}

EXPENSIVE_API = re.compile(
    r"(?:GetProperty|GetServerProperty|GetCurrentMediaFile|GetMediaFiles|"
    r"GetFavorites|GetElement|GetShowEpisode|GetAlbumForFile|"
    r"GetAiring\w+|GetChannel\w+|GetPeople\w+|GetShow\w+)\("
)


def analyze(input_path: str, json_output: str | None = None) -> dict:
    print(f"Parsing {input_path} ...")
    tree = ET.parse(input_path)
    root = tree.getroot()

    type_counts: Counter = Counter()
    sym_prefixes: Counter = Counter()
    name_counts: Counter = Counter()
    expensive_calls: Counter = Counter()
    id_set: set = set()
    ref_set: set = set()
    total_widgets = 0
    has_setlocal = has_getlocal = 0
    max_depth = 0
    theme_refs = 0

    def walk(elem, depth=0):
        nonlocal total_widgets, max_depth, has_setlocal, has_getlocal, theme_refs
        max_depth = max(max_depth, depth)

        tag = elem.tag.split("}")[-1] if "}" in elem.tag else elem.tag

        if tag in WIDGET_TAGS:
            total_widgets += 1
            type_counts[tag] += 1

            sym = elem.attrib.get("Sym", "")
            if "-" in sym:
                sym_prefixes[sym.split("-")[0]] += 1

            name = elem.attrib.get("Name", "")
            if name and "(" not in name and len(name) > 5:
                name_counts[name] += 1

            if "ID" in elem.attrib:
                id_set.add(elem.attrib["ID"])
            if "Ref" in elem.attrib:
                ref_set.add(elem.attrib["Ref"])

            if tag == "Theme" and "Ref" in elem.attrib:
                theme_refs += 1

        for attr_val in elem.attrib.values():
            if "SetLocal" in attr_val:
                has_setlocal += 1
            if "GetLocal" in attr_val:
                has_getlocal += 1
            for m in EXPENSIVE_API.finditer(attr_val):
                expensive_calls[m.group()] += 1

        if elem.text and "(" in (elem.text or ""):
            for m in EXPENSIVE_API.finditer(elem.text):
                expensive_calls[m.group()] += 1

        for child in elem:
            walk(child, depth + 1)

    walk(root)

    size_mb = os.path.getsize(input_path) / 1024 / 1024
    orphans = id_set - ref_set
    theme_total = type_counts.get("Theme", 0)
    top_duplicates = [(n, c) for n, c in name_counts.most_common(20) if c >= 5]

    report = {
        "file": str(input_path),
        "size_mb": round(size_mb, 2),
        "total_widgets": total_widgets,
        "widget_types": dict(type_counts.most_common()),
        "sym_prefixes": dict(sym_prefixes.most_common()),
        "shared_ids": len(id_set),
        "ref_count": len(ref_set),
        "orphaned_ids": len(orphans),
        "top_duplicates": top_duplicates,
        "setlocal_calls": has_setlocal,
        "getlocal_calls": has_getlocal,
        "expensive_calls": dict(expensive_calls.most_common(20)),
        "theme_total": theme_total,
        "theme_refs": theme_refs,
        "theme_inheritance_rate_pct": round(theme_refs / theme_total * 100) if theme_total else 0,
        "max_depth": max_depth,
    }

    # Print report
    sep = "=" * 60
    print(f"\n{sep}\nSTV ANALYSIS REPORT\n{sep}")
    print(f"File : {input_path}  ({size_mb:.1f} MB)")
    print(f"\n--- Widget Inventory ({total_widgets:,} total) ---")
    for tag, count in type_counts.most_common():
        print(f"  {tag:<18} {count:>7,}  ({count/total_widgets*100:.1f}%)")

    print(f"\n--- Mod Origins (Sym Prefixes) ---")
    for prefix, count in sym_prefixes.most_common(10):
        print(f"  {prefix:<12} {count:>7,}")

    print(f"\n--- Sharing ---")
    print(f"  IDs defined : {len(id_set):,}")
    print(f"  Refs used   : {len(ref_set):,}")
    print(f"  Orphaned IDs: {len(orphans):,}")

    print(f"\n--- Top Duplicate Widget Names ---")
    for name, count in top_duplicates[:10]:
        print(f"  {count:>5,}x  '{name[:55]}'")

    print(f"\n--- Expression Caching ---")
    print(f"  SetLocal calls : {has_setlocal:,}")
    print(f"  GetLocal calls : {has_getlocal:,}")
    print(f"  Top expensive calls (uncached):")
    for expr, count in expensive_calls.most_common(10):
        print(f"    {count:>5,}x  {expr}")

    print(f"\n--- Theme Analysis ---")
    print(f"  Theme widgets      : {theme_total:,}")
    print(f"  Theme inheritances : {theme_refs:,}  ({report['theme_inheritance_rate_pct']}%)")

    print(f"\n--- Nesting ---")
    print(f"  Max widget depth   : {max_depth} levels")
    print(sep)

    if json_output:
        Path(json_output).write_text(json.dumps(report, indent=2))
        print(f"JSON report saved to {json_output}")

    return report


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="STV Diagnostic Analyzer")
    parser.add_argument("input", help="Input STV XML file")
    parser.add_argument("--json", dest="json_output", help="Save report as JSON")
    args = parser.parse_args()
    analyze(args.input, args.json_output)
