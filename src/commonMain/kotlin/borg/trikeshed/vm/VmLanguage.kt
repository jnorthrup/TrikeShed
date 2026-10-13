package borg.trikeshed.vm

import borg.trikeshed.lib.*
import borg.trikeshed.pointcut.VmFacet
import borg.trikeshed.parse.jsonOf

/** Authoring language is distinct from the host-access facet called JVM. */
enum class VmLanguage(val id: String, val label: String, val runtime: VmLanguageRuntime, val facet: VmFacet?) {
    KOTLIN_COMMON("kotlin-common", "Kotlin common", VmLanguageRuntime.COMPILED_COMMON, VmFacet.JVM),
    JAVA("java", "Java", VmLanguageRuntime.JVM, VmFacet.JVM),
    CLOJURE("clojure", "Clojure", VmLanguageRuntime.JVM, VmFacet.JVM),
    JAVASCRIPT("js", "JavaScript", VmLanguageRuntime.TRUFFLE, VmFacet.GRAAL_JS),
    GROOVY("groovy", "Groovy", VmLanguageRuntime.JVM, VmFacet.JVM),
    RUBY("ruby", "Ruby", VmLanguageRuntime.TRUFFLE, VmFacet.GRAAL_RUBY),
    PYTHON("python", "Python", VmLanguageRuntime.TRUFFLE, VmFacet.GRAAL_PYTHON);

    val module: String? get() = when (this) {
        GROOVY -> "groovy"
        else -> null
    }

    /** Complete overrides for the generic polyglot node; JVM lanes must not inherit Truffle limits. */
    fun params(source: String): Map<String, String> = mapOf(
        "language" to id,
        "source" to source,
        "trust" to "OWN",
        "statements" to if (facet == VmFacet.JVM) "0" else "5000000",
        "wallMillis" to if (this == JAVA || this == CLOJURE || this == KOTLIN_COMMON) "0" else "5000",
        "calls" to "0",
    )

    fun validate(trust: VmTrust, budget: VmBudget) {
        if (this == JAVA || this == CLOJURE || this == KOTLIN_COMMON) {
            require(trust == VmTrust.OWN) { "$label source requires OWN trust; JVM host access is not a sandbox" }
            require(budget.statements == 0L && budget.wallMillis == 0L) {
                "$label source has no statement or wall-clock enforcement; bounded execution requires a process-isolated compiler lane"
            }
        }
        if (this == GROOVY) {
            require(trust == VmTrust.OWN) { "Groovy source requires OWN trust; JVM host access is not a sandbox" }
            require(budget.statements == 0L) { "Groovy does not expose Truffle statement counts; use its cooperative wallMillis limit" }
        }
    }

    companion object {
        val all: Series<VmLanguage> get() = entries.size j { i: Int -> entries[i] }

        fun of(id: String): VmLanguage? = when (id.lowercase()) {
            "kotlin" -> KOTLIN_COMMON
            "javascript" -> JAVASCRIPT
            else -> entries.firstOrNull { it.id == id.lowercase() || it.name.equals(id, ignoreCase = true) }
        }
    }
}

enum class VmLanguageRuntime { COMPILED_COMMON, JVM, TRUFFLE }

/** Installed engine and usable source lane are separate facts. */
data class VmLanguageCapability(
    val language: VmLanguage,
    val installed: Boolean,
    val available: Boolean,
    val note: String,
) {
    val sample: String get() = if (!available) "" else when (language) {
        VmLanguage.JAVA -> "return input;"
        VmLanguage.CLOJURE -> "input"
        else -> "return input"
    }

    /** Complete language-specific overrides for the generic vm.polyglot palette contract. */
    val params: Map<String, String> get() = language.params(sample)

    fun toMap(): Map<String, Any?> = mapOf(
        "id" to language.id,
        "label" to language.label,
        "runtime" to language.runtime.name,
        "facet" to language.facet?.name,
        "installed" to installed,
        "available" to available,
        "note" to note,
        "sourceForm" to "function-body",
        "sample" to sample,
        "params" to params,
        "camel" to if (available) "payload-transform" else "unavailable",
        "trust" to if (language.facet == VmFacet.JVM) listOf("OWN") else listOf("OWN", "UNTRUSTED"),
        "wallLimit" to when (language) {
            VmLanguage.KOTLIN_COMMON, VmLanguage.JAVA, VmLanguage.CLOJURE -> "unsupported; compilation and host execution are not bounded"
            VmLanguage.GROOVY -> "cooperative; excludes compilation, static methods and blocking host calls"
            else -> "host"
        },
    )
}

