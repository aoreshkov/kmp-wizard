# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

An IntelliJ IDEA plugin ("KMP Project Wizard") that adds a **New Project** generator producing opinionated Kotlin Multiplatform projects (Android / Desktop / iOS). The plugin itself is a small Kotlin/JVM codebase; the bulk of what it ships is a tree of **templates** that get rendered into a new user project.

## Build & run commands

```bash
./gradlew generateTemplates   # regenerate templates into build/ from the pinned kmp-ledger tag (also runs automatically as part of build)
./gradlew build               # compile + test (downloads + generates templates as needed; first run needs network)
./gradlew runIde              # launch a sandbox IDE with the plugin loaded
./gradlew test                # run unit tests
./gradlew verifyPlugin        # IntelliJ Plugin Verifier against recommended IDEs
./gradlew publishPlugin       # publish to JetBrains Marketplace (needs PUBLISH_TOKEN)
```

Run a single test (Gradle test filter):

```bash
./gradlew test --tests "app.oreshkov.kmp.wizard.template.TemplateRendererTest"
./gradlew test --tests "*TemplateRendererTest.feature name camel is camelCase*"
```

JVM toolchain is 21. Targets IntelliJ `2026.1.1`, `sinceBuild=261`.

## The two-repo template pipeline (most important concept)

