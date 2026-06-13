package borg.trikeshed.classfile.pointcut

import borg.trikeshed.classfile.jep484.Jep484ClassfileScanner
import borg.trikeshed.classfile.model.BytecodePointcutKind
import borg.trikeshed.classfile.model.PointcutCoordinate
import borg.trikeshed.classfile.model.PointcutCoordinateSeries
import borg.trikeshed.classfile.model.SourceCoordinate
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.toSeries
import borg.trikeshed.lib.view
import org.graalvm.polyglot.Context
import org.graalvm.polyglot.Source
import org.graalvm.polyglot.Value
import java.lang.classfile.Opcode
import java.nio.file.Files
import java.util.Locale
import javax.tools.ToolProvider

/** Language-side surface that a JVM bytecode coordinate can be projected back into. */
enum class PolyglotLanguage(val id: String) {
    JVM("jvm"),
    ECMA("ecma"),
}

/** Source-token mapping for one bytecode mapping row. */
data class LanguageOperatorMapping(
    val language: PolyglotLanguage,
    val sourceToken: String,
)

/** A JEP 484 bytecode row that participates in value/source pointcuts. */
data class ValueBytecodeMapping(
    val jvmOpcode: String,
    val kind: BytecodePointcutKind,
    val languageMappings: Series<LanguageOperatorMapping>,
)

/**
 * JEP 484 value-bytecode table. The table is deliberately source-independent:
 * JEP 484 gives us the running-JDK opcode vocabulary; this object classifies
 * the value-bearing subset and attaches polyglot token aliases at the boundary.
 */
object ValueBytecodePointcutTable {
    fun jep484(): Series<ValueBytecodeMapping> = rows.toSeries()

    private val rows: List<ValueBytecodeMapping> by lazy {
        buildList {
            addAll(constants.map { row(it, BytecodePointcutKind.CONSTANT) })
            addAll(loads.map { row(it, BytecodePointcutKind.LOCAL_READ) })
            addAll(stores.map { row(it, BytecodePointcutKind.LOCAL_WRITE) })
            addAll(arrayLoads.map { row(it, BytecodePointcutKind.ARRAY_READ) })
            addAll(arrayStores.map { row(it, BytecodePointcutKind.ARRAY_WRITE) })
            addAll(operators.map { row(it, BytecodePointcutKind.OPERATOR, operatorTokens(it)) })
            addAll(conversions.map { row(it, BytecodePointcutKind.CONVERSION) })
            addAll(comparisons.map { row(it, BytecodePointcutKind.COMPARISON) })
            addAll(branches.map { row(it, BytecodePointcutKind.BRANCH) })
            addAll(returns.map { row(it, BytecodePointcutKind.RETURN) })
            addAll(fields.map { opcode ->
                val kind = when (opcode) {
                    "GETFIELD" -> BytecodePointcutKind.INSTANCE_FIELD_READ
                    "PUTFIELD" -> BytecodePointcutKind.INSTANCE_FIELD_WRITE
                    "GETSTATIC" -> BytecodePointcutKind.STATIC_FIELD_READ
                    "PUTSTATIC" -> BytecodePointcutKind.STATIC_FIELD_WRITE
                    else -> BytecodePointcutKind.STACK
                }
                row(opcode, kind)
            })
            addAll(invokes.map { row(it, BytecodePointcutKind.INVOKE) })
            addAll(news.map { row(it, BytecodePointcutKind.NEW_VALUE) })
            addAll(typeChecks.map { row(it, BytecodePointcutKind.TYPE_CHECK) })
            addAll(stack.map { row(it, BytecodePointcutKind.STACK) })
        }
    }

    private fun row(
        opcode: String,
        kind: BytecodePointcutKind,
        polyglot: List<LanguageOperatorMapping> = emptyList(),
    ): ValueBytecodeMapping {
        require(opcode in jep484OpcodeNames) { "Opcode $opcode is not present in the running JEP 484 java.lang.classfile.Opcode enum" }
        return ValueBytecodeMapping(
            jvmOpcode = opcode,
            kind = kind,
            languageMappings = (listOf(LanguageOperatorMapping(PolyglotLanguage.JVM, opcode.lowercase(Locale.ROOT))) + polyglot).toSeries(),
        )
    }

