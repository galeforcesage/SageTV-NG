"""
stv_cache_patcher.py — Phase 2: Expression Caching
Identifies expensive Catbert expressions called multiple times within the
same screen subtree and generates SetLocal/GetLocal patches.

Two modes:
  --report  Print patch plan without modifying the STV (safe first step)
  --apply   Apply patches to the STV (writes output file)

Usage:
    python stv_cache_patcher.py SageTV7.xml --report
    python stv_cache_patcher.py SageTV7.xml --apply SageTV7_cached.xml

Architecture note:
    This module handles build-time patch generation only.
    Runtime cache invalidation belongs in STV XML hooks and Java callbacks.
    See PHASE2_NOTES.md for the two-layer design and acceptance mapping.
"""

import xml.etree.ElementTree as ET
import re
import argparse
from pathlib import Path
from collections import defaultdict

# Expressions worth caching: I/O-bound or computed per-repaint.
# Extend this list as you discover other hot calls via profiling.
EXPENSIVE_PATTERNS = [
    r'GetProperty\("[^"]+(?:",\s*"[^"]*")?\)',
    r'GetServerProperty\("[^"]+(?:",\s*"[^"]*")?\)',
    r'GetCurrentMediaFile\(\)',
    r'GetMediaFiles\([^)]*\)',
    r'GetFavorites\(\)',
    r'GetElement\([^,]+,\s*\d+\)',
    r'GetShowEpisode\([^)]+\)',
    r'GetAlbumForFile\([^)]+\)',
    r'GetCurrentPlaylist\(\)',
    r'GetNumberOfPlaylistItems\(\)',
]
EXPR_RE = re.compile("|".join(EXPENSIVE_PATTERNS))

WIDGET_TAGS = {
    "Menu", "Panel", "Action", "Conditional", "Image", "Video",
    "Text", "Theme", "Hook", "Listener", "Table", "Shape",
}


def var_name(expr: str) -> str:
    """Convert an expression to a safe SetLocal variable name."""
    # GetProperty("video_menu_style", "XWindow") -> _c_GetProperty_video_menu_style
    inner = re.sub(r'[^a-zA-Z0-9]', '_', expr)
    inner = re.sub(r'_+', '_', inner).strip('_')
    return f"_c_{inner[:45]}"


def find_screens(root: ET.Element) -> list[tuple[str, ET.Element]]:
    """Return list of (name, element) for every inline Menu widget."""
    screens = []
    for elem in root.iter():
        tag = elem.tag.split("}")[-1] if "}" in elem.tag else elem.tag
        if tag == "Menu" and "Ref" not in elem.attrib:
            name = elem.attrib.get("Name", f"Menu_{elem.attrib.get('ID', '?')}")
            screens.append((name, elem))
    return screens


def scan_screen(screen_elem: ET.Element) -> dict[str, list]:
    """
    Walk a screen's subtree and collect every expensive expression
    together with the elements that reference it.
    Returns: {expr: [(element, attr_name_or_"text")]}
    """
    locations: dict = defaultdict(list)

    def walk(elem):
        tag = elem.tag.split("}")[-1] if "}" in elem.tag else elem.tag

        # Check Name attribute and other string attribs
        for attr, val in elem.attrib.items():
            if val and "(" in val:
                for m in EXPR_RE.finditer(val):
                    locations[m.group()].append((elem, attr))

        # Check property child element text (e.g. <AnchorX>=someExpr</AnchorX>)
        for child in elem:
            child_tag = child.tag.split("}")[-1] if "}" in child.tag else child.tag
            if child_tag not in WIDGET_TAGS:
                if child.text and "(" in child.text:
                    for m in EXPR_RE.finditer(child.text):
                        locations[m.group()].append((child, "text"))
            else:
                walk(child)

    walk(screen_elem)
    return {expr: locs for expr, locs in locations.items() if len(locs) >= 2}


