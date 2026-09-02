# ADR-0001: Use the platform LSP API, registered under `serverSupportProvider`, behind an optional content module

## Status

Accepted

## Date

2026-09-01

## Context

`intellij-mondoo` ports the Mondoo VS Code extension to JetBrains IDEs. Both Mondoo
scanners expose stdio language servers (`xgrep lsp`, `cnspec lsp`), so the plugin is a
thin LSP client — the same principle as vscode-mondoo ADR-0001, where "all scanning,
rule loading, and fix computation happen in the xgrep binary".

The requirement is that the plugin work in **all** IntelliJ-based IDEs, including
Android Studio and Community builds. That collides with the history of the platform
LSP API, which was commercial-IDE-only. Two JetBrains sources disagree:

- The SDK docs still state that plugins using LSP integration "are not available in
  IntelliJ IDEA open source builds and Android Studio from Google", and the
  September 2025 post is explicit that "Community Edition (CE) will still be available
  in 2025.2 but will not include LSP support."
- The June 2026 post announces the LSP client API is open-sourced and becomes
  "available to JetBrains IDEs, Android Studio, and other products based on the public
  IntelliJ Open Source project", landing in "the 2026.1.4 stable build, not only
  2026.2" — while cautioning that "Android Studio support **may** arrive earlier than
  2026.2".

The alternative client is LSP4IJ (Red Hat, EPL-2.0, IDEA 2024.2+), which xgrep's own
docs currently recommend to JetBrains users.

Rather than resolve this from documentation, we inspected the shipped IDEs directly.

## Evidence

Two rounds. The first, static, led to a **wrong conclusion**; the second, runtime,
corrected it. Both are recorded because the mistake is easy to repeat.

### Round 1 — inspecting the shipped jars (misleading)

Extension points and modules read from `intellij.platform.lsp.impl.xml`, and API classes
listed from `intellij.platform.lsp.jar`, in the installed products:

| IDE | Build | `intellij.platform.lsp*` jars | EPs declared |
|---|---|---|---|
| Android Studio Quail 4 | `AI-261.26222.65` | present | `integrationProvider` **and** `serverSupportProvider` |
| GoLand 2026.2 | `GO-262.9437.286` | present | both |
| GoLand 2026.2 EAP | `GO-262.6228.35` | present | **only `serverSupportProvider`** |
| IntelliJ IDEA CE 2025.2 | `IC-252.23892.409` | **absent** — only `eclipse.lsp4j.jar` | — |

From this we concluded Android Studio would work. **That conclusion was wrong.** The jars
being on disk says nothing about whether the module is *exposed to plugins*.

### Round 2 — running the real plugin, first attempt (also wrong)

The same plugin launched in a sandbox against each IDE, with a probe in the optional
module that logs when it loads. Android Studio produced no line, and we concluded it does
not expose the module.

**That was a false negative**, from two compounding harness bugs:

- The probe was a `ProjectActivity`, so it only fires once a project finishes opening.
- Android Studio blocked on a modal **Trust Project** dialog, so no project ever finished
  opening — and the run was cancelled while that dialog sat there.

"No log line" therefore meant "the probe never ran", not "the module is missing". The two
are indistinguishable unless the probe is independent of project state.

### Round 3 — a probe that cannot produce a false negative (authoritative)

Fixes: the probe moved to `ApplicationInitializedListener` (fires with no project open),
and probe runs set `-Didea.trust.all.projects=true` so no modal dialog can block startup.

Static confirmation first — `product-info.json` in each product lists what it actually
includes, and both declare the module identically:

```
('com.intellij.modules.lsp', 'pluginAlias')
('intellij.platform.lsp',      'productModuleV2')
('intellij.platform.lsp.impl', 'productModuleV2')
```

Then the runtime result, end to end, opening a real file:

| IDE | Build | Plugin loads | LSP module loads | `xgrep lsp` starts |
|---|---|---|---|---|
| Android Studio Quail 4 | `AI-261.26222.65` | yes | **yes** | **yes** — initialized in 1.124 s |
| GoLand 2026.2 | `GO-262.9437.286` | yes | **yes** | **yes** — initialized in 0.593 s |

```
XgrepLspServerDescriptor@mondoo-lsp-probe(Initializing;0): LSP server process started: .../xgrep lsp
XgrepLspServerDescriptor@mondoo-lsp-probe(Running;0): LSP server initialized, name = xgrep
```

**Android Studio works.** The SDK docs' claim that LSP integration is unavailable there is
out of date as of Quail 4 / platform 261.26222.

### The v2 content-module trap

The first implementation used a v2 optional content module:

```xml
<content><module name="com.mondoo.intellij.lsp" loading="optional"/></content>
```

That failed in **both** IDEs with:

