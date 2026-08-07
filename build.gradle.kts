import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.zip.ZipInputStream

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.intellij.platform)
    alias(libs.plugins.changelog)
}

// ═══════════════════════════════════════════════════════════════════════════
// Project coordinates
// ═══════════════════════════════════════════════════════════════════════════

group   = providers.gradleProperty("group").get()
version = providers.gradleProperty("pluginVersion").get()

// Secrets (e.g. the Marketplace publish token) live in the git-ignored
// local.properties — never in the committed gradle.properties, which would leak the
// token into version control. Read through providers.fileContents so the file is a
// tracked configuration-cache input (an absent file yields a value-less provider,
// letting the orElse chain in publishing { } fall through cleanly).
val localPropertiesPublishToken: Provider<String> =
    providers.fileContents(layout.projectDirectory.file("local.properties")).asText
        .map { text ->
            text.lineSequence()
                .firstOrNull { it.startsWith("PUBLISH_TOKEN=") }
                ?.substringAfter("=")
                ?.trim()
        }

// ═══════════════════════════════════════════════════════════════════════════
// Repositories
// ═══════════════════════════════════════════════════════════════════════════

repositories {
    mavenCentral()
    // kmp-ledger source archive, downloaded straight from the GitHub tag archive.
    // exclusiveContent keeps this repo from being probed for anything else AND keeps
    // mavenCentral/the IntelliJ repos from being probed for the ledger.
    // NOTE: GitHub's auto-generated tag archives are not byte-stable, so no checksum
    // verification for this artifact. If ledger CI ever uploads an immutable source
    // zip as a release asset, switch the pattern to
    // [organisation]/[module]/releases/download/v[revision]/... and pin checksums.
    exclusiveContent {
        forRepository {
            ivy {
                name = "kmpLedgerGitHub"
                url = uri("https://github.com/")
                patternLayout { artifact("[organisation]/[module]/archive/refs/tags/v[revision].[ext]") }
                // No ivy.xml on GitHub — the artifact itself is the only metadata.
                metadataSources { artifact() }
            }
        }
        filter { includeModule("aoreshkov", "kmp-ledger") }
    }
    intellijPlatform {
        defaultRepositories()
    }
}

// Resolves the pinned kmp-ledger source archive; consumed by extractLedger.
// Role-locked configuration pair (the current Gradle idiom, replacing the legacy
// isCanBe* flags): dependencies are declared on the `ledger` dependency scope,
// resolution happens through `ledgerZip`.
val ledger = configurations.dependencyScope("ledger")
val ledgerZip = configurations.resolvable("ledgerZip") {
    extendsFrom(ledger.get())
    isTransitive = false
}

// ═══════════════════════════════════════════════════════════════════════════
// Dependencies
// ═══════════════════════════════════════════════════════════════════════════

dependencies {
    intellijPlatform {
        // Target IDE
        intellijIdea(libs.versions.intellij.ide.get())

        // Required for New Project Wizard extension points
        bundledPlugin("org.jetbrains.kotlin")
        bundledPlugin("com.intellij.gradle")

        // Android Studio replaces the platform's New Project dialog with its own NPW,
        // which never enumerates com.intellij.newProjectWizard.generator — so the
        // Studio-side entry point has to be the Android plugin's
        // com.android.tools.idea.wizard.template EP instead (see
        // META-INF/app.oreshkov.kmp.wizard-android.xml). plugin(), not bundledPlugin():
        // the unified IDEA distribution no longer bundles org.jetbrains.android.
        // The dependency is optional at runtime, so IDEs without it still load the plugin.
        plugin("org.jetbrains.android", libs.versions.android.plugin.get())

        pluginVerifier()
        zipSigner()
        // JUnit 4 on the Platform framework is a deliberate choice, not drift:
        // BasePlatformTestCase is JUnit3/4-anchored, and the JUnit5 framework type still
        // has rough edges (IJPL-159134, IJPL-157292). If tests ever fail with
        // NoClassDefFoundError: org/opentest4j/..., add an explicit
        // testImplementation("org.opentest4j:opentest4j:<latest>") as the known remedy.
        testFramework(TestFrameworkType.Platform)
    }

    testImplementation(libs.junit)

    "ledger"("aoreshkov:kmp-ledger:${providers.gradleProperty("ledgerVersion").get()}@zip")
}

