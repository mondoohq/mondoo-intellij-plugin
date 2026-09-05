# Manual verification

What the automated checks cannot reach, and how to check it before a release.

## What is already automated

Run these first; there is no point clicking through a build that fails them.

| Check | Covers |
|---|---|
| `./gradlew test` | Pure logic: argument building, parsers, path resolution, tree grouping. Also `PluginDescriptorTest`, which asserts every class named in a descriptor exists, every action id matches `DeclaredActions`, and — the one that caught a shipped bug — every action class on the classpath is registered somewhere. |
| `scripts/smoke-test.sh` | A real IDE with the plugin installed: the plugin loads, the optional LSP module loads, both language servers start and complete their handshakes, findings reach the store, and `idea.log` has no `ERROR` lines. |
| `MondooSelfCheck` (inside that smoke test) | Every declared action resolves in the running IDE and every service instantiates without throwing. A renamed class behind a menu item, or a service that fails in its constructor, fails here rather than in front of a user. |

Together those prove the plumbing. What they cannot do is click a menu, open a light
bulb, read a Swing tree, or answer a dialog — and the one user-visible bug that reached
a screenshot so far was exactly that kind. So the list below is still worth an hour.

Run this list in a sandbox before cutting a release:

```bash
P=/tmp/mondoo-check && mkdir -p "$P" && printf 'module check\n\ngo 1.22\n' > "$P/go.mod"
printf 'import os\n\ndef run(cmd):\n    os.system(cmd)\n' > "$P/main.py"
./gradlew runGoLand -PmondooProbeProject="$P,$P/main.py"
```

## Findings

- [ ] Highlights appear on the vulnerable lines, with the rule id in the message.
- [ ] Hovering a highlight shows CWE and OWASP metadata.
- [ ] The **Mondoo** tool window lists the findings, grouped by severity and rule.
- [ ] The group-by toggle switches to file grouping and the tree regroups.
- [ ] Double-clicking a finding navigates to the right line.
- [ ] The status bar shows a matching count.

## Quick fixes

- [ ] Alt+Enter on a finding offers **Suppress xgrep finding (nogrep)**.
- [ ] Applying it inserts the comment above the line, and the finding disappears.
- [ ] **Suppress with reason…** prompts, and records the reason *before* the keyword.
- [ ] On a rule that ships a fix, Alt+Enter offers to apply it and the edit is correct.

## Scans

- [ ] **Scan Workspace** runs, shows progress, and reports a result notification.
- [ ] Findings for files that were never opened appear in the tool window.
- [ ] **Scan Changed Files** works in a git project with uncommitted changes.
- [ ] Cancelling a scan says the scan continues rather than claiming it stopped.
- [ ] **Clear Findings** empties the tool window.

## Search

- [ ] **Search Code…** with `os.system($X)` opens results in the **Find** tool window.
- [ ] Results navigate to the right lines.
- [ ] **Export Search as Rule** opens YAML that round-trips through the rules path.
- [ ] **Replace Code…** with `eval($X)` → `safeEval($X)` previews matches before writing.
- [ ] **Replace All** rewrites every match, and a single Undo reverts all of them across
      every file.
- [ ] On `f(f(1))` with pattern `f($X)`, only the outer call is rewritten and the button
      says one was skipped.
- [ ] Editing a matched file while the preview is open does not corrupt it on Replace All.

## Bill of materials

- [ ] **Generate Bill of Materials…** offers software, cryptography and AI.
- [ ] Software offers a format choice; cryptography and AI skip it.
- [ ] The save dialog suggests a name like `check.sbom.cdx.json`.
- [ ] The result notification opens the generated file.

## Setup and agents

- [ ] With the scanner missing, opening a project offers to install it and names the
      version. Nothing is downloaded until **Install** is clicked.
- [ ] **Not now** downloads nothing, and the offer returns on the next project open.
- [ ] **Never** downloads nothing and clears **Offer to download and update the
      scanner** in settings.
- [ ] With the scanner missing, the editor shows the setup banner on a scannable file.
- [ ] **Don't show again** dismisses it for good.
- [ ] **Set Up Scanner** downloads without asking — that is what the action is — and
      the banner clears.
- [ ] **Install AI Skills…** installs, or copies commands when the CLI is absent.
- [ ] **Configure MCP Server…** writes or copies a valid config.

## Trust

- [ ] Opening an untrusted project starts no scanner, and the actions stay disabled.

## Policy bundles

Open `example.mql.yaml` from the smoke-test scratch project (or any `*.mql.yaml`).

- [ ] MQL highlighting and completion work in the bundle.
- [ ] **Lint Policy Bundle** reports findings, or says the bundle is clean.
- [ ] **Format Policy Bundle** rewrites the file and the editor picks up the change.
- [ ] **Format and Sort Policy Bundle** additionally sorts the queries.
- [ ] All three are hidden when the focused file is not a policy bundle.

## Creating a policy

- [ ] **New Policy from Template…** offers `example-policy.mql.yaml` and creates it.
- [ ] The created file opens, and appears in the Policies tab without a manual refresh.
- [ ] A name not ending in `.mql.yaml`/`.mql.yml` is refused by the dialog itself.
- [ ] A name containing a path separator is refused.
- [ ] Running it twice with the same name reports that the file exists and offers no
      overwrite — cnspec would refuse one.
- [ ] With cnspec absent the action is disabled rather than failing when clicked.

## The Policies tab

- [ ] Every `*.mql.yaml` in the project appears, nested by directory.
- [ ] A file shows its policy and query counts; expanding reaches groups and checks.
- [ ] A group's checks show the queries they name, in the order the file declares them.
- [ ] A check naming an undefined uid appears in red as "not defined in this file".
- [ ] Double-clicking a policy, group or query opens the file at that line.
- [ ] Typing filters the tree.
- [ ] Editing a bundle without saving updates the tree; so does saving, adding and
      deleting one.
- [ ] **Run** is disabled with nothing selected, and offers Query / Policy / Bundle
      according to what is.
- [ ] Running a query streams cnspec output into a console tab.
- [ ] Right-clicking a node offers **Jump to Source** and **Run**.
- [ ] Expanding a policy, then saving the bundle, leaves it expanded.
- [ ] In an untrusted project the tree still lists bundles, but **Run** is disabled.

## Targets

Needs `cnspec` on the PATH; the actions explain themselves when it is absent.

- [ ] **Scan Target…** offers "This machine" with no configuration, and runs.
- [ ] Output appears in a console tab in the **Mondoo** tool window.
- [ ] **Run MQL Query…** is seeded with the editor selection when one line is selected.
- [ ] `asset.platform` against this machine returns a result.
- [ ] **Manage Targets…** adds an SSH target; the password prompt is masked.
- [ ] **Edit a target** pre-fills each field with the stored value.
- [ ] Leaving the password blank on edit keeps the stored one; scanning still works.
- [ ] Cancelling midway through an edit leaves the target unchanged.
- [ ] **Test a connection** on "This machine" reports the platform it detected.
- [ ] **Test a connection** on an unreachable host reports a failure, not a success —
      cnspec exits 0 either way, so this is the one worth checking by hand.
- [ ] The added target appears in the **Scan Target…** chooser and its secret is in the
      password safe, not in the project's persisted configuration.
