const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');

const modulePath = process.env.TRIKESHED_URING_MODULE;
assert.ok(modulePath && path.isAbsolute(modulePath), 'Set TRIKESHED_URING_MODULE to the built addon absolute path');
const uring = require(modulePath);

test('repository Node ABI uses a probed native ring and preserves file bytes', (t) => {
    assert.equal(uring.abiVersion(), 1);
    assert.equal(uring.napiVersion(), 8);
    assert.equal(uring.platform(), 'linux');
    assert.equal(uring.architecture(), process.arch === 'arm64' ? 'arm64' : 'x86_64');
    const ring = uring.open(8);
    assert.equal(typeof ring, 'bigint');
    assert.ok(ring > 0n, `io_uring setup failed: ${ring}`);
    const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'trikeshed-node-uring-'));
    const filename = path.join(directory, 'payload');
    let descriptor = -1;
    let admitted = 0;
    let settled = 0;
    const identities = [0n, 9007199254740993n, 9223372036854775807n, -9223372036854775808n, -1n];
    const complete = (op, fd, bytes = null, start = 0, length = 0, offset = 0n) => {
        const identity = identities[admitted % identities.length];
        admitted++;
        // ABI1 returns res; the addon compares the actual terminal CQE userData
        // against this exact signed-64-bit BigInt before releasing its borrow.
        const result = uring.execute(ring, op, fd, bytes, start, length, offset, identity);
        assert.equal(typeof result, 'number');
        assert.ok(Number.isInteger(result));
        settled++;
        return result;
    };
    t.after(() => {
        try {
            if (descriptor >= 0) assert.equal(complete(19, descriptor), 0);
        } finally {
            assert.equal(uring.close(ring), 0);
            assert.equal(admitted, settled);
            fs.rmSync(directory, { recursive: true });
        }
    });
    for (const opcode of [0, 18, 22, 23, 3, 19]) {
        assert.equal(uring.supports(ring, opcode), true, `Required native opcode ${opcode} is absent`);
    }
    assert.equal(complete(0, -1), 0);
    const encodedPath = new TextEncoder().encode(filename);
    descriptor = complete(18, -100, encodedPath, 0, encodedPath.length, 194n); // RDWR|CREAT|EXCL
    assert.ok(descriptor >= 0, `OPENAT failed: ${descriptor}`);
    assert.equal(complete(18, -100, encodedPath, 0, encodedPath.length, 194n), -17);
    const bytes = new Int8Array([99, 11, 22, 33, 88]);
    assert.equal(complete(23, descriptor, bytes, 1, 3), 3);
    assert.equal(complete(3, descriptor), 0);
    const received = new Int8Array([7, 7, 7, 7, 7]);
    assert.equal(complete(22, descriptor, received, 1, 3), 3);
    assert.deepEqual(Array.from(received), [7, 11, 22, 33, 7]);
    assert.equal(complete(22, descriptor, received, 0, 1, 3n), 0);
    assert.equal(complete(55, descriptor, null, 0, 0, 2n), 0);
    assert.equal(fs.statSync(filename).size, 2);
    assert.equal(complete(19, descriptor), 0);
    descriptor = -1;
    console.log(JSON.stringify({ node: process.version, architecture: process.arch,
        module: modulePath, admitted, settled, ftruncateNative: uring.supports(ring, 55) }));
});

test('Node ABI rejects invalid identities and buffer ranges without corrupting the ring', () => {
    const ring = uring.open(2);
    assert.ok(ring > 0n, `io_uring setup failed: ${ring}`);
    try {
        assert.equal(uring.execute(ring, 0, -1, null, 0, 0, 0n, 9007199254740993), -22);
        assert.equal(uring.execute(ring, 0, -1, null, 0, 0, 0n, 9223372036854775808n), -22);
        assert.equal(uring.execute(ring, 0.5, -1, null, 0, 0, 0n, 0n), -22);
        assert.equal(uring.execute(ring, 22, -1, new Int8Array(4), 3, 2, 0n, 0n), -22);
        assert.equal(uring.execute(ring, 22, -1, new Int8Array(4), -1, 2, 0n, 0n), -22);
        assert.equal(uring.execute(ring, 22, -1, new Int16Array(4), 0, 2, 0n, 0n), -22);
        assert.equal(uring.execute(ring, 0, -1, null, 0, 0, 0n, 9007199254740993n), 0);
    } finally {
        assert.equal(uring.close(ring), 0);
    }
    assert.equal(uring.execute(ring, 0, -1, null, 0, 0, 0n, 0n), -9);
    assert.equal(uring.close(ring), -9);
    assert.equal(uring.supports(ring, 0), false);
});
