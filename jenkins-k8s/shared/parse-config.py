#!/usr/bin/env python3
"""
parse-config.py — Parse a simple YAML config file and print JSON.
Zero external dependencies (no pyyaml, no yq).
Handles nested key: value YAML with string values only.

Usage:
    python3 parse-config.py <path-to-config.yaml>
    curl <url-to-config.yaml> | python3 parse-config.py /dev/stdin
"""
import sys
import json


def parse_yaml(path: str) -> dict:
    result = {}
    stack = [(result, -1)]
    try:
        with open(path, encoding='utf-8', errors='replace') as fh:
            lines = fh.readlines()
    except Exception as exc:
        print(f"# parse-config: cannot read {path}: {exc}", file=sys.stderr)
        return {}

    for raw_line in lines:
        raw = raw_line.rstrip()
        stripped = raw.lstrip()

        if not stripped or stripped.startswith('#'):
            continue

        indent = len(raw) - len(stripped)

        if ':' not in stripped:
            continue

        key, _, val = stripped.partition(':')
        key = key.strip()
        val = val.strip().strip('"').strip("'")
        # Strip inline YAML comments (e.g.  value  # comment → value)
        if val and not val.startswith('"') and not val.startswith("'"):
            val = val.split('  #')[0].split('\t#')[0].rstrip()

        # Pop stack back to the right nesting level
        while len(stack) > 1 and stack[-1][1] >= indent:
            stack.pop()

        parent = stack[-1][0]

        if val:
            parent[key] = val
        else:
            child: dict = {}
            parent[key] = child
            stack.append((child, indent))

    return result


if __name__ == '__main__':
    path = sys.argv[1] if len(sys.argv) > 1 else '/dev/stdin'
    cfg = parse_yaml(path)
    print(json.dumps(cfg, indent=None))
