package app.oreshkov.kmp.wizard.license

import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import java.security.GeneralSecurityException
import java.security.Signature
import java.security.cert.CertPathBuilder
import java.security.cert.CertPathValidator
import java.security.cert.CertStore
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.cert.CollectionCertStoreParameters
import java.security.cert.PKIXBuilderParameters
import java.security.cert.TrustAnchor
import java.security.cert.X509CertSelector
import java.security.cert.X509Certificate
import java.util.Base64
import kotlin.math.abs

/**
 * Verifies a JetBrains Marketplace license confirmation stamp.
 *
 * Faithful Kotlin port of JetBrains' reference `CheckLicense` verifier
 * (https://github.com/JetBrains/marketplace-makemecoffee-plugin). The two public
 * JetBrains root certificates it trusts (JetProfile CA, License Servers CA) are
 * shipped verbatim as PEM resources under `/licensing/` and validated via PKIX
 * certificate-path building. Any malformed input or verification failure yields
 * `false` (fail closed).
 */
internal object LicenseManager {

    private const val KEY_PREFIX = "key:"
    private const val STAMP_PREFIX = "stamp:"

    /**
     * A license-server reply is only trusted if it is this fresh. The 1-hour window is
     * fixed by the JetBrains reference verifier (interoperability, tolerant of clock
     * skew in both directions via abs()). ACCEPTED RESIDUAL RISK: a captured reply can
     * be replayed within this window — but only on the same machine, because the reply
     * is bound to the machineId. Do not tighten unilaterally.
     */
    private const val TIMESTAMP_VALIDITY_PERIOD_MS = 60L * 60L * 1000L // 1 hour

    /**
     * Defense-in-depth: the `stamp:` payload names its own JCA signature algorithm, so
     * constrain it to the RSA family JetBrains license servers actually issue before
     * instantiating a Signature from untrusted input. Kept deliberately generous — the
     * real gate is the PKIX chain to a pinned JetBrains root, not this list. If a real
     * server ever sends a different algorithm, extend the list rather than removing it.
     */
    private val ALLOWED_STAMP_SIGNATURE_TYPES = setOf("SHA1withRSA", "SHA256withRSA", "SHA512withRSA")

    /**
     * Public JetBrains root certificates, loaded from plugin resources.
     *
     * WATCH ITEM: these two PEMs (`jetprofile-ca.pem`, `license-servers-ca.pem`) are pinned
     * trust anchors. If JetBrains rotates its licensing CAs, the bundled copies must be
     * re-fetched and re-committed, or every license check will fail closed (no chain to a
     * trusted root). They are not auto-updated.
     */
    private val ROOT_CERTIFICATES: List<String> by lazy {
        listOf("/licensing/jetprofile-ca.pem", "/licensing/license-servers-ca.pem")
            .map { path ->
                requireNotNull(LicenseManager::class.java.getResourceAsStream(path)) {
                    "Missing bundled root certificate: $path"
                }.use { it.readBytes().toString(StandardCharsets.UTF_8) }
            }
    }

    /**
     * @return `true` only when [confirmationStamp] is a well-formed, JetBrains-signed,
     *   unexpired license for this product. Handles both stamp formats:
     *   `key:` (JetBrains Account / activation code) and `stamp:` (Floating License Server).
     */
    internal fun isConfirmationStampValid(
        confirmationStamp: String,
        // Trust roots are injectable purely so tests can substitute a synthetic CA; the
        // default is the bundled JetBrains roots, so production behavior is unchanged.
        rootCertificates: List<String> = ROOT_CERTIFICATES,
    ): Boolean = when {
        confirmationStamp.startsWith(KEY_PREFIX) ->
            isKeyValid(confirmationStamp.substring(KEY_PREFIX.length), rootCertificates)
        confirmationStamp.startsWith(STAMP_PREFIX) ->
            isLicenseServerStampValid(confirmationStamp.substring(STAMP_PREFIX.length), rootCertificates)
        else -> false
    }

    private fun isKeyValid(key: String, rootCertificates: List<String>): Boolean {
        val licenseParts = key.split("-")
        if (licenseParts.size != 4) return false // invalid format

        val licenseId = licenseParts[0]
        val licensePartBase64 = licenseParts[1]
        val signatureBase64 = licenseParts[2]
        val certBase64 = licenseParts[3]

        return try {
            // SHA1withRSA is fixed by JetBrains' license *key* format and inherited verbatim
            // from the reference CheckLicense verifier — it is not a free choice. Do not
            // "upgrade" it to a stronger hash here or genuine keys will fail to verify. (The
            // stamp: path below instead reads its signatureType from the payload.)
            // ACCEPTED RESIDUAL RISK: SHA-1 is retired for signatures (NIST), but the
            // exposure is bounded by trust-anchor pinning — a forged signature would still
            // need a certificate chaining to a pinned JetBrains root.
            val sig = Signature.getInstance("SHA1withRSA")
            // checkValidity = false: the key may also be a perpetual fallback license for
            // older IDE versions; here we only require an authentic JetBrains signature.
            sig.initVerify(
                createCertificate(
                    Base64.getMimeDecoder().decode(certBase64.toByteArray(StandardCharsets.UTF_8)),
                    emptyList(),
                    checkValidityAtCurrentDate = false,
                    rootCertificates = rootCertificates,
                )
            )
            val licenseBytes = Base64.getMimeDecoder().decode(licensePartBase64.toByteArray(StandardCharsets.UTF_8))
            sig.update(licenseBytes)
            if (!sig.verify(Base64.getMimeDecoder().decode(signatureBase64.toByteArray(StandardCharsets.UTF_8)))) {
                return false
            }
            // Confirm the signed payload actually carries the licenseId from the key.
            val licenseData = licenseBytes.toString(Charsets.UTF_8)
            licenseData.contains("\"licenseId\":\"$licenseId\"")
        } catch (_: GeneralSecurityException) {
            false // signature/cert failure
        } catch (_: RuntimeException) {
            false // malformed input: Base64 decode, index, number-format
        }
    }

