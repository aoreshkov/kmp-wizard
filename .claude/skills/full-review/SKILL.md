---
name: full-review
description: Run the six review-* domain-expert agents across the whole codebase (in parallel, batched in waves of 3) and combine their findings into one consolidated best-practices audit. Invoke with /full-review (optionally name domains, e.g. /full-review kotlin testing). Read-only — produces a report, makes no code changes. Do not invoke automatically.
disable-model-invocation: true
argument-hint: [domains... | all]
arguments: domains
allowed-tools: Task, Agent, Read, Glob, Grep, Write
---

Arguments: **$domains** (optional — empty means "all six").

This skill orchestrates the project's `review-*` best-practices agents (defined in
`.claude/agents/`), runs them **in parallel in waves of 3**, then **merges** their
reports into a single consolidated audit. It is a **read-only** audit: neither this
skill nor the agents change any code. Each agent already carries its own scope,
sources, and output contract — your job here is orchestration + synthesis, not
re-reviewing anything yourself.

## The agent roster

| Domain keyword(s) | Agent |
|---|---|
| `platform`, `intellij` | `review-intellij-platform` |
| `kotlin` | `review-kotlin` |
| `gradle`, `build` | `review-gradle-build` |
| `marketplace`, `release` | `review-marketplace-release` |
| `security`, `licensing` | `review-security-licensing` |
| `testing`, `tests` | `review-testing` |

All agents are **read-only** (`Read, Grep, Glob, WebSearch, WebFetch`) and skip
the generated templates (`build/generated-resources/templates/`, produced from
the pinned kmp-ledger release — not authored in this repo).

## Steps

### 1. Resolve the target set

- If `$domains` is empty or `all` → select **all six** agents.
- Otherwise map each token to an agent via the table above (case-insensitive,
  accept partial keywords). Ignore unknown tokens but warn which were ignored.
- Confirm the selected agents exist as files under `.claude/agents/` (glob
  `.claude/agents/review-*.md`). If a requested one is missing, report and skip it.
- Tell the user, in one line, which agents will run and in how many waves.

### 2. Run in parallel, batched in waves of 3

Split the selected agents into waves of **at most 3**. For the full set use:

- **Wave 1:** `review-intellij-platform`, `review-kotlin`, `review-gradle-build`
- **Wave 2:** `review-marketplace-release`, `review-security-licensing`, `review-testing`

(Order between waves does not matter; the cap of 3 concurrent is what matters.
`review-web-frontend` was retired when the web sites moved to the oreshkov-app repo.)

For each wave:
- Launch every agent in the wave **in a single message** (multiple Agent/Task
  tool calls in one assistant turn) so they run **concurrently**. Use
  `subagent_type` = the agent name (e.g. `review-kotlin`).
- Give each the same minimal prompt (it already knows its scope):

  > Run your full best-practices review exactly per your agent definition.
  > Today's date is **<insert today's date>** — treat that as the "as of" date and
  > verify the *current* official recommendation via web search, never from memory.
  > Read your in-scope sources, skip the generated templates under `build/`, and return
  > your **complete findings report** using your output contract (each finding:
  > Severity / Location `file:line` / Current / Best practice + source URL & as-of
  > date / Recommendation), ending with your one-paragraph Verdict and top-3 fixes.

- **Wait for the entire wave to return before starting the next wave.** Do not
  start wave N+1 until wave N's agents have all reported.
- If an agent errors or returns nothing, note it and continue — record it as
  "domain not assessed" in the final report rather than aborting the run.

Note: the agents use `WebSearch`/`WebFetch`. If domains beyond the current
allow-list in `.claude/settings.local.json` are hit, the user may see permission
prompts — that is expected.

### 3. Combine the results

Once all waves are in, synthesize **one** consolidated Markdown report. Do not
just concatenate — integrate:

1. **Executive summary** — a table: Domain | Verdict (Conforms / Needs attention) |
   #blockers | #warnings | #nits. Plus a 2–3 sentence overall health statement.
2. **Cross-cutting themes** — findings that recur across domains (e.g. outdated
   dependency/version currency, missing accessibility/error handling, deprecated
   APIs). Merge duplicates and tag each with the domains that raised it.
3. **Prioritized master list** — every finding ranked **blockers → warnings →
   nits**, each line tagged `[domain]` + `file:line` + one-line recommendation.
   Preserve each agent's cited source URL + as-of date.
4. **Per-domain detail** — one short subsection per agent: its Verdict and its
   top-3 fixes (link/keep the citations).
5. **Top action items** — the 5–10 highest-leverage changes overall, in order.
6. **Footer** — restate that this was a read-only audit (no code changed), list
   any domains that failed to run, and note the date the review was performed.

### 4. Deliver

- Present the consolidated report in the conversation by default.
- If the user passed a save path or asked to save (e.g. `--save` or an explicit
  path), also write it to that file (default suggestion:
  `reviews/full-review-<YYYY-MM-DD>.md`, creating the `reviews/` dir). Otherwise
  do **not** write any file.

Make **no other changes** — fixing findings is a separate, explicit follow-up.
