pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
        maven("https://oss.sonatype.org/content/repositories/snapshots/")
    }
    plugins {
        kotlin("plugin.compose") version "2.4.0"
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_PROJECT)
    repositories {
        mavenCentral()
        google()
        gradlePluginPortal()
        maven("https://oss.sonatype.org/content/repositories/snapshots/")
        maven("https://www.jitpack.io")
        mavenLocal()
    }
}

rootProject.name = "TrikeShed"

// libs/ subprojects were split out to https://github.com/jnorthrup/trikeshed-libs on 2026-07-05.
// TrikeShed's root src/ is self-contained and has zero libs/ imports. To re-attach the libs
// subprojects, either:
//   (a) git submodule add https://github.com/jnorthrup/trikeshed-libs libs
//   (b) check out trikeshed-libs as a sibling and run with --include-build ../trikeshed-libs
// The composite-build include below only activates when libs/ exists locally. When absent,
// TrikeShed builds standalone with no reference to libs/ modules.
val libsDir = rootDir.resolve("libs")
if (libsDir.exists() && libsDir.isDirectory) {
    // Composite include: each direct child with a build.gradle.kts becomes ':libs:<name>'.
    libsDir.listFiles()!!.filter { it.isDirectory }.forEach { sub ->
        if (sub.resolve("build.gradle.kts").exists()) include(":libs:${sub.name}")
    }
}
