#!/usr/bin/env python3
"""Audit byte-free IPNS uring traces; emit public-safe JSON without changing inputs.

Usage: python3 audit_ipns_trace.py TRACE.jsonl [TRACE.jsonl.gz ...]
The sha256 and sizeBytes fields describe the exact input file, including gzip
compression when present. Metadata only is inspected; no key journals are read.
"""
import argparse
import collections
import datetime
import gzip
import hashlib
import json
import pathlib


def audit(path):
    digest = hashlib.sha256()
    with path.open('rb') as source:
        for block in iter(lambda: source.read(1024 * 1024), b''):
            digest.update(block)
    kinds = collections.Counter()
    ops = collections.Counter()
    results = collections.defaultdict(collections.Counter)
    channels = {}
    pending = {}
    seen = set()
    backend_failures = []
    fdops = collections.defaultdict(collections.Counter)
    closed = collections.Counter()
    bytes_done = collections.Counter()
    times = []
    mismatch = []
    unknown = []
    duplicates = []
    bad_order = []
    fatal_channels = collections.defaultdict(set)
    summaries = []
    connect_success = set()
    submitted_connect = set()
    channel_close = collections.defaultdict(list)
    partial = collections.Counter()
    line_number = 0
    opener = gzip.open if path.suffix == '.gz' else open
    with opener(path, 'rt', encoding='utf-8') as source:
        for line_number, line in enumerate(source, 1):
            event = json.loads(line)
            kind = event['event']
            kinds[kind] += 1
            if kind == 'channel':
                channels[event['channel']] = event
            if kind == 'summary':
                summaries.append(event)
            if kind == 'backend_failure':
                backend_failures.append(event)
            if kind not in ('submit', 'complete'):
                continue
            key = (event['channel'], event['request'])
            operation = event['opcode']
            descriptor = event['fd']
            times.append(event['timeNanos'])
            if kind == 'submit':
                if key in seen:
                    duplicates.append(key)
                seen.add(key)
                pending[key] = event
                ops[operation] += 1
                fdops[descriptor][operation] += 1
                if operation == 'CONNECT':
                    submitted_connect.add(event['channel'])
                if closed[descriptor]:
                    bad_order.append((line_number, key, operation, descriptor))
            else:
                submitted = pending.pop(key, None)
                if submitted is None:
                    unknown.append((line_number, key))
                else:
                    for field in ('opcode', 'fd', 'bytes', 'offset'):
                        if submitted[field] != event[field]:
                            mismatch.append((line_number, key, field))
                    if event['timeNanos'] < submitted['timeNanos']:
                        mismatch.append((line_number, key, 'negative latency'))
                result = event['result']
                results[operation][result] += 1
                if operation == 'CLOSE' and result == 0:
                    closed[descriptor] += 1
                    channel_close[event['channel']].append(descriptor)
                if operation == 'CONNECT' and result == 0:
                    connect_success.add(event['channel'])
                if operation in ('READ', 'WRITE') and result > 0:
                    bytes_done[operation] += result
                    if result < event['bytes']:
                        partial[operation] += 1
                if result < 0 and result not in (-11, -115):
                    fatal_channels[(operation, result)].add(event['channel'])
    provenance = [
        dict(availability=availability, nativeCapabilities=native, socketBackend=socket)
        for availability, native, socket in sorted({
            (event['availability'], event['nativeCapabilities'], event.get('socketBackend', 'unspecified'))
            for event in channels.values()
        })
    ]
    without_success = sorted(submitted_connect - connect_success)
    with_error = set().union(*(
        value for (operation, _), value in fatal_channels.items() if operation == 'CONNECT'
    ))
    return {
        'source': str(path),
        'sha256': digest.hexdigest(),
        'sizeBytes': path.stat().st_size,
        'lineCount': line_number,
        'eventCounts': dict(kinds),
        'channels': len(channels),
        'transportProvenance': provenance,
        'submissionCountsByOperation': dict(ops),
        'completionOutcomesByOperation': {
            operation: {
                'positive': sum(count for result, count in counts.items() if result > 0),
                'zero': counts[0],
                'negative': {str(result): count for result, count in sorted(counts.items()) if result < 0},
            } for operation, counts in results.items()
        },
        'completedBytes': dict(bytes_done),
        'partialPositiveTransfers': dict(partial),
        'elapsedSeconds': (max(times) - min(times)) / 1e9 if times else 0,
        'pairing': {
            'unpairedComputed': len(pending),
            'duplicateRequestIdentities': duplicates,
            'unknownCompletions': unknown,
            'metadataMismatches': mismatch,
        },
        'descriptorCleanup': {
            'distinctObservedFds': len(fdops),
            'fdsWithSuccessfulClose': len(closed),
            'unclosedFds': [fd for fd in fdops if fd not in closed],
            'multiplyClosedFds': [fd for fd, count in closed.items() if count != 1],
            'operationsAfterSuccessfulClose': bad_order,
            'channelsWithoutSuccessfulClose': sorted(set(channels) - set(channel_close)),
        },
        'connectionOutcomes': {
            'successfulChannels': len(connect_success),
            'channelsWithoutSuccessfulConnect': without_success,
            'channelsWithoutSuccessfulConnectOrDefinitiveConnectError': sorted(set(without_success) - with_error),
        },
        'nonRetryErrors': [
            {'operation': operation, 'result': result, 'channels': sorted(value)}
            for (operation, result), value in sorted(fatal_channels.items())
        ],
        'backendFailureEvents': backend_failures,
        'reportedSummary': summaries,
        'observations': [
            'Correlation identity is (channel, request). Every observed submission has one matching completion if pairing invariants above are empty.',
            'All advertised channel backends explicitly identify JVM descriptor emulation through commonMain uring; nativeCapabilities=0 is not Linux kernel io_uring evidence.',
            '-11 is retryable EAGAIN, -115 is pending CONNECT, and -5 preserves generic I/O failure without its underlying socket cause.',
            'A channel closed without successful CONNECT or definitive CONNECT error may have timed out or been cancelled; the byte-free trace does not distinguish them.',
            'Observed successful CLOSE balance does not establish descriptor ownership outside the trace.',
            'These traces omit payload bytes and remote peer identities. Protocol reports separately establish public peers, IPNS signatures, acknowledgements and sequence selection.',
        ],
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('traces', nargs='+', type=pathlib.Path)
    args = parser.parse_args()
    result = {
        'audit': 'Read-only public IPNS userspace trace audit',
        'createdAtUtc': datetime.datetime.now(datetime.timezone.utc).isoformat(),
        'scope': 'Explicitly named trace files only; no private journals, key material or payload bytes accessed. No worktree changes or builds.',
        'traces': [audit(path) for path in args.traces],
    }
    print(json.dumps(result, indent=2))


if __name__ == '__main__':
    main()