/** Confix-compatible port kinds. No host object or executable value crosses this lane. */
enum class VmValueKind {
    JSON, TEXT, BOOLEAN, INTEGER, NUMBER, ARRAY, OBJECT, NULL;

    fun accepts(value: Teleported): Boolean = when (this) {
        JSON -> true
        TEXT -> value is Teleported.Str
        BOOLEAN -> value is Teleported.Bool
        INTEGER -> value is Teleported.Num
        NUMBER -> value is Teleported.Num || value is Teleported.Real
        ARRAY -> value is Teleported.Arr
        OBJECT -> value is Teleported.Obj
        NULL -> value === Teleported.Null
    }

    fun validate(value: Teleported): Teleported {
        vmLanguageValue(value)
        require(accepts(value)) { "Expected ${name.lowercase()} VM value" }
        return value
    }

    companion object {
        fun of(name: String): VmValueKind = requireNotNull(entries.firstOrNull { it.name.equals(name, true) }) {
            "Unknown VM value kind '$name'"
        }
    }
}

/** Bound before invoking the existing VM ABI; cycles terminate at the depth check. */
fun vmLanguageValue(value: Any?, depth: Int = 0): Teleported {
    require(depth <= 5) { "VM value exceeds the teleport depth of 5" }
    fun array(size: Int, element: (Int) -> Any?): Teleported.Arr {
        require(depth < 5 && size <= 10_000) { "VM array exceeds teleport bounds" }
        // The crossing is a value boundary: validate now, not through a lazy Series view.
        return Teleported.Arr(List(size) { i -> vmLanguageValue(element(i), depth + 1) })
    }
    fun obj(values: Map<*, *>): Teleported.Obj {
        require(depth < 5 && values.size <= 10_000) { "VM object exceeds teleport bounds" }
        require(values.keys.all { it is String }) { "VM object keys must be text" }
        return Teleported.Obj(values.entries.associate { (key, item) -> key as String to vmLanguageValue(item, depth + 1) })
    }
    return when (value) {
        null, Teleported.Null -> Teleported.Null
        is Teleported.Bool, is Teleported.Str -> value as Teleported
        is Teleported.Num -> vmLanguageValue(value.v, depth)
        is Teleported.Real -> vmLanguageValue(value.v, depth)
        is Teleported.Arr -> array(value.v.size) { value.v[it] }
        is Teleported.Obj -> obj(value.v)
        is Boolean -> Teleported.Bool(value)
        is String -> Teleported.Str(value)
        // On JS a fractional Number also satisfies `is Int`; inspect the fraction first.
        is Float, is Double -> {
            val number = (value as Number).toDouble()
            require(number.isFinite()) { "VM number must be finite" }
            if (number % 1.0 == 0.0) {
                require(number in -9_007_199_254_740_991.0..9_007_199_254_740_991.0) { "VM integer exceeds the exact Confix/JavaScript range" }
                Teleported.Num(number.toLong())
            } else Teleported.Real(number)
        }
        is Byte, is Short, is Int, is Long -> {
            val number = (value as Number).toLong()
            require(number in -9_007_199_254_740_991L..9_007_199_254_740_991L) { "VM integer exceeds the exact Confix/JavaScript range" }
            Teleported.Num(number)
        }
        is List<*> -> array(value.size) { value[it] }
        is Array<*> -> array(value.size) { value[it] }
        is Map<*, *> -> obj(value)
        else -> throw IllegalArgumentException("VM value must be Confix data; bytes and opaque host objects are unsupported")
    }
}

fun Teleported.languageValue(): Any? = when (val value = vmLanguageValue(this)) {
    Teleported.Null -> null
    is Teleported.Bool -> value.v
    is Teleported.Num -> value.v
    is Teleported.Real -> value.v
    is Teleported.Str -> value.v
    is Teleported.Arr -> value.v.map { it.languageValue() }
    is Teleported.Obj -> value.v.mapValues { it.value.languageValue() }
    else -> error("Unsupported VM value")
}

