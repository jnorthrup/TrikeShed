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
| `host.truffle-engine` | OK | 145 | engine 25.0.2 languages=[js, llvm, python] instruments=[cpusampler, cputracer, debugger, heapmonitor, memtracer] |
| `host.execution-listener` | ABSENT | 1 | no execution-listener instrument (native-image without -H:+IncludeInstruments?) |
| `host.native-image-tool` | OK | 0 | /Users/jim/.sdkman/candidates/java/25.0.2-graalce/bin/native-image |
| `host.graaljs-node` | ABSENT | 4 | no GraalJS node launcher (PATH node is stock Node.js, no Truffle); Node APIs cell = absent |
| `host.llvm-language` | OK | 1 | llvm (Sulong) available |
| `js.eval` | OK | 300 | fib(15)=Num(v=610) |
| `js.host-delegate` | OK | 14 | host.call('double',21)=Num(v=42) |
| `js.statement-limit` | OK | 23 | EXHAUSTED after 20k statements; alive=false |
| `js.wall-interrupt` | OK | 412 | INTERRUPTED after 404ms; alive=true |
| `js.root-observation` | OK | 9 | 177 fib frames via listener, 177 self-contained |
| `js.leaf-delegation` | OK | 117 | phase=DELEGATED served={GUEST=12, SHADOW=2, MEMO=9} fires=1 |
| `js.process-wall` | OK | 552 | child pid alive=true; fib(10)=Num(v=55); host.call across wall=Num(v=8) |
| `python.eval` | OK | 1536 | fib(15)=Num(v=610) |
| `python.host-delegate` | OK | 169 | host.call('double',21)=Num(v=42) |
| `python.statement-limit` | BOUNDED | 1 | statementLimit unsafe on PYTHON (GIL assert) → stop=INTERRUPT |
| `python.wall-interrupt` | OK | 585 | INTERRUPTED after 402ms; alive=true |
| `python.root-observation` | OK | 168 | 177 fib frames via binding pointcuts, 177 self-contained |
| `python.leaf-delegation` | OK | 359 | phase=DELEGATED served={GUEST=12, SHADOW=2, MEMO=9} fires=1 |
| `python.process-wall` | OK | 1803 | child pid alive=true; fib(10)=Num(v=55); host.call across wall=Num(v=8) |