    private fun isLicenseServerStampValid(serverStamp: String, rootCertificates: List<String>): Boolean {
        return try {
            val parts = serverStamp.split(":")
            if (parts.size < 6) return false // invalid format
            val base64 = Base64.getMimeDecoder()

            val expectedMachineId = parts[0]
            val timeStamp = parts[1].toLong()
            val machineId = parts[2]
            val signatureType = parts[3]
            val signatureBytes = base64.decode(parts[4].toByteArray(StandardCharsets.UTF_8))
            val certBytes = base64.decode(parts[5].toByteArray(StandardCharsets.UTF_8))
            val intermediate = parts.drop(6).map { base64.decode(it.toByteArray(StandardCharsets.UTF_8)) }

            if (signatureType !in ALLOWED_STAMP_SIGNATURE_TYPES) return false
            val sig = Signature.getInstance(signatureType)
            // checkValidity = true: an expired license-server certificate cannot be trusted.
            sig.initVerify(createCertificate(certBytes, intermediate, checkValidityAtCurrentDate = true, rootCertificates = rootCertificates))
            sig.update("$timeStamp:$machineId".toByteArray(StandardCharsets.UTF_8))
            if (sig.verify(signatureBytes)) {
                // The reply must be for this machine and be reasonably fresh.
                expectedMachineId == machineId &&
                    abs(System.currentTimeMillis() - timeStamp) < TIMESTAMP_VALIDITY_PERIOD_MS
            } else {
                false
            }
        } catch (_: GeneralSecurityException) {
            false // signature/cert failure
        } catch (_: RuntimeException) {
            false // malformed input: Base64 decode, index, number-format
        }
    }

    private fun createCertificate(
        certBytes: ByteArray,
        intermediateCertsBytes: Collection<ByteArray>,
        checkValidityAtCurrentDate: Boolean,
        rootCertificates: List<String>,
    ): X509Certificate {
        val x509factory = CertificateFactory.getInstance("X.509")
        val cert = x509factory.generateCertificate(ByteArrayInputStream(certBytes)) as X509Certificate

        val allCerts = buildSet<Certificate> {
            add(cert)
            intermediateCertsBytes.mapTo(this) { x509factory.generateCertificate(ByteArrayInputStream(it)) }
        }

        try {
            val selector = X509CertSelector().apply { certificate = cert }

            val trustAnchors = rootCertificates.mapTo(HashSet()) { rc ->
                val root = x509factory.generateCertificate(
                    ByteArrayInputStream(rc.toByteArray(StandardCharsets.UTF_8))
                ) as X509Certificate
                TrustAnchor(root, null)
            }

            val pkixParams = PKIXBuilderParameters(trustAnchors, selector)
            // Revocation disabled to match the JetBrains reference verifier: offline license
            // activation must not depend on CRL/OCSP reachability. This is not a weakening —
            // the path below still has to build and validate up to a pinned JetBrains root,
            // so an untrusted certificate is rejected (fail closed).
            // ACCEPTED RESIDUAL RISK: a JetBrains-revoked but unexpired certificate would
            // still validate. Deliberate — do not enable soft-fail revocation; it adds
            // latency without adding real security here.
            pkixParams.isRevocationEnabled = false
            if (!checkValidityAtCurrentDate) {
                // Validate at the certificate's own start date so the result does not depend
                // on when the check happens (supports perpetual fallback keys).
                pkixParams.date = cert.notBefore
            }
            pkixParams.addCertStore(
                CertStore.getInstance("Collection", CollectionCertStoreParameters(allCerts))
            )

            val path = CertPathBuilder.getInstance("PKIX").build(pkixParams).certPath
            if (path != null) {
                CertPathValidator.getInstance("PKIX").validate(path, pkixParams)
                return cert
            }
        } catch (_: GeneralSecurityException) {
            // PKIX build/validate failure — fall through to the throw below
        }
        // GeneralSecurityException (not a bare Exception) so the callers' fail-closed
        // catch covers an authentic-but-untrusted certificate that can't chain to a root.
        throw GeneralSecurityException("Certificate used to sign the license is not signed by a JetBrains root certificate")
    }
}
