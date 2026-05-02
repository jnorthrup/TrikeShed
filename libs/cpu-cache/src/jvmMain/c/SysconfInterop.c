#include <jni.h>
#include <unistd.h>

/**
 * Native implementation of sysconf(2) for Kotlin/JVM.
 * 
 * Parameters:
 *   name: POSIX sysconf constant (e.g., _SC_LEVEL1_DCACHE_SIZE = 188)
 * 
 * Returns:
 *   long: The sysconf return value, or -1 on error/indeterminate
 */
JNIEXPORT jlong JNICALL
Java_borg_trikeshed_cpucache_SysconfInterop_sysconf(JNIEnv *env, jobject thiz, jint name) {
    long result = sysconf((int)name);
    return (jlong)result;
}
