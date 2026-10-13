dependencyResolutionManagement {
    repositories {
        ivy {
            name = "hermesPurePython"
            url = uri("https://files.pythonhosted.org/packages/0f/73/bb1bc2529f852e7bf64a2dec885e89ff9f5cc7bbf6c9340eed30ff2c69c5")
            patternLayout { artifact("[artifact]-[revision]-[classifier].[ext]") }
            metadataSources { artifact() }
            content { includeModule("python.wheels", "ruamel.yaml") }
        }
        ivy {
            name = "hermesDotenv"
            url = uri("https://files.pythonhosted.org/packages/0b/d7/1959b9648791274998a9c3526f6d0ec8fd2233e4d4acce81bbae76b44b2a")
            patternLayout { artifact("[artifact]-[revision]-[classifier].[ext]") }
            metadataSources { artifact() }
            content { includeModule("python.wheels", "python_dotenv") }
        }
        mavenCentral()
        mavenLocal()
        maven("https://oss.sonatype.org/content/repositories/snapshots/")
        maven("https://www.jitpack.io")
    }
}

rootProject.name = "subvm"

// Deliberately NOT a composite build: unlike utils/ingest, this tree does not consume
// TrikeShed and TrikeShed does not consume it. Its only product is jars on disk under
// <module>/lib, which the Oroboros daemon mounts into a guest classloader at runtime.
// Wiring it with includeBuild("../..") would put these coordinates back on TrikeShed's
// classpath, which is the exact thing this directory exists to prevent.
