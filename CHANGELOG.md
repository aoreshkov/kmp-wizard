# Changelog

## Unreleased

### Added

### Changed

### Deprecated

### Removed

### Fixed

### Security

## 1.7.1 - 2026-08-07

### Changed

- **Ledger pin bumped to `v1.6.5`.** Generated projects now pin `packageOfResClass` in every module that generates a Compose `Res` class, so their resource accessors sit under `<your.package>.<module path>.resources` instead of a package derived from the project name — renaming the root project can no longer repackage every module's resources at once. They also move to the Koin compiler `1.1.0`, which verifies the whole dependency graph once at each `@KoinApplication` entry point rather than module by module, and pick up Navigation 3 runtime `1.1.5`, Room `3.0.1` and Logback `1.6.1`.

### Fixed

- **Generated projects no longer fail to compile when the project name is more than one word.** Compose Multiplatform derives the package of its generated `Res` accessor class from the Gradle project group, which in turn comes from `rootProject.name` — lowercased with the words run together. The wizard was rendering those `import` statements in snake_case instead, so a project named `My Awesome App` imported `my_awesome_app.feature.…` from a package actually called `myawesomeapp.feature.…`, and every screen and DI module that reads a string resource failed to resolve. Single-word project names were unaffected, which is why this went unnoticed.

## 1.7.0 - 2026-07-28

### Added

- **The wizard now appears in Android Studio.** Android Studio replaces the platform's New Project dialog with its own, template-driven one, which never showed the wizard no matter how it was configured — the plugin installed, loaded, and offered nothing. It is now also contributed as an Android Studio project template, so it shows up under **File | New | New Project** in the **Phone and Tablet** gallery. Android Studio owns its first page, so project name, package name and save location come from there; the feature name, field name, test value, target platforms and Pro options follow on the next page. The IntelliJ IDEA entry point is unchanged. The same template also appears in IntelliJ IDEA when the Android plugin is installed.

### Changed

- **Minimum supported IDE lowered back to 2026.1** (`sinceBuild` 262 → 261), reverting the 1.6.1 bump. Android Studio runs a full platform branch behind IntelliJ IDEA — the newest Android Studio on any channel is still `AI-261.*` — so a 262 floor made the plugin impossible to install in *every* existing Android Studio. The AGP concern behind the 1.6.1 bump does not apply to Android Studio, whose bundled tooling is AGP 9.3.1, well above the 9.1.1 that generated projects use.
- The plugin is now compiled against IntelliJ IDEA 2026.1 rather than 2026.2, so the compile-time API surface matches the declared `sinceBuild`.
- **Ledger pin bumped to `v1.6.4`.** Generated projects move to Logback `1.6.0` on desktop (the 1.6.x line targets the SLF4J 2.0.18 baseline the project already pins), pick up refreshed GitHub Actions pins in their CI workflows (`actions/checkout` v7.0.1, `ossf/scorecard-action` v2.4.4, `github/codeql-action/upload-sarif` v4.37.3), and ship README / `CLAUDE.md` tech-stack tables that match their own version catalog.

## 1.6.1 - 2026-07-24

### Changed

- **Requires IntelliJ IDEA / Android Studio 2026.2+**: the minimum supported IDE moves up (`sinceBuild` 261 → 262). This lets generated projects target the newest Android Gradle plugin that the IntelliJ Android plugin accepts, instead of being held back to the 2026.1 ceiling.
- **Ledger pin bumped to `v1.6.3`.**
- **Marketplace listing**: the plugin description now includes a Free-vs-Pro comparison table and links to the open-source repository (source code, issue tracker, documentation) — visible on the Marketplace page and in the IDE Plugin Manager. No functional changes.

### Fixed

- **Generated projects no longer fail Gradle sync with "incompatible version (AGP 9.2.1)"**: on IntelliJ IDEA 2026.2 the bundled Android plugin supports the AGP 9.1 line, but generated projects shipped AGP 9.2.1 (from the pinned kmp-ledger release) and sync was refused. The ledger pin now provides AGP 9.1.1 — within the line the IntelliJ Android plugin accepts — so sync succeeds.

## 1.6.0 - 2026-07-18

### Added

