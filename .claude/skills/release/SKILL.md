---
name: release
description: Cut a new KMP Project Wizard release to the JetBrains Marketplace — bump the kmp-ledger template pin, draft the changelog from the source diff, recommend and confirm the version, bump it, verify, push, stage a draft GitHub Release, and (gated) publish it, which triggers the CI publish workflow. Optionally pass a version (e.g. /release 1.3.0) to pre-select; otherwise the skill recommends one from the source diff and confirms it. Do not invoke automatically.
disable-model-invocation: true
argument-hint: [version]
arguments: version
allowed-tools: Bash(./gradlew*), Bash(git status*), Bash(git tag*), Bash(git diff*), Bash(git add*), Bash(git commit*), Bash(git ls-remote*), Bash(git fetch*), Bash(git push*), Bash(gh release*), Bash(gh secret*), Bash(gh run*), Bash(head*), Read, Edit, Glob, AskUserQuestion
context: fork
---

Requested version (optional): **$version**

This skill drives the full release of the **plugin itself** (not generated projects). It encodes
`docs/MAINTAINERS.md` (maintainer-local, gitignored — not present in the public repo).
Marketplace publishing is **irreversible** — never publish without the explicit confirmation
gate before it.

If `$version` is empty, you will recommend one in step 4 and confirm it. If it is set, treat it as
the operator's preferred version and pre-select it during confirmation. Whatever is confirmed in
step 4 — **not** the raw `$version` — is **the confirmed version** consumed by every later step.

## Repo facts (do not violate)

- **Public remote:** `https://github.com/aoreshkov/kmp-wizard.git` (`origin`). Marketplace
  publishing runs in **GitHub Actions** (`.github/workflows/release.yml`), triggered by
  publishing the draft GitHub Release (step 10). GitHub creates the `vX.Y.Z` tag at that
  moment — never create the tag locally; an aborted release (deleted draft) leaves no tag.
  The four publish/signing secrets live in the repo's `marketplace` environment, not on this
  machine.
- **Never push the local `archive/pre-publish` branch or the pre-publish tags reachable only
  from it** — that history is private. Only push `main` and the new release tag.
- The changelog version heading style is `## X.Y.Z - YYYY-MM-DD` (no brackets), matching the
  existing file — house style; the changelog Gradle plugin's parser accepts bracketed headings
  too, and a *missing* section for `pluginVersion` fails the build (`changelog.get` throws),
  it does not render empty.

## Steps

### 1. Bump the ledger pin (fail-fast)

Templates are generated at build time from the **pinned kmp-ledger release tag**
(`ledgerVersion` in `gradle.properties`); there is no committed snapshot and no local ledger
sibling. This runs **first** — the pin bump is what changes generated-project content, so it
must land before any changelog or version work and be inside the diff range step 3 drafts
from. Stop and report if any check fails — do not continue past a failure.

- `git status --porcelain` → working tree must be clean (or only intentionally-staged release
  files). This must pass **before** bumping, so the pin bump lands as an isolated commit and
  never bundles stray edits.
- Find the latest ledger release tag:
  ```
  git ls-remote --tags --sort=-v:refname https://github.com/aoreshkov/kmp-ledger | head -5
  ```
  If the newest `vX.Y.Z` equals `ledgerVersion`, skip the rest of this step. **Never expect a
  moved/re-cut tag** — Gradle caches static versions forever; a content change requires a new
  ledger release.
- Set `ledgerVersion=X.Y.Z` (tag without the leading `v`) in `gradle.properties`, then verify
  the download + generation succeed:

```
./gradlew generateTemplates
```

Commit the bump on its own:

```
git add gradle.properties
git commit -m "chore: bump kmp-ledger templates to vX.Y.Z"
```

### 2. Preflight (publish prerequisites)

The remaining checks — only needed later in the run. A missing item here does **not** block
the early steps; it warns now so there is no surprise at the publish gate.

