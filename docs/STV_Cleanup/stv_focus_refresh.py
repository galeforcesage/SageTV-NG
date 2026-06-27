"""
stv_focus_refresh.py — Phase 2 AC-2.5 Targeted Cache Invalidation

The first AC-2.5 pass refreshed all cached locals on FocusGained for every
screen. That preserved correctness but reduced perceived speed in hot paths.

This targeted pass keeps AC-2.3 as the speed baseline and only refreshes cache
on selected screens where stale state is more likely than focus-loop churn.

Strategy:
    1. Read SetLocal calls from BeforeMenuLoad (AC-2.3 output)
    2. Only select correctness-sensitive variable classes:
             - _c_GetProperty_*
             - _c_GetServerProperty_*
             - _c_GetFavorites*
    3. Skip known hot-path menus (Main Menu, MediaPlayer OSD)
    4. Add/update FocusGained hooks only on selected menus
"""

import xml.etree.ElementTree as ET
import re

# Menus where targeted FocusGained refresh is acceptable and helps correctness.
TARGET_MENU_NAMES = {
        "Configuration Wizard - Network Configuration",
        "Configuration Wizard - Ask Display Videos on Menus",
        "Theme Header & Footer only with Content BG",
        "Theme Preview and Info Top",
        "VideoBG THEME",
        "THEME ORGANIZER 3",
        "Theme Preview Top Right with Info Below",
        "Theme Preview Top Right with Music Info Below",
        "Theme Preview Top Right with Thumb Below for Info Menus ",
        "Online Services Menu",
        "Browser - Photos",
        "Picture Slideshow",
        "Placeshifter",
        "Embedded - Network Config",
        "Favorites Manager",
}

# Menus explicitly excluded due to high-frequency focus churn.
EXCLUDED_MENU_NAMES = {
        "Main Menu",
        "MediaPlayer OSD",
}

TARGET_VAR_RE = re.compile(r'^SetLocal\("(_c_[^\"]+)"')


def extract_before_menu_load_actions(screen: ET.Element) -> list[str]:
    """
    Extract all SetLocal calls from BeforeMenuLoad hook.
    Returns: [SetLocal("_c_var", ...), ...]
    """
    actions = []

    for child in screen:
        tag = child.tag.split("}")[-1] if "}" in child.tag else child.tag
        if tag == "Action" and "BeforeMenuLoad" in child.attrib.get("Name", ""):
            for grandchild in child:
                gc_tag = grandchild.tag.split("}")[-1] if "}" in grandchild.tag else grandchild.tag
                if gc_tag == "Action":
                    action_name = grandchild.attrib.get("Name", "")
                    if "SetLocal" in action_name:
                        actions.append(action_name)

    return actions


def should_refresh_var(setlocal_call: str) -> bool:
    """Only refresh property/server-property/favorites derived cache entries."""
    m = TARGET_VAR_RE.search(setlocal_call)
    if not m:
        return False
    var_name = m.group(1)
    return (
        var_name.startswith("_c_GetProperty_")
        or var_name.startswith("_c_GetServerProperty_")
        or var_name.startswith("_c_GetFavorites")
    )


def find_or_create_focusgained(screen: ET.Element, ns_prefix: str) -> ET.Element:
    """
    Find existing FocusGained hook or create a new one.
    """
    for child in screen:
        tag = child.tag.split("}")[-1] if "}" in child.tag else child.tag
        if tag == "Hook" and child.attrib.get("Name") == "FocusGained":
            return child

    # Create new FocusGained hook
    hook = ET.Element(f"{ns_prefix}Hook" if ns_prefix else "Hook")
    hook.set("Name", "FocusGained")
    screen_name = screen.attrib.get("Name", "Menu")
    safe = re.sub(r'[^A-Za-z0-9]+', '_', screen_name).strip('_')[:24] or "Menu"
    hook.set("Sym", f"REFRESH-FG-TGT-{safe}")
    screen.append(hook)
    return hook


def embed_focus_refresh(xml_path: str, output_path: str) -> int:
    """
    Embed focus-refresh hooks into AC-2.3 cached STV.
    Returns: number of screens modified.
    """
    tree = ET.parse(xml_path)
    root = tree.getroot()

    ns_prefix = ""
    if root.tag.startswith("{"):
        ns_prefix = root.tag.split("}")[0] + "}"

    screens_modified = 0
    total_actions_added = 0

    for elem in root.iter():
        tag = elem.tag.split("}")[-1] if "}" in elem.tag else elem.tag
        if tag != "Menu":
            continue

        screen_name = elem.attrib.get("Name", "Unknown")
        if screen_name in EXCLUDED_MENU_NAMES:
            continue
        if screen_name not in TARGET_MENU_NAMES:
            continue

        cached_locals = extract_before_menu_load_actions(elem)

        if not cached_locals:
            continue  # Screen has no cached locals, skip

        targeted_calls = [call for call in cached_locals if should_refresh_var(call)]
        if not targeted_calls:
            continue

        # Find or create FocusGained hook
        focus_gained = find_or_create_focusgained(elem, ns_prefix)

        existing = {
            child.attrib.get("Name", "")
            for child in focus_gained
            if (child.tag.split("}")[-1] if "}" in child.tag else child.tag) == "Action"
        }

        # Add Actions to re-execute each SetLocal on focus
        added_here = 0
        for setlocal_call in targeted_calls:
            if setlocal_call in existing:
                continue
            action = ET.SubElement(focus_gained, f"{ns_prefix}Action" if ns_prefix else "Action")
            action.set("Name", setlocal_call)
            added_here += 1

        if added_here == 0:
            continue

        screens_modified += 1
        total_actions_added += added_here

    ET.indent(tree, space=" ")
    tree.write(output_path, encoding="UTF-8", xml_declaration=True)

    print(f"Embedded targeted refresh hooks into {screens_modified} screens")
    print(f"Added {total_actions_added} FocusGained refresh actions")
    print(f"Wrote to {output_path}")

    return screens_modified


def run(xml_path: str, output_path: str) -> None:
    print(f"Parsing {xml_path} (AC-2.3 cached STV) ...")
    print("Adding AC-2.5 targeted refresh hooks...\n")

    screens_modified = embed_focus_refresh(xml_path, output_path)

    print(f"\n=== AC-2.5 Targeted Refresh Embedding Complete ===")
    print(f"Screens enhanced: {screens_modified}")
    print("Strategy: targeted FocusGained refresh for property/server/favorites caches")
    print("Impact: correctness where needed without blanket hot-path refresh")


if __name__ == "__main__":
    import argparse
    parser = argparse.ArgumentParser(
        description="Phase 2 AC-2.5 Targeted Cache Invalidation Embedding"
    )
    parser.add_argument("input", help="AC-2.3 patched STV (SageTV7_cached_ac23.xml)")
    parser.add_argument("output", help="Output file with targeted refresh hooks")
    args = parser.parse_args()

    run(args.input, args.output)
