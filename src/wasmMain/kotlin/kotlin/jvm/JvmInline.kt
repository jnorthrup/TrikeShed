package kotlin.jvm

/** No-op JvmInline for non-JVM targets. Value classes are already inline. */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.SOURCE)
annotation class JvmInline()
