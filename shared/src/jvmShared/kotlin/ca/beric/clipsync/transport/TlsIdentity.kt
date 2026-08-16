package ca.beric.clipsync.transport

import ca.beric.clipsync.crypto.ClipsyncCrypto
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.Date
import javax.security.auth.x500.X500Principal

/**
 * A per-device self-signed TLS certificate + EC keypair. Peers do not trust a CA;
 * they pin each other by the SHA-256 fingerprint of this certificate, exchanged at
 * pairing time (the LocalSend model). [fingerprint] is what goes into the pairing
 * payload and what the other side checks against the cert presented on connect.
 */
class TlsIdentity(
    val certificate: X509Certificate,
    val privateKey: PrivateKey,
) {
    /** Lowercase hex SHA-256 of the certificate's DER encoding. */
    val fingerprint: String by lazy { sha256Hex(certificate.encoded) }

    /** In-memory PKCS12 keystore for configuring the Ktor TLS server. */
    fun keyStore(alias: String = "clipsync", password: CharArray): KeyStore =
        KeyStore.getInstance("PKCS12").apply {
            load(null, null)
            setKeyEntry(alias, privateKey, password, arrayOf(certificate))
        }

    companion object {
        fun generate(commonName: String = "clipsync"): TlsIdentity {
            val keyPair = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
            val subject = X500Principal("CN=$commonName")
            val notBefore = Date(0) // fixed epoch start; validity window is not trust-relevant (we pin)
            val notAfter = Date(4102444800000L) // year 2100
            val serial = BigInteger(64, java.security.SecureRandom())

            val builder = JcaX509v3CertificateBuilder(
                subject, serial, notBefore, notAfter, subject, keyPair.public,
            )
            val signer = JcaContentSignerBuilder("SHA256withECDSA").build(keyPair.private)
            val cert = JcaX509CertificateConverter().getCertificate(builder.build(signer))
            return TlsIdentity(cert, keyPair.private)
        }

        fun sha256Hex(bytes: ByteArray): String =
            ClipsyncCrypto.toHex(MessageDigest.getInstance("SHA-256").digest(bytes))
    }
}
