#!/usr/bin/env python3
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parent

SUPPORTED = {
    "detect_method", "validate_silence", "validate_uniform",
    "validate_scenechange", "verbose", "max_brightness", "test_brightness",
    "max_avg_brightness", "intelligent_brightness", "non_uniformity",
    "max_volume", "min_silence", "min_commercialbreak",
    "max_commercialbreak", "min_commercial_size", "max_commercial_size",
    "min_show_segment_length", "logo_percentile", "logo_threshold",
    "punish_no_logo", "use_existing_logo_file", "delete_logo_file",
    "output_edl", "output_vdr", "thread_count", "lowres", "logo_filter",
    "cut_on_ar_change", "ticker_tape", "ticker_tape_percentage",
    "logo_at_bottom"
}

PROFILES = [
    "comskip_drama.delta.ini",
    "comskip_sitcom.delta.ini",
    "comskip_sports.delta.ini",
    "comskip_news.delta.ini",
    "comskip_game_reality.delta.ini",
    "comskip_movie.delta.ini",
]


def parse_ini(path: Path):
    values = {}
    for line_no, raw in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        line = raw.strip()
        if not line or line.startswith((";", "#", "[")):
            continue
        if "=" not in line:
            raise AssertionError(f"{path.name}:{line_no}: invalid active line: {raw}")
        key, value = line.split("=", 1)
        key, value = key.strip().lower(), value.strip()
        if key in values:
            raise AssertionError(f"{path.name}:{line_no}: duplicate key {key}")
        if key not in SUPPORTED:
            raise AssertionError(f"{path.name}:{line_no}: unsupported key {key}")
        values[key] = value
    return values


def main():
    base = parse_ini(ROOT / "comskip_base.ini")
    if base.get("lowres") != "0":
        raise AssertionError("comskip_base.ini must retain verified-safe lowres=0")
    if base.get("output_edl") != "1":
        raise AssertionError("comskip_base.ini must retain output_edl=1")

    for name in PROFILES:
        delta = parse_ini(ROOT / name)
        for key, value in delta.items():
            if base.get(key) == value:
                raise AssertionError(f"{name}: redundant value {key}={value}")
        effective = dict(base)
        effective.update(delta)
        method = int(effective["detect_method"])
        if method < 0 or method > 255:
            raise AssertionError(f"{name}: invalid detect_method={method}")
        print(f"{name}: {len(delta)} overrides; effective detect_method={method}")

    if parse_ini(ROOT / "channel_override.example.delta.ini"):
        raise AssertionError("channel override example must contain comments only")

    mapping = json.loads(
        (ROOT / "category-profile-map.json").read_text(encoding="utf-8"))
    seen = {}
    for profile, labels in mapping["profiles"].items():
        for label in labels:
            normalized = label.casefold()
            if normalized in seen:
                raise AssertionError(
                    f"duplicate category {label!r}: {profile} and {seen[normalized]}")
            seen[normalized] = profile
    if len(seen) != 68:
        raise AssertionError(
            f"expected 68 unique explicit labels, found {len(seen)}")

    print("Category map: 68 unique explicit labels")
    print("All profile checks passed")


if __name__ == "__main__":
    main()
