# Mondoo for JetBrains IDEs

<!-- Plugin description -->
**Find and fix security issues while you write code.** Vulnerabilities and leaked
secrets appear in the editor as you type, with one-click fixes.

No account, no configuration, and your code never leaves your machine.
<!-- Plugin description end -->

![Findings in the editor and the Mondoo tool window](docs/images/plugin-overview.png)

## What you get

- **Security findings as you type** — in the editor, grouped by severity in the Mondoo
  tool window, counted in the status bar.
- **One-click fixes** — apply a fix, or dismiss a false positive with a reason that
  holds in CI too.
- **Scans before you commit** — the whole project, only what git says changed, or only
  what changed since a branch.
- **Dependency triage that starts with what matters** — which packages your code
  actually imports, so you are not chasing vulnerabilities in code that never runs.
- **Bills of materials** — software, cryptography and AI inventories, ready for
  compliance.
- **Policy authoring** — completion, hover, diagnostics, linting and formatting for
  Mondoo policy bundles.

Python, Go, Java, JavaScript, TypeScript, Ruby, Rust, C, C++, C#, Kotlin, Scala, PHP,
Lua, shell, HTML, JSON and YAML.

## Install

Requires an IntelliJ-based IDE **2026.1.4 or newer** — IntelliJ IDEA, GoLand, PyCharm,
WebStorm, PhpStorm, RubyMine, CLion, Rider, RustRover, DataGrip or Android Studio.

1. Download the ZIP from the
   [latest release](https://github.com/mondoohq/mondoo-intellij-plugin/releases/latest).
2. **Settings** → **Plugins** → **⚙** → **Install Plugin from Disk…**
3. Restart.

Then open a file. Findings appear on their own — the scanner is downloaded on first use
and verified against its published checksum.

To see it working straight away: **Tools** → **Mondoo Code Security** → **Open Demo
File**.

## Documentation

| | |
| --- | --- |
| [Code security](docs/code-security.md) | Findings, scans, suppressions, search |
| [Dependencies](docs/dependencies.md) | Which dependencies your code really uses |
| [Bill of materials](docs/bill-of-materials.md) | SBOM, CBOM and AIBOM |
| [Infrastructure security](docs/infrastructure-security.md) | Writing and linting MQL policies |
| [Installing a dev build](docs/install-dev-build.md) | Running an unreleased build |
| [Contributing](CONTRIBUTING.md) · [Releasing](RELEASING.md) · [Decisions](docs/adr/) | Working on the plugin |

## Status

Code security, dependencies, bills of materials and policy authoring are complete.
Running policies against infrastructure targets is next.

Verified in Android Studio and GoLand. See
[ADR-0001](docs/adr/0001-lsp-client-and-ide-compatibility.md) for compatibility detail.

## Help

- Issues: [GitHub Issues](https://github.com/mondoohq/mondoo-intellij-plugin/issues)
- Docs: [mondoo.com/docs](https://mondoo.com/docs)
- Community: [Discussions](https://github.com/orgs/mondoohq/discussions) ·
  [Slack](https://mondoo.link/slack)
- Security: [SECURITY.md](SECURITY.md)

Apache 2.0 — see [LICENSE](LICENSE).