def find_before_menu_load(screen_elem: ET.Element) -> ET.Element | None:
    """Return the BeforeMenuLoad Action element within this screen, if any."""
    for child in screen_elem:
        tag = child.tag.split("}")[-1] if "}" in child.tag else child.tag
        if tag == "Action" and "BeforeMenuLoad" in child.attrib.get("Name", ""):
            return child
    return None


def apply_patches(root: ET.Element, ns_prefix: str, dry_run: bool) -> list[dict]:
    screens = find_screens(root)
    all_patches = []

    for screen_name, screen_elem in screens:
        hot = scan_screen(screen_elem)
        if not hot:
            continue

        patch = {"screen": screen_name, "set_locals": [], "replacements": []}

        bml = find_before_menu_load(screen_elem)

        for expr, locs in sorted(hot.items(), key=lambda x: -len(x[1])):
            vname = var_name(expr)
            set_call = f'SetLocal("{vname}", {expr})'
            get_call = f'GetLocal("{vname}")'

            patch["set_locals"].append(set_call)
            patch["replacements"].append({
                "expr": expr,
                "var": vname,
                "occurrences": len(locs),
            })

            if not dry_run:
                # 1. Add SetLocal to BeforeMenuLoad (or create one if missing)
                if bml is None:
                    bml = ET.SubElement(screen_elem, f"{ns_prefix}Action")
                    bml.set("Name", "BeforeMenuLoad")
                    bml.set("Sym", f"CACHE-BML-{screen_name[:20].replace(' ', '_')}")

                set_action = ET.SubElement(bml, f"{ns_prefix}Action")
                set_action.set("Name", set_call)

                # 2. Replace inline expressions with GetLocal()
                for elem, attr in locs:
                    if attr == "text":
                        if elem.text:
                            elem.text = elem.text.replace(expr, get_call)
                    else:
                        if attr in elem.attrib:
                            elem.attrib[attr] = elem.attrib[attr].replace(expr, get_call)

        if patch["set_locals"]:
            all_patches.append(patch)

    return all_patches


def print_report(patches: list[dict]) -> None:
    total_replacements = sum(
        r["occurrences"] for p in patches for r in p["replacements"]
    )
    print(f"\n=== Expression Cache Patch Report ===")
    print(f"Screens with cacheable expressions : {len(patches)}")
    print(f"Total expression replacements      : {total_replacements}\n")

    for p in patches:
        print(f"Screen: {p['screen']}")
        print(f"  Add to BeforeMenuLoad:")
        for sl in p["set_locals"]:
            print(f"    {sl}")
        print(f"  Replace in subtree:")
        for r in p["replacements"]:
            print(f"    {r['expr'][:60]}  ->  GetLocal(\"{r['var']}\")  "
                  f"[{r['occurrences']} occurrences]")
        print()


def run(input_path: str, output_path: str | None, dry_run: bool) -> None:
    print(f"Parsing {input_path} ...")
    tree = ET.parse(input_path)
    root = tree.getroot()

    ns_prefix = ""
    if root.tag.startswith("{"):
        ns_prefix = root.tag.split("}")[0] + "}"

    patches = apply_patches(root, ns_prefix, dry_run)
    print_report(patches)

    if not dry_run and output_path:
        print(f"Writing patched STV to {output_path} ...")
        ET.indent(tree, space=" ")
        tree.write(output_path, encoding="UTF-8", xml_declaration=True)
        print("Done.")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="STV Expression Cache Patcher — Phase 2")
    parser.add_argument("input", help="Input STV XML file")
    group = parser.add_mutually_exclusive_group(required=True)
    group.add_argument("--report", action="store_true",
                       help="Analyse and print patch plan (no changes written)")
    group.add_argument("--apply", metavar="OUTPUT",
                       help="Apply patches and write to OUTPUT file")
    args = parser.parse_args()

    run(args.input,
        output_path=args.apply if args.apply else None,
        dry_run=args.report)