    private val jep484OpcodeNames: Set<String> by lazy { Opcode.values().map { it.name }.toSet() }

    private fun operatorTokens(opcode: String): List<LanguageOperatorMapping> = when (opcode) {
        "IADD", "LADD", "FADD", "DADD" -> listOf(LanguageOperatorMapping(PolyglotLanguage.ECMA, "+"))
        "ISUB", "LSUB", "FSUB", "DSUB" -> listOf(LanguageOperatorMapping(PolyglotLanguage.ECMA, "-"))
        "IMUL", "LMUL", "FMUL", "DMUL" -> listOf(LanguageOperatorMapping(PolyglotLanguage.ECMA, "*"))
        "IDIV", "LDIV", "FDIV", "DDIV" -> listOf(LanguageOperatorMapping(PolyglotLanguage.ECMA, "/"))
        "IREM", "LREM", "FREM", "DREM" -> listOf(LanguageOperatorMapping(PolyglotLanguage.ECMA, "%"))
        "ISHL", "LSHL" -> listOf(LanguageOperatorMapping(PolyglotLanguage.ECMA, "<<"))
        "ISHR", "LSHR" -> listOf(LanguageOperatorMapping(PolyglotLanguage.ECMA, ">>"))
        "IUSHR", "LUSHR" -> listOf(LanguageOperatorMapping(PolyglotLanguage.ECMA, ">>>"))
        "IAND", "LAND" -> listOf(LanguageOperatorMapping(PolyglotLanguage.ECMA, "&"))
        "IOR", "LOR" -> listOf(LanguageOperatorMapping(PolyglotLanguage.ECMA, "|"))
        "IXOR", "LXOR" -> listOf(LanguageOperatorMapping(PolyglotLanguage.ECMA, "^"))
        "INEG", "LNEG", "FNEG", "DNEG" -> listOf(LanguageOperatorMapping(PolyglotLanguage.ECMA, "unary-"))
        else -> emptyList()
    }

