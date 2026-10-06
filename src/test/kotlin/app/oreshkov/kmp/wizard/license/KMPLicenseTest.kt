package app.oreshkov.kmp.wizard.license

import com.intellij.ui.LicensingFacade
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Covers [KMPLicense]'s tri-state mapping on top of [LicenseManager]: which stamp is
 * looked up, and how a missing facade or stamp is reported. The facade is built
 * directly and passed in, so no running IDE is needed.
 */
class KMPLicenseTest {

    @Test fun `an uninitialized facade is unknown, not unlicensed`() {
        assertNull(KMPLicense.licenseState(facade = null) { error("must not verify without a facade") })
    }

    @Test fun `a facade without a stamp for this product is definitively unlicensed`() {
        val facade = facadeWith(mapOf("SOME_OTHER_PRODUCT" to "key:whatever"))
        assertEquals(false, KMPLicense.licenseState(facade) { error("must not verify a stamp that is absent") })
    }

    @Test fun `the product's own stamp is the one verified, and its verdict is returned`() {
        val stamp = "key:our-stamp"
        val facade = facadeWith(mapOf(KMPLicense.PRODUCT_CODE to stamp, "OTHER" to "key:not-ours"))
        val verified = mutableListOf<String>()

        assertEquals(true, KMPLicense.licenseState(facade) { verified += it; true })
        assertEquals(false, KMPLicense.licenseState(facade) { verified += it; false })
        assertEquals(listOf(stamp, stamp), verified)
    }

    @Test fun `by default a stamp goes through real verification and garbage fails closed`() {
        val facade = facadeWith(mapOf(KMPLicense.PRODUCT_CODE to "key:not-a-real-license"))
        assertEquals(false, KMPLicense.licenseState(facade))
    }

    @Test fun `a facade with no stamp map at all is unlicensed`() {
        assertEquals(false, KMPLicense.licenseState(LicensingFacade()) { true })
    }

    private fun facadeWith(stamps: Map<String, String>) = LicensingFacade().apply { confirmationStamps = stamps }
}
