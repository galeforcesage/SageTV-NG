#!/usr/bin/env python3
"""Canonical linear-time expression analyzer for large SageTV STV/STVi XML files."""

import json
import re
import sys
import xml.etree.ElementTree as ET
from collections import Counter

FUNC_RE = re.compile(r'([A-Za-z_][A-Za-z0-9_]*)\s*\(')

HIGH = {
    'GetCurrentMediaFile', 'GetServerProperty', 'GetFavorites',
    'GetScheduledRecordings', 'GetAiringForID', 'GetMediaFiles',
}
MEDIUM = {
    'GetProperty', 'GetLocal', 'GetMenuName', 'GetUIContextName',
    'FilterByBoolMethod', 'Sort', 'Size', 'GetElement',
}


def cost(fn: str, expr_text: str) -> str:
    if fn in HIGH:
        return 'HIGH'
    if fn == 'GetProperty' and ('server/' in expr_text or 'epg/' in expr_text):
        return 'HIGH'
    if fn in MEDIUM:
        return 'MEDIUM'
    return 'LOW'


def main(stv_path: str, out_path: str) -> int:
    fn_counts = Counter()
    cost_counts = Counter()

    # Streaming parse keeps this linear-time and memory-safe for huge STV XML.
    for _, elem in ET.iterparse(stv_path, events=('end',)):
        name = elem.attrib.get('Name')
        if name:
            for fn in FUNC_RE.findall(name):
                fn_counts[fn] += 1
                cost_counts[cost(fn, name)] += 1
        elem.clear()

    total = sum(cost_counts.values())
    high_ratio = (cost_counts['HIGH'] / total) if total else 0.0

    top = fn_counts.most_common(30)
    high_targets = [
        {'function': fn, 'count': c}
        for fn, c in top
        if fn in HIGH or fn == 'GetProperty'
    ]

    if high_ratio > 0.40:
        assessment = 'CRITICAL'
    elif high_ratio > 0.25:
        assessment = 'SIGNIFICANT'
    elif high_ratio > 0.15:
        assessment = 'MODERATE'
    else:
        assessment = 'LOW'

    report = {
        'total_function_calls': total,
        'high_cost_calls': cost_counts['HIGH'],
        'medium_cost_calls': cost_counts['MEDIUM'],
        'low_cost_calls': cost_counts['LOW'],
        'high_cost_ratio': round(high_ratio, 4),
        'assessment': assessment,
        'top_functions': [{'function': fn, 'count': c} for fn, c in top],
        'high_value_targets': high_targets,
    }

    with open(out_path, 'w', encoding='utf-8') as f:
        json.dump(report, f, indent=2)

    print(f"total={total} high={cost_counts['HIGH']} medium={cost_counts['MEDIUM']} low={cost_counts['LOW']} ratio={high_ratio:.1%} assessment={assessment}")
    return 0


if __name__ == '__main__':
    if len(sys.argv) != 3:
        print('Usage: stv_expression_analyzer_fast.py <input_stv> <output_json>')
        sys.exit(1)
    sys.exit(main(sys.argv[1], sys.argv[2]))