- **Community & CI scaffolding in generated projects**: `CONTRIBUTING.md`, `CODE_OF_CONDUCT.md`, an OpenSSF Scorecard workflow, a dependency-submission workflow, and `androidApp/proguard-rules.pro` — picked up from the kmp-ledger `v1.5.0` release.
- **API dumps generated from the user's code**: after the generated project's first Gradle sync, the wizard runs `apiDump` in the background so the binary-compatibility dumps under `<module>/api/` reflect the project's own package, app, and feature names. If the run fails, a notification points to `./gradlew apiDump`.
- **Repository support & ownership files**: a `SUPPORT.md` routing questions to the right channels (issues, kmp-ledger, Marketplace, private contact) and a `CODEOWNERS` file.

### Changed

- **Source license clarified — MIT**: the plugin's source code is published under the MIT license. The plugin as distributed on JetBrains Marketplace (Free/Pro tiers) remains governed by its [EULA](https://kmpwizard.oreshkov.app/license.html).
- **Templates now come from the published kmp-ledger release**: the plugin's project templates are generated at build time from the pinned [kmp-ledger](https://github.com/aoreshkov/kmp-ledger) release tag, so generated projects always match a public, tagged state of the reference app.
- **Ledger pin bumped to `v1.6.2`.**

### Fixed

- **No half-built projects on cancel or failure**: generation now runs in an isolated staging directory and is committed to the project folder only after it fully succeeds — cancelling the wizard or hitting a generation error leaves the project directory untouched.
- **No more maintainer-local files in generated projects**: the previous template snapshot could pick up files that existed only in the maintainer's working copy (internal review documents under `docs/`, a local `gradle-daemon-jvm.properties`); generating from the published release tag makes that impossible.
- **Generated projects no longer fail `check` on stale API dumps**: the templates used to ship the ledger's `*.api` / `*.klib.api` dumps with placeholder substitution applied. Dumps are compiler-output snapshots — compiler-generated symbol names, declaration sort order, and Compose lambda keys all derive from the original names — so the substituted copies could never match a fresh `apiDump`, and `./gradlew check` (`apiCheck`) failed in any project not named exactly like the ledger. Dump files are now excluded from the templates entirely (see the `apiDump`-after-sync entry above).
- **No sponsor button on generated repos**: the ledger's `.github/FUNDING.yml` (its own sponsor config) is no longer copied into templates.

### Security