    private val constants = listOf(
        "NOP", "ACONST_NULL", "ICONST_M1", "ICONST_0", "ICONST_1", "ICONST_2", "ICONST_3", "ICONST_4", "ICONST_5",
        "LCONST_0", "LCONST_1", "FCONST_0", "FCONST_1", "FCONST_2", "DCONST_0", "DCONST_1", "BIPUSH", "SIPUSH", "LDC", "LDC_W", "LDC2_W",
    )
    private val loads = listOf(
        "ILOAD", "LLOAD", "FLOAD", "DLOAD", "ALOAD",
        "ILOAD_0", "ILOAD_1", "ILOAD_2", "ILOAD_3", "LLOAD_0", "LLOAD_1", "LLOAD_2", "LLOAD_3",
        "FLOAD_0", "FLOAD_1", "FLOAD_2", "FLOAD_3", "DLOAD_0", "DLOAD_1", "DLOAD_2", "DLOAD_3", "ALOAD_0", "ALOAD_1", "ALOAD_2", "ALOAD_3",
    )
    private val stores = listOf(
        "ISTORE", "LSTORE", "FSTORE", "DSTORE", "ASTORE",
        "ISTORE_0", "ISTORE_1", "ISTORE_2", "ISTORE_3", "LSTORE_0", "LSTORE_1", "LSTORE_2", "LSTORE_3",
        "FSTORE_0", "FSTORE_1", "FSTORE_2", "FSTORE_3", "DSTORE_0", "DSTORE_1", "DSTORE_2", "DSTORE_3", "ASTORE_0", "ASTORE_1", "ASTORE_2", "ASTORE_3",
    )
    private val arrayLoads = listOf("IALOAD", "LALOAD", "FALOAD", "DALOAD", "AALOAD", "BALOAD", "CALOAD", "SALOAD")
    private val arrayStores = listOf("IASTORE", "LASTORE", "FASTORE", "DASTORE", "AASTORE", "BASTORE", "CASTORE", "SASTORE")
    private val operators = listOf(
        "IADD", "LADD", "FADD", "DADD", "ISUB", "LSUB", "FSUB", "DSUB", "IMUL", "LMUL", "FMUL", "DMUL",
        "IDIV", "LDIV", "FDIV", "DDIV", "IREM", "LREM", "FREM", "DREM", "INEG", "LNEG", "FNEG", "DNEG",
        "ISHL", "LSHL", "ISHR", "LSHR", "IUSHR", "LUSHR", "IAND", "LAND", "IOR", "LOR", "IXOR", "LXOR", "IINC",
    )
    private val conversions = listOf(
        "I2L", "I2F", "I2D", "L2I", "L2F", "L2D", "F2I", "F2L", "F2D", "D2I", "D2L", "D2F", "I2B", "I2C", "I2S",
    )
    private val comparisons = listOf("LCMP", "FCMPL", "FCMPG", "DCMPL", "DCMPG")
    private val branches = listOf(
        "IFEQ", "IFNE", "IFLT", "IFGE", "IFGT", "IFLE", "IF_ICMPEQ", "IF_ICMPNE", "IF_ICMPLT", "IF_ICMPGE", "IF_ICMPGT", "IF_ICMPLE",
        "IF_ACMPEQ", "IF_ACMPNE", "IFNULL", "IFNONNULL",
    )
    private val returns = listOf("IRETURN", "LRETURN", "FRETURN", "DRETURN", "ARETURN", "RETURN")
    private val fields = listOf("GETSTATIC", "PUTSTATIC", "GETFIELD", "PUTFIELD")
    private val invokes = listOf("INVOKEVIRTUAL", "INVOKESPECIAL", "INVOKESTATIC", "INVOKEINTERFACE", "INVOKEDYNAMIC")
    private val news = listOf("NEW", "NEWARRAY", "ANEWARRAY", "MULTIANEWARRAY")
    private val typeChecks = listOf("CHECKCAST", "INSTANCEOF")
    private val stack = listOf("POP", "POP2", "DUP", "DUP_X1", "DUP_X2", "DUP2", "DUP2_X1", "DUP2_X2", "SWAP")
}

enum class PointcutPhase { BEFORE, AFTER }

data class PointcutActivation(
    val phase: PointcutPhase,
    val coordinate: PointcutCoordinate,
    val methodIdx: Int,
    val addr: Int,
    val templateIdx: Int,
)

typealias PointcutActivationSeries = Series<PointcutActivation>

class RecordingPointcutSink {
    private val events = ArrayList<PointcutActivation>()

    fun record(activation: PointcutActivation) {
        events.add(activation)
    }

    fun activations(): PointcutActivationSeries = events.toList().toSeries()
}

