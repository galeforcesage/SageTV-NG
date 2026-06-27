#!/usr/bin/env python3
"""
Streaming expression analysis for SageTV STV/STVi XML files.

This is the compatibility-facing analyzer for larger templates and plugin STVi
files. It avoids parent walks and full-tree retention so it scales linearly.
"""

import json
import re
import sys
import xml.etree.ElementTree as ET
from collections import Counter, defaultdict


FUNC_RE = re.compile(r'([A-Za-z_][A-Za-z0-9_]*)\s*\(')
MAX_LOCATION_SAMPLES = 5

HIGH_COST_FUNCTIONS = {
    'GetCurrentMediaFile',
    'GetServerProperty',
    'GetFavorites',
    'GetScheduledRecordings',
    'GetAiringForID',
    'GetMediaFiles',
}

MEDIUM_COST_FUNCTIONS = {
    'GetProperty',
    'GetLocal',
    'GetMenuName',
    'GetUIContextName',
    'FilterByBoolMethod',
    'Sort',
    'Size',
    'GetElement',
}


def classify_cost(function_name, expr_text):
    if function_name in HIGH_COST_FUNCTIONS:
        return 'HIGH'
    if function_name == 'GetProperty' and ('server/' in expr_text or 'epg/' in expr_text):
        return 'HIGH'
    if function_name in MEDIUM_COST_FUNCTIONS:
        return 'MEDIUM'
    return 'LOW'


def generate_assessment(high_cost_ratio):
    if high_cost_ratio > 0.40:
        return (
            'CRITICAL: >40% of function calls are high-cost. Expression evaluation is likely '
            'the primary bottleneck and broader caching would be justified.'
        )
    if high_cost_ratio > 0.25:
        return (
            'SIGNIFICANT: 25-40% of function calls are high-cost. Expression evaluation is '
            'a meaningful bottleneck and targeted caching could still help.'
        )
    if high_cost_ratio > 0.15:
        return (
            'MODERATE: 15-25% of function calls are high-cost. Refresh-path work should help, '
            'with limited remaining upside from extra expression caching.'
        )
    return (
        'LOW: <15% of function calls are high-cost. Expression overhead is not the dominant '
        'problem; refresh/render behavior is the better optimization target.'
    )


def analyze_expression_overhead(xml_path):
    menu_stack = []
    counts_by_tag = Counter()
    function_counts = Counter()
    cost_counts = Counter()
    function_samples = defaultdict(list)

    for event, elem in ET.iterparse(xml_path, events=('start', 'end')):
        tag = elem.tag.split('}')[-1]

        if event == 'start':
            counts_by_tag[tag] += 1
            if tag == 'Menu':
                menu_stack.append(elem.attrib.get('Name', 'unknown'))
            continue

        name = elem.attrib.get('Name')
        if name:
            current_menu = menu_stack[-1] if menu_stack else 'unknown'
            for function_name in FUNC_RE.findall(name):
                function_counts[function_name] += 1
                call_cost = classify_cost(function_name, name)
                cost_counts[call_cost] += 1

                samples = function_samples[function_name]
                if len(samples) < MAX_LOCATION_SAMPLES:
                    samples.append({
                        'menu': current_menu,
                        'tag': tag,
                        'text': name[:120],
                    })

        if tag == 'Menu' and menu_stack:
            menu_stack.pop()
        elem.clear()

    total_function_calls = sum(cost_counts.values())
    high_cost_ratio = (
        cost_counts['HIGH'] / total_function_calls if total_function_calls else 0.0
    )

    top_functions = function_counts.most_common(30)
    high_value_targets = [
        {
            'function': function_name,
            'count': count,
            'cost': classify_cost(function_name, function_name),
            'sample_locations': function_samples[function_name],
        }
        for function_name, count in top_functions
        if classify_cost(function_name, function_name) in ('HIGH', 'MEDIUM')
    ]

    return {
        'summary': {
            'menus': counts_by_tag['Menu'],
            'actions': counts_by_tag['Action'],
            'conditionals': counts_by_tag['Conditional'],
            'total_function_calls': total_function_calls,
            'unique_functions': len(function_counts),
            'high_cost_calls': cost_counts['HIGH'],
            'medium_cost_calls': cost_counts['MEDIUM'],
            'low_cost_calls': cost_counts['LOW'],
            'high_cost_ratio': round(high_cost_ratio, 4),
            'assessment': generate_assessment(high_cost_ratio),
        },
        'top_functions': [
            {'function': function_name, 'count': count}
            for function_name, count in top_functions
        ],
        'high_value_targets': high_value_targets,
    }


def main(xml_path, out_path):
    report = analyze_expression_overhead(xml_path)

    with open(out_path, 'w', encoding='utf-8') as handle:
        json.dump(report, handle, indent=2)

    summary = report['summary']
    print(f"Scanning {xml_path} for expression overhead...")
    print(f"  Menus: {summary['menus']}")
    print(f"  Actions: {summary['actions']}")
    print(f"  Conditionals: {summary['conditionals']}")
    print(f"  Unique functions: {summary['unique_functions']}")
    print('')
    print('Expression Cost Distribution:')
    print(f"  HIGH: {summary['high_cost_calls']} ({summary['high_cost_ratio']:.1%})")
    print(f"  MEDIUM: {summary['medium_cost_calls']}")
    print(f"  LOW: {summary['low_cost_calls']}")
    print('')
    print(f"Assessment: {summary['assessment']}")
    print(f"Report written to {out_path}")
    return 0


if __name__ == '__main__':
    if len(sys.argv) != 3:
        print('Usage: stv_expression_analyzer.py <input_xml> <output_json>')
        sys.exit(1)
    sys.exit(main(sys.argv[1], sys.argv[2]))
