package cbadvanced

import borg.trikeshed.userspace.nio.file.Files
import borg.trikeshed.userspace.nio.file.Path

data class DotenvResolution(
    val resolvedPath: Path,
    val searchedPaths: List<Path>,
)

fun resolveDotenvPath(
    explicitPath: CharSequence? = System.getProperty("cbadvanced.dotenv") ?: System.getenv("CBADVANCED_DOTENV"),
    workingDir: Path = Files.cwd(),
): Path = resolveDotenv(workingDir = workingDir, explicitPath = explicitPath).resolvedPath

fun resolveDotenv(
    workingDir: Path = Files.cwd(),
    explicitPath: CharSequence? = System.getProperty("cbadvanced.dotenv") ?: System.getenv("CBADVANCED_DOTENV"),
): DotenvResolution {
    val searched = mutableListOf<Path>()
    explicitPath?.takeUnless { it.isBlank() }?.let { raw ->
        val candidate = Path(raw)
        searched.add(candidate)
        require(Files.exists(candidate)) { "Explicit dotenv path does not exist: $candidate" }
        return DotenvResolution(candidate, searched)
    }

    val candidates = dotenvCandidates(workingDir)
    searched.addAll(candidates)
    val resolved = candidates.firstOrNull(Files::exists)
        ?: throw IllegalStateException(
            buildString {
                append("No .env found for cbadvanced. Searched:")
                searched.forEach { append("\n - ").append(it) }
            },
        )
    return DotenvResolution(resolved, searched)
}

fun dotenvCandidates(workingDir: Path): List<Path> {
    val normalized = workingDir
    val parent = normalized.parent
    return listOfNotNull(
        normalized.resolve(".env"),
        normalized.resolve("libs").resolve("dreamer-kmm").resolve(".env"),
        normalized.resolveSibling("dreamer-kmm").resolve(".env"),
        normalized.resolveSibling("libs").resolve("dreamer-kmm").resolve(".env"),
        parent?.resolve("dreamer-kmm")?.resolve(".env"),
        parent?.resolve("libs")?.resolve("dreamer-kmm")?.resolve(".env"),
    ).distinct()
}