/** Function source and typed data travel through eval and call respectively. */
data class VmLanguageProgram(
    val language: VmLanguage,
    val source: String,
    val inputKind: VmValueKind = VmValueKind.JSON,
    val outputKind: VmValueKind = VmValueKind.JSON,
) {
    fun declaration(budget: VmBudget = VmBudget(), name: String = "lcnc"): String {
        require(source.isNotBlank()) { "VM function body is blank" }
        require(source.encodeToByteArray().size <= 65_536) { "VM source exceeds 65536 bytes" }
        return when (language) {
            VmLanguage.JAVASCRIPT -> "function $ROOT(input) {\n$source\n}\n"
            // ProxyObject exposes members, not Python's mapping protocol. Decode the
            // data argument inside the guest so nested values are native dict/list.
            VmLanguage.PYTHON -> "def $ROOT(input):\n    import json\n    input = json.loads(input)\n" +
                source.lineSequence().joinToString("\n") { "    $it" } + "\n"
            // Ruby's JSON library requires native facilities denied by this facet.
            // Copy the typed proxy argument into native containers without requiring a library.
            VmLanguage.RUBY -> """
                def $ROOT(input)
                  convert = ->(value) do
                    if value.nil?
                      nil
                    elsif value.is_a?(Polyglot::ArrayTrait)
                      value.map { |item| convert.call(item) }
                    elsif value.is_a?(Polyglot::StringTrait)
                      value.to_s
                    elsif value.is_a?(Polyglot::ForeignObject)
                      value.instance_variables.to_h { |key| [key.to_s, convert.call(value[key.to_s])] }
                    else
                      value
                    end
                  end
                  input = convert.call(input)
            """.trimIndent() + "\n" + source.lineSequence().joinToString("\n") { "  $it" } + "\nend\n"
            VmLanguage.KOTLIN_COMMON -> {
                language.validate(VmTrust.OWN, budget)
                """
                function $ROOT(input) {
                    return Java.type('borg.trikeshed.graal.subvm.KotlinSource').invoke(${jsonOf(source)}, JSON.stringify(input), ${jsonOf(name)});
                }
                """.trimIndent()
            }
            VmLanguage.JAVA -> {
                language.validate(VmTrust.OWN, budget)
                """
                function $ROOT(input) {
                    return Java.type('borg.trikeshed.graal.subvm.JavaSource').invoke(${jsonOf(source)}, JSON.stringify(input), ${jsonOf(name)});
                }
                """.trimIndent()
            }
            VmLanguage.CLOJURE -> {
                language.validate(VmTrust.OWN, budget)
                """
                function $ROOT(input) {
                    return Java.type('borg.trikeshed.graal.subvm.ClojureSource').invoke(${jsonOf(source)}, JSON.stringify(input), ${jsonOf(name)});
                }
                """.trimIndent()
            }
            VmLanguage.GROOVY -> {
                language.validate(VmTrust.OWN, budget)
                val wall = budget.wallMillis.takeIf { it > 0 } ?: 5_000L
                // Graal remains the invocation/receipt boundary. Groovy compiles the actual
                // operator source; JSON is only the input data crossing, never executable text.
                """
                function $ROOT(input) {
                    var Shell = Java.type('groovy.lang.GroovyShell');
                    var Binding = Java.type('groovy.lang.Binding');
                    var Config = Java.type('org.codehaus.groovy.control.CompilerConfiguration');
                    var Customizer = Java.type('org.codehaus.groovy.control.customizers.ASTTransformationCustomizer');
                    var Timed = Java.type('groovy.transform.TimedInterrupt');
                    var limits = new (Java.type('java.util.HashMap'))();
                    limits.put('value', Java.type('java.lang.Long').valueOf('$wall'));
                    limits.put('unit', Java.type('java.util.concurrent.TimeUnit').MILLISECONDS);
                    var config = new Config();
                    config.addCompilationCustomizers(new Customizer(limits, Timed.class));
                    var binding = new Binding();
                    binding.setVariable('input', new (Java.type('groovy.json.JsonSlurper'))().parseText(JSON.stringify(input)));
                    var shell = new Shell(Shell.class.getClassLoader(), binding, config);
                    try {
                        return shell.evaluate(${jsonOf(source)}, ${jsonOf("$name.groovy")});
                    } finally {
                        shell.getClassLoader().close();
                    }
                }
                """.trimIndent()
            }
        }
    }

    fun evaluate(handle: VmHandle, input: Teleported, name: String, budget: VmBudget = VmBudget()): Teleported {
        require(handle.facet == language.facet) { "VM facet does not match ${language.label}" }
        inputKind.validate(input)
        handle.eval(declaration(budget, name), name)
        val argument = if (language == VmLanguage.PYTHON) Teleported.Str(input.canonical()) else input
        return outputKind.validate(handle.call(ROOT, argument))
    }

    companion object { const val ROOT = "lcnc_polyglot" }
}
