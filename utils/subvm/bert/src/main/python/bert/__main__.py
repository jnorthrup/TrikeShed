"""Isolated source-file entry; no inherited Python module path is required."""

import importlib.util
from pathlib import Path
import sys


def main():
    source = Path(__file__).with_name('encoder.py')
    spec = importlib.util.spec_from_file_location('bert.encoder', source)
    encoder = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(encoder)
    encoder.main(sys.argv[1:], sys.stdin, sys.stdout)


if __name__ == '__main__':
    main()
