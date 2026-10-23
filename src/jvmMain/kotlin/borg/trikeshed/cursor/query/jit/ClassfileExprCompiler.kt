package borg.trikeshed.cursor.query.jit

import borg.trikeshed.cursor.query.ClosureExprCompiler
import borg.trikeshed.cursor.query.ExprCompiler
import borg.trikeshed.job.CasStore
import borg.trikeshed.job.ContentId
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.get
import borg.trikeshed.lib.size
import borg.trikeshed.parse.kursive.sql.*
import java.lang.classfile.ClassFile
import java.lang.classfile.CodeBuilder
import java.lang.classfile.TypeKind
import java.lang.constant.ClassDesc
import java.lang.constant.ConstantDescs.*
import java.lang.constant.MethodTypeDesc
import java.lang.invoke.MethodHandles

/**
 * Compiles a SQL scalar expression to a class of its own: one `invoke(Object): Object` whose body
 * is straight-line calls into [SqlRuntime], so the JIT sees a single monomorphic method per
 * statement instead of a tree of closure objects. The class is defined hidden, from bytes the
 * [cas] holds under their own content id.
 *
 * Content-bound: the statement's identity is the content id of its column layout and canonical
 * text (`expr.render()`); a repeated statement over the same layout reuses its class and only
 * rebuilds the constants (literal numbers, paths, compiled `LIKE` patterns). Any expression the
 * emitter does not cover (or any emission failure) answers with the closure compiler, which
 * evaluates through the same [SqlRuntime], so the result never depends on which one ran.
 */
class ClassfileExprCompiler(val cas: CasStore = CasStore.inMemory()) : ExprCompiler {
    override val identity: String = "classfile"

    /** statement content id → its loaded class. */
    private val loaded = HashMap<ContentId, Class<*>>()
    /** statement content id → content id of the class bytes in [cas]. */
    val bound = HashMap<ContentId, ContentId>()

    var emitted = 0
        private set
    var reused = 0
        private set

    @Synchronized
    override fun compile(expr: Expr, names: Series<CharSequence>): RowExpr {
        val statement = statementCid(expr, names)
        val constants = ArrayList<Any?>()
        val klass = loaded[statement]?.also { reused++ } ?: run {
            val bytes = try {
                emit(expr, names, constants.also { it.clear() })
            } catch (_: Throwable) {
                return ClosureExprCompiler.compile(expr, names)
            }
            val classCid = cas.put(bytes)
            bound[statement] = classCid
            define(bytes).also { loaded[statement] = it; emitted++ }
        }
        // Constants are per compile: rebuild them in emission order against this expression's literals.
        if (constants.isEmpty()) collect(expr, names, constants)
        @Suppress("UNCHECKED_CAST")
        return klass.getDeclaredConstructor(Array<Any?>::class.java).newInstance(constants.toTypedArray()) as RowExpr
    }

    private fun define(bytes: ByteArray): Class<*> = MethodHandles.lookup().defineHiddenClass(bytes, true).lookupClass()

    /** The class bytes the [cas] holds for a statement compiled here, or null. */
    fun classBytes(expr: Expr, names: Series<CharSequence>): ByteArray? = bound[statementCid(expr, names)]?.let { cas.get(it) }

    private fun statementCid(expr: Expr, names: Series<CharSequence>): ContentId {
        val layout = (0 until names.size).joinToString("\u001f") { names[it].toString() }
        return ContentId.of((layout + "\u0000" + expr.render()).encodeToByteArray())
    }

    // ── emission ────────────────────────────────────────────────────────────────────────────────

    private val self = ClassDesc.of("borg.trikeshed.cursor.query.jit.Stmt")
    private val runtime = ClassDesc.of("borg.trikeshed.parse.kursive.sql.SqlRuntime")
    private val function1 = ClassDesc.of("kotlin.jvm.functions.Function1")
    private val objects = CD_Object.arrayType()
    private val binary = MethodTypeDesc.of(CD_Object, CD_Object, CD_Object)

    private fun emit(expr: Expr, names: Series<CharSequence>, constants: ArrayList<Any?>): ByteArray =
        ClassFile.of().build(self) { cb ->
            cb.withFlags(ClassFile.ACC_PUBLIC or ClassFile.ACC_FINAL or ClassFile.ACC_SYNTHETIC)
            cb.withInterfaceSymbols(function1)
            cb.withField("k", objects, ClassFile.ACC_PRIVATE or ClassFile.ACC_FINAL)
            cb.withMethod(INIT_NAME, MethodTypeDesc.of(CD_void, objects), ClassFile.ACC_PUBLIC) { mb ->
                mb.withCode { c ->
                    c.aload(0).invokespecial(CD_Object, INIT_NAME, MTD_void)
                    c.aload(0).aload(1).putfield(self, "k", objects)
                    c.return_()
                }
            }
            cb.withMethod("invoke", MethodTypeDesc.of(CD_Object, CD_Object), ClassFile.ACC_PUBLIC) { mb ->
                mb.withCode { c ->
                    node(c, expr, names, constants)
                    c.areturn()
                }
            }
        }