// ═══════════════════════════════════════════════════════════════════════════
// IntelliJ Platform plugin configuration
// ═══════════════════════════════════════════════════════════════════════════

changelog {
    groups.set(listOf("Added", "Changed", "Deprecated", "Removed", "Fixed", "Security"))
}

val pluginDescription = """
    <p><b>KMP Project Wizard</b> generates a <b>Kotlin Multiplatform</b> project
    straight from <b>File → New Project</b> in <b>IntelliJ IDEA</b> and <b>Android Studio</b>.</p>

    <p>Instead of hand-wiring Gradle, DI, navigation and a database, it generates a clean,
    modular, test-covered KMP project that targets
    <b>Android, Desktop (JVM) and iOS</b> from a single dialog.</p>

    <p><b>What you get out of the box</b></p>
    <ul>
      <li><b>Compose Multiplatform</b> UI shared across platforms, with type-safe <b>Navigation 3</b>.</li>
      <li><b>Room 3</b> (SQLite) persistence, KMP-ready.</li>
      <li><b>Koin</b> dependency injection with annotation processing (KSP).</li>
      <li><b>Clean Architecture</b>: clear Domain / Data / UI layers and a feature API/impl split.</li>
      <li><b>Gradle convention plugins</b> + version catalog for tidy, scalable builds.</li>
      <li><b>Coroutines &amp; Flow</b>, plus a <b>full unit &amp; UI test suite</b> in every generated project.</li>
    </ul>

    <p><b>How it works</b></p>
    <ul>
      <li>Pick your targets and your feature &amp; entity names — the wizard generates the rest.</li>
      <li>Compatible with the latest IntelliJ IDEA and Android Studio (K2 Kotlin compiler).</li>
      <li>Defaults mirror a real, open-source reference app.</li>
    </ul>

    <p><b>Free &amp; Pro:</b> core templates for Android, iOS and Desktop are <b>free</b>.
    A <b>Pro</b> tier adds Claude Code agent config and GitHub Actions CI scaffolding to the generated project.</p>

    <p><b>Free vs Pro</b></p>
    <table>
      <tr><th>Feature</th><th>Free</th><th>Pro</th></tr>
      <tr><td>KMP project generation (Android, Desktop, iOS)</td><td>✓</td><td>✓</td></tr>
      <tr><td>Compose Multiplatform UI, Navigation 3, Room 3, Koin, Clean Architecture</td><td>✓</td><td>✓</td></tr>
      <tr><td>Convention plugins, version catalog, unit &amp; UI test suite</td><td>✓</td><td>✓</td></tr>
      <tr><td>Claude Code agent config (<code>CLAUDE.md</code>, <code>.claude/</code>)</td><td>—</td><td>✓</td></tr>
      <tr><td>GitHub Actions CI scaffolding (<code>.github/</code>)</td><td>—</td><td>✓</td></tr>
    </table>
    <p>Pro is a one-time purchase. Personal and organization licenses available.</p>

    <p><b>Requirements:</b> IntelliJ IDEA or Android Studio (2026.1+). Generated projects
    build with Gradle and run on Android (SDK 24+), Desktop (JVM) and iOS.</p>

    <p><b>Resources:</b> the plugin is open source (MIT) —
    <a href="https://github.com/aoreshkov/kmp-wizard">source code</a> ·
    <a href="https://github.com/aoreshkov/kmp-wizard/issues">issue tracker</a> ·
    <a href="https://kmpwizard.oreshkov.app">documentation</a>.
    Templates mirror the open-source
    <a href="https://github.com/aoreshkov/kmp-ledger">kmp-ledger</a> reference app.</p>
""".trimIndent()

