"""Pure GraalPy dotenv subset over the supervised TrikeShed VFS."""

import os
from pathlib import Path


def _parse(text):
    values = {}
    for raw in text.splitlines():
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        if line.startswith("export "):
            line = line[7:].lstrip()
        if "=" not in line:
            continue
        key, value = line.split("=", 1)
        key = key.strip()
        value = value.strip()
        if len(value) >= 2 and value[0] == value[-1] and value[0] in "'\"":
            value = value[1:-1]
        if key:
            values[key] = value
    return values


def find_dotenv(filename=".env", raise_error_if_not_found=False, usecwd=False):
    current = Path.cwd() if usecwd else Path.cwd()
    for directory in (current, *current.parents):
        candidate = directory / filename
        if candidate.is_file():
            return str(candidate)
    if raise_error_if_not_found:
        raise IOError("dotenv file not found")
    return ""


def dotenv_values(dotenv_path=None, stream=None, **_kwargs):
    if stream is not None:
        return _parse(stream.read())
    path = Path(dotenv_path or find_dotenv())
    if not str(path) or not path.is_file():
        return {}
    return _parse(path.read_text(encoding="utf-8"))


def load_dotenv(dotenv_path=None, stream=None, override=False, **kwargs):
    values = dotenv_values(dotenv_path=dotenv_path, stream=stream, **kwargs)
    changed = False
    for key, value in values.items():
        if override or key not in os.environ:
            os.environ[key] = value
            changed = True
    return changed


def get_key(dotenv_path, key_to_get):
    return dotenv_values(dotenv_path).get(key_to_get)


def set_key(dotenv_path, key_to_set, value_to_set, **_kwargs):
    path = Path(dotenv_path)
    values = dotenv_values(path)
    values[key_to_set] = value_to_set
    path.write_text("".join(f"{key}={value}\n" for key, value in sorted(values.items())), encoding="utf-8")
    return True, key_to_set, value_to_set


def unset_key(dotenv_path, key_to_unset, **_kwargs):
    path = Path(dotenv_path)
    values = dotenv_values(path)
    existed = values.pop(key_to_unset, None) is not None
    path.write_text("".join(f"{key}={value}\n" for key, value in sorted(values.items())), encoding="utf-8")
    return existed, key_to_unset
