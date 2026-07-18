package app.oreshkov.kmp.wizard.license

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUiKind
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.ui.LicensingFacade

/**
 * Entitlement check for the Pro tier (agent + CI scaffolding).
 *
 * Reads the confirmation stamp from the platform's [LicensingFacade] and delegates
 * cryptographic verification to [LicenseManager].
 */
object KMPLicense {

    /** Marketplace product code; must match plugin.xml's product-descriptor `code`. */
    const val PRODUCT_CODE = "PKMPPROJECTWIZA"

    /**
     * Tri-state license check, mirroring JetBrains' reference contract:
     * - `true`  — a valid, non-expired Pro license is present;
     * - `false` — definitively unlicensed;
     * - `null`  — [LicensingFacade] is not initialized yet, so it cannot be determined.
     */
    fun isLicensed(): Boolean? {
        val facade = LicensingFacade.getInstance() ?: return null
        val stamp = facade.getConfirmationStamp(PRODUCT_CODE) ?: return false
        return LicenseManager.isConfirmationStampValid(stamp)
    }

    /**
     * Gating convenience: `true` only when definitively licensed. The unknown (`null`)
     * state is treated as not-Pro — safe here because the wizard runs well after IDE
     * startup, by which point the facade is initialized.
     */
    fun isPro(): Boolean = isLicensed() == true

    /**
     * Opens the IDE's built-in plugin registration dialog (JetBrains Account / activation
     * code / trial / license server), pre-selecting this product. This is the recommended
     * activation entry point — not an external browser link.
     *
     * Must be called on the EDT (e.g. from a UI action). It opens in the current modality
     * so it appears on top of an open modal dialog such as the New Project wizard — using
     * a non-modal modality here would defer it until the wizard closes.
     *
     * Deliberately passes no `register.message`: the dialog renders that key as an
     * error-styled banner, which fits an interception (expired license, blocked feature)
     * but not a dialog the user explicitly asked for.
     */
    fun requestLicense() {
        val app = ApplicationManager.getApplication()
        if (app.isDispatchThread) {
            showRegisterDialog()
        } else {
            app.invokeLater({ showRegisterDialog() }, ModalityState.current())
        }
    }

    private fun showRegisterDialog() {
        val actionManager = ActionManager.getInstance()
        // "RegisterPlugins" in open-source builds; "Register" in commercial IDE distributions.
        val registerAction = actionManager.getAction("RegisterPlugins")
            ?: actionManager.getAction("Register")
            ?: return

        val dataContext = DataContext { dataId ->
            when (dataId) {
                "register.product-descriptor.code" -> PRODUCT_CODE
                // Run the registration/subscription UI synchronously on the current EDT.
                // Without this the platform's Register action shows its dialog via
                // invokeLater(ModalityState.nonModal()); since the New Project wizard is itself
                // a modal dialog, that runnable is queued behind the wizard's modal event loop
                // and only surfaces once the wizard is dismissed (Cancel/Create). Requesting a
                // direct call makes the dialog appear on top of the wizard immediately. This
                // mirrors the platform's own SubscriptionBannerComponent.
                "register.request.direct.call" -> true
                else -> null
            }
        }
        ActionUtil.performAction(
            registerAction,
            AnActionEvent.createEvent(dataContext, Presentation(), "", ActionUiKind.NONE, null),
        )
    }
}
