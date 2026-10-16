package borg.trikeshed.relaxfactory

import com.google.web.bindery.requestfactory.vm.impl.Deobfuscator

/** Proxy token metadata loaded by the original JVM RequestFactory client. */
class CouchGwtFactoryDeobfuscatorBuilderLite : Deobfuscator.Builder() {
    init {
        withRawTypeToken(RequestFactorySchema.proxyToken(CouchDocumentProxy::class.java), CouchDocumentProxy::class.java.name)
    }
}
