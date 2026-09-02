# Manual verification

What the automated suites cannot reach, and how to check it before a release.

`./gradlew test` covers pure logic. `scripts/smoke-test.sh` covers plugin loading, the
optional LSP module, server startup, and findings reaching the store. Neither can click
a menu, open a light bulb, or read a Swing component — and the one user-visible bug
that reached a screenshot so far was exactly that kind.

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
