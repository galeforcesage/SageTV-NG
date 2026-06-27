"""
Phase 3 analyzer: identify STV UI hot paths around refresh churn.

Focuses on:
- NeedRefresh assignments
- Refresh() actions
- Action Ref fan-in (how many call sites invoke the same action)
- FocusGained hooks per menu

Usage:
  python stv_hotpath_analyzer.py <input_stv.xml>
"""

from __future__ import annotations

import argparse
import json
import re
import xml.etree.ElementTree as ET
from collections import defaultdict
from pathlib import Path


def local_tag(tag: str) -> str:
    return tag.split("}")[-1] if "}" in tag else tag


def parse_action_id(elem: ET.Element) -> str | None:
    if local_tag(elem.tag) != "Action":
        return None
    return elem.attrib.get("ID")


def menu_name_for(elem: ET.Element, parent_map: dict[ET.Element, ET.Element]) -> str:
    cur = elem
    while cur in parent_map:
        if local_tag(cur.tag) == "Menu":
            return cur.attrib.get("Name", "<unnamed-menu>")
        cur = parent_map[cur]
    if local_tag(cur.tag) == "Menu":
        return cur.attrib.get("Name", "<unnamed-menu>")
    return "<global>"


def analyze(path: Path) -> dict:
    tree = ET.parse(path)
    root = tree.getroot()
    parent_map = {child: parent for parent in root.iter() for child in parent}

    action_defs: dict[str, dict] = {}
    action_ref_counts: dict[str, int] = defaultdict(int)
    action_ref_locations: dict[str, list[dict]] = defaultdict(list)

    menu_stats: dict[str, dict] = defaultdict(lambda: {
        "focus_gained_hooks": 0,
        "needrefresh_true": 0,
        "refresh_calls": 0,
        "ref75_calls": 0,
    })

    needrefresh_re = re.compile(r"\bNeedRefresh\s*=\s*true\b", re.IGNORECASE)
    refresh_re = re.compile(r"^Refresh\(\)$")

    for elem in root.iter():
        tag = local_tag(elem.tag)
        menu_name = menu_name_for(elem, parent_map)

        if tag == "Hook" and elem.attrib.get("Name") == "FocusGained":
            menu_stats[menu_name]["focus_gained_hooks"] += 1

        if tag == "Action":
            name = elem.attrib.get("Name", "")
            action_id = elem.attrib.get("ID")
            ref = elem.attrib.get("Ref")

            if action_id:
                action_defs[action_id] = {
                    "name": name,
                    "menu": menu_name,
                }

            if needrefresh_re.search(name):
                menu_stats[menu_name]["needrefresh_true"] += 1

            if refresh_re.match(name):
                menu_stats[menu_name]["refresh_calls"] += 1

            if ref:
                action_ref_counts[ref] += 1
                action_ref_locations[ref].append({
                    "menu": menu_name,
                    "name": name,
                })
                if ref == "75":
                    menu_stats[menu_name]["ref75_calls"] += 1

    top_refs = sorted(action_ref_counts.items(), key=lambda kv: kv[1], reverse=True)[:30]

    # Focus report on Main Menu + MediaPlayer OSD first.
    focus_menus = [
        "Main Menu",
        "MediaPlayer OSD",
        "Theme Preview Top Right with Info Below",
    ]

    focused = {m: menu_stats.get(m, {}) for m in focus_menus}

    ref75 = {
        "definition": action_defs.get("75"),
        "count": action_ref_counts.get("75", 0),
        "sample_callers": action_ref_locations.get("75", [])[:20],
    }

    return {
        "input": str(path),
        "menus_analyzed": len(menu_stats),
        "focused_menus": focused,
        "ref75": ref75,
        "top_action_refs": [
            {
                "ref": ref_id,
                "count": count,
                "definition": action_defs.get(ref_id),
            }
            for ref_id, count in top_refs
        ],
    }


def main() -> None:
    parser = argparse.ArgumentParser(description="Analyze STV refresh hot paths")
    parser.add_argument("input", help="Path to STV XML file")
    parser.add_argument(
        "--out",
        help="Optional path to write JSON report",
        default=None,
    )
    args = parser.parse_args()

    report = analyze(Path(args.input))
    text = json.dumps(report, indent=2)

    if args.out:
        out_path = Path(args.out)
        out_path.parent.mkdir(parents=True, exist_ok=True)
        out_path.write_text(text, encoding="utf-8")
        print(f"Wrote report: {out_path}")
    else:
        print(text)


if __name__ == "__main__":
    main()
