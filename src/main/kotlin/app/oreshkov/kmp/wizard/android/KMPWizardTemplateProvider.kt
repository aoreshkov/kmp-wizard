package app.oreshkov.kmp.wizard.android

import app.oreshkov.kmp.wizard.KMPWizardBundle
import app.oreshkov.kmp.wizard.license.KMPLicense
import com.android.tools.idea.wizard.template.Category
import com.android.tools.idea.wizard.template.CheckBoxWidget
import com.android.tools.idea.wizard.template.Constraint
import com.android.tools.idea.wizard.template.FormFactor
import com.android.tools.idea.wizard.template.LabelWidget
import com.android.tools.idea.wizard.template.ModuleTemplateData
import com.android.tools.idea.wizard.template.Separator
import com.android.tools.idea.wizard.template.StringParameter
import com.android.tools.idea.wizard.template.StringParameterBuilder
import com.android.tools.idea.wizard.template.Template
import com.android.tools.idea.wizard.template.TemplateConstraint
import com.android.tools.idea.wizard.template.TemplateData
import com.android.tools.idea.wizard.template.TextFieldWidget
import com.android.tools.idea.wizard.template.Thumb
import com.android.tools.idea.wizard.template.UrlLinkWidget
import com.android.tools.idea.wizard.template.WizardTemplateProvider
import com.android.tools.idea.wizard.template.WizardUiContext
import com.android.tools.idea.wizard.template.booleanParameter
import com.android.tools.idea.wizard.template.template

/** Matches the generated project's `minSdk`. */
private const val MIN_API = 24

/** Where the "Pro" checkboxes send users who are not entitled yet. */
private const val MARKETPLACE_URL = "https://plugins.jetbrains.com/plugin/31786-kmp-project-wizard"

/**
 * Stands in for the platform's `stringParameter { }` DSL.
 *
 * That helper is `inline` and its body still calls the pre-`loggable`
 * StringParameterBuilder constructor Studio deprecated ("Specify loggable parameter") —
 * inlining surfaces the deprecation at every call site here with no way to opt out.
 * Building the parameter directly selects the current constructor and pins
 * `loggable = false` (the library default, and the right value regardless: these fields
 * carry user-authored names that must not reach Studio's template usage analytics).
 */
private fun stringParameter(block: StringParameterBuilder.() -> Unit): StringParameter =
    StringParameterBuilder(loggable = false).apply(block).build()

/**
 * Android Studio entry point for the wizard.
 *
 * Studio replaces the platform's New Project dialog with its own, template-driven NPW, so
 * the `newProjectWizard.generator` used in IntelliJ IDEA is never rendered there. This
 * contributes the same wizard as an Android template instead — the approach JetBrains'
 * own Kotlin Multiplatform plugin takes for the identical problem.
 *
 * Registered from `META-INF/app.oreshkov.kmp.wizard-android.xml`, which only loads when
 * `org.jetbrains.android` is present.
 *
 * The two paths cannot look identical: Studio owns its first page, so project name,
 * package name and save location come from Studio's model rather than from widgets here.
 */
class KMPWizardTemplateProvider : WizardTemplateProvider() {

    override fun getTemplates(): List<Template> = listOf(kmpProjectTemplate())