```
module com.intellij.modules.lsp (namespace=jetbrains) is not resolved
  └ dependent module com.mondoo.intellij.lsp (namespace=...) excluded
```

`com.intellij.modules.lsp` is a plugin-*alias* module in the `jetbrains` namespace with
internal visibility, and a v2 content module cannot depend on one. The legacy optional
dependency does resolve it, and is what we ship:

```xml
<depends optional="true" config-file="mondoo-lsp.xml">com.intellij.modules.lsp</depends>
```

This is the one genuine defect the spike caught: shipping the content-module form would
have left live scanning silently dead in every IDE, with no error anywhere.

### Lessons, since two of three rounds reached the wrong answer

1. **Jar presence proves nothing** about whether a module is exposed to plugins. Check
   `product-info.json`, or better, run the plugin.
2. **A probe that depends on project open cannot distinguish "absent" from "never ran".**
   Application-level, or it is not evidence.
3. **Modal dialogs silently invalidate headless-looking runs.** Always set
   `idea.trust.all.projects` for automated IDE runs.
4. Give throwaway probe projects an **obvious name** (`mondoo-lsp-probe`). A dialog about
   a project called `lsproj` gets declined, and the run dies for a reason nobody records.

## Decision

1. **Use the platform LSP API**, not LSP4IJ. It is present in every product we need at
   the 2026.1.4 floor, and it requires no third-party plugin dependency.
2. **`sinceBuild = 261.26222`**, `untilBuild` left open. The CE 2025.2 row above shows
   why a lower floor is not possible.
3. **Register under `com.intellij.platform.lsp.serverSupportProvider`**, not
   `integrationProvider`. This deviates from the SDK docs deliberately: the GoLand EAP
   row shows `integrationProvider` can be absent from builds *above* our floor, and
   `untilBuild` is open, so a plugin registering only under the newer EP would silently
   fail to start its server there. Nothing is lost, since the newer interface is a
   supertype.
4. **Make the LSP integration an optional dependency**, via the legacy
   `<depends optional="true" config-file="mondoo-lsp.xml">com.intellij.modules.lsp</depends>`
   form — *not* a v2 content module, for the namespace reason above. The core plugin
   depends only on `com.intellij.modules.platform`. Settings, binary management, the tool
   window, actions, the status bar and MCP all keep working where the LSP module is
   absent; only live-as-you-type diagnostics degrade there, and a `xgrep scan --json` CLI
   path covers scanning.

   Android Studio does expose the module (round 3), so this is insurance rather than a
   workaround — but it is nearly free, and the v2 form we tried first was actively broken.
5. **Depend on no product-specific module.** No `com.intellij.modules.java`, `.python`,
   `.go`. This is what makes the Marketplace mark the plugin compatible with all
   products, and it is enforced by the Plugin Verifier matrix in CI.

## Consequences

- The plugin installs and runs in every current IntelliJ-based IDE, **Android Studio
  included**, with live diagnostics everywhere — verified end to end in both Android
  Studio Quail 4 and GoLand 2026.2.
- The optional dependency is retained anyway: it costs one descriptor file and keeps the
  plugin loadable if some future product ships without the module. It is cheap insurance,
  not a workaround for a known gap.
- File scoping must be keyed off file name / extension, never off IntelliJ `Language`:
  GoLand has no Python language and Android Studio has no Go language, so a `.py` or
  `.go` file resolves to `PlainTextLanguage` there. See `XgrepLanguages`.
- Point 4 costs one extra descriptor file and a CLI fallback path we would want anyway.
- Users below 2026.1.4 cannot install the plugin. Accepted: there is no existing user
  base to strand, and the alternative (LSP4IJ) adds a permanent third-party dependency.

## Alternatives considered

- **LSP4IJ with a 2024.2 floor.** Widest reach and works in every flavour today. Rejected
  because the evidence shows the platform API now covers the same products, and a bundled
  client means one less moving part and no dependency the Marketplace must co-install.
- **Hard `<depends>com.intellij.modules.lsp</depends>`.** Simpler, and the evidence says
  it would work today. Rejected because it makes the *entire* plugin fail to load on any
  host without the module, for no saving over an optional content module.
- **Registering under `integrationProvider`** as the docs recommend. Rejected on the EAP
  evidence above.

## Follow-ups

- Settle whether IDEA Community 2026.1.4 carries the module via the `verifyPlugin`
  matrix (`IntellijIdeaCommunity` is already in it). Non-blocking either way.
- Update `xgrep/docs/06-ide/editors.md`, which currently tells JetBrains users to
  configure LSP4IJ by hand, to point at this plugin.

## Appendix: xgrep LSP wire format, captured 2026-09-01

