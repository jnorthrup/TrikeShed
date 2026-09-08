#!/usr/bin/env python3
"""Exercise the existing application route; run reopen only after restarting its process.

This is an external HTTP verification client. It does not implement ingestion,
storage, NLP, model response generation, assertion admission or server routing.
"""

import argparse
import hashlib
import json
from pathlib import Path
import urllib.error
import urllib.request


def cid(data):
    return "sha256:" + hashlib.sha256(data).hexdigest()


def utf16_slice(text, begin, end):
    return text.encode("utf-16-le")[int(begin) * 2:int(end) * 2].decode("utf-16-le")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("phase", choices=["write", "reopen"])
    parser.add_argument("url", help="Application origin, for example http://127.0.0.1:18889")
    parser.add_argument("output", type=Path, help="Evidence directory, reused after process restart")
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    with urllib.request.urlopen(args.url.rstrip("/") + "/blackboard/sheet?key=daemon%2Fdocument-feed", timeout=15) as response:
        (args.output / f"{args.phase}-storage-sheet.json").write_bytes(response.read())

    def run(label, source):
        request = {"type": "document.curate", "inputs": {"source": source}}
        (args.output / f"{args.phase}-{label}-request.json").write_text(json.dumps(request, indent=2) + "\n")
        wire = urllib.request.Request(args.url.rstrip("/") + "/api/lcnc/run",
                                      json.dumps(request).encode(), {"Content-Type": "application/json"})
        try:
            with urllib.request.urlopen(wire, timeout=240) as response:
                status, body = response.status, response.read()
        except urllib.error.HTTPError as error:
            status, body = error.code, error.read()
        (args.output / f"{args.phase}-{label}-response.json").write_bytes(body)
        result = json.loads(body)
        assert status == 200 and result.get("ok") is True, (status, result)
        output = result["outputs"]
        record = output["record"]
        assert output["nlpStatus"] == "AVAILABLE", record["reasons"]
        assert record["nlp"]["text"] == record["source"]["text"]
        assert cid(record["source"]["text"].encode()) == record["source"]["extractedTextCid"]
        for sentence in record["nlp"]["sentences"]:
            assert sentence["tokens"] and sentence["dependencies"]
            for token in sentence["tokens"]:
                assert utf16_slice(record["source"]["text"], token["begin"], token["end"]) == token["word"]
        if record["model"] is not None:
            assert record["model"]["providerId"] != "fixture", "Fixture provider cannot establish a live-model pass"
        assert output["sheets"], "No downstream cursor sheets"
        return output

    if args.phase == "write":
        repository = Path(__file__).resolve().parent.parent
        file_bytes = (repository / "PRELOAD.md").read_bytes()
        file_text = file_bytes.decode()
        passage = "Bounded\n  channels carry work through stages and return results or failures."
        begin = file_text.index(passage)
        provenance = {"path": "PRELOAD.md", "fileCid": cid(file_bytes),
                      "begin": len(file_text[:begin].encode("utf-16-le")) // 2,
                      "end": len(file_text[:begin + len(passage)].encode("utf-16-le")) // 2}
        real = run("source", {"text": passage, "name": "PRELOAD.md:identified-passage", "mediaType": "text/plain"})
        control = run("control", {"text": "Acme pays Beta.", "name": "fixture:positive", "mediaType": "text/plain"})
        assert real["record"]["source"]["originalCid"] == cid(passage.encode())
        state = {"source": provenance, "real": real, "control": control}
        (args.output / "write-state.json").write_text(json.dumps(state, indent=2) + "\n")
        print(json.dumps({"phase": "write", "sourceCid": real["record"]["source"]["originalCid"],
                          "model": real["record"]["model"],
                          "modelFailureReasons": real["record"]["reasons"] if real["record"]["model"] is None else [],
                          "controlSubmitted": len(control["record"]["submitted"])}))
    else:
        state = json.loads((args.output / "write-state.json").read_text())
        restored = {}
        for label in ["real", "control"]:
            original = state[label]["record"]
            source = original["source"]
            output = run(label, {"originalCid": source["originalCid"], "name": source["name"], "mediaType": "text/plain"})
            record = output["record"]
            assert record["source"]["originalCid"] == source["originalCid"]
            assert record["source"]["extractedTextCid"] == source["extractedTextCid"]
            assert record["nlp"] == original["nlp"]
            # A real model may propose no supported assertion; report it rather than inventing one.
            if original["submitted"]:
                assert not record["submitted"] and record["duplicates"], record
            restored[label] = output
        (args.output / "reopen-state.json").write_text(json.dumps(restored, indent=2) + "\n")
        print(json.dumps({"phase": "reopen", "casRecovered": True, "nlpStable": True,
                          "controlHadSubmission": bool(state["control"]["record"]["submitted"]),
                          "controlDuplicates": len(restored["control"]["record"]["duplicates"])}))


if __name__ == "__main__":
    main()
