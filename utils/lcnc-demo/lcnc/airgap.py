"""Egress control for a run: kernel denial for the process tree, probes, and the per-process ledger.

Profiles, after docs/lcnc/airgap-demonstrator/03-source-intake-and-airgap.md:

  egress-blocked      The run re-executes itself under a kernel sandbox that denies every network operation
                      to this process and all its descendants. Probes confirm that TCP, UDP, name resolution
                      and bind are refused before any engine starts; a run whose probes do not all come back
                      denied starts no engine. Evidence kept: the probes, the process inventory, the policy.
  application-local   No kernel control. Engines still run behind the in-process ledger, which refuses and
                      records Python-level socket use. The host may have network; this is not an airgap.

Neither is the physically disconnected profile: the machine keeps its interfaces, and other processes on it
are not constrained by this run.
"""
from __future__ import annotations

import errno
import hashlib
import importlib.util
import json
import os
import platform
import socket
import subprocess
import sys
import time
from pathlib import Path

GUARD = Path(__file__).resolve().parents[1] / "airgap" / "egress_guard.py"
SANDBOX_EXEC = "/usr/bin/sandbox-exec"
PROFILE = "(version 1)\n(allow default)\n(deny network*)\n"
MARK = "TRIKESHED_AIRGAP_POLICY"
LOG_ENV = "TRIKESHED_EGRESS_LOG"
OFFLINE_ENV = {"HF_HUB_OFFLINE": "1", "TRANSFORMERS_OFFLINE": "1", "HF_HUB_DISABLE_TELEMETRY": "1"}
PROFILES = ("egress-blocked", "application-local")
DENIED = ("EPERM", "EACCES")


class AirgapError(RuntimeError):
    pass


