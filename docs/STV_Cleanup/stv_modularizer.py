"""
stv_modularizer.py — Phase 4: Screen Isolation & Modularization
Two sub-commands:

  split     Split the monolith into per-Sym-prefix module files and add
            IsCurrentMenu() guards to every screen subtree.

  compose   Merge module files back into a canonical STV and apply any
            STVi plugin injections via the hook registry.

Usage:
    # Step 1 — add guards and split into modules
    python stv_modularizer.py split SageTV7.xml ./modules/

    # Step 2 — edit modules, then recompose
    python stv_modularizer.py compose ./modules/ SageTV7_composed.xml

    # Step 2 with plugins
    python stv_modularizer.py compose ./modules/ SageTV7_composed.xml \\
        --plugins ./plugins/ --hooks hooks.json \\
        --order BASE OPUS4 OPUS4A NFLX1 COMSKIP XHDFU
"""

import xml.etree.ElementTree as ET
import json
import re
import argparse
from pathlib import Path
from collections import defaultdict

WIDGET_TAGS = {
    "Menu", "Panel", "Action", "Conditional", "Image", "Video",
    "Text", "Theme", "Hook", "Listener", "Table", "Shape",
}


def strip_ns(tag: str) -> str:
    return tag.split("}")[-1] if "}" in tag else tag


# ──────────────────────────────────────────────
# SPLIT
# ──────────────────────────────────────────────

def sym_prefix(elem: ET.Element) -> str:
    sym = elem.attrib.get("Sym", "")
    return sym.split("-")[0] if "-" in sym else "MISC"


def add_screen_guards(root: ET.Element, ns: str) -> int:
    """
    Wrap each inline Menu's widget children in an IsCurrentMenu() Conditional.
    Catbert short-circuits on false Conditionals — inactive screens are skipped.
    Returns the number of screens guarded.
    """
    guarded = 0

    for elem in root.iter():
        if strip_ns(elem.tag) != "Menu" or "Ref" in elem.attrib:
            continue

        menu_name = elem.attrib.get("Name", "")
        if not menu_name:
            continue

        # Skip if already guarded
        already = any(
            "IsCurrentMenu" in c.attrib.get("Name", "")
            for c in elem
            if strip_ns(c.tag) == "Conditional"
        )
        if already:
            continue

        # Partition children into properties vs widget children
        widget_kids = [c for c in elem if strip_ns(c.tag) in WIDGET_TAGS]
        if not widget_kids:
            continue

        guard = ET.Element(f"{ns}Conditional")
        guard.set("Name", f'IsCurrentMenu("{menu_name}")')
        safe_id = re.sub(r"[^A-Z0-9]", "_", menu_name.upper())[:30]
        guard.set("Sym", f"GUARD-{safe_id}")

        for kid in widget_kids:
            elem.remove(kid)
            guard.append(kid)

        elem.append(guard)
        guarded += 1

    return guarded


def split_by_prefix(root: ET.Element) -> dict[str, list]:
    modules: dict = defaultdict(list)
    for child in root:
        modules[sym_prefix(child)].append(child)
    return modules


def write_module(prefix: str, elements: list, out_dir: Path,
                 ns_uri: str) -> Path:
    mod_root = ET.Element("Module")
    mod_root.set("xmlns", ns_uri)
    mod_root.set("Name", prefix)
    for elem in elements:
        mod_root.append(elem)

    tree = ET.ElementTree(mod_root)
    ET.indent(tree, space=" ")
    out_path = out_dir / f"{prefix.lower()}.stv"
    tree.write(str(out_path), encoding="UTF-8", xml_declaration=True)
    return out_path


def cmd_split(input_path: str, output_dir: str,
              skip_guards: bool, dry_run: bool) -> None:
    print(f"Parsing {input_path} ...")
    tree = ET.parse(input_path)
    root = tree.getroot()

    ns_uri = "urn:tv.sage/stv"
    ns = ""
    if root.tag.startswith("{"):
        ns = root.tag.split("}")[0] + "}"
        ns_uri = root.tag.split("}")[0].lstrip("{")

    if not skip_guards:
        print("Adding IsCurrentMenu() screen isolation guards ...")
        guarded = add_screen_guards(root, ns)
        print(f"  Guarded {guarded} screens")

    print("Splitting by Sym prefix ...")
    modules = split_by_prefix(root)

    print("\nModule breakdown:")
    for prefix, elems in sorted(modules.items(), key=lambda x: -len(x[1])):
        print(f"  {prefix:<14} {len(elems):>6,} widgets")

    if dry_run:
        print("\n[dry-run] No files written.")
        return

    out_dir = Path(output_dir)
    out_dir.mkdir(parents=True, exist_ok=True)

    for prefix, elems in modules.items():
        path = write_module(prefix, elems, out_dir, ns_uri)
        print(f"  Written: {path}")

    # Write module manifest for compose step
    manifest = {
        "ns_uri": ns_uri,
        "modules": sorted(modules.keys(), key=lambda p: -len(modules[p])),
    }
    manifest_path = out_dir / "manifest.json"
    manifest_path.write_text(json.dumps(manifest, indent=2))
    print(f"  Written: {manifest_path}")
    print(f"\nDone — {len(modules)} modules in {out_dir}/")


# ──────────────────────────────────────────────
# COMPOSE
# ──────────────────────────────────────────────