- **CodeQL code scanning**: a CodeQL workflow analyzes the plugin's Kotlin/JVM sources on every push, pull request, and weekly schedule.
- **CI actions pinned to commit SHAs**: all GitHub Actions in the build and CodeQL workflows are pinned to full commit SHAs (supply-chain hardening, per GitHub's Actions security guidance); Dependabot keeps the pins current.

## 1.5.0 - 2026-06-30

### Added

- **Settings screen with theme switching**: generated projects gain a new `feature:settings` module (api/impl split) with a Settings screen that lets users pick Light, Dark, or System theme. The choice drives `{{APP_NAME}}Theme`, which resolves `SYSTEM` via the OS setting.
- **Persistent preferences via DataStore**: a new `core:datastore` module backs settings with AndroidX **DataStore Preferences**. `PlatformDataStoreModule` is an `expect class` with Android/iOS/JVM actuals (the JVM path resolves the same OS-aware data dirs as the Room DB), the store installs a `ReplaceFileCorruptionHandler`, and reads fall back to `emptyPreferences()` / `ThemeMode.SYSTEM` on corruption or unknown values. `SettingsRepository` is declared in `core:domain` and implemented in `core:datastore`, with `GetThemeModeUseCase` / `SetThemeModeUseCase` use cases and a `FakeSettingsRepository` in `core:test`.
- **Adaptive top-level navigation**: the app shell (`core:ui` `App()`) now renders a `NavigationSuiteScaffold` — bottom bar, rail, or drawer depending on window size — wrapping `NavDisplay`. Each feature contributes its own `TopLevelDestination` (in `core:navigation`) through Koin, and the shell aggregates them with `getAll<TopLevelDestination>().sortedBy { it.order }` without referencing feature routes directly.
- **Per-section navigation back stacks**: `Navigator` now holds one `NavBackStack` per top-level section. `switchTopLevel` preserves each section's stack (re-selecting the current section resets it to root), `goBack` is exit-through-home, and inactive sections keep their ViewModels and saved UI state alive via per-section entry decorators.
- **Public-API guarding (binary-compatibility-validator)**: every module now commits an API dump under `<module>/api/`, and `check` runs `apiCheck` (JVM + klib). Changing a public API fails CI until `./gradlew apiDump` is run and the updated dumps are committed.

### Changed

- **Dependency & catalog refresh** for generated projects: added AndroidX DataStore `1.2.1` (`datastore` + `datastore-preferences`) and the Compose Material 3 `adaptive-navigation-suite`. Default generated project version bumped to `1.4.0` (code `6`).
- **CI gates lint and the full check graph**: the build workflow now runs `./gradlew check` (test tasks + Android lint) instead of `allTests`, so lint actually gates PRs. The coverage PR comment is documented as display-only (the real gate is `koverVerify`).
- **Refreshed generated `.claude/` review tooling**: added KMP and CI review agents (`rv-kmp`, `rv-ci`), a `review-all` skill, and updated review conventions/skills and agent memory.

### Removed

- **Dropped obsolete catalog entries**: removed the `room3-sqlite-wrapper` dependency and the `kotlin.swift-export.experimental.nowarn` gradle property from generated projects.

### Fixed

- **Desktop app icons no longer corrupted**: the template pipeline treated `.icns`/`.ico` files as UTF-8 text, silently mangling their bytes — generated desktop projects shipped broken macOS/Windows packaging icons. Binary detection is now deterministic, and the icons are packaged byte-for-byte.
- **Quieter Windows CI**: the desktop packaging job disables the Gradle cache on `windows-latest`, removing the noisy "Path Validation Error" save warnings on a leg that never seeded the cache anyway.

## 1.4.0 - 2026-06-27

### Added

- **Cancellation-safe Save/Delete**: generated projects gain a `runCatchingCancellable` helper in `core:common`, and the Save/Delete use cases now use it — coroutine cancellation no longer surfaces as a spurious error in the UI.
- **Delete errors are now visible**: a failed delete on the feature details screen shows a snackbar ("Failed to delete. Please try again.") instead of failing silently.
- **Pro — Claude Code agent setup scaffolded**: generated projects now include a full `.claude/` configuration (review + currency agents, skills, agent-memory, hooks, settings, and review conventions).
- **No dark-mode launch flash**: Android launch-window theming ships light/dark variants (`values` + `values-night`) so the splash no longer flashes white in dark mode.

### Changed

- **JDK 21**: the JVM target moves from 17 to 21 across the Android app, desktop app, and the base KMP convention plugin — newly generated projects build on JDK 21.
- **Dependency & tooling refresh**: Gradle wrapper `9.6.0` → `9.6.1`, logback `1.5.34` → `1.5.37`, a corrected Room `sqlite-wrapper` version alignment, and a new `common-test` version-catalog bundle.
- **Faster, config-cache-friendly builds**: parallel Gradle builds are enabled (`org.gradle.parallel=true`), and version code/name are read via `providers.gradleProperty`.
- **Leaner Compose & DI**: navigation now remembers its decorators/scene strategies to avoid per-recomposition reallocation, and the Android database module uses the application context instead of an activity context.
- **Hardened CI**: `persist-credentials: false` on all checkouts, a pinned `GRADLE_USER_HOME` (fixes the Windows cache "Path Validation Error"), `macos-26` runners, and an updated attestation action.
- **Polished wizard**: the plugin now ships a custom wizard icon and a properly named "KMP Project Wizard" notification group.

### Fixed

- Generation-failure notifications now include a fallback detail when the underlying exception has no message.

### Security

- **Backup lockdown**: generated projects now set `allowBackup="false"` and ship `data_extraction_rules.xml` / `backup_rules.xml` excluding all domains, so the local app database never leaves the sandbox via cloud backup or device transfer.

## 1.3.1 - 2026-06-24

### Fixed

- **"Get a license" now opens immediately**: clicking the Pro activation link in the New Project dialog now brings up the license/subscription window on top of the wizard right away, instead of only appearing after the wizard was closed.

## 1.3.0 - 2026-06-22

### Added

- **Pro tier — Claude Code agent config & GitHub Actions CI**: opt in from the New Project dialog to scaffold `CLAUDE.md` and a `.claude/` skills set, plus a complete `.github/` pipeline (build, release, and dependency-review workflows, issue/PR templates, Dependabot, and a security policy) directly into your generated project.
- **In-wizard license activation**: a "Pro" section in the wizard unlocks the Pro scaffolding via your JetBrains Marketplace license, with activation available without leaving the dialog.
- **Broader generated test suite**: new projects now ship tests for coroutine dispatchers, UUID generation, the database, navigation keys, input validation, and Koin module verification, alongside a streamlined Android smoke test.
- **Centralized coroutine dispatchers**: generated projects include a dedicated dispatcher module for testable, structured concurrency.

### Changed

- **Dependency & Gradle wrapper refresh** for generated projects via the version catalog.
- Streamlined the generated feature screens, view models, and dependency-injection wiring.
- Wizard strings are now fully externalized for localization.

## 1.2.1 - 2026-06-17

### Added

- **Reactive State Management**: Refined `EditViewModel` to reactively track database changes while preserving user input (dirty state), preventing UI overwrites during background sync.
- **Enhanced Navigation APIs**: Added `entries` property to `Navigator` and improved encapsulation for safer backstack management.

### Changed

- **Navigation DI Refactoring**: Removed `NavigationModule` and switched to providing the `Navigator` via `CompositionLocalProvider` in `App.kt`, simplifying the dependency graph.
- **Functional Error Handling**: Migrated `Save` and `Delete` UseCases to use `kotlin.Result` with `runCatching`, enabling cleaner functional patterns like `onSuccess` and `onFailure` in the UI layer.
- **Refined UseCase APIs**: Simplified `Delete{{FEATURE_NAME}}UseCase` to accept a `String` ID directly instead of a full entity object.
- **Dependency Updates**:
    - Kotlin to `2.4.0`.
    - Room and SQLite to `3.0.0-alpha06` / `2.7.0-alpha06`.
    - Koin and Koin Compiler to `4.2.2` / `1.0.1`.
    - Jetpack Lifecycle and Adaptive to `2.11.0-beta02` / `1.3.0-beta02`.
- **Project Versioning**: Updated the default version of generated projects to `1.1.1`.

### Fixed

- **Template Reliability**: Improved `KoinModuleVerificationTest` and refined `EditViewModelTest` with new test cases for reactive updates and external data changes.

### Removed

- **Unused Database Utilities**: Deleted `Converters.kt` and removed `@TypeConverters` from the database configuration as they are no longer required following the UUID transition.

## 1.2.0 - 2026-06-02

### Added

- **Multiplatform Logging**: Integrated **Kermit** as the default logging engine across all platforms, including pre-configured platform-specific writers:
    - Android: `LogcatWriter` with `slf4j-android` support.
    - iOS: `OSLogWriter` for native Apple logging.
    - JVM/Desktop: `Slf4jWriter` with `logback` configuration.
- **UUID Integration**: Switched entity and model identifiers from `Long` to `String` (UUID) throughout the entire template stack (Database, Data, Domain, and UI) to support decentralized ID generation.

### Changed

- **Project Stack Update**: Bumped **Compose Multiplatform** to `1.11.1`.
- **Database Refinement**: Updated `Room` database templates to support `String` primary keys and refined initialization logic for `RoomDatabaseConstructor`.
- **DI Enhancements**: Added Koin `LoggingModule` for injectable multiplatform loggers.

### Fixed

- Improved `KoinModuleVerificationTest` to account for injectable `Logger` dependencies.

## 1.1.0 - 2026-05-28

### Added

- **iOS Target Support**: The Project Wizard now supports generating iOS modules (`iosApp` and `iosExport`), enabling full mobile multiplatform development.
- **Desktop Customization**: Generated projects now include default application icons for Linux, macOS, and Windows.

### Changed

- **Modernized Project Wizard**: Refined the platform selection interface to include iOS alongside Android and Desktop.
- **Updated Project Stack**: Generated projects now use the latest versions of Navigation 3, Koin Compiler (1.0.0), and Material 3 Adaptive components.

### Fixed

- Improved stability of the template generation process and refined dependency injection configuration for better developer experience.

## 1.0.1 - 2026-05-23

### Added

- **Improved Testing Suite**: Added Koin module verification and repository tests to generated projects for better reliability.
- **Refined Navigation**: Introduced `LocalNavigator` for seamless access to navigation within Compose components.

### Changed

- **Enhanced Template Architecture**: Optimized the Clean Architecture template with streamlined dependency injection and refined UseCase implementations.
- **Design System Alignment**: Updated the plugin icon and template UI components to follow Material 3 standards.
- **Dependency Updates**: Bumped Room and SQLite versions in generated project templates.

### Fixed

- Internal stability and maintenance improvements.

## 1.0.0 - 2026-05-11

### Added

- Initial release of the KMP Project Wizard.
- Support for **Android** and **Desktop (JVM)** targets.
- Parameterized **Feature Name** and **Field Name** for entity generation.
- Integrated **Room 3** (Database) and **Navigation 3**.
- **Koin Annotations** DI pre-configured.
- **Clean Architecture** project structure.
- **Convention Plugins** for Gradle build logic.
- **Full Test Suite** (Unit and UI tests) included in generated projects.
- Support for **Kotlin K2 Mode**.
