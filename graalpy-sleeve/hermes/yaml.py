"""GraalPy-safe PyYAML waist backed by TrikeShed's Confix/YAML parser."""

import json

host = globals().get("host")

class YAMLError(Exception):
    pass

class SafeLoader:
    pass

class FullLoader(SafeLoader):
    pass

class SafeDumper:
    def increase_indent(self, flow=False, indentless=False):
        return None

def _text(stream):
    return stream.read() if hasattr(stream, "read") else stream

def safe_load(stream):
    try:
        if host is None:
            raise RuntimeError("TrikeShed host delegate is unavailable")
        return json.loads(host.call("yaml_load", str(_text(stream))))
    except Exception as exc:
        raise YAMLError(str(exc)) from exc

def load(stream, Loader=None):
    return safe_load(stream)

def _scalar(value):
    if value is None:
        return "null"
    if value is True:
        return "true"
    if value is False:
        return "false"
    if isinstance(value, (int, float)):
        return str(value)
    text = str(value)
    if not text or any(ch in text for ch in ":#{}[],-&*!|>'\"%@`\n\r\t"):
        return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""
    return text

def _lines(value, indent=0):
    pad = " " * indent
    if isinstance(value, dict):
        out = []
        for key in sorted(value, key=str):
            item = value[key]
            if isinstance(item, (dict, list, tuple)):
                out.append(pad + _scalar(key) + ":")
                out.extend(_lines(item, indent + 2))
            else:
                out.append(pad + _scalar(key) + ": " + _scalar(item))
        return out
    if isinstance(value, (list, tuple)):
        out = []
        for item in value:
            if isinstance(item, (dict, list, tuple)):
                out.append(pad + "-")
                out.extend(_lines(item, indent + 2))
            else:
                out.append(pad + "- " + _scalar(item))
        return out
    return [pad + _scalar(value)]

def safe_dump(data, stream=None, sort_keys=True, **_kwargs):
    text = "\n".join(_lines(data)) + "\n"
    if stream is not None:
        stream.write(text)
        return None
    return text

def dump(data, stream=None, **kwargs):
    return safe_dump(data, stream=stream, **kwargs)

def parse(content):
    safe_load(content)
    return iter((None,))
