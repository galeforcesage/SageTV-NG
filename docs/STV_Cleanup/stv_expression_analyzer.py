#!/usr/bin/env python3
"""
Expression Overhead Analysis for SageTV7

Purpose: Determine if expression evaluation (GetProperty, GetCurrentMediaFile, etc.)
is a significant bottleneck vs. the refresh/rendering overhead we've optimized so far.

Methodology:
1. Scan all expressions in the STV
2. Identify expensive patterns (GetCurrentMediaFile, GetProperty with complex args)
3. Count duplicates (same expression appearing multiple times across screens)
4. Estimate cost: duplication count * expression complexity * frequency

Output: JSON report with top expensive expressions and recommendations.
"""

import xml.etree.ElementTree as ET
import re
import json
from pathlib import Path
from collections import defaultdict


def load_stv(stv_path):
    tree = ET.parse(stv_path)
    return tree, tree.getroot()


def extract_expressions(text):
    """
    Extract all expressions from an action/conditional name.
    Examples: GetProperty("foo"), GetCurrentMediaFile(), SetLocal("x", value), etc.
    
    Returns list of unique expression patterns found.
    """
    if not text:
        return []
    
    # Find all function calls: name(...)
    pattern = r'([A-Za-z_][A-Za-z0-9_]*)\s*\([^)]*\)'
    matches = re.findall(pattern, text)
    return matches


def classify_expression_cost(expr_name, full_text):
    """
    Classify expression by cost:
    HIGH: GetCurrentMediaFile, GetProperty(server/*), database queries
    MEDIUM: GetProperty(ui/*), GetLocal, math operations
    LOW: constants, simple assignments, system info
    """
    
    if 'GetCurrentMediaFile' in expr_name:
        return 'HIGH'
    elif 'GetProperty' in expr_name and 'server' in full_text.lower():
        return 'HIGH'
    elif 'GetServerProperty' in expr_name:
        return 'HIGH'
    elif 'GetFavorites' in expr_name:
        return 'HIGH'
    elif 'GetProperty' in expr_name or 'GetLocal' in expr_name:
        return 'MEDIUM'
    elif any(op in expr_name for op in ['PlayMedia', 'RecordMedia', 'AddToPlaylist', 'Search']):
        return 'MEDIUM'
    else:
        return 'LOW'


def profile_stv_expressions(stv_path):
    """
    Scan STV and profile all expressions.
    """
    tree, root = load_stv(stv_path)
    
    expression_counts = defaultdict(int)
    expression_costs = {}
    expression_locations = defaultdict(list)
    
    menu_count = 0
    action_count = 0
    conditional_count = 0
    
    # Iterate through all elements and extract expressions
    for elem in root.iter():
        tag = elem.tag.split('}')[-1]  # Remove namespace
        
        if tag == 'Menu':
            menu_count += 1
        elif tag == 'Action':
            action_count += 1
        elif tag == 'Conditional':
            conditional_count += 1
        
        # Get expression text from Name attribute
        name = elem.get('Name', '')
        if not name:
            continue
        
        # Extract expressions
        exprs = extract_expressions(name)
        
        for expr in exprs:
            key = expr.lower()
            expression_counts[key] += 1
            
            if key not in expression_costs:
                expression_costs[key] = classify_expression_cost(expr, name)
            
            # Track location (menu/action/conditional)
            menu_name = 'unknown'
            for parent in root.iter():
                if elem in list(parent.iter()):
                    if parent.tag.endswith('Menu'):
                        menu_name = parent.get('Name', 'unknown')
                        break
            
            expression_locations[key].append({
                'tag': tag,
                'menu': menu_name,
                'text': name[:80]  # Truncate for readability
            })
    
    return {
        'menu_count': menu_count,
        'action_count': action_count,
        'conditional_count': conditional_count,
        'expression_counts': expression_counts,
        'expression_costs': expression_costs,
        'expression_locations': expression_locations
    }


