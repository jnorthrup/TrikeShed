import borg.trikeshed.userspace.nio.platform.spi.SystemOperations

class MockSystemOperations : SystemOperations {
    private val envs = mutableMapOf<String, String>()

    fun setEnv(key: String, value: String) {
        envs[key] = value
    }

    override val key: kotlin.coroutines.CoroutineContext.Key<*> get() = SystemOperations.Key

    override fun getenv(name: String, defaultVal: String?): String? = envs[name] ?: defaultVal
    override fun getProperty(name: String, defaultVal: String?): String? = null
    override val homedir: String = "/"
}
