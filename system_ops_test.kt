import borg.trikeshed.userspace.nio.platform.spi.SystemOperations

class CustomSystemOperations : SystemOperations {
    private val properties = mutableMapOf<String, String>()

    override val key: kotlin.coroutines.CoroutineContext.Key<*> get() = SystemOperations.Key
    override fun getenv(name: String, defaultVal: String?): String? = null
    override fun getProperty(name: String, defaultVal: String?): String? = properties[name] ?: defaultVal
    override val homedir: String = "/"
}