    /** Pushes one Object: the value of [e] for the RowVec in local 1. */
    private fun node(c: CodeBuilder, e: Expr, names: Series<CharSequence>, constants: ArrayList<Any?>) {
        when (e) {
            is ColumnRef -> {
                c.aload(1).loadConstant(names.ordinalOf(e.id.asString()))
                c.invokestatic(runtime, "cell", MethodTypeDesc.of(CD_Object, CD_Object, CD_int))
            }
            is LitExpr -> when (val value = literal(e.lit)) {
                is String -> c.ldc(c.constantPool().stringEntry(value))
                else -> constant(c, constants, value)
            }
            is Path -> {
                node(c, e.cell, names, constants)
                constant(c, constants, e.path)
                c.loadConstant(if (e.text) 1 else 0)
                c.invokestatic(runtime, "path", MethodTypeDesc.of(CD_Object, CD_Object, CD_Object, CD_boolean))
            }
            is Cast ->
                // A cast of a literal folds once, at emission; anything else casts per row.
                if (e.expr is LitExpr) constant(c, constants, SqlRuntime.cast(literal(e.expr.lit), e.type))
                else {
                    node(c, e.expr, names, constants)
                    c.ldc(c.constantPool().stringEntry(e.type))
                    c.invokestatic(runtime, "cast", MethodTypeDesc.of(CD_Object, CD_Object, CD_String))
                }
            is BinaryExpr -> binaryNode(c, e, names, constants)
            is Not -> {
                node(c, e.expr, names, constants)
                c.invokestatic(runtime, "not", MethodTypeDesc.of(CD_Object, CD_Object))
            }
            is Is -> {
                node(c, e.expr, names, constants)
                when (val expected = e.value) {
                    null -> c.invokestatic(runtime, "isNull", MethodTypeDesc.of(CD_Object, CD_Object))
                    else -> c.loadConstant(if (expected) 1 else 0).invokestatic(runtime, "isBoolean", MethodTypeDesc.of(CD_Object, CD_Object, CD_boolean))
                }
                if (e.negated) c.invokestatic(runtime, "not", MethodTypeDesc.of(CD_Object, CD_Object))
            }
            // IN lists stay on the closure path (see the class comment).
            is InList -> error("IN (...) has no emitted form")
            is Call -> error("function ${e.name}(...) has no scalar form")
            Star -> error("* is only an argument of COUNT")
        }
    }

    private fun binaryNode(c: CodeBuilder, e: BinaryExpr, names: Series<CharSequence>, constants: ArrayList<Any?>) {
        val isTrue = MethodTypeDesc.of(CD_boolean, CD_Object)
        when (val op = e.op.uppercase()) {
            // Three-valued: a false left decides AND and a true left decides OR without the right.
            "AND", "OR" -> {
                val decided = c.newLabel()
                val end = c.newLabel()
                val left = c.allocateLocal(TypeKind.REFERENCE)
                node(c, e.left, names, constants); c.astore(left)
                c.aload(left)
                if (op == "AND") c.invokestatic(runtime, "isFalse", isTrue).ifne(decided)
                else c.invokestatic(runtime, "isTrue", isTrue).ifne(decided)
                c.aload(left); node(c, e.right, names, constants)
                c.invokestatic(runtime, op.lowercase(), binary).goto_(end)
                c.labelBinding(decided).getstatic(CD_Boolean, if (op == "AND") "FALSE" else "TRUE", CD_Boolean)
                c.labelBinding(end)
            }
            "LIKE", "ILIKE" -> {
                node(c, e.left, names, constants)
                val pattern = e.right
                if (pattern is LitExpr) {
                    constant(c, constants, likeRegex(literal(pattern.lit).toString(), ignoreCase = op == "ILIKE"))
                    c.invokestatic(runtime, "likeCompiled", binary)
                } else {
                    node(c, pattern, names, constants)
                    c.invokestatic(runtime, op.lowercase(), binary)
                }
            }
            else -> {
                node(c, e.left, names, constants)
                node(c, e.right, names, constants)
                c.invokestatic(runtime, operator(op), binary)
            }
        }
    }

    private fun operator(op: String): String = when (op) {
        "=" -> "eq"
        "!=", "<>" -> "ne"
        "<" -> "lt"
        "<=" -> "le"
        ">" -> "gt"
        ">=" -> "ge"
        "||" -> "concat"
        "@@" -> "matches"
        "<=>" -> "cosineDistance"
        "<->" -> "l2Distance"
        "<#>" -> "negInnerProduct"
        "+" -> "add"
        "-" -> "sub"
        "*" -> "mul"
        "/" -> "div"
        "%" -> "mod"
        else -> error("operator $op")
    }

    /** Pushes `k[constants.size]` and records [value] as that constant. */
    private fun constant(c: CodeBuilder, constants: ArrayList<Any?>, value: Any?) {
        c.aload(0).getfield(self, "k", objects).loadConstant(constants.size).aaload()
        constants += value
    }

    /** The constants [emit] would record for [expr], in the same order, without emitting. */
    private fun collect(expr: Expr, names: Series<CharSequence>, constants: ArrayList<Any?>) {
        when (expr) {
            is LitExpr -> literal(expr.lit).let { if (it !is String) constants += it }
            is Path -> { collect(expr.cell, names, constants); constants += expr.path }
            is Cast ->
                if (expr.expr is LitExpr) constants += SqlRuntime.cast(literal(expr.expr.lit), expr.type)
                else collect(expr.expr, names, constants)
            is BinaryExpr -> {
                val op = expr.op.uppercase()
                collect(expr.left, names, constants)
                if ((op == "LIKE" || op == "ILIKE") && expr.right is LitExpr) constants += likeRegex(literal(expr.right.lit).toString(), ignoreCase = op == "ILIKE")
                else collect(expr.right, names, constants)
            }
            is Not -> collect(expr.expr, names, constants)
            is Is -> collect(expr.expr, names, constants)
            else -> Unit
        }
    }
}
