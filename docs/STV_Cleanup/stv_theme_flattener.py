"""
stv_theme_flattener.py — Phase 3: Theme Chain Flattening
Pre-resolves theme inheritance chains and writes final property values
directly onto each theme widget, eliminating ancestor traversal at paint time.

Usage:
    python stv_theme_flattener.py SageTV7.xml SageTV7_flat.xml
    python stv_theme_flattener.py SageTV7.xml SageTV7_flat.xml --dry-run
    python stv_theme_flattener.py SageTV7.xml SageTV7_flat.xml --depth-report
"""

import xml.etree.ElementTree as ET
import argparse
from pathlib import Path
from collections import defaultdict

# All known STV property element names that can appear inside a Theme widget.
# Extend if your STV uses custom theme properties.
THEME_PROP_TAGS = {
    "Font", "FontStyle", "FontSize", "TextColor", "ForegroundColor",
    "BackgroundColor", "ForegroundAlpha", "BackgroundAlpha",
    "TextShadow", "TextShadowColor", "TextShadowOffsetX", "TextShadowOffsetY",
    "Insets", "CornerArc", "Padding", "Layout",
    "AnchorX", "AnchorY", "AnchorPointX", "AnchorPointY",
    "FixedWidth", "FixedHeight",
    "ScalingInsets", "ShapeType", "ShapeFill", "BorderColor",
}


def strip_ns(tag: str) -> str:
    return tag.split("}")[-1] if "}" in tag else tag


def collect_id_themes(root: ET.Element) -> dict[str, ET.Element]:
    """Return {id_str: theme_element} for every Theme that carries an ID."""
    themes: dict[str, ET.Element] = {}
    for elem in root.iter():
        if strip_ns(elem.tag) == "Theme" and "ID" in elem.attrib:
            themes[elem.attrib["ID"]] = elem
    return themes


def theme_parent_ref(theme_elem: ET.Element) -> str | None:
    """Return the Ref ID of this theme's parent, or None."""
    for child in theme_elem:
        if strip_ns(child.tag) == "Theme" and "Ref" in child.attrib:
            return child.attrib["Ref"]
    return None


def resolve_chain(theme_id: str,
                  id_themes: dict[str, ET.Element],
                  cache: dict[str, dict]) -> dict[str, str]:
    """
    Walk the inheritance chain (child overrides parent).
    Returns {prop_name: value_text} with the fully resolved property set.
    Uses memoisation to avoid re-walking shared ancestors.
    """
    if theme_id in cache:
        return cache[theme_id]

    elem = id_themes.get(theme_id)
    if elem is None:
        cache[theme_id] = {}
        return {}

    parent_ref = theme_parent_ref(elem)
    resolved: dict = {}
    if parent_ref:
        resolved = dict(resolve_chain(parent_ref, id_themes, cache))

    # This theme's own props override parent
    for child in elem:
        prop = strip_ns(child.tag)
        if prop in THEME_PROP_TAGS:
            resolved[prop] = child.text or ""

    cache[theme_id] = resolved
    return resolved


def chain_depth(theme_id: str,
                id_themes: dict[str, ET.Element],
                _seen: set | None = None) -> int:
    if _seen is None:
        _seen = set()
    if theme_id in _seen or theme_id not in id_themes:
        return 0
    _seen.add(theme_id)
    parent = theme_parent_ref(id_themes[theme_id])
    return 1 + (chain_depth(parent, id_themes, _seen) if parent else 0)


def flatten_theme_elem(theme_elem: ET.Element,
                       resolved: dict[str, str],
                       ns_prefix: str) -> None:
    """
    Remove all property children and the parent Ref child from this theme,
    then write the fully resolved property values as new child elements.
    """
    to_remove = []
    for child in theme_elem:
        prop = strip_ns(child.tag)
        if prop in THEME_PROP_TAGS or (prop == "Theme" and "Ref" in child.attrib):
            to_remove.append(child)
    for child in to_remove:
        theme_elem.remove(child)

    for prop_name, prop_value in resolved.items():
        el = ET.SubElement(theme_elem, f"{ns_prefix}{prop_name}")
        el.text = prop_value


def flatten(input_path: str, output_path: str,
            dry_run: bool = False, depth_report: bool = False) -> None:
    print(f"Parsing {input_path} ...")
    tree = ET.parse(input_path)
    root = tree.getroot()

    ns_prefix = ""
    if root.tag.startswith("{"):
        ns_prefix = root.tag.split("}")[0] + "}"

    id_themes = collect_id_themes(root)
    print(f"Found {len(id_themes):,} ID'd theme widgets")

    if depth_report:
        depths: dict = defaultdict(int)
        for tid in id_themes:
            d = chain_depth(tid, id_themes)
            depths[d] += 1
        print("\nTheme chain depth distribution:")
        for d in sorted(depths):
            print(f"  depth {d:>2}: {depths[d]:>5} themes")

    cache: dict = {}
    flattened = 0
    max_depth_seen = 0

    for theme_id, theme_elem in id_themes.items():
        parent_ref = theme_parent_ref(theme_elem)
        if parent_ref is None:
            continue  # Root theme — nothing to flatten

        d = chain_depth(theme_id, id_themes)
        max_depth_seen = max(max_depth_seen, d)

        resolved = resolve_chain(theme_id, id_themes, cache)

        if not dry_run:
            flatten_theme_elem(theme_elem, resolved, ns_prefix)

        flattened += 1

    print(f"\nThemes flattened  : {flattened:,}")
    print(f"Max chain depth   : {max_depth_seen}")
    print(f"Paint-time savings: O(depth) → O(1) per theme property lookup")

    if not dry_run:
        print(f"\nWriting to {output_path} ...")
        ET.indent(tree, space=" ")
        tree.write(output_path, encoding="UTF-8", xml_declaration=True)
        orig = Path(input_path).stat().st_size
        new = Path(output_path).stat().st_size
        delta_kb = (new - orig) / 1024
        sign = "+" if delta_kb >= 0 else ""
        print(f"Done.  Size delta: {sign}{delta_kb:.0f} KB  "
              f"(expected slight increase — pre-resolved values are inline now)")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="STV Theme Chain Flattener — Phase 3")
    parser.add_argument("input", help="Input STV XML file")
    parser.add_argument("output", help="Output STV XML file")
    parser.add_argument("--dry-run", action="store_true",
                        help="Analyse only — do not write output")
    parser.add_argument("--depth-report", action="store_true",
                        help="Print chain depth distribution before flattening")
    args = parser.parse_args()
    flatten(args.input, args.output, args.dry_run, args.depth_report)
