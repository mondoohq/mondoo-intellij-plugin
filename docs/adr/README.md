# Architecture Decision Records

Records of the decisions that shaped this plugin, and the evidence behind them. Same
convention as [vscode-mondoo](https://github.com/mondoohq/vscode-mondoo/tree/main/docs/adr):
one file per decision, numbered, never rewritten once accepted — supersede instead.

| ADR | Title |
| --- | ----- |
| [0001](0001-lsp-client-and-ide-compatibility.md) | Use the platform LSP API, registered under `serverSupportProvider`, behind an optional content module |

## Related decisions in vscode-mondoo

The behaviour this plugin ports is specified there. The most load-bearing:

- **ADR-0001** — integrate xgrep via its LSP server; the extension stays a thin client.
- **ADR-0002** — finding suppression, exclude patterns, and the status bar contract.
- **ADR-0015** — AI-assisted autofix (the author → verify → apply loop).
- **ADR-0016** — xgrep version resolution: trust the release manifest, not a mutable tag.

## Known considerations, not yet decided

### Workspace trust

The VS Code extension refuses to run in untrusted workspaces
(`capabilities.untrustedWorkspaces.supported: false`), because it executes scanner
binaries against workspace files. This plugin does not gate on
`TrustedProjects.isTrusted`, and that is a deliberate, revisitable position:

- **Phase 1 risk is low.** xgrep parses project files; it does not execute
  project-provided code. The rules path is a machine-level setting, so an untrusted
  project cannot inject rules. The exposure is comparable to the IDE's own parsers
  running over the same files.
- **Phase 2 changes that.** cnspec executes policies, reads project-provided
  `.mql.yaml` bundles and connects to targets with credentials. Trust gating becomes
  mandatory before that ships, not optional.
- **The obvious implementation is not available.** Both trust APIs
  (`com.intellij.ide.impl.TrustedProjects` and `com.intellij.ide.trustedProjects.TrustedProjects`)
  live in `intellij.platform.ide.impl`, which is not on the compile classpath, and
  `bundledModule("intellij.platform.ide.impl")` does not put it there. Reaching it
  reflectively would produce a gate that fails open silently if the class moves —
  worse than no gate, because it invites the belief that one exists.

Resolve this before phase 2 by finding the supported API for the check, rather than by
adding a fragile one now.