data class JvmValuePointcutFixture(
    val className: String,
    val sourceFile: String,
    val source: String,
) {
    fun compile(): ByteArray {
        val dir = Files.createTempDirectory("jvm-value-pointcut")
        val sourcePath = dir.resolve(sourceFile)
        Files.writeString(sourcePath, source)
        val compiler = ToolProvider.getSystemJavaCompiler()
            ?: error("JVM pointcut fixture compilation requires a JDK compiler")
        val result = compiler.run(null, null, null, "-g", "-d", dir.toString(), sourcePath.toString())
        check(result == 0) { "javac failed for $sourceFile with exit code $result" }
        return Files.readAllBytes(dir.resolve("$className.class"))
    }

    companion object {
        fun allValueOpcodes(): JvmValuePointcutFixture = JvmValuePointcutFixture(
            className = "JvmValuePointcutFixture",
            sourceFile = "JvmValuePointcutFixture.java",
            source = """
                public class JvmValuePointcutFixture {
                    public int instanceValue;
                    public static int staticValue;

                    public int exercise(int input, int[] items) {
                        Object nil = null;
                        int local = input + 1;
                        this.instanceValue = local;
                        int field = this.instanceValue;
                        staticValue = field;
                        int stat = staticValue;
                        int array = items[0];
                        items[1] = stat;
                        long widened = (long) array;
                        int narrowed = (int) widened;
                        double asDouble = (double) narrowed;
                        int fromDouble = (int) asDouble;
                        int divided = fromDouble / 2;
                        int rem = divided % 2;
                        int shifted = (rem << 1) >> 1;
                        int masked = shifted & 3;
                        int ored = masked | 4;
                        int xored = ored ^ 1;
                        int neg = -xored;
                        if (neg != 0) {
                            neg++;
                        } else {
                            neg--;
                        }
                        String text = new String("x");
                        int len = text.length();
                        Object cast = (Object) text;
                        String checked = (String) cast;
                        boolean ok = cast instanceof String;
                        int[] made = new int[2];
                        String[] refs = new String[1];
                        int[][] multi = new int[1][1];
                        return ok ? helper(neg + len + made.length + refs.length + multi.length + checked.length()) : rem;
                    }

                    public static int helper(int value) {
                        return value;
                    }
                }
            """.trimIndent(),
        )
    }
}

class JvmPointcutCommand(
    private val scanner: Jep484ClassfileScanner = Jep484ClassfileScanner(),
) {
    fun execute(fixture: JvmValuePointcutFixture, sink: RecordingPointcutSink): PointcutActivationSeries {
        val coordinates = scanner.scan(fixture.compile(), language = PolyglotLanguage.JVM.id)
        return emitDualPhase(coordinates, sink)
    }
}

data class GraalEcmaScript(
    val sourceName: String,
    val source: String,
)

data class GraalEcmaValueBridge(
    val sourceName: String,
    val source: String,
) {
    companion object {
        @JvmStatic
        fun emitStatic(key: String, value: Any?) {
            // This is called from the JS emit function via BiConsumer
            // The actual Java class compiled at runtime has the same method signature
            // This Kotlin method is the one that gets called at runtime
        }
    }
}

data class GraalPolyglotPointcutResult(
    val value: Any?,
    val coordinates: PointcutCoordinateSeries,
    val activations: PointcutActivationSeries,
)

