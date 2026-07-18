# Contributing to KMP Project Wizard

Thanks for your interest in contributing! Bug reports, feature requests, and
pull requests are welcome.

## Before you start: the two-repo template pipeline

This plugin does **not** contain the project templates it generates. Templates
are produced at build time from a pinned release of
[kmp-ledger](https://github.com/aoreshkov/kmp-ledger) — a real, working KMP
reference app (`ledgerVersion` in `gradle.properties`).

- Want to change **what generated projects look like** (dependencies, modules,
  code style of the output)? Contribute to
  [kmp-ledger](https://github.com/aoreshkov/kmp-ledger), not here.
- Want to change the **wizard itself** (UI, placeholder substitution, platform
  post-processing, build)? You're in the right repo.

## Building

Prerequisites: JDK 21 (the build uses a Gradle toolchain). The first build
downloads the pinned kmp-ledger release archive from GitHub; afterwards it is
served from the Gradle cache and `--offline` works.

```bash
./gradlew build      # compile + test
./gradlew runIde     # launch a sandbox IDE with the plugin loaded
./gradlew test       # unit tests only
./gradlew verifyPlugin
```

Run a single test:

```bash
./gradlew test --tests "app.oreshkov.kmp.wizard.template.TemplateRendererTest"
```

## Making changes

1. Fork and create a topic branch from `main`.
2. Keep the `templateSubstitutions` list in `build.gradle.kts` (forward) and
   `buildSubstitutions` in `TemplateRenderer.kt` (reverse) **in sync** if you
   touch either.
3. Add or update tests (`src/test/`) for behavior changes.
4. Add an entry under `## Unreleased` in `CHANGELOG.md`
   ([Keep a Changelog](https://keepachangelog.com/en/1.1.0/) format).
5. Make sure `./gradlew build` is green.
6. Open a pull request describing **what** changed and **why**.

## Reporting bugs

Use the issue templates. Always include the plugin version, IDE version/build
number, and OS — wizard bugs are often specific to one of these.

## License

The source code is MIT-licensed. By contributing you agree that your
contributions are licensed under the MIT license (see `LICENSE`). The plugin
*as distributed* on JetBrains Marketplace is governed by a separate
[EULA](https://kmpwizard.oreshkov.app/license.html).
