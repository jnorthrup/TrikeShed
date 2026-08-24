package borg.trikeshed.graal.subvm

import borg.trikeshed.pointcut.VmFacet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue

/** Bytes pushed by the console's stdin POST, drained one at a time by the guest's blocking `input()`. */
private class QueueInputStream : InputStream() {
    private val q = LinkedBlockingQueue<Int>()
    fun push(text: String) { for (b in text.toByteArray(Charsets.UTF_8)) q.put(b.toInt() and 0xFF) }
    override fun read(): Int = q.take()
}

/** Captured stdout+stderr as one VT-shaped scrollback the console polls whole; trimmed, not diffed. */
private class RingOutputStream(private val cap: Int = 400_000) : OutputStream() {
    private val buf = StringBuilder()
    @Synchronized override fun write(b: Int) = append(byteArrayOf(b.toByte()))
    @Synchronized override fun write(b: ByteArray, off: Int, len: Int) = append(b.copyOfRange(off, off + len))
    private fun append(bytes: ByteArray) {
        buf.append(bytes.toString(Charsets.UTF_8))
        if (buf.length > cap) buf.delete(0, buf.length - cap)
    }
    @Synchronized fun snapshot(): String = buf.toString()
}

/**
 * A capsuled Hermes sleeve: one [GraalBtrfsSupervisor] guest — GraalPy with its own UserspaceBtrfs
 * subvolume as its ENTIRE filesystem (`os.getcwd`, `open`, `os.listdir` all resolve there; host
 * files and sockets stay unreachable, per [InProcessIsolate]'s `allowHostFileAccess(false)`) — wired
 * to a captured stdin/stdout pair. That pair, plus [SHELL_SOURCE], IS the "whole new set of GraalVM
 * Hermes tools": no PTY, no child process (`allowCreateProcess(false)` never relaxes), no new IO
 * primitive beyond what [InProcessIsolate] already exposed. The shell is Python's own `input()`/
 * `print()` reading and writing exactly those streams — POSIX emulation is GraalPy's `os`/`pathlib`
 * against [TrikeShedGraalVfs], not a hand-rolled syscall layer.
 */
class HermesCapsule(val id: String) {
    private val stdin = QueueInputStream()
    private val stdout = RingOutputStream()
    private val guest = GraalBtrfsSupervisor(id, VmFacet.GRAAL_PYTHON, budget = Budget(statements = 0, wallMillis = 0), input = stdin, output = stdout, error = stdout)

    /** Wall-clock stamp of the last read/write, so idle capsules can be reaped without a policy of their own. */
    @Volatile var lastActiveMs: Long = System.currentTimeMillis(); private set
    @Volatile var alive = true; private set
    private var job: Job? = null

    fun start(scope: CoroutineScope) {
        job = scope.launch(Dispatchers.IO) {
            try {
                guest.eval(SHELL_SOURCE, "hermes-shell")
            } catch (t: Throwable) {
                stdout.write("\n[capsule ended: ${t.message}]\n".toByteArray())
            } finally {
                alive = false
            }
        }
    }

    fun type(text: String) { lastActiveMs = System.currentTimeMillis(); stdin.push(if (text.endsWith("\n")) text else "$text\n") }
    fun output(): String { lastActiveMs = System.currentTimeMillis(); return stdout.snapshot() }
    fun kill() { alive = false; job?.cancel(); runCatching { guest.close() } }

    companion object {
        val registry = ConcurrentHashMap<String, HermesCapsule>()

        /** The parsimonious shell: a handful of POSIX-flavoured verbs over real `os`/`open` calls
         *  against the guest's own virtual filesystem, plus a raw `python <expr>` escape hatch. */
        private val SHELL_SOURCE = """
import os, sys
def _ls(a):
    p = a or '.'
    try: print('  '.join(sorted(os.listdir(p))))
    except Exception as e: print('ls: ' + str(e))
def _cat(a):
    try:
        with open(a) as f: sys.stdout.write(f.read())
        if not a: pass
    except Exception as e: print('cat: ' + str(e))
print('hermes sleeve -- graalpy ' + sys.version.split()[0] + ' -- posix over trikeshed-graal-btrfs')
print('cwd ' + os.getcwd())
while True:
    sys.stdout.write('# '); sys.stdout.flush()
    line = sys.stdin.readline()
    if not line:
        break
    line = line.rstrip('\n')
    if not line:
        continue
    parts = line.split(' ', 1)
    cmd, arg = parts[0], (parts[1] if len(parts) > 1 else '')
    try:
        if cmd in ('exit', 'quit'):
            print('bye'); break
        elif cmd == 'pwd':
            print(os.getcwd())
        elif cmd == 'cd':
            os.chdir(arg or '/')
        elif cmd == 'ls':
            _ls(arg)
        elif cmd == 'cat':
            _cat(arg)
        elif cmd == 'echo':
            print(arg)
        elif cmd == 'mkdir':
            os.mkdir(arg)
        elif cmd == 'rm':
            os.remove(arg)
        elif cmd == 'write':
            path, _, text = arg.partition(' ')
            with open(path, 'w') as f:
                f.write(text)
        elif cmd == 'python':
            print(eval(arg))
        else:
            print(cmd + ': command not found (try ls, cd, pwd, cat, echo, mkdir, rm, write, python <expr>, exit)')
    except Exception as e:
        print(cmd + ': ' + str(e))
""".trimIndent()
    }
}
