package app.oreshkov.kmp.wizard.license

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Base64

class LicenseManagerTest {

    // ── Fail-closed on malformed input ───────────────────────────────────────

    @Test fun `stamp without a known prefix is rejected`() {
        assertFalse(LicenseManager.isConfirmationStampValid("totally-bogus"))
    }

    @Test fun `key stamp with wrong part count is rejected`() {
        assertFalse(LicenseManager.isConfirmationStampValid("key:only-three-parts"))
    }

    @Test fun `key stamp with unparseable certificate is rejected, not thrown`() {
        assertFalse(LicenseManager.isConfirmationStampValid("key:id-bm90-c2ln-bm90Y2VydA=="))
    }

    @Test fun `license-server stamp with garbage payload is rejected, not thrown`() {
        assertFalse(LicenseManager.isConfirmationStampValid("stamp:a:b:c:d:e:f"))
    }

    @Test fun `empty stamp is rejected`() {
        assertFalse(LicenseManager.isConfirmationStampValid(""))
    }

    // ── Bundled root certificates are shipped and valid ──────────────────────

    @Test fun `both JetBrains root certificates are present and parse as X509`() {
        val factory = CertificateFactory.getInstance("X.509")
        val expectedSubjects = mapOf(
            "/licensing/jetprofile-ca.pem" to "CN=JetProfile CA",
            "/licensing/license-servers-ca.pem" to "CN=License Servers CA",
        )
        for ((path, subject) in expectedSubjects) {
            val stream = LicenseManager::class.java.getResourceAsStream(path)
                ?: error("Missing bundled root certificate resource: $path")
            val cert = stream.use { factory.generateCertificate(it) as X509Certificate }
            assertEquals(subject, cert.subjectX500Principal.name)
        }
    }

    // ── Accept / reject paths against a synthetic test CA ─────────────────────
    //
    // `isConfirmationStampValid` takes the trust roots as an injectable parameter
    // (defaulting to the bundled JetBrains roots). These tests pass a self-signed
    // certificate generated once with `keytool` and committed as a PKCS#12 fixture
    // so the real signature/PKIX/freshness logic runs end to end without needing a
    // JetBrains private key. The certificate doubles as its own trust root.

    @Test fun `a key signed by the trusted cert with a matching licenseId is accepted`() {
        val licenseId = "12345"
        val key = signedKey(keyLicenseId = licenseId, payloadLicenseId = licenseId)
        assertTrue(LicenseManager.isConfirmationStampValid(key, testRoots))
    }

    @Test fun `a key whose payload licenseId differs from the prefix is rejected`() {
        val key = signedKey(keyLicenseId = "12345", payloadLicenseId = "99999")
        assertFalse(LicenseManager.isConfirmationStampValid(key, testRoots))
    }

    @Test fun `a key verified against a different trust root is rejected`() {
        // Same well-formed key, but validated against the real JetBrains roots: the
        // PKIX path can't be built, so it must fail closed even though the signature
        // itself is internally consistent.
        val licenseId = "12345"
        val key = signedKey(keyLicenseId = licenseId, payloadLicenseId = licenseId)
        assertFalse(LicenseManager.isConfirmationStampValid(key))
    }

    @Test fun `a fresh server stamp for this machine is accepted`() {
        val stamp = signedServerStamp(
            expectedMachineId = "machine-1",
            machineId = "machine-1",
            timeStamp = System.currentTimeMillis(),
        )
        assertTrue(LicenseManager.isConfirmationStampValid(stamp, testRoots))
    }

    @Test fun `a server stamp for a different machine is rejected`() {
        val stamp = signedServerStamp(
            expectedMachineId = "machine-1",
            machineId = "machine-2",
            timeStamp = System.currentTimeMillis(),
        )
        assertFalse(LicenseManager.isConfirmationStampValid(stamp, testRoots))
    }

    @Test fun `a server stamp older than the one-hour freshness window is rejected`() {
        val twoHoursAgo = System.currentTimeMillis() - 2L * 60L * 60L * 1000L
        val stamp = signedServerStamp(
            expectedMachineId = "machine-1",
            machineId = "machine-1",
            timeStamp = twoHoursAgo,
        )
        assertFalse(LicenseManager.isConfirmationStampValid(stamp, testRoots))
    }

    @Test fun `a server stamp dated in the future beyond the window is rejected`() {
        // The freshness check uses abs(), so the window must be symmetric: a
        // forward-dated stamp (clock skew or replay-forward) must also fail.
        val twoHoursAhead = System.currentTimeMillis() + 2L * 60L * 60L * 1000L
        val stamp = signedServerStamp(
            expectedMachineId = "machine-1",
            machineId = "machine-1",
            timeStamp = twoHoursAhead,
        )
        assertFalse(LicenseManager.isConfirmationStampValid(stamp, testRoots))
    }

    @Test fun `a server stamp with an unlisted signature algorithm is rejected`() {
        // Well-formed and internally consistent, but the algorithm is outside the
        // RSA-family allow-list — must fail closed before Signature.getInstance.
        val stamp = signedServerStamp(
            expectedMachineId = "machine-1",
            machineId = "machine-1",
            timeStamp = System.currentTimeMillis(),
            sigType = "SHA256withECDSAinP1363Format",
        )
        assertFalse(LicenseManager.isConfirmationStampValid(stamp, testRoots))
    }

    // ── PKIX path building through an intermediate CA ─────────────────────────
    //
    // The chain fixture (test-license-chain.p12, generated once with keytool) holds a
    // leaf entry whose certificate chain is leaf -> intermediate -> root. Only the
    // root is injected as a trust anchor; the intermediate travels inside the stamp
    // (parts[6+]), exercising LicenseManager's `parts.drop(6)` path building.