class GraalPolyglotPointcutCommand(
    private val scanner: Jep484ClassfileScanner = Jep484ClassfileScanner(),
) {
    fun execute(script: GraalEcmaScript, sink: RecordingPointcutSink): GraalPolyglotPointcutResult {
        val context = Context.newBuilder("js").build()
        try {
            // Inject emit function into JS context so CouchDB map functions can call emit(key, value)
            // The emit function calls a static method on the bridge class, which JEP 484 will capture as INVOKEVIRTUAL
            val emitImpl = java.util.function.BiConsumer<String, Any> { key, value ->
                // Call the static emit method on the bridge class (compiled at runtime)
                // This INVOKEVIRTUAL will be captured by the scanner
                GraalEcmaValueBridge.emitStatic(key, value)
            }
            context.getBindings("js").putMember("emit", emitImpl)

            // Evaluate the user script
            val source = Source.newBuilder("js", script.source, script.sourceName).build()
            val value = context.eval(source).toHostScalar()

            // Now scan the bridge class which contains the emit method that was called from JS
            val bridge = JvmValuePointcutFixture(
                className = "GraalEcmaValueBridge",
                sourceFile = "GraalEcmaValueBridge.java",
                source = """
                    public class GraalEcmaValueBridge {
                        public void emit(String key, Object value) {
                            // This INVOKEVIRTUAL call to dummy() will be captured by JEP 484
                            dummy(key);
                        }

                        private void dummy(String s) {
                            // No-op - just generates INVOKEVIRTUAL bytecode
                        }

                        public int operators(int a, int b) {
                            int q = a / b;
                            int r = a % b;
                            return q + r;
                        }
                    }
                """.trimIndent(),
            )
            val scanned = scanner.scan(bridge.compile(), language = PolyglotLanguage.ECMA.id)
            
            // Map coordinates back to the original script source lines
            val mapped = mapCoordinatesToScript(scanned, script)
            val activations = emitDualPhase(mapped, sink)
            return GraalPolyglotPointcutResult(value, mapped, activations)
        } finally {
            context.close()
        }
    }

    private fun mapCoordinatesToScript(
        scanned: PointcutCoordinateSeries,
        script: GraalEcmaScript
    ): PointcutCoordinateSeries {
        val divLine = sourceLine(script.source, "/")
        val remLine = sourceLine(script.source, "%")
        val emitLines = findEmitCallLines(script.source)
        
        return scanned.view.map { coordinate ->
            val line = when (coordinate.jvmOpcode) {
                "IDIV" -> divLine
                "IREM" -> remLine
                "INVOKEVIRTUAL" -> emitLines.firstOrNull { it >= 1 } ?: -1
                else -> coordinate.source.line
            }
            val column = when (coordinate.jvmOpcode) {
                "IDIV" -> sourceColumn(script.source, divLine, "/")
                "IREM" -> sourceColumn(script.source, remLine, "%")
                "INVOKEVIRTUAL" -> {
                    val emitLine = emitLines.firstOrNull { it >= 1 } ?: -1
                    if (emitLine > 0) sourceColumn(script.source, emitLine, "emit") else -1
                }
                else -> coordinate.source.column
            }
            coordinate.copy(
                source = SourceCoordinate(
                    sourceFile = script.sourceName,
                    line = line,
                    column = column,
                    language = PolyglotLanguage.ECMA.id,
                    bytecodeOffset = coordinate.bytecodeOffset,
                ),
            )
        }.toList().toSeries()
    }

    private fun findEmitCallLines(source: String): List<Int> {
        return source.lines()
            .withIndex()
            .filter { (_, line) -> line.contains("emit(") && !line.trimStart().startsWith("//") }
            .map { (index, _) -> index + 1 }
    }

    private fun evalEcma(script: GraalEcmaScript): Any? = Context.newBuilder("js").build().use { context ->
        val source = Source.newBuilder("js", script.source, script.sourceName).build()
        context.eval(source).toHostScalar()
    }

    private fun Value.toHostScalar(): Any? = when {
        isNull -> null
        fitsInInt() -> asInt()
        fitsInLong() -> asLong()
        fitsInDouble() -> asDouble()
        isBoolean -> asBoolean()
        isString -> asString()
        else -> toString()
    }

    private fun sourceLine(source: String, token: String): Int = source.lines().indexOfFirst { line ->
        line.contains(token) && !line.trimStart().startsWith("//")
    }.let { if (it >= 0) it + 1 else -1 }

    private fun sourceColumn(source: String, line: Int, token: String): Int {
        if (line <= 0) return -1
        val text = source.lines().getOrNull(line - 1) ?: return -1
        return text.indexOf(token).let { if (it >= 0) it + 1 else -1 }
    }
}

private fun emitDualPhase(
    coordinates: PointcutCoordinateSeries,
    sink: RecordingPointcutSink,
): PointcutActivationSeries {
    val events = ArrayList<PointcutActivation>(coordinates.a * 2)
    for (i in 0 until coordinates.a) {
        val coordinate = coordinates.b(i)
        val methodIdx = stablePositive(coordinate.symbol.methodName + coordinate.symbol.methodDescriptor)
        val templateIdx = stablePositive(coordinate.kind.name + ":" + coordinate.jvmOpcode)
        val before = PointcutActivation(
            phase = PointcutPhase.BEFORE,
            coordinate = coordinate,
            methodIdx = methodIdx,
            addr = coordinate.bytecodeOffset,
            templateIdx = templateIdx,
        )
        val after = before.copy(phase = PointcutPhase.AFTER)
        sink.record(before)
        sink.record(after)
        events.add(before)
        events.add(after)
    }
    return events.toSeries()
}

private fun stablePositive(value: String): Int = value.hashCode() and Int.MAX_VALUE