    private fun kmpProjectTemplate(): Template = template {
        name = KMPWizardBundle.message("studio.template.name")
        description = KMPWizardBundle.message("studio.template.description")
        documentationUrl = "https://kmpwizard.oreshkov.app"

        // Category.Other keeps a whole-project generator out of the activity/fragment
        // galleries; FormFactor.Mobile files it under "Phone and Tablet", which is where
        // users looking for a Kotlin Multiplatform project start.
        category = Category.Other
        formFactor = FormFactor.Mobile
        minApi = MIN_API
        screens = listOf(WizardUiContext.NewProject, WizardUiContext.NewProjectExtraDetail)
        constraints = listOf(
            TemplateConstraint.AndroidX,
            TemplateConstraint.Kotlin,
            TemplateConstraint.Material3,
            TemplateConstraint.Compose,
        )
        // The templates ship their own unit and UI test suites; Studio must not scaffold
        // its own on top of them.
        useGenericAndroidTests = false
        useGenericLocalTests = false

        // A missing thumbnail must degrade to the gallery placeholder, never break the
        // New Project dialog. See thumbnailUrl() for why it is not resolved via javaClass.
        thumb = { thumbnailUrl()?.let { url -> Thumb { url } } ?: Thumb.NoThumb }

        // Studio's template DSL offers no custom validator — only the built-in Constraint
        // enum — so these are checked for emptiness only. That is safe because
        // TemplateRenderer's case converters accept any input form (snake_case, camelCase,
        // spaced words, acronyms) by design and normalise it themselves.
        val featureName = stringParameter {
            name = KMPWizardBundle.message("studio.param.feature")
            help = KMPWizardBundle.message("settings.feature.comment")
            default = "note"
            constraints = listOf(Constraint.NONEMPTY)
        }
        val fieldName = stringParameter {
            name = KMPWizardBundle.message("studio.param.field")
            help = KMPWizardBundle.message("settings.field.comment")
            default = "content"
            constraints = listOf(Constraint.NONEMPTY)
        }
        val testValueName = stringParameter {
            name = KMPWizardBundle.message("studio.param.testValue")
            help = KMPWizardBundle.message("settings.testValue.comment")
            default = "Buy groceries"
            constraints = listOf(Constraint.NONEMPTY)
        }

        val includeAndroid = booleanParameter {
            name = KMPWizardBundle.message("platforms.android")
            default = true
        }
        val includeDesktop = booleanParameter {
            name = KMPWizardBundle.message("platforms.desktop")
            default = true
        }
        val includeIos = booleanParameter {
            name = KMPWizardBundle.message("platforms.ios")
            default = true
        }

        // Pro features follow the user's entitlement: pre-checked and editable when
        // licensed, off and greyed out otherwise. `enabled` is re-evaluated by Studio as
        // the form changes, so activating a license without leaving the dialog is picked
        // up. Unlike the IDEA path there is no way to open the registration dialog from
        // here — the template DSL has no action widget — hence the link below.
        val includeAgentConfig = booleanParameter {
            name = KMPWizardBundle.message("pro.agentConfig")
            default = KMPLicense.isPro()
            enabled = { KMPLicense.isPro() }
        }
        val includeCi = booleanParameter {
            name = KMPWizardBundle.message("pro.ci")
            default = KMPLicense.isPro()
            enabled = { KMPLicense.isPro() }
        }

        widgets(
            TextFieldWidget(featureName),
            TextFieldWidget(fieldName),
            TextFieldWidget(testValueName),
            Separator,
            LabelWidget(KMPWizardBundle.message("studio.platforms.hint")),
            CheckBoxWidget(includeAndroid),
            CheckBoxWidget(includeDesktop),
            CheckBoxWidget(includeIos),
            Separator,
            LabelWidget(KMPWizardBundle.message("studio.pro.hint")),
            CheckBoxWidget(includeAgentConfig),
            CheckBoxWidget(includeCi),
            UrlLinkWidget(KMPWizardBundle.message("studio.pro.link"), MARKETPLACE_URL),
        )

        recipe = { data: TemplateData ->
            val moduleData = data as ModuleTemplateData
            // The PROJECT root, not moduleData.rootDir — that one points at the module
            // directory Studio was about to create.
            val rootDir = moduleData.projectTemplateData.rootDir

            // Studio runs every recipe twice; only the second pass may touch the disk.
            if (!isDryRunPass(rootDir)) {
                generateKmpProject(
                    rootDir = rootDir,
                    settings = studioSettings(
                        appName = moduleData.themesData.appName,
                        packageName = moduleData.packageName,
                        featureName = featureName.value,
                        fieldName = fieldName.value,
                        testValueName = testValueName.value,
                        includeAndroid = includeAndroid.value,
                        includeDesktop = includeDesktop.value,
                        includeIos = includeIos.value,
                        includeAgentConfig = includeAgentConfig.value,
                        includeCi = includeCi.value,
                        pro = KMPLicense.isPro(),
                    ),
                )
            }
        }
    }
}
