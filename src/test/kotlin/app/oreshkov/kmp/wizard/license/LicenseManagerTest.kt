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

    // ── Signature verification is the gate (tampered payloads) ────────────────
    //
    // Every other rejection test fails for a reason unrelated to the signature. These
    // carry an authentic certificate and a well-formed structure but bytes that differ
    // from what was signed, so only `sig.verify(...)` returning false can reject them.

    @Test fun `a key whose payload was altered after signing is rejected`() {
        val licenseId = "12345"
        val key = signedKey(
            keyLicenseId = licenseId,
            payloadLicenseId = licenseId,
            // Still carries the right licenseId, so the later payload check would pass.
            carriedPayload = """{"licenseId":"$licenseId","licenseeName":"Mallory"}""",
        )
        assertFalse(LicenseManager.isConfirmationStampValid(key, testRoots))
    }

    @Test fun `a server stamp whose timestamp was altered after signing is rejected`() {
        val now = System.currentTimeMillis()
        val stamp = signedServerStamp(
            expectedMachineId = "machine-1",
            machineId = "machine-1",
            timeStamp = now + 1, // fresh and for this machine — only the signature is wrong
            signedMessage = "$now:machine-1",
        )
        assertFalse(LicenseManager.isConfirmationStampValid(stamp, testRoots))
    }

    @Test fun `a server stamp whose machineId was altered after signing is rejected`() {
        val now = System.currentTimeMillis()
        // expected == carried, so the machine check alone would accept it.
        val stamp = signedServerStamp(
            expectedMachineId = "machine-2",
            machineId = "machine-2",
            timeStamp = now,
            signedMessage = "$now:machine-1",
        )
        assertFalse(LicenseManager.isConfirmationStampValid(stamp, testRoots))
    }

    @Test fun `a server stamp declaring a different allowed algorithm than it was signed with is rejected`() {
        val stamp = signedServerStamp(
            expectedMachineId = "machine-1",
            machineId = "machine-1",
            timeStamp = System.currentTimeMillis(),
            sigType = "SHA512withRSA", // allow-listed, but the bytes are SHA256withRSA
        )
        assertFalse(LicenseManager.isConfirmationStampValid(stamp, testRoots))
    }

    // ── Freshness window boundaries ───────────────────────────────────────────

    @Test fun `a server stamp just inside the one-hour window is accepted, both directions`() {
        val now = System.currentTimeMillis()
        for (offset in listOf(-59L * MINUTE_MS, 59L * MINUTE_MS)) {
            val stamp = signedServerStamp("machine-1", "machine-1", timeStamp = now + offset)
            assertTrue("offset ${offset / MINUTE_MS} min must be accepted", LicenseManager.isConfirmationStampValid(stamp, testRoots))
        }
    }

    @Test fun `a server stamp just outside the one-hour window is rejected, both directions`() {
        val now = System.currentTimeMillis()
        for (offset in listOf(-61L * MINUTE_MS, 61L * MINUTE_MS)) {
            val stamp = signedServerStamp("machine-1", "machine-1", timeStamp = now + offset)
            assertFalse("offset ${offset / MINUTE_MS} min must be rejected", LicenseManager.isConfirmationStampValid(stamp, testRoots))
        }
    }

    // ── More fail-closed stamp shapes ─────────────────────────────────────────

    @Test fun `a server stamp with fewer than six parts is rejected`() {
        val full = signedServerStamp("machine-1", "machine-1", System.currentTimeMillis())
        val withoutCert = full.substringBeforeLast(':')
        assertFalse(LicenseManager.isConfirmationStampValid(withoutCert, testRoots))
    }

    @Test fun `a well-formed server stamp verified against the bundled JetBrains roots is rejected`() {
        val stamp = signedServerStamp("machine-1", "machine-1", System.currentTimeMillis())
        assertFalse(LicenseManager.isConfirmationStampValid(stamp))
    }

    // ── Expired certificates: accepted for keys, rejected for stamps ──────────
    //
    // test-license-expired.p12 holds a leaf whose certificate expired in 2024, issued
    // by a root valid until 2043. Keys validate the chain at the leaf's notBefore
    // (perpetual fallback licenses outlive their certificate); license-server stamps
    // validate at the current date. Flipping either flag fails one of these tests.
    //
    // Regenerate (password "changeit"): `keytool -genkeypair` the root with
    // `-ext bc:c -startdate -3y -validity 7300` and a plain leaf, `-certreq` the leaf,
    // `-gencert` it from the root with `-startdate -2y -validity 30`, then import the
    // reply into the leaf alias.

    @Test fun `a key signed with an expired certificate is still accepted`() {
        val licenseId = "12345"
        val payload = """{"licenseId":"$licenseId","licenseeName":"Test"}""".toByteArray(StandardCharsets.UTF_8)
        val signature = sign("SHA1withRSA", payload, expiredLeafKey)
        val key = "key:$licenseId-${b64(payload)}-${b64(signature)}-${b64(expiredLeafCert.encoded)}"
        assertTrue(LicenseManager.isConfirmationStampValid(key, expiredRootAsTrustAnchor))
    }

    @Test fun `a server stamp signed with an expired certificate is rejected`() {
        val machineId = "machine-1"
        val timeStamp = System.currentTimeMillis()
        val signature = sign("SHA256withRSA", "$timeStamp:$machineId".toByteArray(StandardCharsets.UTF_8), expiredLeafKey)
        val stamp = "stamp:$machineId:$timeStamp:$machineId:SHA256withRSA:${b64(signature)}:${b64(expiredLeafCert.encoded)}"
        assertFalse(LicenseManager.isConfirmationStampValid(stamp, expiredRootAsTrustAnchor))
    }

    @Test fun `the expired fixture really is expired and chains to its still-valid root`() {
        // Guards the two tests above against a regenerated fixture silently losing
        // the property they depend on.
        val now = java.util.Date()
        assertTrue("leaf must be expired", expiredLeafCert.notAfter.before(now))
        assertTrue("root must still be valid", expiredRootCert.notAfter.after(now))
        assertEquals(expiredRootCert.subjectX500Principal, expiredLeafCert.issuerX500Principal)
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

    /**
     * Builds a `key:` stamp `key:<licenseId>-<payload>-<signature>-<cert>` (parts split on '-').
     * [carriedPayload], when given, replaces the signed payload in the key — a tampering
     * case that only signature verification can catch.
     */
    private fun signedKey(keyLicenseId: String, payloadLicenseId: String, carriedPayload: String? = null): String {
        val payload = """{"licenseId":"$payloadLicenseId","licenseeName":"Test"}"""
            .toByteArray(StandardCharsets.UTF_8)
        val signature = sign("SHA1withRSA", payload)
        val carried = carriedPayload?.toByteArray(StandardCharsets.UTF_8) ?: payload
        return "key:$keyLicenseId-${b64(carried)}-${b64(signature)}-${b64(certificate.encoded)}"
    }

    /**
     * Builds a `stamp:` server reply
     * `stamp:<expectedMachineId>:<timeStamp>:<machineId>:<sigType>:<signature>:<cert>`
     * (parts split on ':'). The signed message is `<timeStamp>:<machineId>`.
     * [sigType] is declared in the stamp; the actual signing always uses SHA256withRSA
     * so an allow-list rejection is exercised on otherwise well-formed input.
     * [signedMessage] defaults to what the stamp carries; overriding it models a stamp
     * altered after signing.
     */
    private fun signedServerStamp(
        expectedMachineId: String,
        machineId: String,
        timeStamp: Long,
        sigType: String = "SHA256withRSA",
        signedMessage: String = "$timeStamp:$machineId",
    ): String {
        val signature = sign("SHA256withRSA", signedMessage.toByteArray(StandardCharsets.UTF_8))
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

    // ── Expired-leaf fixture (expired leaf -> valid root) ─────────────────────

    private val expiredKeyStore: KeyStore by lazy {
        KeyStore.getInstance("PKCS12").apply {
            (LicenseManagerTest::class.java.getResourceAsStream(EXPIRED_KEYSTORE_RESOURCE)
                ?: error("Missing test keystore resource: $EXPIRED_KEYSTORE_RESOURCE"))
                .use { load(it, KEYSTORE_PASSWORD) }
        }
    }
    private val expiredLeafKey: PrivateKey by lazy { expiredKeyStore.getKey(EXPIRED_LEAF_ALIAS, KEYSTORE_PASSWORD) as PrivateKey }
    private val expiredLeafCert: X509Certificate by lazy { expiredKeyStore.getCertificate(EXPIRED_LEAF_ALIAS) as X509Certificate }
    private val expiredRootCert: X509Certificate by lazy { expiredKeyStore.getCertificate(EXPIRED_ROOT_ALIAS) as X509Certificate }
    private val expiredRootAsTrustAnchor: List<String> by lazy { listOf(expiredRootCert.toPem()) }

    private fun sign(algorithm: String, data: ByteArray, key: PrivateKey = privateKey): ByteArray =
        Signature.getInstance(algorithm).run {
            initSign(key)
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
        private const val EXPIRED_KEYSTORE_RESOURCE = "/licensing/test-license-expired.p12"
        private const val EXPIRED_LEAF_ALIAS = "testexpiredleaf"
        private const val EXPIRED_ROOT_ALIAS = "testexpiredroot"
        private const val MINUTE_MS = 60L * 1000L
        private val KEYSTORE_PASSWORD = "changeit".toCharArray()
    }
}
