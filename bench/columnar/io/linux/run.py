#!/usr/bin/env python3
"""Build the pinned Linux JNI environment and execute the actual ISAM distribution."""
import argparse
import pathlib
import shutil
import subprocess
import tempfile
import uuid

root = pathlib.Path(__file__).resolve().parents[4]
io = root / "bench/columnar/io"
p = argparse.ArgumentParser()
p.add_argument("--mode", choices=["native", "emulated", "auto"], default="native")
p.add_argument("--rows", type=int, default=256)
p.add_argument("--warmup", type=int, default=1)
p.add_argument("--iterations", type=int, default=3)
p.add_argument("--trace", choices=["off", "application", "backend", "both"], default="off")
p.add_argument("--trace-ops", default="")
p.add_argument("--trace-phases", default="")
p.add_argument("--trace-every", type=int, default=1)
p.add_argument("--syscalls", action="store_true", help="Capture io_uring syscalls; separate from timing baseline")
p.add_argument("--unconfined", action="store_true", help="Explicitly remove container seccomp filtering for this run")
p.add_argument("--skip-image-build", action="store_true")
p.add_argument("--output", type=pathlib.Path, default=io.parent / "results/linux")
p.add_argument("--timeout", type=int, default=180)
a = p.parse_args()
if a.rows < 2 or a.warmup < 0 or a.iterations < 1 or a.timeout < 1 or a.trace_every < 1:
    p.error("rows>=2, warmup>=0, iterations>=1, timeout>=1 required")
out = a.output.resolve()
out.mkdir(parents=True, exist_ok=True)
image = "trikeshed-columnar-io:b163"
if not a.skip_image_build:
    with tempfile.TemporaryDirectory(prefix="trikeshed-io-image-") as directory:
        stage = pathlib.Path(directory)
        shutil.copy2(io / "linux/Dockerfile", stage / "Dockerfile")
        shutil.copy2(root / "src/jvmMain/c/uring_jni.c", stage / "uring_jni.c")
        subprocess.run(["docker", "build", "--platform", "linux/arm64", "-t", image, str(stage)], check=True)
dist = io / "build/install/trikeshed-columnar-io"
if not (dist / "bin/trikeshed-columnar-io").is_file():
    p.error("Build root jvmJar, then ./gradlew -p bench/columnar/io installDist first")
container = "trikeshed-columnar-io-" + uuid.uuid4().hex[:12]
cmd = ["docker", "run", "--rm", "--name", container, "--platform", "linux/arm64"]
if a.unconfined:
    cmd += ["--security-opt", "seccomp=unconfined"]
cmd += ["--mount", f"type=bind,source={dist},target=/app,readonly",
        "--mount", f"type=bind,source={out},target=/results",
        "-e", "JAVA_OPTS=--enable-native-access=ALL-UNNAMED -Dtrikeshed.uring.library=/opt/trikeshed/libtrikeshed_uring.so",
        image]
if a.syscalls:
    cmd += ["strace", "-f", "-qq", "-e", "trace=io_uring_setup,io_uring_register,io_uring_enter", "-o", "/results/syscalls.log"]
cmd += ["/app/bin/trikeshed-columnar-io", a.mode, str(a.rows), str(a.warmup), str(a.iterations), "/results/isam.json", a.trace, a.trace_ops, a.trace_phases, str(a.trace_every)]
import json
(out / "launch.json").write_text(json.dumps({"command":cmd,"timeout_seconds":a.timeout}, indent=2)+"\n")
try:
    with (out / "run.log").open("w") as log:
        completed = subprocess.run(cmd, stdout=log, stderr=subprocess.STDOUT, timeout=a.timeout)
except subprocess.TimeoutExpired:
    subprocess.run(["docker", "rm", "-f", container], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    raise SystemExit("Linux ISAM run timed out; container removed, evidence retained")
raise SystemExit(completed.returncode)
