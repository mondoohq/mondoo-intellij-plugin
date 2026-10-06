# ADR-0002: Fixing in the IDE drives `xgrep fix serve`; the plugin only draws it

## Status

Accepted

## Date

2026-10-06

## Context

`xgrep fix` has a terminal UI that walks findings, records triage verdicts, previews
deterministic fixes, applies them, hands assisted findings to a coding agent
(Claude Code, Codex or a custom command) and opens a pull request. We want that in the
IDE as native UI.

The agent steering has to stay in xgrep, so the IDE and the terminal behave the same
and there is one implementation to improve:
- the prompts;
- the agent templates and their flags;
- trusting the re-scan rather than the agent's exit code;
- the PR flow.

The VS Code extension's earlier approach (vscode-mondoo ADR-0015, calling a model from
the extension) is the opposite of that.

Until now the plugin had only two ways to reach xgrep: the language server, and
one-shot CLI runs that wait for exit. Neither fits a session that runs a coding agent
for minutes, streams its output and must be cancellable.

## Decision

**1. xgrep serves the session; the plugin is a client.**
- xgrep gained `xgrep fix serve`, a Connect RPC service (`xgrep.fix.v1`; xgrep
  ADR-0877) with RPCs to list findings, rescan, preview, triage, run fixes (a server
  stream), open a PR and fetch graph context.
- The plugin has no fix logic of its own. It decides nothing about which findings can
  be fixed or how. It shows what the server says and sends what the user picks.

**2. A child process per project, not a daemon.** `FixServer` starts the server on
first use.
- It runs in the project root, after the same trusted-project gate as the language
  server.
- Before starting it, the plugin probes `xgrep fix serve --help`. An older xgrep
  does not reject the unknown subcommand: it reads `serve` as a path and runs `fix`
  on it.
- **Handshake.** The server announces its loopback port in one stdout line. The
  plugin accepts only `xgrep.fix.v1` on 127.0.0.1 or ::1.
- **Auth.** The plugin generates a random token and passes it in `XGREP_FIX_TOKEN`,
  never in argv.
- **Lifetime.** The server exits when its stdin closes, so it dies with the IDE.
- **Restarts.** It is replaced when it dies or when the binary, rules path or agent
  setting changes.
- **Why not a system daemon.** A system daemon, like `xgrep guard daemon`, would
  bring version skew with the downloaded binary. It also cannot run on Windows, and
  it would run agents outside the project the user trusted.

**3. Connect over the JDK HTTP client and Gson, with no new dependencies.**
- Connect's JSON protocol is plain HTTP: unary calls are JSON POSTs, and server
  streams are 5-byte envelopes. `ConnectProtocol` is about 150 lines and pure.
- connect-kotlin plus a protobuf runtime would add dependencies and risk clashing
  with the protobuf the IDE bundles.
- The client is pinned to HTTP/1.1. Closing a stream closes its connection, which is
  how cancelling the progress stops the agent on the server; the integration test
  checks this.

**4. Native UI, with the IDE's own safety around a run.**
- The **Fix** tab in the Mondoo tool window has:
  - a checkbox tree;
  - a details pane (verdict, fix plan, acceptance criteria);
  - an IntelliJ diff (the preview, or before/after once applied);
  - triage, graph context and pull request actions.
- An **xgrep fix** console tab streams the agent's output.
- Alt+Enter on an assisted finding offers **Fix with coding agent (xgrep)**.
- **Before a run** the plugin saves all documents, because xgrep edits files on disk,
  and puts a Local History label ("Before xgrep fix") in front of it, so the whole
  run can be undone.
- **After each FilesChanged event** it refreshes the files xgrep wrote.

## Consequences

- One implementation of agent steering for terminal and IDE. A new agent, a prompt
  fix or a PR change in xgrep reaches the IDE without a plugin release.
- Fixing needs an xgrep with `fix serve`. An older one gets a clear "update xgrep"
  message. `XgrepVersionPolicy.MINIMUM_VERSION` is not raised until that release is
  published, because the floor drives what the managed install downloads.
- The language server's own deterministic quick fix still bypasses the harness. The
  Fix tab is the verified path.
- Findings in the Fix tab come from the findings cache (`.xgrep/findings.json`), not
  from the language server. The two can differ until **Scan Project** refreshes the
  cache. Alt+Enter rescans when the cache does not have the finding yet.
