# Mondoo for JetBrains IDEs

Catch security issues while you write code, without leaving your IDE.

Powered by [xgrep](https://mondoo.com/xgrep), Mondoo's software development security
scanner: findings for vulnerabilities and leaked secrets appear as you type, with
one-click fixes and suppressions. No account, no configuration, and your code never
leaves your machine.

This is the JetBrains counterpart to the
[Mondoo VS Code extension](https://github.com/mondoohq/vscode-mondoo).

## Compatibility

Requires **2026.1.4 or later** (build `261.26222+`) of any IntelliJ-based IDE —
IntelliJ IDEA, GoLand, PyCharm, WebStorm, PhpStorm, RubyMine, CLion, Rider, RustRover,
DataGrip, and Android Studio. The plugin declares no product-specific modules, so it
installs everywhere.

Live scanning is verified end to end in **Android Studio Quail 4** and **GoLand 2026.2**;
see [ADR-0001](docs/adr/0001-lsp-client-and-ide-compatibility.md).

The 2026.1.4 floor is where the platform LSP client API became available outside the
commercial IDEs.

## Status

Early development. Roadmap:

1. **Code security (xgrep)** — live findings, workspace and changed-file scans, fix and
   suppress quick fixes, structural search. *In progress.*
2. **Infrastructure security (cnspec/MQL)** — policy authoring, MQL language support,
   scans against local, SSH, Docker, Kubernetes and cloud targets.
3. **Bill of materials** — SBOM and AIBOM generation.

## Development

See [DEVELOPMENT.md](DEVELOPMENT.md).
