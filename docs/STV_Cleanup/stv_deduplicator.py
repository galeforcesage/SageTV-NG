"""
stv_deduplicator.py — Phase 1: Widget Deduplication
Finds inline widget definitions that appear multiple times with identical
subtrees and collapses them to a single shared widget referenced via Ref=.

Usage:
    python stv_deduplicator.py SageTV7.xml SageTV7_deduped.xml
    python stv_deduplicator.py SageTV7.xml SageTV7_deduped.xml --dry-run
    python stv_deduplicator.py SageTV7.xml SageTV7_deduped.xml --min-copies 3
"""

import xml.etree.ElementTree as ET
import hashlib
import argparse
from pathlib import Path
from collections import defaultdict

WIDGET_TAGS = {
    "Menu", "Panel", "Action", "Conditional", "Image", "Video",
    "Text", "Theme", "Hook", "Listener", "Table", "Shape",
}


def canonical_hash(element: ET.Element) -> str:
    """
    Produce a stable hash of a widget subtree for identity comparison.
    Strips Sym and ID attributes (instance-specific) before hashing.
    """
    def _canon(elem) -> str:
        tag = elem.tag.split("}")[-1] if "}" in elem.tag else elem.tag
        attribs = {k: v for k, v in sorted(elem.attrib.items())
                   if k not in ("Sym", "ID")}
        attr_str = "".join(f"{k}={v}" for k, v in attribs.items())
        text = (elem.text or "").strip()
        children = "".join(_canon(c) for c in elem)
        return f"<{tag}{attr_str}>{text}{children}</{tag}>"

    return hashlib.md5(_canon(element).encode()).hexdigest()


def collect_inline_widgets(root: ET.Element) -> dict:
    """
    Walk the entire graph and collect all inline widgets (no ID, no Ref)
    grouped by (tag, Name, content_hash).
    Returns: {group_key: [{"element": elem, "parent": parent_elem}]}
    """
    groups: dict = defaultdict(list)

    def walk(elem, parent=None):
        tag = elem.tag.split("}")[-1] if "}" in elem.tag else elem.tag
        if tag in WIDGET_TAGS and "ID" not in elem.attrib and "Ref" not in elem.attrib:
            name = elem.attrib.get("Name", "")
            h = canonical_hash(elem)
            groups[(tag, name, h)].append({"element": elem, "parent": parent})
        for child in elem:
            walk(child, elem)

    walk(root)
    return groups


def get_max_id(root: ET.Element) -> int:
    max_id = 0
    for elem in root.iter():
        for attr in ("ID", "Ref"):
            val = elem.attrib.get(attr, "")
            if val.isdigit():
                max_id = max(max_id, int(val))
    return max_id


def deduplicate(input_path: str, output_path: str,
                min_copies: int = 2, dry_run: bool = False) -> int:
    print(f"Parsing {input_path} ...")
    tree = ET.parse(input_path)
    root = tree.getroot()

    # Detect namespace prefix for creating new Ref elements
    ns_prefix = ""
    if root.tag.startswith("{"):
        ns_prefix = root.tag.split("}")[0] + "}"

    print("Collecting inline widgets ...")
    groups = collect_inline_widgets(root)

    dup_groups = {k: v for k, v in groups.items() if len(v) >= min_copies}
    print(f"Found {len(dup_groups)} duplicate groups (min {min_copies} copies each)\n")

    next_id = get_max_id(root) + 1
    total_removed = 0
    report_lines = []

    # Process largest groups first (most savings)
    for (tag, name, _), instances in sorted(dup_groups.items(),
                                             key=lambda x: -len(x[1])):
        count = len(instances)
        savings = count - 1
        report_lines.append(f"  {tag} '{name[:55]}': {count} copies → save {savings} defs")

        if dry_run:
            total_removed += savings
            continue

        # Keep first instance, give it an ID
        canonical_elem = instances[0]["element"]
        canonical_elem.set("ID", str(next_id))

        # Replace remaining instances with lightweight Ref elements
        for inst in instances[1:]:
            parent = inst["parent"]
            elem = inst["element"]
            if parent is None:
                continue

            children = list(parent)
            if elem not in children:
                continue
            idx = children.index(elem)

            ref_elem = ET.Element(f"{ns_prefix}{tag}")
            ref_elem.set("Ref", str(next_id))
            ref_elem.set("Name", name)

            parent.remove(elem)
            parent.insert(idx, ref_elem)
            total_removed += 1

        next_id += 1

    # Print report
    print("=== Deduplication Report ===")
    for line in report_lines[:25]:
        print(line)
    if len(report_lines) > 25:
        print(f"  ... and {len(report_lines) - 25} more groups")
    print(f"\nTotal redundant definitions removed: {total_removed:,}")

    if not dry_run:
        print(f"\nWriting to {output_path} ...")
        ET.indent(tree, space=" ")
        tree.write(output_path, encoding="UTF-8", xml_declaration=True)
        orig_size = Path(input_path).stat().st_size
        new_size = Path(output_path).stat().st_size
        saved_kb = (orig_size - new_size) / 1024
        print(f"Done.  {orig_size/1024:.0f} KB → {new_size/1024:.0f} KB  (saved {saved_kb:.0f} KB)")

    return total_removed


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="STV Widget Deduplicator — Phase 1")
    parser.add_argument("input", help="Input STV XML file")
    parser.add_argument("output", help="Output STV XML file")
    parser.add_argument("--min-copies", type=int, default=2,
                        help="Minimum copies required to deduplicate (default: 2)")
    parser.add_argument("--dry-run", action="store_true",
                        help="Report only — do not write output file")
    args = parser.parse_args()
    deduplicate(args.input, args.output, args.min_copies, args.dry_run)