Captured from `xgrep lsp` (v0.54.0-3-gffb6d1eab) via a minimal stdio client performing
`initialize` + `didOpen` against `vscode-mondoo/resources/demo/welcome.js`.

Server capabilities:

```json
{
  "textDocumentSync": { "openClose": true, "change": 1, "save": { "includeText": true } },
  "codeActionProvider": { "codeActionKinds": ["quickfix"] },
  "executeCommandProvider": {
    "commands": ["xgrep.scanWorkspace", "xgrep.scanChanged", "xgrep.search", "xgrep.exportRule"]
  }
}
```

Notes:

- `change: 1` is **full** document sync — the server re-scans whole buffer contents.
- There is no `diagnosticProvider`, so diagnostics are push-only (`publishDiagnostics`);
  pull diagnostics are not available and must not be advertised.
- No completion / hover / definition / formatting providers. `LspCustomization` should
  disable those client-side so the IDE never advertises or requests them — this is what
  keeps the scanner from competing with GoLand's or PyCharm's own language support.
- `initializationOptions: {"scanJobs": 2}` was accepted without error.

A representative diagnostic, confirming the custom `data` payload the findings view and
quick fixes depend on:

```json
{
  "range": { "start": { "line": 27, "character": 11 }, "end": { "line": 27, "character": 25 } },
  "severity": 1,
  "code": "js-express-command-injection",
  "codeDescription": { "href": "https://cheatsheetseries.owasp.org/..." },
  "source": "xgrep",
  "message": "User input from an Express request flows into a command execution function...",
  "data": {
    "ruleId": "js-express-command-injection",
    "cwe": ["CWE-78: Improper Neutralization of Special Elements used in an OS Command..."],
    "owasp": ["A03:2021", "A05:2025"],
    "references": ["https://cheatsheetseries.owasp.org/..."],
    "hasFix": false,
    "fixKind": "assisted"
  }
}
```

The shape matches what vscode-mondoo's `xgrepExplainProvider.ts` reads, so the payload
contract has held across the 0.11 → 0.54 range. Re-capture on every xgrep bump: the
`data` payload, the `window/showMessage` scan-completion text, and
`executeCommandProvider.commands` are the three things worth re-checking.

## Appendix: suppression format, verified against xgrep 2026-09-01

Verified by generating each form and running `xgrep scan --json --no-cache` against it.
Suppressed findings are **retained** with `is_ignored` set rather than dropped, so the
CLI fallback path must filter on that flag; the LSP server already hides them.

| Form | Suppresses |
| --- | --- |
| `# nogrep: <rule>` on the line above | yes |
| `# nogrep: <rule>` trailing on the same line | yes |
| `# nogrep: <rule-a>, <rule-b>` | yes, both |
| `# <reason> nogrep: <rule>` — reason **before** the keyword | yes |
| `# nogrep: <rule> — <reason>` — reason **after** the id | **no** |

The last row is the constraint `xgrepSuppressionProvider.ts` encodes and that this port
preserves: a rule id runs to the end of the line, so a trailing reason is swallowed into
the id and matches nothing. The suppression then silently does nothing — the worst
possible failure mode for a dismissal feature.

**Upstream bug:** `xgrep/docs/09-dspm/index.md:180` documents exactly this broken form:

```go
// nogrep: go-classified-data-egress — audit connection legitimately needs the credential
```

That example does not suppress the finding. Worth filing against xgrep, along with the
suggestion in ADR-0001's follow-ups that the LSP server offer `nogrep` as a server-side
code action — which would let both editors delete their comment-building code and remove
this whole class of divergence.

## Appendix: structural search commands, verified 2026-09-01

`workspace/executeCommand` against the running server, confirming the contract the
Code Search feature depends on.

**`xgrep.search`** — arguments `[pattern, language]`, or `[pattern, language, replacement]`.
Returns a JSON **array** synchronously (it answers a query rather than publishing
diagnostics, so the client renders the results itself):

```json
{
  "path": "/abs/path/vuln.py",
  "line": 4, "col": 5, "endLine": 4, "endCol": 19,
  "text": "    os.system(cmd)",
  "replacement": "subprocess.run(cmd, shell=False)"
}
```

`replacement` is present only when a replacement argument was supplied, with
`$metavariables` already substituted.

**Positions are 1-based.** `line: 4, col: 5` is the `os` of a four-space-indented
`    os.system(cmd)`. IntelliJ documents and offsets are 0-based, so every value must be
decremented on the way in. This is exactly the kind of off-by-one that produces
plausible-looking but wrong highlight ranges, so it is unit-tested in
`XgrepSearchMatchTest`.

**`xgrep.exportRule`** — same arguments, returns a YAML **string**:

```yaml
rules:
    - id: search-rule
      languages:
        - python
      severity: WARNING
      message: matches the search pattern
      pattern: os.system($X)
      fix: subprocess.run($X, shell=False)
```

