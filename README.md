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
its bundled skills for agents that support them.

Supported languages: Python, Go, Java, JavaScript, TypeScript (including React), Ruby,
Rust, C, C++, C#, Kotlin, Scala, PHP, Lua, shell scripts, HTML, JSON and YAML.
<!-- Plugin description end -->

## Requirements

Just the IDE. The plugin finds an existing `xgrep` on your machine, and otherwise
downloads one — verifying the published SHA-256 before unpacking. Nothing is downloaded
before you ask: leave automatic download on, or run **Set Up Scanner**.

## Compatibility

Requires **2026.1.4 or later** (build `261.26222+`) of any IntelliJ-based IDE:
IntelliJ IDEA, GoLand, PyCharm, WebStorm, PhpStorm, RubyMine, CLion, Rider, RustRover,
DataGrip and Android Studio.

That floor is where the platform LSP client API became available outside the commercial
IDEs. The plugin declares no product-specific modules, so it installs everywhere, and
LSP-backed scanning sits behind an optional dependency so it still loads in any product
that lacks the module.

Live scanning is verified end to end in **Android Studio Quail 4** (`AI-261.26222.65`)
and **GoLand 2026.2** (`GO-262.9437.286`), and the Plugin Verifier reports *Compatible*
against both. The evidence, including two mistakes made on the way to it, is in
[ADR-0001](docs/adr/0001-lsp-client-and-ide-compatibility.md).

## Installation

Not yet on the JetBrains Marketplace. To install a local build:

```bash
./gradlew buildPlugin
```

then **Settings | Plugins | ⚙ | Install Plugin from Disk…** and pick
`build/distributions/*.zip`.

## Getting started

Open a file in a supported language — findings appear on their own. If you would rather
see it on something deliberately broken, run **Tools | Mondoo Code Security | Open Demo
File**.

Every action lives under **Tools | Mondoo Code Security**, in the status-bar menu, and
in Find Action (⇧⌘A / Ctrl+Shift+A).

## Documentation

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
- **Community**: [Mondoo discussions](https://github.com/orgs/mondoohq/discussions)