- Confirm the publish workflow exists on `main`: `.github/workflows/release.yml` (Glob/Read).
- Confirm the four secrets exist in the `marketplace` environment (values are unreadable —
  existence only): `gh secret list --env marketplace` must show `PUBLISH_TOKEN`,
  `CERTIFICATE_CHAIN`, `PRIVATE_KEY`, `PRIVATE_KEY_PASSWORD`. If any is missing, **warn
  loudly** here and again at the publish gate — the workflow fails fast on a missing token
  or signing secret (it refuses to publish unsigned), so the release would stall at CI.
- Release tags are created by GitHub on origin (step 10), so the local clone may lag: run
  `git ls-remote --tags --sort=-v:refname origin | head -5` to find the previous tag (e.g.
  `v1.2.1`), and `git fetch origin tag v<previous>` if it is missing locally — step 3's diff
  range needs it.
- Confirm the `CHANGELOG.md` section matching the current `pluginVersion` is **the content you
  intend to publish**, and that anything under `## Unreleased` belongs to a *future* version:
  re-running `publishPlugin` without a version bump republishes the current version's notes and
  silently ignores the staged Unreleased items.

### 3. Draft the changelog from the source diff

**Do not use git commit messages.** Derive entries entirely from the actual file changes. Run, in
order, and read each carefully:

1. `git diff <last_tag>..HEAD --stat`
2. `git diff <last_tag>..HEAD -- '*.kt'` — plugin behaviour, wizard, licensing
3. `git diff <last_tag>..HEAD -- '*.kts' 'gradle/libs.versions.toml' 'gradle.properties'`
4. If step 1 bumped `ledgerVersion`: what generated projects gain is the ledger delta between
   the old and new pins — read the ledger's own changelog from the extracted source:
   `head -120 build/ledger-src/CHANGELOG.md` (present after `./gradlew generateTemplates`)

Write **user-facing, benefit-led** bullets only — describe what the user/generated project gains,
not internal class refactors. Keep each release to ~3–6 bullets per category, **bucketed** under
`Added / Changed / Deprecated / Removed / Fixed / Security` (only non-empty groups). These buckets
both populate the changelog **and** drive the version recommendation in step 4 — note which
buckets are non-empty and whether any `Changed` entry is breaking/incompatible.

**Exclude from the changelog:** changes under `docs/` (landing page, portfolio, plan docs),
`build.gradle.kts` plugin-build tweaks, and other maintainer-only tooling — the changelog is for
Marketplace users of generated projects, not for this repo's internals.

### 4. Recommend the next version & confirm

Derive a recommended version from the change buckets in step 3 (do **not** ask the operator to
decide unaided — propose, then confirm). **Write nothing before this gate.**

Parse the previous tag's `MAJOR.MINOR.PATCH` (e.g. `v1.3.1` → `1`, `3`, `1`). The project is past
`1.0.0`, so standard SemVer applies. Map the buckets to a bump:

- Any **Removed** entry, or any breaking/incompatible **Changed** entry → **major**: `<major+1>.0.0`.
- Otherwise any **Added** entry → **minor**: `<major>.<minor+1>.0`.
- Otherwise (only **Fixed**, or internal/non-breaking **Changed**) → **patch**: `<major>.<minor>.<patch+1>`.

If step 3 found no user- or developer-visible changes, say so and default to a **patch** bump
rather than inventing changelog entries.

Compute all three candidates from the previous tag (e.g. from `v1.3.1`: major `2.0.0`, minor
`1.4.0`, patch `1.3.2`). Then call **`AskUserQuestion`** with a single question — "Confirm the
release version" — and these options:

- **First option = the version to release.** If `$version` was supplied, use it here and note it
  is operator-supplied; otherwise use the recommended version, labelled with its bump type and a
  one-line justification (e.g. "1.4.0 — minor: new features added, no breaking changes").
- The other two SemVer candidates (the major/minor/patch versions not chosen as the first option),
  each labelled with its bump type.

The tool automatically offers an "Other" entry for a custom version, so do not add one. Whatever
the operator confirms (or types) becomes **the confirmed version** used by every step below.

> **Plugin-specific note:** the confirmed version's **major.minor** decides whether
> `release-version`/`release-date` change in step 6 — they move **only** at a new minor/major,
> never on a patch.

### 5. Update `CHANGELOG.md`

