package borg.trikeshed.ipns

import borg.trikeshed.lib.toSeries
import kotlin.coroutines.CoroutineContext

/** Platform cryptography supplies bytes; commonMain owns the complete node lifecycle and I/O. */
fun jvmIpnsNode(path: String, context: CoroutineContext,
    bootstrap: String = IpnsDhtAddresses.PUBLIC_BOOTSTRAP,
    limits: IpnsDhtLimits = IpnsDhtLimits(),
    policy: IpnsPublicationPolicy = IpnsPublicationPolicy(),
): IpnsNode {
    val crypto = JvmIpnsCrypto()
    return IpnsNode(path, crypto,
        IpnsDhtAddresses.bootstrap(bootstrap.split(',').toSeries(), limits.allowPrivateAddresses),
        { identity -> JvmLibp2pTls(identity, crypto)::backend }, context, limits, policy)
}
