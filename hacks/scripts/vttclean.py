#!/usr/bin/env python3

import re
import sys

def clean_text(text):
    # Remove HTML tags
    text = re.sub(r'<[^>]+>', '', text)
    # Remove multiple spaces
    text = re.sub(r'\s+', ' ', text)
    # Remove leading/trailing whitespace
    return text.strip()

def is_prefix(a, b):
    return b.startswith(a)

def process_vtt(content):
    # Remove WEBVTT header and metadata
    content = re.sub(r'^WEBVTT\n.*?\n\n', '', content, flags=re.DOTALL)

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
                clean_caption = clean_text(text)
                if clean_caption:
                    current_line = f"{timestamp} {clean_caption}"

                    if not buffer:
                        buffer.append(current_line)
                    else:
                        _, prev_text = buffer[-1].split(' ', 1)
                        if is_prefix(prev_text, clean_caption):
                            buffer.append(current_line)
                        else:
                            flush_buffer()
                            buffer.append(current_line)

    flush_buffer()  # Don't forget to flush the buffer at the end

    return '\n'.join(processed_captions)

if __name__ == "__main__":
    try:
        if len(sys.argv) > 1:
            # File input
            with open(sys.argv[1], 'r', encoding='utf-8') as file:
                content = file.read()
        else:
            # Stdin input
            content = sys.stdin.read()

        result = process_vtt(content)
        print(result)
    except Exception as e:
        print(f"Error processing input: {e}", file=sys.stderr)
        sys.exit(1)