def _sha(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def policy(profile: str) -> dict:
    """The control in force, as data: what denies, to whom, and the exact rule text."""
    if profile == "application-local":
        return {"profile": profile, "kernelControl": None,
                "inProcess": "egress_guard.py refuses and records Python-level socket use in each engine process",
                "claim": "model and engine egress refused at the Python socket layer; the host may have network"}
    launcher = Path(SANDBOX_EXEC)
    return {"profile": profile, "kernelControl": "macOS sandbox (seatbelt), applied with sandbox-exec",
            "rule": PROFILE, "ruleSha256": _sha(PROFILE.encode()),
            "launcher": SANDBOX_EXEC, "launcherSha256": _sha(launcher.read_bytes()) if launcher.exists() else None,
            "appliesTo": "this process and every descendant", "os": platform.platform(),
            "inProcess": "egress_guard.py also records what each Python engine process attempted",
            "claim": "no network operation is available to the run's process tree; the host itself is not disconnected"}


def enter(argv: list) -> None:
    """Re-execute this program under the network-denying sandbox, once. Returns only when already inside."""
    if os.environ.get(MARK) == _sha(PROFILE.encode()):
        return      # re-executed already; the probes, not this variable, say whether the denial holds
    if platform.system() != "Darwin" or not os.access(SANDBOX_EXEC, os.X_OK):
        raise AirgapError("no verified kernel egress control for %s in this demonstrator; pass "
                          "--airgap application-local, which makes no airgap claim" % platform.system())
    sys.stdout.flush()
    sys.stderr.flush()
    env = dict(os.environ)
    env[MARK] = _sha(PROFILE.encode())
    os.execve(SANDBOX_EXEC, [SANDBOX_EXEC, "-p", PROFILE, sys.executable] + list(argv), env)


def _errname(e: OSError) -> str:
    return errno.errorcode.get(e.errno or 0, type(e).__name__)


def probes() -> dict:
    """What the kernel lets this process do with the network.

    The first probe points a datagram socket at a documentation address (192.0.2.1, RFC 5737); connect() on
    a datagram socket sends nothing. The remaining probes would emit traffic if they were allowed, so they
    run only after the first one was denied: a probe is never itself the egress."""
    results = {}

    def attempt(label, family, kind, address, op="connect"):
        try:
            s = socket.socket(family, kind)
        except OSError as e:
            results[label] = _errname(e)
            return
        try:
            s.settimeout(1.0)
            getattr(s, op)(address)
            results[label] = "allowed"
        except OSError as e:
            results[label] = _errname(e)
        finally:
            s.close()

    first = "udp connect 192.0.2.1:9"
    attempt(first, socket.AF_INET, socket.SOCK_DGRAM, ("192.0.2.1", 9))
    if results[first] not in DENIED:
        return {"denied": False, "probes": results,
                "note": "the kernel does not deny network to this process; further probes were not run "
                        "because they would emit traffic"}
    attempt("tcp connect 192.0.2.1:80", socket.AF_INET, socket.SOCK_STREAM, ("192.0.2.1", 80))
    attempt("tcp connect 127.0.0.1:9 (loopback)", socket.AF_INET, socket.SOCK_STREAM, ("127.0.0.1", 9))
    attempt("tcp6 connect [::1]:9 (loopback)", socket.AF_INET6, socket.SOCK_STREAM, ("::1", 9))
    attempt("tcp bind 127.0.0.1:0 (listen)", socket.AF_INET, socket.SOCK_STREAM, ("127.0.0.1", 0), op="bind")
    try:
        socket.getaddrinfo("example.com", 80)
        results["resolve example.com"] = "allowed"
    except socket.gaierror as e:
        results["resolve example.com"] = "no resolution (gaierror %s)" % e.errno
    except OSError as e:
        results["resolve example.com"] = _errname(e)
    denied = all(v in DENIED or v.startswith("no resolution") for v in results.values())
    return {"denied": denied, "probes": results}


def load_guard(log_path: Path):
    """Install the egress ledger in this process and point every child at the same log."""
    os.environ[LOG_ENV] = str(log_path)
    spec = importlib.util.spec_from_file_location("egress_guard", str(GUARD))
    if spec is None or spec.loader is None:
        raise AirgapError("egress guard not found at %s" % GUARD)
    module = importlib.util.module_from_spec(spec)
    sys.modules["egress_guard"] = module
    spec.loader.exec_module(module)
    module.install()
    return module


class Launcher:
    """The host-owned process boundary: every engine process starts here, behind the ledger, and is listed."""

    def __init__(self, run_dir: Path, profile: str):
        self.profile = profile
        self.log = Path(run_dir) / "egress.jsonl"
        self.inventory = []

    def env(self, extra=None) -> dict:
        env = dict(os.environ)
        env.update(OFFLINE_ENV)
        env[LOG_ENV] = str(self.log)
        env.update(extra or {})
        return env

    def _entry(self, name, pid, program, interpreter, guard):
        entry = {"name": name, "pid": pid, "program": Path(program).name,
                 "interpreter": str(interpreter) if interpreter else None,
                 "ledger": "egress_guard.py" if guard else "none (native binary; kernel control only)"}
        self.inventory.append(entry)
        return entry

    def run(self, name, python, script, args, cwd, extra_env=None, timeout=900):
        """Run a Python program to completion behind the ledger. Returns (exit code, stdout, stderr)."""
        argv = [str(python), str(GUARD), str(script)] + [str(a) for a in args]
        t0 = time.time()
        proc = subprocess.Popen(argv, cwd=str(cwd), env=self.env(extra_env), stdout=subprocess.PIPE,
                                stderr=subprocess.PIPE, text=True)
        entry = self._entry(name, proc.pid, script, python, True)
        try:
            out, err = proc.communicate(timeout=timeout)
        except subprocess.TimeoutExpired:
            proc.kill()
            out, err = proc.communicate()
            entry.update(exit=None, status="deadline", seconds=round(time.time() - t0, 3))
            raise AirgapError("%s exceeded its %ss deadline and was killed; partial output is not a result"
                              % (name, timeout))
        entry.update(exit=proc.returncode, seconds=round(time.time() - t0, 3))
        return proc.returncode, out, err

    def popen(self, name, python, script, args=(), cwd=None, extra_env=None):
        """Start a long-lived Python worker behind the ledger; the caller owns its pipes and calls finished()."""
        argv = [str(python), str(GUARD), str(script)] + [str(a) for a in args]
        proc = subprocess.Popen(argv, cwd=str(cwd) if cwd else None, env=self.env(extra_env),
                                stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        entry = self._entry(name, proc.pid, script, python, True)
        entry["started"] = time.time()
        return proc

    def popen_native(self, name, argv, extra_env=None):
        """Start a native worker. It has no in-process ledger; only the kernel control constrains it."""
        proc = subprocess.Popen([str(a) for a in argv], env=self.env(extra_env), stdin=subprocess.PIPE,
                                stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        entry = self._entry(name, proc.pid, argv[0], None, False)
        entry["started"] = time.time()
        return proc

    def finished(self, proc) -> None:
        for entry in self.inventory:
            if entry["pid"] == proc.pid and "exit" not in entry:
                entry["exit"] = proc.returncode
                entry["seconds"] = round(time.time() - entry.pop("started", time.time()), 3)

    def ledger(self) -> dict:
        """Process inventory joined with what each process's own ledger reported when it ended."""
        reports = []
        if self.log.exists():
            with open(self.log, encoding="utf-8") as f:
                reports = [json.loads(line) for line in f if line.strip()]
        by_pid = {r["pid"]: r for r in reports}
        keep = ("attempts", "external", "loopback", "kernelDenied", "kernelProbe", "refused")
        rows = []
        for entry in self.inventory:
            report = by_pid.get(entry["pid"])
            rows.append({**entry, "reported": None if report is None else {k: report.get(k) for k in keep}})
        known = {e["pid"] for e in self.inventory}
        others = [{"pid": r["pid"], "ppid": r.get("ppid"), "script": r.get("script"),
                   **{k: r.get(k) for k in keep}} for r in reports if r["pid"] not in known]
        return {"processes": rows, "descendants": others}


def summarize(ledger: dict, profile: str) -> dict:
    """The claims the ledger supports, each one a count the report can show."""
    rows = ledger["processes"]
    python_rows = [r for r in rows if r["ledger"] == "egress_guard.py"]
    reported = [r for r in python_rows if r["reported"] is not None]
    everything = [r["reported"] for r in reported] + ledger["descendants"]
    return {"profile": profile, "processes": len(rows), "pythonProcesses": len(python_rows),
            "ledgersReported": len(reported), "descendants": len(ledger["descendants"]),
            "externalAttempts": sum(r.get("external") or 0 for r in everything),
            "loopbackAttempts": sum(r.get("loopback") or 0 for r in everything),
            "kernelDeniedEverywhere": len(reported) == len(python_rows)
            and all(r.get("kernelDenied") is True for r in everything),
            "refused": [{"process": r["name"], **a} for r in reported for a in (r["reported"].get("refused") or [])]}
