#!/usr/bin/env python3

import re
import sys
import os
import json
from datetime import datetime, timedelta

CACHE_FILE = os.path.expanduser("~/.vttclean_cache.json")
CACHE_EXPIRY = timedelta(hours=1)

def clean_text(text):
    # Remove HTML tags
    text = re.sub(r'<[^>]+>', '', text)
    # Remove multiple spaces
    text = re.sub(r'\s+', ' ', text)
    # Remove leading/trailing whitespace
    return text.strip()

def is_prefix(a, b):
    return b.startswith(a)

def load_cache():
    if os.path.exists(CACHE_FILE):
        try:
            with open(CACHE_FILE, 'r') as f:
                cache = json.load(f)
            if datetime.fromisoformat(cache['timestamp']) + CACHE_EXPIRY > datetime.now():
                return cache['data']
        except (json.JSONDecodeError, KeyError, ValueError):
            pass
    return {}

def save_cache(data):
    cache = {
        'timestamp': datetime.now().isoformat(),
        'data': data
    }
    with open(CACHE_FILE, 'w') as f:
        json.dump(cache, f)

def invalidate_cache():
    if os.path.exists(CACHE_FILE):
        os.remove(CACHE_FILE)

def process_vtt(content, cache):
    # Remove WEBVTT header and metadata
    content = re.sub(r'^WEBVTT\n.*?\n\n', '', content, flags=re.DOTALL)

    for line in content.splitlines():
        if re.match(r'^\d\d:\d\d:\d\d.\d\d\d --> \d\d:\d\d:\d\d.\d\d\d$', line):
            continue

    # Split into captions
    captions = re.split(r'\n\n+', content)

    processed_captions = []
    buffer = []

    def flush_buffer():
        if buffer:
            processed_captions.append(buffer[-1])  # Keep the last (most complete) line
            buffer.clear()

    for caption in captions:
        lines = caption.split('\n')
        if len(lines) >= 2:
            # Extract only the start time and remove milliseconds
            timestamp_match = re.match(r'(\d{2}:\d{2}:\d{2})', lines[0])
            if timestamp_match:
                timestamp = timestamp_match.group(1)
                text = ' '.join(lines[1:])
                
                # Use cached clean caption if available
                cache_key = f"{timestamp}_{text}"
                if cache_key in cache:
                    clean_caption = cache[cache_key]
                else:
                    clean_caption = clean_text(text)
                    cache[cache_key] = clean_caption

                if clean_caption:
                    current_line = f"{timestamp} {clean_caption}"

                    if not buffer:
                        buffer.append(current_line)
                    else:
                        _, prev_text = buffer[-1].split(' ', 1)
                        if is_prefix(prev_text, clean_caption):
                            # Keep the first timestamp, use the longer text
                            buffer[-1] = current_line 
                        else:
                            flush_buffer()
                            buffer.append(current_line)

    flush_buffer()  # Don't forget to flush the buffer at the end

    return '\n'.join(processed_captions)

if __name__ == "__main__":
    try:
        cache = load_cache()

        if len(sys.argv) > 1:
            # File input
            with open(sys.argv[1], 'r', encoding='utf-8') as file:
                content = file.read()
        else:
            # Stdin input
            content = sys.stdin.read()

        result = process_vtt(content, cache)
        print(result)

        # Save the updated cache
        save_cache(cache)

    except Exception as e:
        print(f"Error processing input: {e}", file=sys.stderr)
        invalidate_cache()  # Invalidate cache on error
        sys.exit(1)
