# Sub-VM capability matrix

Measured, not declared: every cell is a probe result on a real host. `✓` OK · `◐` BOUNDED (works within a documented bound) · `✗` FAILED · `—` ABSENT (not available on that host, reason in the host section).

| capability | jvm-macos-arm64 |
|---|---|
| `host.runtime` | ✓ |
| `host.truffle-engine` | ✓ |
| `host.execution-listener` | — |
| `host.native-image-tool` | ✓ |
| `host.graaljs-node` | — |
| `host.llvm-language` | ✓ |
| `js.eval` | ✓ |
| `js.host-delegate` | ✓ |
| `js.statement-limit` | ✓ |
| `js.wall-interrupt` | ✓ |
| `js.root-observation` | ✓ |
| `js.leaf-delegation` | ✓ |
| `js.process-wall` | ✓ |
| `python.eval` | ✓ |
| `python.host-delegate` | ✓ |
| `python.statement-limit` | ◐ |
| `python.wall-interrupt` | ✓ |
| `python.root-observation` | ✓ |
| `python.leaf-delegation` | ✓ |
| `python.process-wall` | ✓ |

## jvm-macos-arm64 (macos/arm64, jvm)

| probe | verdict | ms | evidence |
|---|---|---|---|
| `host.runtime` | OK | 0 | jvm-macos-arm64 (macos/arm64, jvm) vm=OpenJDK 64-Bit Server VM jit=default |
| `host.truffle-engine` | OK | 0 | engine 25.3.4.1 languages=[js, llvm, python] instruments=[cpusampler, cputracer, debugger, heapmonitor, memtracer, sandbox] |
| `host.execution-listener` | ABSENT | 0 | no execution-listener instrument (native-image without -H:+IncludeInstruments?) |
| `host.native-image-tool` | OK | 0 | /Users/jim/.sdkman/candidates/java/25.0.4.1-graal/bin/native-image |
| `host.graaljs-node` | ABSENT | 0 | no GraalJS node launcher (PATH node is stock Node.js, no Truffle); Node APIs cell = absent |
| `host.llvm-language` | OK | 0 | llvm (Sulong) available |
| `js.eval` | OK | 5 | fib(15)=Num(v=610) |
| `js.host-delegate` | OK | 5 | host.call('double',21)=Num(v=42) |
| `js.statement-limit` | OK | 7 | EXHAUSTED after 20k statements; alive=false |
| `js.wall-interrupt` | OK | 404 | INTERRUPTED after 401ms; alive=true |
| `js.root-observation` | OK | 4 | 177 fib frames via listener, 177 self-contained |
| `js.leaf-delegation` | OK | 13 | phase=DELEGATED served={GUEST=12, SHADOW=2, MEMO=9} fires=1 |
| `js.process-wall` | OK | 584 | child pid alive=true; fib(10)=Num(v=55); host.call across wall=Num(v=8) |
| `python.eval` | OK | 72 | fib(15)=Num(v=610) |
| `python.host-delegate` | OK | 57 | host.call('double',21)=Num(v=42) |
| `python.statement-limit` | BOUNDED | 0 | statementLimit unsafe on PYTHON (GIL assert) → stop=INTERRUPT |
| `python.wall-interrupt` | OK | 452 | INTERRUPTED after 400ms; alive=true |
| `python.root-observation` | OK | 72 | 177 fib frames via binding pointcuts, 177 self-contained |
| `python.leaf-delegation` | OK | 123 | phase=DELEGATED served={GUEST=12, SHADOW=2, MEMO=9} fires=1 |
| `python.process-wall` | OK | 1994 | child pid alive=true; fib(10)=Num(v=55); host.call across wall=Num(v=8) |
