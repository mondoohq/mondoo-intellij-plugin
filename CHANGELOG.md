# Changelog

All notable changes to the Mondoo plugin for JetBrains IDEs.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- **Edit a target** and **Test a connection** in Manage Targets. Editing re-prompts
  each field with what is stored, keeping a secret unless you type a new one; the test
  asks cnspec whether it can reach the target before you commit to a scan.
- **Replace Code…** — structural search and replace. Matches are previewed in the Find
  tool window and applied as a single undoable command; nested matches are skipped
  rather than corrupted, and the count of skipped ones is shown.

- A **Policies** tab listing every `*.mql.yaml` bundle in the project by directory,
  down to the queries each group's checks name. Double-click navigates to the
  declaration, typing filters the tree, and **Run** executes the selected query,
  policy or bundle against a target. A check referring to a uid the bundle does not
  define is shown rather than dropped.

### Changed

### Fixed

### Removed

## [0.1.0] - 2026-09-03

### Added

- Code security powered by [xgrep](https://mondoo.com/xgrep): findings as you type,
  shown in the editor, the Problems view, a dedicated Mondoo tool window grouped by
  severity and rule or by file, and a status-bar count.
- Workspace, changed-files and changes-since-a-git-ref scans.
- `nogrep` suppression quick fixes, with an optional recorded reason. The comment is
  honoured by the xgrep command line and by CI, so a dismissal is not editor-local.
- Structural code search, with results in the Find tool window, and export of a
  pattern as a reusable rule.
- Automatic scanner setup: xgrep is discovered, or offered for download and then
  verified against its published SHA-256 before unpacking. Nothing reaches the
  network until you accept the offer, and you are asked again for each new version.
- Scan scope settings (include/exclude globs), custom rules path, and scan parallelism.
- An in-editor setup banner when the scanner is unavailable on a file it would scan.
- **Reload Rules**, to pick up edits to a rule file.
- MCP server registration, and installation of Mondoo's agent skills from
  https://github.com/mondoohq/skills.
- **Clear Findings**, to empty the findings view after changing rules or scan scope.
- MQL language support for policy bundles (`*.mql.yaml`) and query files (`*.mql`):
  live diagnostics, resource and field completion, and hover documentation from the
  cnspec language server. cnspec is discovered, never downloaded.
- **Scan Target** and **Run MQL Query** — run cnspec against this machine, an SSH
  host, a Docker container or image, or Kubernetes, with output streamed into the
  Mondoo tool window. **Manage Targets** configures them; secrets go to the IDE
  password safe and reach cnspec through the environment, never through settings or
  a command line.
- **Lint Policy Bundle** — policy hygiene checks the language server does not make:
  required tags, missing asset filters, unreferenced queries. Findings appear in the
  Mondoo tool window.
- **Format Policy Bundle**, with an optional sort.
- **Analyze Dependencies** — a Dependencies tab showing which packages first-party
  code imports, grouped by reachability, with the importing files. Fully offline.
- **Generate Bill of Materials** — software (SBOM), cryptography (CBOM) and AI (AIBOM)
  inventories, in CycloneDX or SPDX. The format step is skipped for cryptography and
  AI bills, which the scanner can only express as CycloneDX JSON.

### Fixed

- The status-bar menu no longer renders most actions as disabled. The status bar's
  data context carries no project, so every action that consults it appeared greyed
  out.

### Security

- The scanner is never downloaded without being asked for. Fetching and running a
  binary is not something a plugin should do on its own, so the plugin offers, names
  the version, and waits — per version, so agreeing to one release is not agreement
  to every later one.
- Scanner downloads are pinned to HTTPS on the release host and capped at the size
  the manifest declares. The release manifest is data from the network and names an
  artifact the plugin then executes; the checksum alone cannot protect against a
  tampered manifest, since the same document supplies the hash, and it cannot be
  checked at all until a stream ends.
- The scanner no longer runs in a project that has not been trusted. It is a process
  spawned over project contents, so an untrusted project is not scanned and no scanner
  is downloaded for one.

### Changed

- Scans are cancellable. The scanner exposes no cancel command, so cancelling stops
  the wait rather than the scan, and says so.

[Unreleased]: https://github.com/mondoohq/mondoo-intellij-plugin/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/mondoohq/mondoo-intellij-plugin/commits/v0.1.0
