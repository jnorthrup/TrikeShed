#!/usr/bin/env python3
"""Verify and safely extract recorded evidence, then run the unchanged data-only auditor. No JVM."""
import argparse, hashlib, json, pathlib, subprocess, sys, tarfile

def sha(path):
    h = hashlib.sha256()
    with pathlib.Path(path).open('rb') as stream:
        for b in iter(lambda: stream.read(1024 * 1024), b''):
            h.update(b)
    return h.hexdigest()

def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--extract', type=pathlib.Path, required=True, help='Fresh nonexistent extraction directory')
    args = p.parse_args()
    here = pathlib.Path(__file__).resolve().parent
    outer = json.loads((here / 'SHA256.json').read_text())
    for name, expected in outer.items():
        member = pathlib.PurePosixPath(name)
        assert not member.is_absolute() and '..' not in member.parts
        assert sha(here / name) == expected, name
    archive = here / 'records.tar.gz'
    manifest = json.loads((here / 'records-manifest.json').read_text())
    dest = args.extract.resolve(); dest.mkdir(exist_ok=False)
    total = 0
    with tarfile.open(archive, 'r:gz') as tar:
        seen = set()
        for member in tar:
            name = pathlib.PurePosixPath(member.name)
            assert member.isfile() and not name.is_absolute() and '..' not in name.parts
            assert member.name in manifest and member.name not in seen
            assert len(member.name) <= 4096 and member.size <= 8 * 1024 * 1024
            total += member.size
            assert total <= 20 * 1024 * 1024 and len(seen) < 4096
            expected = manifest[member.name]
            assert member.size == expected['bytes']
            target = dest.joinpath(*name.parts)
            assert target.is_relative_to(dest)
            target.parent.mkdir(parents=True, exist_ok=True)
            source = tar.extractfile(member); assert source is not None
            with target.open('xb') as output:
                while b := source.read(1024 * 1024):
                    output.write(b)
            assert sha(target) == expected['sha256'], member.name
            seen.add(member.name)
        assert seen == set(manifest)
    proof = dest / 'proof'; output = dest / 'replayed-audit'
    argv = [sys.executable, str(here / 'recorded-audit.py.txt'), str(proof), str(proof / 'native-attempt1'),
            '--recorded-only', '--source-head', 'c23dd240190d8023437ba4121485b1c28ea42b89',
            '--main-sha', 'b43294535ababc88dc53db415f8cb54639d03bfda2f52c209e1350f910685448', '--out', str(output)]
    subprocess.run(argv, check=True)
    assert (output / 'result.json').read_bytes() == (here / 'recorded-audit-result.json').read_bytes()
    print(json.dumps({'result': 'PASS_EXACT_RECORDED_DATA_REPLAY', 'archiveSha256': sha(archive),
                      'resultSha256': sha(output / 'result.json'), 'extracted': str(dest), 'files': len(seen),
                      'scope': 'No JVM, parser, production JAR, live REF target or new inference execution'}))

if __name__ == '__main__':
    main()
