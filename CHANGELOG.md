# Changelog

All notable changes to the Mondoo plugin for JetBrains IDEs.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- Code security powered by [xgrep](https://mondoo.com/xgrep): findings as you type,
  shown in the editor, the Problems view, a dedicated Mondoo tool window grouped by
  severity and rule or by file, and a status-bar count.
- Workspace, changed-files and changes-since-a-git-ref scans.
- `nogrep` suppression quick fixes, with an optional recorded reason. The comment is
  honoured by the xgrep command line and by CI, so a dismissal is not editor-local.
- Structural code search, with results in the Find tool window, and export of a
  pattern as a reusable rule.
- Automatic scanner setup: xgrep is discovered or downloaded, with its published
  SHA-256 verified before unpacking. Nothing is downloaded before you ask.
- Scan scope settings (include/exclude globs), custom rules path, and scan parallelism.
- An in-editor setup banner when the scanner is unavailable on a file it would scan.
- **Reload Rules**, to pick up edits to a rule file.
- MCP server registration, and installation of Mondoo's agent skills from
  https://github.com/mondoohq/skills.
- **Clear Findings**, to empty the findings view after changing rules or scan scope.

### Changed

- Scans are cancellable. The scanner exposes no cancel command, so cancelling stops
  the wait rather than the scan, and says so.
