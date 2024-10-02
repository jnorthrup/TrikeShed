#!/usr/bin/python3
 
import re
import datetime
import glob
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
         lines = caption.split('\n')1
        if len(lines) >= 2:
            # Extract only the start time and remove milliseconds
            timestamp_match = re.match(r'(\d{2}:\d{2}:\d{2})\.(\d{3})', lines[0])
            if timestamp_match:
                                                       timestamp = f"{timestamp_match.group(1)}.{timestamp_match.group(2)}"
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
        if len(sys.argv) < 2:
            print("Usage: python vttclean.py <file_pattern>", file=sys.stderr)
            sys.exit(1)

        file_pattern = sys.argv[1]
        for filename in glob.glob(file_pattern):
            with open(filename, 'r', encoding='utf-8') as file:
                content = file.read()

        result = process_vtt(content)
        print(result)
    except Exception as e:
        print(f"Error processing input: {e}", file=sys.stderr)
        sys.exit(1)