`fix:` appears only when a replacement was supplied. The generated rule can be fed back
through the `xgrepRulesPath` setting, which is the round trip verification step 8 checks.

## Appendix: toolchain facts, measured 2026-09-01

Both were guessed wrong initially and corrected by measurement, so they are recorded
here rather than left to the next person to rediscover.

| Fact | Value | How it was established |
| --- | --- | --- |
| Platform bytecode target | **Java 21** (class file major 65) | `od` on `LspServerDescriptor.class` from Android Studio's `intellij.platform.lsp.jar` |
| Bundled JBR | 25 (Android Studio, GoLand 2026.2); 21 (IDEA CE 2025.2) | `jbr/Contents/Home/bin/java -version` |
| Kotlin metadata version | **2.3.0** | `compileKotlin` against `idea-2026.1.4`: *"binary version of its metadata is 2.3.0, expected version is 2.1.0"* |

The JBR being 25 while the class files are 21 is the trap: matching the runtime rather
than the bytecode target would pick the wrong toolchain. `gradle.properties` therefore
pins `javaVersion = 21`.

The Kotlin version must be **2.3.0 or newer**. Anything older fails `compileKotlin`
outright — every platform `.kotlin_module` is rejected, ending in an internal compiler
error rather than a clear message, which makes the root cause easy to misread.

## Appendix: milestone 7 verification gate, 2026-09-02

Phase 1 acceptance, run against the real scanner and a real IDE rather than mocks.

| # | Check | Result |
| --- | --- | --- |
| 5 | Findings appear; no errors | GoLand 2026.2, `vuln.py`: plugin loaded, LSP module loaded, `xgrep lsp` started and initialized in 0.7 s, **0 ERROR lines**. Note the file is Python in a Go IDE — the cross-IDE case the extension-keyed language table exists for. |
| 6 | Suppression holds in the CLI | The exact comment the intention inserts (`# validated upstream nogrep: python-command-injection`) removes that rule from `xgrep scan` active findings while leaving the other. |
| 7 | Workspace and changed-files scans | `xgrep: workspace scan found 3 finding(s) in 1 file(s)` and `xgrep: changed-files scan found 4 finding(s) in 2 file(s)`; both match the completion regex, both publish diagnostics. |
| 8 | Structural search round trip | Exported rule fed back via `-f` fires as `search-rule`. |
| 9 | Binary bootstrap | Covered at milestone 1: with PATH and `~/go/bin` hidden, the plugin downloaded, hash-verified and installed 0.57.0, then restarted the server against it. |
| 10 | `verifyPlugin` | **Compatible** against Android Studio Quail 4 (`AI-261.26222.65`) and GoLand 2026.2 (`GO-262.9437.286`). |

The suppression check is the one worth keeping in any future regression suite: it is
the only one that proves the plugin and the CLI agree on a format, and a silent
disagreement there means dismissals stop working with no error anywhere.

### What the verifier caught

Two real problems, both of which would have surfaced at publish time or later:

1. **The plugin ID may not contain the word "intellij".** `com.mondoo.intellij` was
   rejected outright by Marketplace rules. Renamed to **`com.mondoo.security`** before
   anything was published — changing it after release would orphan every install. The
   Kotlin package stays `com.mondoo.intellij`, which is unaffected.
2. **`IntellijIdeaCommunity` has no 2026.1.4 download.** IDEA moved to a unified
   distribution in 2025.3, so there is no separate Community product at 261. This also
   closes this ADR's open question about whether Community exposes the LSP module:
   there is no Community build at that version to expose it.

The local matrix filters by build number against `pluginSinceBuild`. An older IDE left
on disk (a 2025.2 install, say) has no LSP module and reports a compatibility problem
for a version the plugin does not claim to support — a false failure that would hide
real ones.

Remaining verifier notes are advisory, not problems: 31 deprecated-API usages, dominated
by the deliberate `LspServerSupportProvider` choice above, plus `startServersIfNeeded`.
Those are the cost of supporting builds that lack the renamed API, and are revisited
when the floor rises.

## Appendix: didSave, checked 2026-09-02

Some IntelliJ LSP clients hand-send `textDocument/didSave` from a VFS listener, because
on older platforms `clientCapabilities.textDocument.synchronization.didSave` could be
false and the platform would not send it. xgrep advertises
`textDocumentSync.save.includeText = true` and rescans on save, so it is worth knowing
whether we need that workaround.

We do not. On platform 261/262 the capability is **true**, verified by logging it from
`serverInitialized` in a sandbox GoLand run. The platform sends `didSave` itself, so the
save-triggered rescan works unaided.

Re-check only if the supported floor ever moves backwards, which it should not.
