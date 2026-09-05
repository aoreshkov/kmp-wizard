package app.oreshkov.kmp.wizard

import com.intellij.DynamicBundle
import org.jetbrains.annotations.Nls
import org.jetbrains.annotations.NonNls
import org.jetbrains.annotations.PropertyKey

@NonNls
private const val BUNDLE = "messages.KMPWizardBundle"

/**
 * Localizable, user-facing strings for the wizard.
 *
 * Delegates to a [DynamicBundle] instance rather than inheriting from one: the
 * `DynamicBundle(String)` constructor a subclass needs is deprecated in favour of
 * `DynamicBundle(Class, String)`, which takes the class whose classloader locates
 * the properties file. It carried only `@Obsolete` through 2026.2 and became a real
 * `@Deprecated` in 2026.3, where the Plugin Verifier started reporting it.
 */
object KMPWizardBundle {

    private val INSTANCE = DynamicBundle(KMPWizardBundle::class.java, BUNDLE)

    @Nls
    fun message(@PropertyKey(resourceBundle = BUNDLE) key: String, vararg params: Any): String =
        INSTANCE.getMessage(key, *params)
}
