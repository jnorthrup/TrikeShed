package borg.trikeshed.spokes

/**
 * Coordinate - uniquely identifies an artifact across .m2/npm/p2p.
 */
data class Coordinate(
    val group: String,
    val artifact: String,
    val version: String,
    val packaging: Packaging = Packaging.JAR,
) {
    val mavenCoord: String
        get() = "$group:$artifact:$version:${packaging.ext}"

    /** Maven layout path used by the VPS server. */
    val mavenPath: String
        get() {
            val gp = group.replace('.', '/')
            return "/$gp/$artifact/$version/$artifact-$version.${packaging.ext}"
        }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Coordinate) return false
        return group == other.group && artifact == other.artifact &&
            version == other.version && packaging == other.packaging
    }
    override fun hashCode(): Int {
        var h = group.hashCode()
        h = 31 * h + artifact.hashCode()
        h = 31 * h + version.hashCode()
        h = 31 * h + packaging.hashCode()
        return h
    }
    override fun toString() = mavenCoord
}

enum class Packaging(val ext: String, val contentType: String) {
    JAR("jar", "application/java-archive"),
    POM("pom", "application/xml"),
    AAR("aar", "application/octet-stream"),
    ZIP("zip", "application/zip"),
    MODULE("module", "application/json"),
    JS("js", "application/javascript"),
    TGZ("tgz", "application/gzip"),
    // Rust/Cargo
    CRATE("crate", "application/gzip"),
    // OCaml/opam
    OCAML("tar.gz", "application/gzip"),
    OPAM("opam", "application/x-yaml"),
}
