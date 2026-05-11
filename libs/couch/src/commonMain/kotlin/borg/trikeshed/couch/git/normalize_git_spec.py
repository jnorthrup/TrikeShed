#!/usr/bin/env python3
"""
Normalize an OpenAPI YAML spec for TrikeShed's Confix-based YAML parser.

Covers:
- Long strings → double-quoted single-line (avoids parser breaking on multiline descriptions)
- Strips top-level noise: servers, tags, security, externalDocs, components
- Strips vendor extensions (x-*) and security from operations
- Keeps only: openapi, info, paths
"""

import sys
import yaml
import re


class TrikeShedDumper(yaml.Dumper):
    def increase_indent(self, flow=False, indentless=False):
        return super().increase_indent(flow, False)


class LiteralStr(str):
    pass


def literal_str_representer(dumper, data):
    return dumper.represent_scalar('tag:yaml.org,2002:str', data, style='"')


TrikeShedDumper.add_representer(LiteralStr, literal_str_representer)


def normalize_spec(input_path: str) -> str:
    with open(input_path) as f:
        raw = f.read()

    # Patch: unquote top-level keys that have quoted values
    # TrikeShed parser breaks on quoted keys like "openapi": "3.1.0"
    lines = raw.split('\n')
    fixed = []
    for line in lines:
        m = re.match(r'^(\s*)"([^"]+)":\s*"(.*)"$', line)
        if m:
            indent, key, val = m.group(1), m.group(2), m.group(3)
            fixed.append(f'{indent}{key}: "{val}"')
        else:
            fixed.append(line)
    raw = '\n'.join(fixed)

    doc = yaml.safe_load(raw)
    if doc is None:
        raise ValueError(f"Failed to parse {input_path}")

    # Strip noise
    for key in ['servers', 'tags', 'security', 'externalDocs', 'x-trikeshed-context',
                'components', 'info.contact', 'info.license']:
        parts = key.split('.')
        d = doc
        for p in parts[:-1]:
            if p not in d:
                break
            d = d[p]
        d.pop(parts[-1], None)

    # Strip vendor extensions + security from all operations
    def clean_ops(obj):
        if isinstance(obj, dict):
            for k in list(obj.keys()):
                if k.startswith('x-') or k in ('security', 'tags'):
                    del obj[k]
            for v in obj.values():
                if isinstance(v, (dict, list)):
                    clean_ops(v)
        elif isinstance(obj, list):
            for v in obj:
                if isinstance(v, (dict, list)):
                    clean_ops(v)

    if 'paths' in doc:
        clean_ops(doc['paths'])

    # Force long strings to quoted single-line
    def force_long_strs(obj, depth=0):
        if depth > 12:
            return
        if isinstance(obj, dict):
            for k, v in list(obj.items()):
                if isinstance(v, str) and len(v) > 80:
                    obj[k] = LiteralStr(v)
                elif isinstance(v, (dict, list)):
                    force_long_strs(v, depth + 1)
        elif isinstance(obj, list):
            for i, v in enumerate(obj):
                if isinstance(v, str) and len(v) > 80:
                    obj[i] = LiteralStr(v)
                elif isinstance(v, (dict, list)):
                    force_long_strs(v, depth + 1)

    force_long_strs(doc)

    # Keep only openapi, info, paths
    ordered = {}
    for key in ['openapi', 'info', 'paths']:
        if key in doc:
            ordered[key] = doc[key]

    result = yaml.dump(ordered, Dumper=TrikeShedDumper, default_flow_style=False,
                       allow_unicode=True, sort_keys=False, width=999999)
    return result


if __name__ == '__main__':
    spec_path = sys.argv[1]
    out_path = sys.argv[2] if len(sys.argv) > 2 else spec_path
    normalized = normalize_spec(spec_path)
    with open(out_path, 'w') as f:
        f.write(normalized)
    print(f"Normalized spec written to {out_path}")