Roll the contents of `## Unreleased` into a new dated heading and leave a fresh empty
`## Unreleased` skeleton above it:

```markdown
## Unreleased

### Added

### Changed

### Deprecated

### Removed

### Fixed

### Security

## <confirmed version> - YYYY-MM-DD

### Added
- …
```

Use today's date and **the confirmed version** from step 4. The `## <confirmed version>` heading
**must** match `pluginVersion` exactly — if no section matches, `changelog.get(version)` throws
and the build **fails** (a loud safety net, not silently empty `changeNotes`).

### 6. Bump the version (two files)

1. Set `pluginVersion=<confirmed version>` in `gradle.properties`.
2. In `src/main/resources/META-INF/plugin.xml`, update the `<product-descriptor>` (drives paid
   licensing — easy to get wrong). `release-version` and `release-date` identify the licensable
   **release line** (its perpetual-fallback window), **not** the individual build — so for a
   patch they usually stay the **same**:
   - `release-version` — the Marketplace parses this integer into two numbers where the
     **second is a single digit**, encoding the plugin's **major.minor** (`20211 → (2021, 1)`;
     `1.2.x → 12`; `1.3.x → 13`). So for any `1.3.x` build the only valid value is `13` — `14`
     or `131` are rejected with a "matching beginning" error. It must be **non-descending**.
   - **Patch/minor releases keep `release-version` AND `release-date` unchanged** from the `.0`
     release of that line (official guidance: *"when we upload any minor updates, we keep the
     release-version untouched and only increment the version"*). The Marketplace also **rejects
     reusing a release-version with a different release-date** — so the date must match the one
     already published for that release-version.
   - **Only bump them at a new minor/major** (e.g. `1.4.0` → `release-version=14`,
     `release-date` = that day as `YYYYMMDD`).
   - This is independent of the changelog heading date, which is always today's build date.

### 7. Verify

```
./gradlew build
./gradlew verifyPlugin
```

Both must pass. `build` runs `check`, which includes the `verifyProductDescriptor` task — it
hard-fails if step 6's `<product-descriptor>` doesn't match the confirmed version (release-version
↔ major.minor, valid non-future release-date, code ↔ `KMPLicense.PRODUCT_CODE`), so a forgotten
descriptor bump cannot reach the tag or the publish gate. Do not tag or publish on any failure;
report the output and stop.

### 8. Commit and push

```
git add gradle.properties CHANGELOG.md src/main/resources/META-INF/plugin.xml
git commit -m "chore: release <confirmed version>"
git push origin main
```

Do **not** create a tag — GitHub creates `v<confirmed version>` when the draft release is
published in step 10. Pushing `main` here is safe: the release commit is inert until then.
Never push `archive/pre-publish` or any pre-publish tag.

### 9. Create the draft GitHub Release

```
gh release create v<confirmed version> --draft --target main --title "v<confirmed version>" --notes "<the confirmed version's CHANGELOG section>"
```

Use the exact changelog section rolled in step 5 as the release notes. A draft creates no tag
and triggers nothing — it is the staged, reviewable gate.

### 10. Publish gate (irreversible)

Publishing the release fires `.github/workflows/release.yml`, which runs
`./gradlew publishPlugin` in CI — Marketplace publishing cannot be undone. **Ask the user to
confirm explicitly** before it. If any secret was missing at preflight, **repeat that warning
here**. Only on a clear "yes":

```
gh release edit v<confirmed version> --draft=false
```

GitHub now creates the tag and starts the workflow (if the `marketplace` environment has a
required reviewer, the user must also approve the run in the Actions UI). Watch it:

```
gh run watch $(gh run list --workflow=release.yml --limit 1 --json databaseId --jq '.[0].databaseId')
```

Report the outcome and remind the user to verify the listing and "What's New" on the
Marketplace. If the run fails **after** the tag exists (e.g. Marketplace outage), fix the cause
and re-run the workflow from the Actions UI — the checkout is by tag, so a re-run is
reproducible; do not delete the tag.

If the gate is **declined**: `gh release delete v<confirmed version>` — the draft vanishes
cleanly, no tag ever existed, and `main` merely carries an unpublished version bump.
