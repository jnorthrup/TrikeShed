package borg.trikeshed.pointcut

enum class VmFacet(val id: String) {
    JVM("java"),
    GRAAL_JS("js"),
    GRAAL_PYTHON("python"),
    GRAAL_RUBY("ruby"),
    GRAAL_CLOJURE("clojure")
}
