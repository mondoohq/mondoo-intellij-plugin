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

## Bill of materials

- [ ] **Generate Bill of Materials…** offers software, cryptography and AI.
- [ ] Software offers a format choice; cryptography and AI skip it.
- [ ] The save dialog suggests a name like `check.sbom.cdx.json`.
- [ ] The result notification opens the generated file.

## Setup and agents

- [ ] With the scanner missing, the editor shows the setup banner on a scannable file.
- [ ] **Don't show again** dismisses it for good.
- [ ] **Set Up Scanner** downloads and the banner clears.
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

## Targets

Needs `cnspec` on the PATH; the actions explain themselves when it is absent.

- [ ] **Scan Target…** offers "This machine" with no configuration, and runs.
- [ ] Output appears in a console tab in the **Mondoo** tool window.
- [ ] **Run MQL Query…** is seeded with the editor selection when one line is selected.
- [ ] `asset.platform` against this machine returns a result.
- [ ] **Manage Targets…** adds an SSH target; the password prompt is masked.
- [ ] The added target appears in the **Scan Target…** chooser and its secret is in the
      password safe, not in the project's persisted configuration.
