import re

script_content = open('src/commonMain/resources/web/script.js').read()

start_idx = script_content.find("function mutate(updater)")
if start_idx == -1:
    print("Could not find function mutate(updater)")
else:
    end_idx = script_content.find("// ── Element refs", start_idx)
    mutate = script_content[start_idx:end_idx]
    print("Found mutate!")
