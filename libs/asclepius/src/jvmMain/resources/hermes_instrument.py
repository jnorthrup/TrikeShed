# ──────────────────────────────────────────────────────────────────────
# HermesPointcutEmitter mirror (from HermesGraalHarness.kt)
# ──────────────────────────────────────────────────────────────────────

# Phase constants
ASC_PHASE_BEFORE = 0
ASC_PHASE_AFTER = 1

# Opcode constants (match HermesGraalHarness.kt exactly)
OP_TOOL_CALL = 0xB0
OP_TOOL_RESULT = 0xB1
OP_AGENT_TURN_START = 0xB2
OP_AGENT_TURN_END = 0xB3
OP_SKILL_LOAD = 0xB4
OP_SKILL_EXEC = 0xB5
OP_CONTEXT_COMPRESS = 0xB6
OP_MEMORY_READ = 0xB7
OP_MEMORY_WRITE = 0xB8
OP_CRON_TICK = 0xB9
OP_GATEWAY_MSG = 0xBA


class HermesPointcutEmitter:
    """Python mirror of HermesPointcutEmitter bound as `hermesPointcuts` in Graal.

    Host (JVM) calls methods on this object from Python/JS via Graal polyglot.
    """

    def __init__(self):
        self._harness = None
        self._seq_counter = 0

    def _bind_harness(self, harness):
        """Called by JVM to bind the HermesGraalHarness."""
        self._harness = harness

    def _next_seq(self) -> int:
        self._seq_counter += 1
        return self._seq_counter

    # ──────────────────────────────────────────────────────────────
    # Public API — matches HermesPointcutEmitter @HostAccess.Export methods
    # ──────────────────────────────────────────────────────────────

    def emitToolCall(self, phase: int, toolName: str, argsJson: str, seq: int = 0):
        """Emit tool call/result pointcut."""
        if self._harness:
            args_hash = hash(argsJson)
            self._harness.emitToolCall(phase.to_bytes(1, 'little')[0], toolName, args_hash, seq or self._next_seq())

    def emitAgentTurn(self, phase: int, turnId: str, seq: int = 0):
        """Emit agent turn start/end pointcut."""
        if self._harness:
            self._harness.emitAgentTurn(phase.to_bytes(1, 'little')[0], turnId, seq or self._next_seq())

    def emitSkillExec(self, phase: int, skillName: str, seq: int = 0):
        """Emit skill load/exec pointcut."""
        if self._harness:
            self._harness.emitSkillExec(phase.to_bytes(1, 'little')[0], skillName, seq or self._next_seq())

    def emitContextCompress(self, phase: int, compressionRatio: float, seq: int = 0):
        """Emit context compression pointcut."""
        if self._harness:
            self._harness.emitContextCompress(phase.to_bytes(1, 'little')[0], compressionRatio, seq or self._next_seq())

    def emitMemoryOp(self, phase: int, isWrite: bool, key: str, seq: int = 0):
        """Emit memory read/write pointcut."""
        if self._harness:
            self._harness.emitMemoryOp(phase.to_bytes(1, 'little')[0], isWrite, hash(key), seq or self._next_seq())

    def emitGatewayMessage(self, phase: int, channel: str, seq: int = 0):
        """Emit gateway message pointcut."""
        if self._harness:
            self._harness.emitGatewayMessage(phase.to_bytes(1, 'little')[0], hash(channel), seq or self._next_seq())

    # ──────────────────────────────────────────────────────────────
    # Convenience helpers for Python callers
    # ──────────────────────────────────────────────────────────────

    def tool_call_before(self, toolName: str, argsJson: str) -> int:
        seq = self._next_seq()
        self.emitToolCall(ASC_PHASE_BEFORE, toolName, argsJson, seq)
        return seq

    def tool_call_after(self, toolName: str, argsJson: str, seq: int):
        self.emitToolCall(ASC_PHASE_AFTER, toolName, argsJson, seq)

    def agent_turn_before(self, turnId: str) -> int:
        seq = self._next_seq()
        self.emitAgentTurn(ASC_PHASE_BEFORE, turnId, seq)
        return seq

    def agent_turn_after(self, turnId: str, seq: int):
        self.emitAgentTurn(ASC_PHASE_AFTER, turnId, seq)

    def skill_load(self, skillName: str) -> int:
        seq = self._next_seq()
        self.emitSkillExec(ASC_PHASE_BEFORE, skillName, seq)
        return seq

    def skill_exec(self, skillName: str, seq: int):
        self.emitSkillExec(ASC_PHASE_AFTER, skillName, seq)

    def context_compress(self, compressionRatio: float) -> int:
        seq = self._next_seq()
        self.emitContextCompress(ASC_PHASE_BEFORE, compressionRatio, seq)
        return seq

    def memory_read(self, key: str) -> int:
        seq = self._next_seq()
        self.emitMemoryOp(ASC_PHASE_BEFORE, False, key, seq)
        return seq

    def memory_write(self, key: str) -> int:
        seq = self._next_seq()
        self.emitMemoryOp(ASC_PHASE_BEFORE, True, key, seq)
        return seq

    def gateway_message(self, channel: str) -> int:
        seq = self._next_seq()
        self.emitGatewayMessage(ASC_PHASE_BEFORE, channel, seq)
        return seq


# Module-level singleton (bound by host)
_emitter = HermesPointcutEmitter()


def set_emitter(emitter: HermesPointcutEmitter):
    """Host calls this to replace the singleton."""
    global _emitter
    _emitter = emitter


def get_emitter() -> HermesPointcutEmitter:
    return _emitter


# Make available as importable module
sys.modules['hermes_instrument'] = sys.modules[__name__]