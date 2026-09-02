# Mondoo for JetBrains IDEs

<!-- Plugin description -->
Catch security issues while you write code, without leaving your IDE.

Powered by [xgrep](https://mondoo.com/xgrep), Mondoo's software development security
scanner. Findings for vulnerabilities and leaked secrets appear as you type. There is
no account, no login and no configuration, and your code never leaves your machine.

This is the JetBrains counterpart to the
[Mondoo VS Code extension](https://github.com/mondoohq/vscode-mondoo).

## What you get

**Findings as you type.** Open a file in a supported language and it is analysed
immediately. Findings show up as editor highlights carrying the rule id, in the
Problems view for the current file, and in the **Mondoo** tool window for the whole
project — grouped by severity and rule, or by file. The status bar shows the
project-wide count.

**Scans for code you do not have open.** Scan the whole workspace, only what git
reports as changed, or only what changed since a ref you pick — the last for reviewing
a branch against `main` without walking the whole tree.

**Dismissals that hold in CI.** Alt+Enter on a finding suppresses it with a `nogrep`
comment, optionally recording *why*. The same comment is honoured by the xgrep command
line and by your pipeline, so a dismissal made here is not editor-local.

**Structural search.** `eval($X)` matches every call to `eval` whatever the argument,
and ignores the word in a comment. Results open in the Find tool window. Export a
pattern as a reusable rule and point the scanner at it.

**Your AI agent, with xgrep's analysis.** Register xgrep as an MCP server, and install
[Mondoo's agent skills](https://github.com/mondoohq/skills) — triage, code inspection,
rule authoring, remediation and secure coding.

Supported languages: Python, Go, Java, JavaScript, TypeScript (including React), Ruby,
Rust, C, C++, C#, Kotlin, Scala, PHP, Lua, shell scripts, HTML, JSON and YAML.

**Requires an IntelliJ-based IDE version 2026.1.4 or newer** (build `261.26222` or
later). Nothing else — the scanner is found or downloaded automatically.
<!-- Plugin description end -->

## Requirements

### IDE version

**2026.1.4 or newer — build `261.26222` or later.** This is a hard minimum. An older IDE
will refuse to install the plugin rather than installing a broken one, and the
Marketplace will not offer it.

To check yours: **Help** → **About**. The build number is the `IU-`/`GO-`/`AI-` prefixed
line, and the part before the first dot must be **261 or higher**.

| IDE | Minimum |
| --- | --- |
| IntelliJ IDEA, GoLand, PyCharm, WebStorm, PhpStorm, RubyMine, CLion, Rider, RustRover, DataGrip | 2026.1.4 |
| Android Studio | Quail 4 (`AI-261.26222.65`) or newer |

The floor is not arbitrary: 2026.1.4 is the first build where the platform LSP client
API is available outside the commercial IDEs, and that API is how the plugin talks to
the scanner. Below it there is no working implementation to fall back to.

The plugin declares no product-specific modules, so it installs in every
IntelliJ-based IDE, and LSP-backed scanning sits behind an optional dependency so it
still loads in a product that lacks the module.

Live scanning is verified end to end in **Android Studio Quail 4** (`AI-261.26222.65`)
and **GoLand 2026.2** (`GO-262.9437.286`), and the Plugin Verifier reports *Compatible*
against both. The evidence, including two mistakes made on the way to it, is in
[ADR-0001](docs/adr/0001-lsp-client-and-ide-compatibility.md).

### Operating system

macOS, Linux and Windows, on x86-64 or ARM64.

### The scanner

Nothing to install by hand. The plugin uses an existing `xgrep` if it finds one — the
configured path, your `PATH`, or common locations including `~/go/bin` — and otherwise
downloads a release, verifying the published SHA-256 before unpacking.

Nothing is downloaded before you ask: leave automatic download on, or run **Set Up
Scanner**.

You do **not** need Node, Python, or a JDK. The IDE's own runtime is used.

## Installation

Not on the JetBrains Marketplace yet, so install from a release ZIP.

### From a GitHub release

1. Download `intellij-mondoo-<version>.zip` from the
   [latest release](https://github.com/mondoohq/mondoo-intellij-plugin/releases/latest).
   Take the ZIP itself — not "Source code (zip)".
2. In your IDE: **Settings/Preferences** → **Plugins** → the **⚙** gear icon →
   **Install Plugin from Disk…**
3. Select the downloaded ZIP.
4. **Restart** when prompted.

That is the whole process — no build tools, and nothing else to install. Works the same
in GoLand, IntelliJ IDEA, PyCharm, WebStorm, PhpStorm, RubyMine, CLion, Rider,
RustRover, DataGrip and Android Studio.

> No releases published yet. Until the first one, build it yourself as below.

To update later, install the newer ZIP the same way; it replaces the old version. To
remove it: **Settings** → **Plugins** → **Installed** → **Mondoo** → gear →
**Uninstall**.

### From source

```bash
git clone https://github.com/mondoohq/mondoo-intellij-plugin.git
cd mondoo-intellij-plugin
./gradlew buildPlugin
# -> build/distributions/intellij-mondoo-<version>.zip
```

Then follow steps 2–4 above with that ZIP.

To try it without touching the IDE you work in, `./gradlew runGoLand` launches a
sandboxed GoLand with the plugin already loaded. See
**[Installing a development build](docs/install-dev-build.md)** for the other IDEs, how
to confirm it loaded, and what the log lines mean.

### Checking it worked

**Tools** → **Mondoo Code Security** should exist. Open a file in a supported language;
findings appear in the editor and in the **Mondoo** tool window.

## Getting started

Open a file in a supported language — findings appear on their own. If you would rather
see it on something deliberately broken, run **Tools | Mondoo Code Security | Open Demo
File**.

Every action lives under **Tools | Mondoo Code Security**, in the status-bar menu, and
in Find Action (⇧⌘A / Ctrl+Shift+A).

## Documentation

- **[Installing a development build](docs/install-dev-build.md)** — running an
  unreleased build in GoLand or any other JetBrains IDE.
- **[Code security](docs/code-security.md)** — findings, scans, suppressions, scan
  scope, structural search, AI agents, troubleshooting.
- **[Architecture decisions](docs/adr/)** — why the plugin is built the way it is.
- **[DEVELOPMENT.md](DEVELOPMENT.md)** — building, testing and the cross-IDE rules.
- **[RELEASING.md](RELEASING.md)** — how a release is cut and published.

## Status

Phase 1, code security, is feature-complete and has passed its verification gate.
Phases 2 and 3 are not started:

| Phase | Scope | State |
| --- | --- | --- |
| 1 | **Code security** — findings, scans, suppressions, structural search, AI agents | Complete |
| 2 | **Infrastructure security** — MQL language support, policy authoring, scans against local, SSH, Docker, Kubernetes and cloud targets | Planned |
| 3 | **Bill of materials** — SBOM and AIBOM generation | Planned |

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md). Security reports go to
[SECURITY.md](SECURITY.md), not to the issue tracker.

## License

Apache License 2.0 — see [LICENSE](LICENSE).

## Getting help

- **Issues**: [GitHub Issues](https://github.com/mondoohq/mondoo-intellij-plugin/issues)
- **Documentation**: [mondoo.com/docs](https://mondoo.com/docs)
- **Community**: [GitHub Discussions](https://github.com/orgs/mondoohq/discussions) or
  [Mondoo Community Slack](https://mondoo.link/slack)
