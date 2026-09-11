"""Independently verify public record bytes emitted by the TrikeShed/Go probes."""
import argparse
import base64
import datetime
import hashlib
import json
from pathlib import Path
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PublicKey


def varint(data, offset=0):
    value = 0
    for shift in range(0, 70, 7):
        byte = data[offset]
        offset += 1
        value |= (byte & 127) << shift
        if byte < 128:
            return value, offset
    raise ValueError("varint overflow")


def protobuf(data):
    fields, at = {}, 0
    while at < len(data):
        tag, at = varint(data, at)
        number, wire = tag >> 3, tag & 7
        assert number and number not in fields
        if wire == 0:
            value, at = varint(data, at)
        elif wire == 2:
            size, at = varint(data, at)
            value = data[at:at + size]
            assert len(value) == size
            at += size
        else:
            raise ValueError(f"unsupported wire type {wire}")
        fields[number] = value
    return fields


def cbor(data, at=0):
    first = data[at]
    at += 1
    major, value = first >> 5, first & 31
    if value >= 24:
        width = {24: 1, 25: 2, 26: 4, 27: 8}[value]
        value = int.from_bytes(data[at:at + width], "big")
        at += width
    if major == 0:
        return value, at
    if major in (2, 3):
        result = data[at:at + value]
        assert len(result) == value
        return (result.decode() if major == 3 else result), at + value
    if major == 5:
        result = {}
        for _ in range(value):
            key, at = cbor(data, at)
            item, at = cbor(data, at)
            assert key not in result
            result[key] = item
        return result, at
    raise ValueError(f"unsupported CBOR major type {major}")


def name_bytes(name):
    assert name[0] == "k", "probe should print canonical base36 libp2p-key CID"
    integer = int(name[1:], 36)
    raw = integer.to_bytes((integer.bit_length() + 7) // 8, "big")
    version, at = varint(raw)
    codec, at = varint(raw, at)
    assert (version, codec) == (1, 0x72)
    return raw[at:]


def verify(record):
    wire = base64.b64decode(record["wireBase64"], validate=True)
    assert len(wire) <= 10240
    fields = protobuf(wire)
    multihash = name_bytes(record["name"])
    code, at = varint(multihash)
    width, at = varint(multihash, at)
    assert code == 0 and width == len(multihash) - at
    key = protobuf(multihash[at:])
    assert key[1] == 1 and len(key[2]) == 32
    Ed25519PublicKey.from_public_bytes(key[2]).verify(fields[8], b"ipns-signature:" + fields[9])
    signed, end = cbor(fields[9])
    assert end == len(fields[9]) and signed["ValidityType"] == 0
    assert signed["Value"].decode() == record["value"]
    assert signed["Sequence"] == int(record["sequence"])
    eol = datetime.datetime.fromisoformat(signed["Validity"].decode().replace("Z", "+00:00"))
    assert eol == datetime.datetime.fromisoformat(record["validUntil"].replace("Z", "+00:00"))
    assert eol > datetime.datetime.now(datetime.timezone.utc)
    routing = b"/ipns/" + multihash
    return {"name": record["name"], "sequence": signed["Sequence"], "value": record["value"],
            "validUntil": signed["Validity"].decode(), "ttlNanos": signed["TTL"],
            "wireSha256": hashlib.sha256(wire).hexdigest(), "wireBytes": len(wire),
            "publicKeyHex": key[2].hex(), "dhtKeyHex": routing.hex(),
            "routingTargetSha256": hashlib.sha256(routing).hexdigest(),
            "signatureValid": True, "unexpiredAtVerification": True}


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("logs", nargs="+")
    parser.add_argument("--payload", nargs=2, action="append", default=[], metavar=("MANIFEST", "PAYLOAD"))
    args = parser.parse_args()
    results = []
    for filename in args.logs:
        for line in Path(filename).read_text().splitlines():
            if not line.startswith("{"):
                continue
            value = json.loads(line)
            if "wireBase64" in value:
                results.append({"source": filename, "sourceOperation": value.get("operation", "independent-get"),
                                "sourceComplete": value.get("complete"), **verify(value)})
    assert results, "no signed records found"
    payloads = []
    for manifest_path, payload_path in args.payload:
        manifest = json.loads(Path(manifest_path).read_text())
        payload = Path(payload_path).read_bytes()
        digest = hashlib.sha256(payload).digest()
        cid = "b" + base64.b32encode(bytes([1, 0x55, 0x12, 32]) + digest).decode().lower().rstrip("=")
        assert manifest["sha256"] == digest.hex()
        assert manifest["cid"] == cid and manifest["value"] == "/ipfs/" + cid
        assert any(record["value"] == manifest["value"] for record in results)
        payloads.append({"manifest": manifest_path, "source": payload_path, "bytes": len(payload),
                         "sha256": digest.hex(), "cid": cid, "matchesSignedValue": True})
    print(json.dumps({"verifiedAt": datetime.datetime.now(datetime.timezone.utc).isoformat(),
                      "verifier": "Python cryptography Ed25519, independently decoded name/protobuf/CBOR",
                      "records": results, "payloads": payloads}, indent=2))