intellijPlatform {
    pluginConfiguration {
        id          = providers.gradleProperty("pluginId").get()
        name        = "KMP Project Wizard"
        version     = project.version.toString()
        description = pluginDescription
        // Intentionally overrides the IntelliJ Platform Gradle Plugin's built-in
        // changelog auto-wiring (available since IPGP 2.15) so the rendered notes are
        // explicit here: current-version section only, no header, no empty sections.
        changeNotes = provider {
            changelog.renderItem(
                changelog.get(project.version.toString())
                    .withHeader(false)
                    .withEmptySections(false),
                org.jetbrains.changelog.Changelog.OutputType.HTML
            )
        }

        ideaVersion {
            sinceBuild = libs.versions.intellij.since.build.get()
            // Open upper bound: opt out of an until-build so the plugin isn't pinned to a
            // maximum IDE version. Confirmed Compatible by the Plugin Verifier (run
            // `./gradlew verifyPlugin`); revisit only if a future platform breaks API compat.
            untilBuild = provider { null }
        }

        vendor {
            name  = providers.gradleProperty("vendorName").get()
            email = providers.gradleProperty("vendorEmail").get()
            url   = providers.gradleProperty("vendorUrl").get()
        }
    }

    // No `signing { }` block on purpose: author-side plugin signing is deliberately
    // unused — JetBrains Marketplace does not support signature verification (staff
    // statement, 2026-05: https://platform.jetbrains.com/t/-/2584), so signPlugin would
    // sign something nothing verifies. All releases ship unsigned; revisit if JetBrains
    // ever enables verification. (The plugin would still pick up the standard
    // CERTIFICATE_CHAIN/PRIVATE_KEY/PRIVATE_KEY_PASSWORD env vars if ever set.)

    publishing {
        // Resolution order: env var → -PPUBLISH_TOKEN / ~/.gradle/gradle.properties →
        // git-ignored local.properties. Never put PUBLISH_TOKEN in the committed
        // gradle.properties — the gradleProperty fallback would happily read it from
        // there and the token would be in version control.
        token = providers.environmentVariable("PUBLISH_TOKEN")
            .orElse(providers.gradleProperty("PUBLISH_TOKEN"))
            .orElse(localPropertiesPublishToken)
    }

    pluginVerification {
        ides {
            recommended()
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// Template generation
// ═══════════════════════════════════════════════════════════════════════════

/**
 * Extracts the pinned kmp-ledger GitHub source archive, stripping the single
 * kmp-ledger-<ver>/ root directory the archive wraps everything in.
 *
 * Deliberately uses plain java.util.zip instead of a Sync/Copy over zipTree:
 * Gradle's copy infrastructure applies Ant *default excludes* (**&#47;.gitignore,
 * **&#47;.DS_Store, ...), which would silently drop files the template pipeline
 * must see — the ledger's root .gitignore in particular.
 */
abstract class ExtractLedgerTask : DefaultTask() {

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val ledgerArchive: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun extract() {
        val outDir = outputDir.get().asFile
        if (outDir.exists()) outDir.deleteRecursively()
        outDir.mkdirs()

        ZipInputStream(ledgerArchive.singleFile.inputStream().buffered()).use { zip ->
            generateSequence { zip.nextEntry }
                .filterNot { it.isDirectory }
                .forEach { entry ->
                    // Strip the root dir generically rather than hardcoding its
                    // name (GitHub trims the leading 'v' from version-like tags).
                    val relative = entry.name.substringAfter('/', "")
                    if (relative.isEmpty()) return@forEach
                    val dest = outDir.resolve(relative)
                    // zip-slip guard
                    require(dest.canonicalPath.startsWith(outDir.canonicalPath + File.separator)) {
                        "Archive entry escapes the output directory: ${entry.name}"
                    }
                    dest.parentFile.mkdirs()
                    dest.outputStream().use { zip.copyTo(it) }
                }
        }
    }
}

val extractLedger = tasks.register<ExtractLedgerTask>("extractLedger") {
    group       = "templates"
    description = "Extracts the pinned kmp-ledger GitHub source archive into build/ledger-src."
    ledgerArchive.from(ledgerZip)
    outputDir.set(layout.buildDirectory.dir("ledger-src"))
}

/**
 * Directories (relative to any point in the tree) that are never copied.
 */
val templateExcludedDirs = setOf(
    ".git", ".gradle", ".idea",
    "build", ".kotlin", ".DS_Store",
    ".artifacts", "art", "assets"
)

val templateExcludedFiles = setOf(
    "README.md",
    "CHANGELOG.md",
    "AGENT.md",
    "PLAN.md",
    "LICENSE",
    "local.properties",
    "settings.local.json",
    "CODEOWNERS",
    "CONTRIBUTING.md",
    "CODE_OF_CONDUCT.md",
    "FUNDING.yml"
)

/**
 * File-name suffixes never copied. BCV ABI dumps (*.api, *.klib.api) are
 * compiler-output snapshots of the ledger's code — substituted copies cannot
 * match what apiDump produces in a generated project (compiler-generated
 * symbol names, declaration sort order, and Compose lambda keys all change
 * with the user's names), so the wizard runs apiDump after the first sync
 * instead of shipping dumps.
 */
val templateExcludedFileSuffixes = setOf(".api")

/**
 * Extensions treated as binary — copied byte-for-byte, no substitution.
 */
val templateBinaryExtensions = setOf(
    "png", "webp", "jpg", "jpeg", "gif",
    "jar", "zip", "keystore", "ico", "icns"
)

/**
 * Ordered substitution table: (literal → placeholder).
 *
 * Rules:
 *  - More-specific strings MUST precede less-specific ones that overlap.
 *  - Both dot-form (package) and slash-form (directory path) are listed
 *    wherever a name appears in both roles.
 *  - "posting" is fully parameterised at three levels:
 *      PascalCase  → {{FEATURE_NAME}}          (class / type names)
 *      lower_snake → {{FEATURE_NAME_LOWER}}     (module dirs, NavKey files, DB columns …)
 *      camelCase   → {{FEATURE_NAME_CAMEL}}     (variable / function names)
 */
val templateSubstitutions = listOf(

    // ── Package & path  ────────────────────────────────────────────────────
    // Full dot-notation first, then slash-notation, then bare segments.
    "app.oreshkov.ledger"           to "{{PACKAGE_NAME}}",
    "app/oreshkov/ledger"           to "{{PACKAGE_PATH}}",

    // ── App identity  ──────────────────────────────────────────────────────
    // Compose Multiplatform's generated Res class lands in
    // "{group}.{module}.generated.resources", and Gradle derives a subproject's
    // implicit group from rootProject.name — which the templates set to {{APP_NAME}}
    // (PascalCase). CMP lowercases it and runs asUnderscoredIdentifier() over it, so the
    // package root is the FLAT lowercase app name, never the snake_case one that the
    // bare "ledger" rule below would produce.
    //
    // Keyed on the package roots rather than on "import ledger": these fire wherever the
    // package is written — imports, fully-qualified references, KDoc, Kover/ProGuard
    // filters — not only on Kotlin import syntax. ":core" and ":feature" are the ledger's
    // only module groups; a new top-level group holding resources would need a line here.
    // Safe to key on the bare "ledger." prefix because the two package rules above have
    // already rewritten every app.oreshkov.ledger.* occurrence to {{PACKAGE_NAME}}.*.
    "ledger.core."                  to "{{APP_NAME_LOWER_FLAT}}.core.",
    "ledger.feature."               to "{{APP_NAME_LOWER_FLAT}}.feature.",
    "Ledger"                        to "{{APP_NAME}}",
    "ledger"                        to "{{APP_NAME_LOWER}}",   // e.g. "ledger.db", module ids

    // ── Feature name — PascalCase  ─────────────────────────────────────────
    // Must come before the lowercase variants so "Posting" isn't caught by
    // the lowercase rule first in mixed-case contexts.
    "POSTING"                       to "{{FEATURE_NAME_UPPER}}",
    "Posting"                       to "{{FEATURE_NAME}}",

    // ── Feature name — camelCase  ──────────────────────────────────────────
    // "posting" with a lowercase 'p' used as a variable/function name prefix,
    // e.g. postingNavigationModule, postingId.
    // Listed before the plain lowercase so the camel-prefix form is matched
    // while the plain form still applies to standalone occurrences.
    "postingId"                     to "{{FEATURE_NAME_CAMEL}}Id",
    "postingNavigation"             to "{{FEATURE_NAME_CAMEL}}Navigation",
    "postingDetails"                to "{{FEATURE_NAME_CAMEL}}Details",
    "postingEdit"                   to "{{FEATURE_NAME_CAMEL}}Edit",
    "postingList"                   to "{{FEATURE_NAME_CAMEL}}List",

    // ── Feature name — lower_snake  ────────────────────────────────────────
    "posting"                       to "{{FEATURE_NAME_LOWER}}",

    // ── Entity Fields ──────────────────────────────────────────────────────
    "NARRATIVE"                     to "{{FIELD_NAME_UPPER}}",
    "Narrative"                     to "{{FIELD_NAME_PASCAL}}",
    "narrative"                     to "{{FIELD_NAME}}",

    "Groceries"                     to "{{TEST_VALUE_NAME}}",

    // Directory / module path segment (slash-form of the feature name).
    // Appears in Gradle include() strings, source-set paths, etc.
    // e.g.  "feature/posting/impl"  →  "feature/{{FEATURE_NAME_LOWER}}/impl"
    // Covered automatically because we apply substitutions to the relative
    // path string as well as file contents — no extra entry needed here.
)

// ── Task ─────────────────────────────────────────────────────────────────────

/**
 * Generates the template resources from the extracted kmp-ledger source: copies
 * every file, replacing fixed strings with placeholders in both the relative
 * path and (for text files) the contents, and writes MANIFEST.txt.
 *
 * Configuration-cache compatible: the action only reads the task's own
 * declared properties.
 */
abstract class GenerateTemplatesTask : DefaultTask() {

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val ledgerDir: DirectoryProperty

    /** Ordered (literal → placeholder) pairs — more-specific literals first. */
    @get:Input
    abstract val substitutions: ListProperty<Pair<String, String>>

    @get:Input
    abstract val excludedDirs: SetProperty<String>

    @get:Input
    abstract val excludedFiles: SetProperty<String>

    @get:Input
    abstract val excludedFileSuffixes: SetProperty<String>

    @get:Input
    abstract val binaryExtensions: SetProperty<String>

    @get:OutputDirectory
    abstract val templatesDir: DirectoryProperty

    @TaskAction
    fun generate() {
        val source     = ledgerDir.get().asFile
        val outputDir  = templatesDir.get().asFile
        val subs       = substitutions.get()
        val skipDirs   = excludedDirs.get()
        val skipFiles  = excludedFiles.get()
        val skipSuffixes = excludedFileSuffixes.get()
        val binaryExts = binaryExtensions.get()

        fun String.applySubstitutions(): String =
            subs.fold(this) { acc, (literal, placeholder) -> acc.replace(literal, placeholder) }

        // Exclusion is decided on path segments *relative to the source root* —
        // the extracted archive lives under build/ledger-src, so a walk up the
        // absolute path would false-positive on the "build" segment.
        fun File.isExcluded(): Boolean =
            relativeTo(source).invariantSeparatorsPath.split('/').any { it in skipDirs }

        fun File.isBinary() = extension.lowercase() in binaryExts

        // Start clean so renamed/deleted files in the source don't linger.
        if (outputDir.exists()) outputDir.deleteRecursively()
        outputDir.mkdirs()

        logger.lifecycle("Generating templates from ${source.absolutePath}...")

        data class Stats(var text: Int = 0, var modified: Int = 0, var binary: Int = 0, var fallback: Int = 0)
        val stats = Stats()

        val allTemplatePaths = mutableListOf<String>()

        source.walkTopDown()
            .onEnter { dir -> !dir.isExcluded() }
            .filter  {
                it.isFile && !it.isExcluded() && it.name !in skipFiles &&
                    skipSuffixes.none { suffix -> it.name.endsWith(suffix) }
            }
            .forEach { sourceFile ->

                // Apply substitutions to the path itself so that directories
                // and files named after the package/app/feature are renamed.
                // e.g.  .../app/oreshkov/ledger/core/posting/…
                //   →   .../{{PACKAGE_PATH}}/core/{{FEATURE_NAME_LOWER}}/…
                val relativePath = sourceFile
                    .relativeTo(source)
                    .invariantSeparatorsPath
                    .applySubstitutions()

                // Special handling for .gitignore to ensure it's not hidden/ignored by Gradle
                val templateFileName = if (relativePath == ".gitignore") "gitignore.txt" else relativePath
                allTemplatePaths.add(templateFileName.replace(File.separatorChar, '/'))

                val destFile = outputDir.resolve(templateFileName)
                destFile.parentFile.mkdirs()

                when {
                    sourceFile.isBinary() -> {
                        sourceFile.copyTo(destFile, overwrite = true)
                        stats.binary++
                        logger.debug("  [binary]          $relativePath")
                    }
                    else -> {
                        // Deterministic text detection, mirroring TemplateRenderer's runtime
                        // heuristic: a NUL byte means binary, and decoding must be strict —
                        // File.readText would silently *replace* malformed bytes with U+FFFD
                        // and corrupt the generated template instead of falling back.
                        val bytes = sourceFile.readBytes()
                        val original = if (bytes.contains(0)) null else try {
                            Charsets.UTF_8.newDecoder()
                                .onMalformedInput(CodingErrorAction.REPORT)
                                .onUnmappableCharacter(CodingErrorAction.REPORT)
                                .decode(ByteBuffer.wrap(bytes))
                                .toString()
                        } catch (_: CharacterCodingException) {
                            null
                        }
                        if (original == null) {
                            // Not valid UTF-8 text (rare) — copy raw and warn.
                            destFile.writeBytes(bytes)
                            stats.fallback++
                            logger.warn("  [fallback binary] $relativePath")
                        } else {
                            val substituted = original.applySubstitutions()
                            destFile.writeText(substituted, Charsets.UTF_8)
                            stats.text++
                            if (original != substituted) {
                                stats.modified++
                                logger.info("  [text, modified]  $relativePath")
                            } else {
                                logger.debug("  [text, unchanged] $relativePath")
                            }
                        }
                    }
                }

                // No executable-bit handling here: templates ship inside the plugin
                // jar, which cannot carry POSIX modes — TemplateRenderer restores
                // gradlew's exec bit at render time.
            }

        // Fail fast on names that Gradle/Ant *default excludes* would silently drop
        // from processResources/jar while MANIFEST.txt still lists them — the
        // manifest-driven runtime would then fail with "Template resource not found".
        val antDefaultExcludedNames =
            setOf(".gitignore", ".gitattributes", ".gitmodules", ".DS_Store", ".cvsignore")
        val clashes = allTemplatePaths.filter { path ->
            path.split('/').any { it in antDefaultExcludedNames }
        }
        require(clashes.isEmpty()) {
            "Template paths would be silently dropped by Gradle's default excludes: " +
            "$clashes — add a rename rule (like .gitignore → gitignore.txt)."
        }

        outputDir.resolve("MANIFEST.txt")
            .writeText(allTemplatePaths.sorted().joinToString("\n"))

        // The forward substitution table, shipped alongside MANIFEST.txt so the test
        // suite can assert the reverse map (TemplateRenderer.buildSubstitutions)
        // reproduces every forward literal — value symmetry, not just key symmetry.
        // Not listed in MANIFEST.txt, so the renderer never emits it.
        outputDir.resolve("SUBSTITUTIONS.txt")
            .writeText(subs.joinToString("\n") { (literal, placeholder) -> "$literal\t$placeholder" })

        logger.lifecycle(
            "generateTemplates done — " +
                    "text: ${stats.text} (${stats.modified} modified)  " +
                    "binary: ${stats.binary}  fallback: ${stats.fallback}  " +
                    "manifest: ${allTemplatePaths.size} entries"
        )
    }
}

val generateTemplates = tasks.register<GenerateTemplatesTask>("generateTemplates") {
    group       = "templates"
    description = "Generates template resources from the extracted kmp-ledger source, " +
                  "replacing fixed strings with placeholders."
    ledgerDir.set(extractLedger.flatMap { it.outputDir })
    substitutions.set(templateSubstitutions)
    excludedDirs.set(templateExcludedDirs)
    excludedFiles.set(templateExcludedFiles)
    excludedFileSuffixes.set(templateExcludedFileSuffixes)
    binaryExtensions.set(templateBinaryExtensions)
    templatesDir.set(layout.buildDirectory.dir("generated-resources/templates"))
}

// Templates are generated into the build directory (never committed) and flow
// into the plugin jar, sandbox, and test classpath through processResources
// under the templates/ resource prefix.
tasks.processResources {
    from(generateTemplates) { into("templates") }
}

// ═══════════════════════════════════════════════════════════════════════════
// Release-integrity verification
// ═══════════════════════════════════════════════════════════════════════════

// Guards the paid-plugin <product-descriptor> against drifting from pluginVersion —
// the Marketplace encodes major.minor into release-version, and forgetting to bump it
// at a minor release silently anchors new Pro licenses to the previous release line
// (this exact mistake nearly shipped with 1.5.0). Wired into `check`, so both
// `./gradlew build` and the /release skill's verify step fail on drift.
val verifyProductDescriptor = tasks.register("verifyProductDescriptor") {
    group = "verification"
    description = "Checks that plugin.xml's <product-descriptor> matches pluginVersion and KMPLicense.PRODUCT_CODE."

    val pluginXml = providers.fileContents(
        layout.projectDirectory.file("src/main/resources/META-INF/plugin.xml")
    ).asText
    val licenseSource = providers.fileContents(
        layout.projectDirectory.file("src/main/kotlin/app/oreshkov/kmp/wizard/license/KMPLicense.kt")
    ).asText
    val pluginVersion = providers.gradleProperty("pluginVersion")

    doLast {
        val xml = pluginXml.get()
        fun attr(name: String): String =
            Regex("""<product-descriptor[^>]*\b$name="([^"]*)"""").find(xml)?.groupValues?.get(1)
                ?: error("plugin.xml: <product-descriptor> is missing the '$name' attribute")

        val version = pluginVersion.get()
        val (major, minor) = Regex("""^(\d+)\.(\d+)\.\d+$""").find(version)?.destructured
            ?: error("pluginVersion '$version' is not MAJOR.MINOR.PATCH")
        require(minor.length == 1) {
            "pluginVersion '$version': the Marketplace parses release-version's trailing SINGLE " +
                "digit as the minor — a two-digit minor ($minor) cannot be encoded. Bump the major instead."
        }
        val expectedReleaseVersion = "$major$minor"
        val releaseVersion = attr("release-version")
        require(releaseVersion == expectedReleaseVersion) {
            "plugin.xml product-descriptor release-version=\"$releaseVersion\" does not match " +
                "pluginVersion $version (expected \"$expectedReleaseVersion\"). At a new minor/major, " +
                "bump release-version AND release-date together (see .claude/skills/release/SKILL.md step 6)."
        }

        val releaseDate = attr("release-date")
        val parsedDate = runCatching { LocalDate.parse(releaseDate, DateTimeFormatter.BASIC_ISO_DATE) }
            .getOrElse { error("plugin.xml product-descriptor release-date=\"$releaseDate\" is not a valid YYYYMMDD date") }
        require(!parsedDate.isAfter(LocalDate.now())) {
            "plugin.xml product-descriptor release-date=\"$releaseDate\" is in the future — " +
                "the Marketplace rejects future release dates."
        }

        val productCode = Regex("""const val PRODUCT_CODE = "([^"]+)"""").find(licenseSource.get())?.groupValues?.get(1)
            ?: error("Could not find PRODUCT_CODE in KMPLicense.kt")
        require(attr("code") == productCode) {
            "plugin.xml product-descriptor code=\"${attr("code")}\" does not match " +
                "KMPLicense.PRODUCT_CODE (\"$productCode\") — the runtime license check would never match."
        }

        logger.lifecycle("Product descriptor OK: code=$productCode release-version=$releaseVersion release-date=$releaseDate (pluginVersion $version)")
    }
}

tasks.named("check") {
    dependsOn(verifyProductDescriptor)
}

// ═══════════════════════════════════════════════════════════════════════════
// Kotlin compiler options
// ═══════════════════════════════════════════════════════════════════════════

kotlin {
    jvmToolchain(21)
}
