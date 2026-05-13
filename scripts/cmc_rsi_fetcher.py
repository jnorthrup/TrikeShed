#!/usr/bin/env python3
"""
Fetch CoinMarketCap RSI chart page using curl, extract the embedded data,
and identify channeling pairs (crypto that oscillates in RSI range for mean-reversion trading).
"""
import json
import re
import subprocess
import sys

def fetch_cmc_rsi():
    """Fetch the CMC RSI page using curl"""
    result = subprocess.run(
        ["curl", "-sS", "-o", "/tmp/cmc_rsi.html", "-w", "%{http_code}",
         "https://coinmarketcap.com/charts/rsi/"],
        capture_output=True, text=True
    )
    print(f"HTTP Status: {result.stdout}")
    if result.returncode != 0:
        print(f"Curl error: {result.stderr}")
        sys.exit(1)
    
    with open("/tmp/cmc_rsi.html", "r") as f:
        return f.read()

def extract_data(html):
    """Extract the JSON data structure from the page"""
    # Scripts containing dehydrated state
    script_pattern = r'<script[^>]*>(.*?)</script>'
    scripts = re.findall(script_pattern, html, re.DOTALL)
    
    for script in scripts:
        if 'dehydratedState' in script or 'quotes' in script:
            try:
                data = json.loads(script)
                return data
            except json.JSONDecodeError:
                continue
    
    # Fallback: look for JSON in __NEXT_DATA__ or similar
    next_data = re.search(r'<script[^>]*id="__NEXT_DATA__"[^>]*>(.*?)</script>', html, re.DOTALL)
    if next_data:
        try:
            return json.loads(next_data.group(1))
        except json.JSONDecodeError:
            pass
    
    return None

def print_data_structure(data, indent=0, max_depth=3, path=""):
    """Print the structure of the JSON data"""
    if max_depth <= 0:
        return
    
    if isinstance(data, dict):
        for key, value in list(data.items())[:20]:  # Limit to first 20 keys
            print(f"{'  ' * indent}{path}.{key}: {type(value).__name__}")
            if isinstance(value, (dict, list)):
                print_data_structure(value, indent + 1, max_depth - 1, f"{path}.{key}")
    elif isinstance(data, list) and len(data) > 0:
        print(f"{'  ' * indent}{path}: list[{len(data)}] -> {type(data[0]).__name__}")
        if isinstance(data[0], dict):
            print_data_structure(data[0], indent + 1, max_depth - 1, f"{path}[0]")

if __name__ == "__main__":
    html = fetch_cmc_rsi()
    print(f"Fetched {len(html)} bytes")
    
    data = extract_data(html)
    if data:
        print("\n=== Data Structure (first 3 levels) ===")
        print_data_structure(data, max_depth=3)
    else:
        print("\nNo JSON data found in page")