def load_modules(module_dir: Path, order: list[str] | None) -> list[tuple[str, ET.Element]]:
    if order:
        paths = [module_dir / f"{p.lower()}.stv" for p in order]
        paths += sorted(p for p in module_dir.glob("*.stv")
                        if p.stem.upper() not in (o.upper() for o in order))
    else:
        manifest_path = module_dir / "manifest.json"
        if manifest_path.exists():
            manifest = json.loads(manifest_path.read_text())
            order = manifest.get("modules", [])
            paths = [module_dir / f"{p.lower()}.stv" for p in order]
            paths += sorted(p for p in module_dir.glob("*.stv")
                            if p.stem.upper() not in (o.upper() for o in order))
        else:
            paths = sorted(module_dir.glob("*.stv"))

    modules = []
    for path in paths:
        if path.exists():
            mod_root = ET.parse(str(path)).getroot()
            name = path.stem.upper()
            widget_count = sum(1 for _ in mod_root)
            modules.append((name, mod_root))
            print(f"  Loaded {path.name}  ({widget_count:,} widgets)")
    return modules


def load_hooks(hooks_path: str) -> dict:
    p = Path(hooks_path)
    if not p.exists():
        return {}
    return json.loads(p.read_text())


def apply_plugins(merged_root: ET.Element, plugins_dir: str, hooks: dict) -> None:
    plugins_dir = Path(plugins_dir)
    if not plugins_dir.exists():
        print(f"  WARNING: plugins dir {plugins_dir} not found — skipping")
        return

    id_index = {elem.attrib["ID"]: elem
                for elem in merged_root.iter()
                if "ID" in elem.attrib}

    for stvi_path in sorted(plugins_dir.glob("*.stvi")):
        print(f"  Plugin: {stvi_path.name}")
        stvi_root = ET.parse(str(stvi_path)).getroot()

        for hook_elem in stvi_root.iter("ImportSTV"):
            hook_name = hook_elem.attrib.get("hook", "")
            target_id = hook_elem.attrib.get("target", "")

            # Resolve named hook → widget ID
            if hook_name and hook_name in hooks:
                target_id = str(hooks[hook_name].get("target", target_id))

            target = id_index.get(target_id)
            if target is None:
                print(f"    WARNING: hook target ID={target_id!r} not found")
                continue

            for widget in hook_elem:
                target.append(widget)
                print(f"    Injected {strip_ns(widget.tag)} → widget {target_id}")


def validate_ids(root: ET.Element) -> tuple[int, list]:
    seen: dict = {}
    conflicts = []
    for elem in root.iter():
        elem_id = elem.attrib.get("ID", "")
        if elem_id:
            if elem_id in seen:
                conflicts.append(elem_id)
            seen[elem_id] = elem
    return len(seen), conflicts


def cmd_compose(module_dir: str, output_path: str,
                plugins_dir: str | None, hooks_path: str,
                order: list[str] | None) -> None:
    mod_dir = Path(module_dir)

    # Detect namespace from manifest or first module
    manifest_path = mod_dir / "manifest.json"
    ns_uri = "urn:tv.sage/stv"
    if manifest_path.exists():
        ns_uri = json.loads(manifest_path.read_text()).get("ns_uri", ns_uri)

    print(f"Loading modules from {mod_dir} ...")
    modules = load_modules(mod_dir, order)
    if not modules:
        print("No modules found — aborting.")
        return

    merged = ET.Element("Module")
    merged.set("xmlns", ns_uri)
    merged.set("Name", "default")
    merged.set("PersistentPrimaryRefs", "true")

    total = 0
    for _name, mod_root in modules:
        for child in mod_root:
            merged.append(child)
            total += 1

    print(f"\nMerged {total:,} top-level widgets from {len(modules)} modules")

    if plugins_dir:
        hooks = load_hooks(hooks_path)
        print(f"\nApplying plugins from {plugins_dir} ...")
        apply_plugins(merged, plugins_dir, hooks)

    unique_ids, conflicts = validate_ids(merged)
    if conflicts:
        print(f"\nWARNING: {len(conflicts)} duplicate IDs: {conflicts[:5]}")
    else:
        print(f"ID validation: OK ({unique_ids:,} unique IDs)")

    print(f"\nWriting to {output_path} ...")
    tree = ET.ElementTree(merged)
    ET.indent(tree, space=" ")
    tree.write(output_path, encoding="UTF-8", xml_declaration=True)
    size_mb = Path(output_path).stat().st_size / 1024 / 1024
    print(f"Done.  {size_mb:.1f} MB")


# ──────────────────────────────────────────────
# CLI
# ──────────────────────────────────────────────

if __name__ == "__main__":
    parser = argparse.ArgumentParser(
        description="STV Modularizer — Phase 4  (split | compose)"
    )
    sub = parser.add_subparsers(dest="cmd", required=True)

    sp = sub.add_parser("split", help="Split monolith into module files")
    sp.add_argument("input", help="Input STV XML file")
    sp.add_argument("output_dir", help="Directory to write module files")
    sp.add_argument("--no-guards", action="store_true",
                    help="Skip adding IsCurrentMenu() guards")
    sp.add_argument("--dry-run", action="store_true",
                    help="Report only — do not write files")

    cp = sub.add_parser("compose", help="Merge modules into a composed STV")
    cp.add_argument("module_dir", help="Directory containing .stv module files")
    cp.add_argument("output", help="Output composed STV file")
    cp.add_argument("--plugins", metavar="DIR",
                    help="Directory containing .stvi plugin files")
    cp.add_argument("--hooks", default="hooks.json",
                    help="Hook point registry JSON (default: hooks.json)")
    cp.add_argument("--order", nargs="+", metavar="PREFIX",
                    help="Module load order  e.g.  BASE OPUS4 OPUS4A NFLX1")

    args = parser.parse_args()

    if args.cmd == "split":
        cmd_split(args.input, args.output_dir,
                  skip_guards=args.no_guards, dry_run=args.dry_run)
    else:
        cmd_compose(args.module_dir, args.output,
                    plugins_dir=args.plugins,
                    hooks_path=args.hooks,
                    order=args.order)
