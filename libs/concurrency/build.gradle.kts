apply(from = "../../gradle/macros/trikeshed-lib.gradle")

// Add project dependency on libs:miniduck for both main and test source sets so tests can reference RowVec types.
dependencies {
    // Kotlin Multiplatform creates configurations like 'commonMainImplementation' and 'commonTestImplementation'
    add("commonMainImplementation", project(":libs:miniduck"))
    add("commonTestImplementation", project(":libs:miniduck"))
}

// Keep a defensive afterEvaluate reflection attempt for older Gradle/Kotlin setups.
afterEvaluate {
    val kotlinExt = project.extensions.findByName("kotlin")
    if (kotlinExt != null) {
        // configure via reflection to avoid compile-time dependency on kotlin-gradle-plugin
        val klass = kotlinExt::class.java
        try {
            val sourceSetsMethod = klass.getMethod("getSourceSets")
            val sourceSets = sourceSetsMethod.invoke(kotlinExt)
            // sourceSets is of type NamedDomainObjectContainer<*>; call getByName("commonMain")
            val getByName = sourceSets::class.java.getMethod("getByName", String::class.java)
            val commonMain = getByName.invoke(sourceSets, "commonMain")
            val dependenciesMethod = commonMain::class.java.getMethod("getDependencies")
            val deps = dependenciesMethod.invoke(commonMain)
            val addMethod = deps::class.java.getMethod("add", String::class.java, Object::class.java)
            addMethod.invoke(deps, "implementation", project(":libs:miniduck"))
        } catch (ex: Exception) {
            logger.warn("Could not attach libs:miniduck dependency: ${ex.message}")
        }
    } else {
        logger.warn("Kotlin extension not found while configuring libs:miniduck dependency")
    }
}
