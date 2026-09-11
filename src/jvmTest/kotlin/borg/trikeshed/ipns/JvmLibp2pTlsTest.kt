package borg.trikeshed.ipns

import java.io.ByteArrayInputStream
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import kotlin.test.*

class JvmLibp2pTlsTest {
    @Test fun issued_identity_certificate_authenticates_only_its_peer() {
        val crypto = JvmIpnsCrypto()
        val identity = crypto.generate()
        val certificate = JvmLibp2pTls(identity, crypto).certificate
        JvmLibp2pTls.verifyCertificate(certificate, identity.name.multihash, crypto)
        assertFailsWith<IllegalArgumentException> { JvmLibp2pTls.verifyCertificate(certificate, crypto.generate().name.multihash, crypto) }
    }

    @Test fun published_libp2p_ed25519_certificate_vector() {
        // libp2p/specs tls/tls.md, valid Ed25519 identity vector (certificate and peer ID).
        val hex = "308201ae30820156a0030201020204499602d2300a06082a8648ce3d040302302031123010060355040a13096c69627032702e696f310a300806035504051301313020170d3735303130313133303030305a180f34303936303130313133303030305a302031123010060355040a13096c69627032702e696f310a300806035504051301313059301306072a8648ce3d020106082a8648ce3d030107034200040c901d423c831ca85e27c73c263ba132721bb9d7a84c4f0380b2a6756fd601331c8870234dec878504c174144fa4b14b66a651691606d8173e55bd37e381569ea37c307a3078060a2b0601040183a25a0101046a3068042408011220a77f1d92fedb59dddaea5a1c4abd1ac2fbde7d7b879ed364501809923d7c11b90440d90d2769db992d5e6195dbb08e706b6651e024fda6cfb8846694a435519941cac215a8207792e42849cccc6cd8136c6e4bde92a58c5e08cfd4206eb5fe0bf909300a06082a8648ce3d0403020346003043021f50f6b6c52711a881778718238f650c9fb48943ae6ee6d28427dc6071ae55e702203625f116a7a454db9c56986c82a25682f7248ea1cb764d322ea983ed36a31b77"
        val bytes = ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        val certificate = CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(bytes)) as X509Certificate
        JvmLibp2pTls.verifyCertificate(certificate, IpnsName.parse("12D3KooWM6CgA9iBFZmcYAHA6A2qvbAxqfkmrYiRQuz3XEsk4Ksv").multihash, JvmIpnsCrypto())
    }
}
