package borg.trikeshed.graal.subvm

import borg.trikeshed.vm.VmLanguage
import java.lang.reflect.InvocationTargetException
import java.net.URLClassLoader

/** Native OWN-trust DSLs share one context gate; compilers never inherit the host classpath. */
internal object CamelSource {
    const val CLASS = "LcncCamelRoute"
    const val CONTEXT = "LcncCamelContext"

    /** Inspection only: no source compilation, class initialization or Camel context creation. */
    fun checkAvailable(language: VmLanguage, module: String = CamelRuntime.MODULE) {
        require(language in setOf(VmLanguage.JAVA, VmLanguage.GROOVY)) { "Native Camel supports Java and Groovy" }
        check(JavaSource.available()) { "Native Camel source requires a JDK compiler" }
        val jars = classpath(module) + if (language == VmLanguage.GROOVY) classpath("groovy") else emptyList()
        URLClassLoader(jars.map { it.toURI().toURL() }.toTypedArray(), ClassLoader.getPlatformClassLoader()).use { loader ->
            loader.loadClass("org.apache.camel.builder.RouteBuilder")
            loader.loadClass("org.apache.camel.impl.DefaultCamelContext")
            if (language == VmLanguage.GROOVY) loader.loadClass("groovy.lang.GroovyClassLoader")
                .getMethod("parseClass", String::class.java, String::class.java)
        }
    }

    fun classpath(module: String): List<java.io.File> {
        val chain = GuestModules.chain(module).asReversed()
        for (part in chain) {
            check(GuestModules.manifestFile(part) != null) { "Native Camel module '$part' requires a manifest" }
            check(GuestModules.classesDir(part) == null) { "Native Camel module '$part' has unverified classes/" }
            val verification = GuestModules.verify(part)
            check(verification.ok) { "Native Camel module '$part' failed verification: ${verification.problems.joinToString()}" }
        }
        // Compiler search order agrees with the mounted loaders' parent-first delegation.
        return chain.flatMap { GuestModules.jars(it) }
    }

    /** The action must finish Camel shutdown before its compiler and generated classes are released. */
    fun <T> withRoute(language: VmLanguage, body: String, name: String, module: String, loader: ClassLoader,
                      action: (Class<*>, Class<*>) -> T): T {
        require(language in setOf(VmLanguage.JAVA, VmLanguage.GROOVY)) { "Native Camel supports Java and Groovy" }
        require(body.isNotBlank() && body.encodeToByteArray().size <= 65_536) { "Invalid native Camel source size" }
        val jars = classpath(module)
        val context = JavaSource.compile(CONTEXT, contextSource, CONTEXT, 1, jars, loader)
        val prefix = """
            import java.util.*;
            import org.apache.camel.*;
            import org.apache.camel.builder.*;
            import org.apache.camel.model.*;
            public final class $CLASS extends RouteBuilder {
                public final Object input;
                public final String id;
                public final String entry;
                public $CLASS(Object input, String id, String entry) { this.input=input; this.id=id; this.entry=entry; }
                public void configure() throws Exception {
        """.trimIndent() + "\n"
        val source = prefix + body + "\n}\n}\n"
        if (language == VmLanguage.JAVA)
            return action(context, JavaSource.compile(CLASS, source, name, prefix.count { it == '\n' } + 1, jars, loader))
        // A fresh Groovy runtime owns its metaclasses and compilation cache for this invocation.
        return URLClassLoader(classpath("groovy").map { it.toURI().toURL() }.toTypedArray(), loader).use { runtime ->
            val compilerClass = runtime.loadClass("groovy.lang.GroovyClassLoader")
            (compilerClass.getConstructor(ClassLoader::class.java).newInstance(runtime) as URLClassLoader).use { compiler ->
                val authored = try {
                    compilerClass.getMethod("parseClass", String::class.java, String::class.java)
                        .invoke(compiler, source, "$name.groovy") as Class<*>
                } catch (failure: InvocationTargetException) {
                    val cause = failure.cause ?: failure
                    throw IllegalArgumentException("$name.groovy (configure body starts at line ${prefix.count { it == '\n' } + 1}): ${cause.message}", cause)
                }
                action(context, authored)
            }
        }
    }

    private val contextSource = """
                import java.util.*;
                import org.apache.camel.*;
                import org.apache.camel.builder.*;
                import org.apache.camel.model.*;
                public final class $CONTEXT extends org.apache.camel.impl.DefaultCamelContext {
                    private final java.util.function.Consumer<String> admit;
                    public $CONTEXT(java.util.function.Consumer<String> admit) { this.admit = admit; }
                    private String admitted(String uri) {
                        String resolved = resolvePropertyPlaceholders(uri);
                        admit.accept(resolved);
                        return resolved;
                    }
                    @Override protected Endpoint doGetEndpoint(String uri, Map<String,Object> parameters,
                                                               boolean normalized, boolean prototype) {
                        String resolved = admitted(uri);
                        return super.doGetEndpoint(resolved, parameters, normalized && resolved.equals(uri), prototype);
                    }
                    private void errorHandler(ErrorHandlerFactory factory) {
                        if (factory instanceof org.apache.camel.model.errorhandler.DeadLetterChannelDefinition dead)
                            admitted(dead.getDeadLetterUri());
                    }
                    private void visit(ProcessorDefinition<?> node, Set<Object> visited) {
                        if (!visited.add(node)) return;
                        if (node instanceof EndpointRequiredDefinition endpoint) admitted(endpoint.getEndpointUri());
                        if (node instanceof ToDynamicDefinition dynamic)
                            dynamic.allowOptimisedComponents(false).autoStartComponents("false");
                        for (ProcessorDefinition<?> child : node.getOutputs()) visit(child, visited);
                    }
                    public void install(RouteBuilder builder, String entry) throws Exception {
                        RoutesDefinition model = builder.configureRoutes(this);
                        if (model.getRoutes().isEmpty()) throw new IllegalArgumentException("Native Camel DSL declares no routes");
                        Set<Object> visited = Collections.newSetFromMap(new IdentityHashMap<>());
                        boolean hasEntry = false;
                        errorHandler(model.getErrorHandlerFactory());
                        for (RouteDefinition route : model.getRoutes()) {
                            String from = admitted(route.getInput().getEndpointUri());
                            hasEntry |= from.equals(entry);
                            errorHandler(route.getErrorHandlerFactory());
                            visit(route, visited);
                        }
                        for (ProcessorDefinition<?> node : model.getOnExceptions()) visit(node, visited);
                        for (ProcessorDefinition<?> node : model.getIntercepts()) visit(node, visited);
                        for (ProcessorDefinition<?> node : model.getInterceptFroms()) visit(node, visited);
                        for (ProcessorDefinition<?> node : model.getInterceptSendTos()) visit(node, visited);
                        if (!hasEntry) throw new IllegalArgumentException("Native Camel DSL has no declared consumer for " + entry);
                        addRoutes(builder);
                    }
                }
        """.trimIndent()
}