    @Test fun `a server stamp chaining through an intermediate CA is accepted`() {
        val stamp = chainSignedServerStamp(includeIntermediate = true)
        assertTrue(LicenseManager.isConfirmationStampValid(stamp, chainRootAsTrustAnchor))
    }

    @Test fun `the same chained stamp without its intermediate cannot build a path and is rejected`() {
        // Sanity counter-case: proves the acceptance above really came from the
        // intermediate carried in the stamp, not from some other trust source.
        val stamp = chainSignedServerStamp(includeIntermediate = false)
        assertFalse(LicenseManager.isConfirmationStampValid(stamp, chainRootAsTrustAnchor))
    }

    // ── Fixture + signing helpers ────────────────────────────────────────────

    private val keyStore: KeyStore by lazy {
        KeyStore.getInstance("PKCS12").apply {
            (LicenseManagerTest::class.java.getResourceAsStream(KEYSTORE_RESOURCE)
                ?: error("Missing test keystore resource: $KEYSTORE_RESOURCE"))
                .use { load(it, KEYSTORE_PASSWORD) }
        }
    }
    private val privateKey: PrivateKey by lazy { keyStore.getKey(ALIAS, KEYSTORE_PASSWORD) as PrivateKey }
    private val certificate: X509Certificate by lazy { keyStore.getCertificate(ALIAS) as X509Certificate }
    private val testRoots: List<String> by lazy { listOf(certificate.toPem()) }

    /** Builds a `key:` stamp `key:<licenseId>-<payload>-<signature>-<cert>` (parts split on '-'). */
    private fun signedKey(keyLicenseId: String, payloadLicenseId: String): String {
        val payload = """{"licenseId":"$payloadLicenseId","licenseeName":"Test"}"""
            .toByteArray(StandardCharsets.UTF_8)
        val signature = sign("SHA1withRSA", payload)
        return "key:$keyLicenseId-${b64(payload)}-${b64(signature)}-${b64(certificate.encoded)}"
    }

    /**
     * Builds a `stamp:` server reply
     * `stamp:<expectedMachineId>:<timeStamp>:<machineId>:<sigType>:<signature>:<cert>`
     * (parts split on ':'). The signed message is `<timeStamp>:<machineId>`.
     * [sigType] is declared in the stamp; the actual signing always uses SHA256withRSA
     * so an allow-list rejection is exercised on otherwise well-formed input.
     */
    private fun signedServerStamp(
        expectedMachineId: String,
        machineId: String,
        timeStamp: Long,
        sigType: String = "SHA256withRSA",
    ): String {
        val signature = sign("SHA256withRSA", "$timeStamp:$machineId".toByteArray(StandardCharsets.UTF_8))
        return "stamp:$expectedMachineId:$timeStamp:$machineId:$sigType:${b64(signature)}:${b64(certificate.encoded)}"
    }

    // ── Chain fixture (leaf -> intermediate -> root) ─────────────────────────

    private val chainKeyStore: KeyStore by lazy {
        KeyStore.getInstance("PKCS12").apply {
            (LicenseManagerTest::class.java.getResourceAsStream(CHAIN_KEYSTORE_RESOURCE)
                ?: error("Missing test keystore resource: $CHAIN_KEYSTORE_RESOURCE"))
                .use { load(it, KEYSTORE_PASSWORD) }
        }
    }
    private val chainLeafKey: PrivateKey by lazy { chainKeyStore.getKey(CHAIN_LEAF_ALIAS, KEYSTORE_PASSWORD) as PrivateKey }
    private val chainCerts: List<X509Certificate> by lazy {
        chainKeyStore.getCertificateChain(CHAIN_LEAF_ALIAS).map { it as X509Certificate }
    }
    private val chainRootAsTrustAnchor: List<String> by lazy { listOf(chainCerts.last().toPem()) }

    /** A fresh, well-formed stamp signed by the chain fixture's leaf key. */
    private fun chainSignedServerStamp(includeIntermediate: Boolean): String {
        val machineId = "machine-1"
        val timeStamp = System.currentTimeMillis()
        val signature = Signature.getInstance("SHA256withRSA").run {
            initSign(chainLeafKey)
            update("$timeStamp:$machineId".toByteArray(StandardCharsets.UTF_8))
            sign()
        }
        val leaf = chainCerts.first()
        val intermediate = chainCerts[1]
        return buildString {
            append("stamp:$machineId:$timeStamp:$machineId:SHA256withRSA:${b64(signature)}:${b64(leaf.encoded)}")
            if (includeIntermediate) append(":${b64(intermediate.encoded)}")
        }
    }

    private fun sign(algorithm: String, data: ByteArray): ByteArray =
        Signature.getInstance(algorithm).run {
            initSign(privateKey)
            update(data)
            sign()
        }

    private fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    private fun X509Certificate.toPem(): String =
        "-----BEGIN CERTIFICATE-----\n" +
            Base64.getMimeEncoder(64, "\n".toByteArray(StandardCharsets.UTF_8)).encodeToString(encoded) +
            "\n-----END CERTIFICATE-----\n"

    companion object {
        private const val KEYSTORE_RESOURCE = "/licensing/test-license-keystore.p12"
        private const val ALIAS = "testlicense"
        private const val CHAIN_KEYSTORE_RESOURCE = "/licensing/test-license-chain.p12"
        private const val CHAIN_LEAF_ALIAS = "testchainleaf"
        private val KEYSTORE_PASSWORD = "changeit".toCharArray()
    }
}
