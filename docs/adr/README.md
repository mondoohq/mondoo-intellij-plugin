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