def analyze_expression_overhead(profile_data):
    """
    Analyze the profile and determine if expression overhead is a bottleneck.
    """
    
    # Calculate total cost
    high_cost_count = sum(count for expr, count in profile_data['expression_counts'].items()
                         if profile_data['expression_costs'].get(expr.lower()) == 'HIGH')
    medium_cost_count = sum(count for expr, count in profile_data['expression_counts'].items()
                           if profile_data['expression_costs'].get(expr.lower()) == 'MEDIUM')
    low_cost_count = sum(count for expr, count in profile_data['expression_counts'].items()
                        if profile_data['expression_costs'].get(expr.lower()) == 'LOW')
    
    total_expressions = high_cost_count + medium_cost_count + low_cost_count
    
    # Find top duplicates
    top_expressions = sorted(profile_data['expression_counts'].items(), 
                            key=lambda x: x[1], reverse=True)[:20]
    
    # Find expressions appearing in many places (candidates for caching)
    hotspot_expressions = [
        (expr, count, profile_data['expression_costs'].get(expr.lower()))
        for expr, count in top_expressions
        if count >= 5 and profile_data['expression_costs'].get(expr.lower()) in ['HIGH', 'MEDIUM']
    ]
    
    return {
        'total_expressions': total_expressions,
        'high_cost_expressions': high_cost_count,
        'medium_cost_expressions': medium_cost_count,
        'low_cost_expressions': low_cost_count,
        'top_expressions': top_expressions,
        'hotspot_expressions': hotspot_expressions,
        'high_cost_ratio': high_cost_count / total_expressions if total_expressions > 0 else 0,
    }


def generate_report(analysis, output_path):
    """Generate JSON report."""
    
    report = {
        'analysis': {
            'total_expressions': analysis['total_expressions'],
            'high_cost_ratio': f"{analysis['high_cost_ratio']:.1%}",
            'high_cost_count': analysis['high_cost_count'],
            'medium_cost_count': analysis['medium_cost_expressions'],
            'assessment': generate_assessment(analysis),
        },
        'top_20_expressions': [
            {'expr': expr, 'count': count}
            for expr, count in analysis['top_expressions']
        ],
        'high_value_caching_targets': [
            {'expr': expr, 'count': count, 'cost': cost, 'reason': f'appears {count}x, HIGH cost'}
            for expr, count, cost in analysis['hotspot_expressions']
        ]
    }
    
    with open(output_path, 'w') as f:
        json.dump(report, f, indent=2)


def generate_assessment(analysis):
    """Determine if expression overhead is significant."""
    
    high_ratio = analysis['high_cost_ratio']
    
    if high_ratio > 0.40:
        return (
            "CRITICAL: >40% of expressions are high-cost (GetProperty/GetCurrentMediaFile). "
            "Expression overhead is likely the PRIMARY bottleneck. "
            "Phase 2 caching helped, but more aggressive expression deduplication needed."
        )
    elif high_ratio > 0.25:
        return (
            "SIGNIFICANT: 25-40% high-cost expressions. Expression evaluation is a notable bottleneck. "
            "Phase 2 caching (SetLocal/GetLocal) helps but may not be sufficient. "
            "Consider wrapping expensive expressions in SetLocal at BeforeMenuLoad."
        )
    elif high_ratio > 0.15:
        return (
            "MODERATE: 15-25% high-cost expressions. Expression overhead is present but "
            "refresh deduplication (Phase 3) should provide visible improvement. "
            "Further optimization may show diminishing returns."
        )
    else:
        return (
            "LOW: <15% high-cost expressions. Expression overhead is minimal. "
            "Phase 3 refresh deduplication should provide most remaining gains. "
            "Focus on other bottlenecks (rendering, network latency)."
        )


if __name__ == '__main__':
    import sys
    
    if len(sys.argv) < 3:
        print("Usage: stv_expression_analyzer.py <input_stv> <output_report>")
        sys.exit(1)
    
    stv_file = sys.argv[1]
    output_file = sys.argv[2]
    
    print(f"Scanning {stv_file} for expression overhead...")
    profile = profile_stv_expressions(stv_file)
    
    print(f"  Menus: {profile['menu_count']}")
    print(f"  Actions: {profile['action_count']}")
    print(f"  Conditionals: {profile['conditional_count']}")
    print(f"  Unique expressions: {len(profile['expression_counts'])}")
    
    analysis = analyze_expression_overhead(profile)
    
    print(f"\nExpression Cost Distribution:")
    print(f"  HIGH: {analysis['high_cost_expressions']} ({analysis['high_cost_ratio']:.1%})")
    print(f"  MEDIUM: {analysis['medium_cost_expressions']}")
    print(f"  LOW: {analysis['low_cost_expressions']}")
    print(f"\nAssessment: {analysis.get('assessment', 'N/A')}")
    
    generate_report(analysis, output_file)
    print(f"\nReport written to {output_file}")