Templates are **not authored by hand** in this repo and are **not committed**. They are generated at build time from a **pinned release tag** of [kmp-ledger](https://github.com/aoreshkov/kmp-ledger) (a real, working KMP reference app — the "source of truth"). The pin is `ledgerVersion` in `gradle.properties`.

The flow is **forward (build time)** then **reverse (runtime)**:

1. Gradle resolves the ledger's GitHub tag archive as an Ivy dependency (`ledgerZip` configuration, `exclusiveContent` repo in `build.gradle.kts`) — cached in the Gradle module cache, so only the first build needs network and `--offline` works afterwards. `extractLedger` unzips it into `build/ledger-src/` (plain `java.util.zip` on purpose: Gradle copy specs apply Ant default excludes that would silently drop `.gitignore`).
2. **`generateTemplates` task** (`GenerateTemplatesTask` in `build.gradle.kts`) walks the extracted tree, copies every file into `build/generated-resources/templates/`, and **replaces concrete strings with placeholders** (e.g. `app.oreshkov.ledger` → `{{PACKAGE_NAME}}`, `Posting` → `{{FEATURE_NAME}}`, `Ledger` → `{{APP_NAME}}`). It writes a `MANIFEST.txt` listing every templated file. `processResources` copies the output under the `templates/` resource prefix, so the jar, tests, sandbox, and `buildPlugin` all get it automatically.
3. At **plugin runtime**, `TemplateRenderer` reads `MANIFEST.txt` from plugin resources and renders each file back out, substituting the placeholders with values the user entered in the wizard (the **reverse** of step 2).

Implications when editing:
- To change what generated projects look like, **edit `kmp-ledger`, cut a ledger release (tag `vX.Y.Z`), then bump `ledgerVersion`** here. There is no committed snapshot to edit, and the local `../kmp-ledger` sibling is no longer consulted — unreleased ledger changes cannot reach the plugin.
- **Never re-tag a ledger release** — Gradle caches static versions forever; always bump `ledgerVersion` instead.
- The `templateSubstitutions` list in `build.gradle.kts` (forward) and `buildSubstitutions` in `TemplateRenderer.kt` (reverse) must stay **in sync**. Order matters in the forward list — more-specific literals must precede overlapping less-specific ones (e.g. `postingId` before `posting`, `Posting` before `posting`).
- Substitutions apply to **both file contents and the relative file path**, so directory/file names derived from package/app/feature are renamed too.
- `.gitignore` is stored as `gitignore.txt` in templates (Ant default excludes would drop it from the jar) and renamed back on render. The `gradlew` executable bit is restored on render (jars can't carry POSIX modes). `generateTemplates` fails fast if any template path would be eaten by Ant default excludes.
- **BCV ABI dumps (`*.api`, `*.klib.api`) are never templated** (`templateExcludedFileSuffixes`): dumps are compiler-output snapshots whose generated symbol names, declaration sort order, and Compose lambda keys derive from the ledger's names — a substituted copy can never match `apiDump` output in a renamed project and would fail `apiCheck`. Instead the wizard runs `apiDump` after the generated project's first Gradle sync (`scheduleApiDumpAfterSync` in `KMPProjectWizard.kt`). Maintainer-only files (`CODEOWNERS`, `FUNDING.yml`, …) are excluded via `templateExcludedFiles`.

## Plugin runtime architecture (`src/main/kotlin/app/oreshkov/kmp/wizard/`)

- **`KMPProjectWizard.kt`** — entry point, registered via `newProjectWizard.generator` in `plugin.xml`. Builds the multi-step wizard (`RootNewProjectWizardStep` → `NewProjectWizardBaseStep` → `KMPWizardStep`). `KMPWizardStep` defines the form fields (package, feature name, field name, test value, platform checkboxes) with `validationOnInput`, and `setupProject` kicks off generation on a project-scoped coroutine (`KMPWizardCoroutineService`), then refreshes the VFS and calls `linkAndSyncGradleProject`.
- **`KMPProjectSettings.kt`** — plain data class carrying the user's choices into generation.
- **`template/TemplateRenderer.kt`** — manifest-driven renderer + the case-conversion helpers (`toPascalCase`, `toCamelCase`, `toSnakeCase`, `toUpperSnakeCase`) that derive all placeholder values from the raw feature/field names.
- **`template/ProjectStructureGenerator.kt`** — orchestrates render then **post-processes**: deletes platform module dirs (`androidApp`, `desktopApp`, `iosApp`/`iosExport`) for deselected platforms and strips the matching `include()` lines from `settings.gradle.kts`.

Tests in `src/test/` cover `buildSubstitutions`/case conversion (`TemplateRendererTest`) and the platform post-processing/settings patching (`ProjectStructureGeneratorTest`).

## Release & changelog

- Releases follow `docs/MAINTAINERS.md` (maintainer-local, gitignored — not present in the public repo): bump `ledgerVersion` to the latest ledger release → roll `## Unreleased` in `CHANGELOG.md` into a dated version matching `pluginVersion` in `gradle.properties` → annotated git tag `vX.Y.Z` → `publishPlugin` → push `main` + tag and create a GitHub Release with the changelog notes.
- The public remote is `https://github.com/aoreshkov/kmp-wizard.git`. Public history starts at the squashed publish commit; the pre-publish development history and the `v1.0.0`–`v1.5.0` tags live only on the local `archive/pre-publish` branch and must **never be pushed**.
- `changeNotes` for the Marketplace are generated **from `CHANGELOG.md`** at build time (org.jetbrains.changelog plugin), so the changelog must contain a section matching the current `pluginVersion`.
- The **`/release` skill** (`.claude/skills/release/SKILL.md`) automates the whole flow and gates the irreversible `publishPlugin` behind explicit confirmation. Draft the changelog **from the source diff, not commit messages**. (The former `prepareChangelogPrompt` Gradle task was removed in 1.3.0 in favor of the skill.)

## Conventions

- Generated-project dependency versions live in **`kmp-ledger`'s** `gradle/libs.versions.toml` (rendered into the template), not this repo's. This repo's own `gradle/libs.versions.toml` only versions the plugin's build (Kotlin, IntelliJ platform, changelog plugin, JUnit).
- The Marketplace `PUBLISH_TOKEN` must **never** be committed. Provide it via the `PUBLISH_TOKEN` env var or the git-ignored `local.properties`; `build.gradle.kts` resolves the token in that order (env var → gradle property → `local.properties`).
