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
        ivy {
            name = "hermesRequests"
            url = uri("https://files.pythonhosted.org/packages/56/5d/c814546c2333ceea4ba42262d8c4d55763003e767fa169adc693bd524478")
            patternLayout { artifact("[artifact]-[revision]-[classifier].[ext]") }
            metadataSources { artifact() }
            content { includeModule("python.wheels", "requests") }
        }
        ivy {
            name = "hermesCertifi"
            url = uri("https://files.pythonhosted.org/packages/59/8c/57e832b7af6d7c5abe66eb3fbe3a3a32f4d11ea23a1aa7131371035be991")
            patternLayout { artifact("[artifact]-[revision]-[classifier].[ext]") }
            metadataSources { artifact() }
            content { includeModule("python.wheels", "certifi") }
        }
        ivy {
            name = "hermesCharsetNormalizer"
            url = uri("https://files.pythonhosted.org/packages/cc/61/d01fc49b8dea277640b55a9e15960dbca9fdc8c9fde18e572d39c59f4019")
            patternLayout { artifact("[artifact]-[revision]-[classifier].[ext]") }
            metadataSources { artifact() }
            content { includeModule("python.wheels", "charset_normalizer") }
        }
        ivy {
            name = "hermesIdna"
            url = uri("https://files.pythonhosted.org/packages/57/b0/0e52c878c53f245edd3a11020f20979b3f490f245af532c7cae3027754b5")
            patternLayout { artifact("[artifact]-[revision]-[classifier].[ext]") }
            metadataSources { artifact() }
            content { includeModule("python.wheels", "idna") }
        }
        ivy {
            name = "hermesUrllib3"
            url = uri("https://files.pythonhosted.org/packages/7f/3e/5db95bcf282c52709639744ca2a8b149baccf648e39c8cc87553df9eae0c")
            patternLayout { artifact("[artifact]-[revision]-[classifier].[ext]") }
            metadataSources { artifact() }
            content { includeModule("python.wheels", "urllib3") }
        }
        ivy {
            name = "hermesHttpx"
            url = uri("https://files.pythonhosted.org/packages/2a/39/e50c7c3a983047577ee07d2a9e53faf5a69493943ec3f6a384bdc792deb2")
            patternLayout { artifact("[artifact]-[revision]-[classifier].[ext]") }
            metadataSources { artifact() }
            content { includeModule("python.wheels", "httpx") }
        }
        ivy {
            name = "hermesHttpcore"
            url = uri("https://files.pythonhosted.org/packages/7e/f5/f66802a942d491edb555dd61e3a9961140fd64c90bce1eafd741609d334d")
            patternLayout { artifact("[artifact]-[revision]-[classifier].[ext]") }
            metadataSources { artifact() }
            content { includeModule("python.wheels", "httpcore") }
        }
        ivy {
            name = "hermesAnyio"
            url = uri("https://files.pythonhosted.org/packages/da/35/f2287558c17e29fafc8ef3daf819bb9834061cfa43bff8014f7df7f63bdc")
            patternLayout { artifact("[artifact]-[revision]-[classifier].[ext]") }
            metadataSources { artifact() }
            content { includeModule("python.wheels", "anyio") }
        }
        ivy {
            name = "hermesH11"
            url = uri("https://files.pythonhosted.org/packages/04/4b/29cac41a4d98d144bf5f6d33995617b185d14b22401f75ca86f384e87ff1")
            patternLayout { artifact("[artifact]-[revision]-[classifier].[ext]") }
            metadataSources { artifact() }
            content { includeModule("python.wheels", "h11") }
        }
        ivy {
            name = "hermesMarkdownitpy"
            url = uri("https://files.pythonhosted.org/packages/b3/81/4da04ced5a082363ecfa159c010d200ecbd959ae410c10c0264a38cac0f5")
            patternLayout { artifact("[artifact]-[revision]-[classifier].[ext]") }
            metadataSources { artifact() }
            content { includeModule("python.wheels", "markdown_it_py") }
        }
        ivy {
            name = "hermesMdurl"
            url = uri("https://files.pythonhosted.org/packages/b3/38/89ba8ad64ae25be8de66a6d463314cf1eb366222074cfda9ee839c56a4b4")
            patternLayout { artifact("[artifact]-[revision]-[classifier].[ext]") }
            metadataSources { artifact() }
            content { includeModule("python.wheels", "mdurl") }
        }
        ivy {
            name = "hermesPrompttoolkit"
            url = uri("https://files.pythonhosted.org/packages/84/03/0d3ce49e2505ae70cf43bc5bb3033955d2fc9f932163e84dc0779cc47f48")
            patternLayout { artifact("[artifact]-[revision]-[classifier].[ext]") }
            metadataSources { artifact() }
            content { includeModule("python.wheels", "prompt_toolkit") }
        }
        ivy {
            name = "hermesPygments"
            url = uri("https://files.pythonhosted.org/packages/71/46/17f022dd3e953bf20a04a028a21ec746d942f8d2af30fa0f124fa0e6a684")
            patternLayout { artifact("[artifact]-[revision]-[classifier].[ext]") }
            metadataSources { artifact() }
            content { includeModule("python.wheels", "pygments") }
        }
        ivy {
            name = "hermesRich"
            url = uri("https://files.pythonhosted.org/packages/14/25/b208c5683343959b670dc001595f2f3737e051da617f66c31f7c4fa93abc")
            patternLayout { artifact("[artifact]-[revision]-[classifier].[ext]") }
            metadataSources { artifact() }
            content { includeModule("python.wheels", "rich") }
        }
        ivy {
            name = "hermesWcwidth"
            url = uri("https://files.pythonhosted.org/packages/96/42/3e5985a0a7e57de470b320c6d6a1a67c844f6737a587f3d44dd13d1819e7")
            patternLayout { artifact("[artifact]-[revision]-[classifier].[ext]") }
            metadataSources { artifact() }
            content { includeModule("python.wheels", "wcwidth") }
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
