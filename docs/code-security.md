# Code security

Find and fix security issues while you write code, so they never reach review or
production.

Powered by [xgrep](https://mondoo.com/xgrep), Mondoo's software development security
scanner. There is no account, no login, and no configuration, and your code never
leaves your machine.

## Requirements

An IntelliJ-based IDE at **2026.1.4 or newer** (build `261.26222+`). Check with
**Help** → **About**: the part of the build number before the first dot must be 261 or
higher.

Nothing else. The scanner is found on your machine or downloaded on first use, with its
published checksum verified.

## See issues while you write

Open a file in a supported language and it is analysed immediately; findings update
as you type. They appear three places:

- **In the editor**, as highlights with the rule id in the message.
- **In the Problems view**, for the file you are looking at.
- **In the Mondoo tool window**, for the whole project — including files you have
  never opened, which is what a workspace scan produces and what the Problems view
  cannot show.

The status bar shows the project-wide finding count. Click it for the scanner menu.

Out of the box the scanner applies Mondoo's built-in security and secrets rules.
Point **Settings | Tools | Mondoo | Custom rules path** at a rule file or directory
to enforce your team's own rules, so the editor matches your pipeline.

## Check the whole project before you commit

Three actions cover code you do not have open, under **Tools | Mondoo Code Security**:

| Action | What it checks |
| --- | --- |
| Scan Workspace | Every file in the project |
| Scan Changed Files (Fast) | Only what is new or modified according to git |
| Scan Changes Since... | Only what changed since a git ref you pick |

The last is for reviewing a feature branch against `main` without scanning the whole
tree.

## Dismiss a finding that does not apply

False positives erode trust in every other finding, so dismissing one is two clicks
and leaves an audit trail. Put the caret on the finding and press Alt+Enter:

- **Suppress xgrep finding (nogrep)** inserts a comment above the line:

  ```python
  # nogrep: python-os-system
  os.system(cmd)
  ```

- **Suppress xgrep finding with reason...** records a justification in the same
  comment, so reviewers see *why* it was dismissed:

  ```python
  # input is validated upstream nogrep: python-os-system
  os.system(cmd)
  ```

The reason goes **before** the keyword. That is not cosmetic: rule ids run to the end
of the line, so a reason placed after the id is swallowed into it and the suppression
silently does nothing.

A dismissal made here holds everywhere — the same comments are understood by the
xgrep command line and by CI. Suppressing several rules on one line extends the same
comment (`nogrep: rule-one, rule-two`). JSON has no comments, so findings there
cannot be suppressed inline.

## Keep noise out of your results

Generated code, vendored dependencies and test fixtures produce findings nobody
intends to fix. Exclude them under **Settings | Tools | Mondoo | Scan scope**, one
glob per line:

```
src/generated/**
vendor
*.min.js
```

- `*` matches within one path segment, `**` spans segments, `?` matches one character.
- A pattern containing `/` matches the project-relative path.
- A pattern without `/` matches any single segment at any depth, so `vendor` excludes
  every vendor directory.

**Include only** does the opposite: when non-empty, only matching files are scanned.
An exclude still wins. Files ignored by `.gitignore` are never scanned.

On large repositories, **Scan parallelism** caps how many files on-demand scans process
at once. `0` uses the scanner's own default: at most four workers, and never more than
half your cores. That is deliberately conservative for an editor, so lowering it only
matters on a shared or heavily loaded machine.

Editor scanning is per file and is not affected by this setting.

## Search your code by structure

Text search cannot tell `eval(userInput)` from the word "eval" in a comment.
**Search Code...** matches on structure instead. `$X` binds any expression and `...`
matches anything:

```
eval($X)
```

Results open in the Find tool window, with grouping, preview and occurrence
navigation. **Export Search as Rule** turns the pattern into a reusable
xgrep rule, so a pattern worth finding once becomes a finding the scanner flags from
then on — point the custom rules path at it to enforce it.


### Replacing what you find

**Replace Code…** asks for a pattern and a replacement, then shows every match in the
Find tool window *before* anything is written. **Replace All** applies them as one
undoable command — a multi-file replace that undoes file by file leaves a state nobody
asked for.

Two things it does quietly and should be trusted on:

- Matches are applied last-first, because each edit shifts everything after it.
- Where a pattern matches inside its own match — `f($X)` against `f(f(1))` — only the
  outer one is rewritten. Applying both would splice one replacement into text the
  other had already replaced. The button says how many were skipped.

Ranges are recomputed against the file as it is when you press the button, not as it
was when the search ran, so edits made while the preview is open cannot corrupt it.

## Bring xgrep to your AI agent

- **Install AI Skills...** installs a skill from
  [mondoohq/skills](https://github.com/mondoohq/skills) — finding triage, code
  inspection, rule authoring, remediation, bulk fixes, secure coding, or MQL. It uses
  the `claude` CLI when that is on your PATH, and otherwise copies the equivalent
  `/plugin` commands for you to paste into an agent session.
- **Configure MCP Server...** registers xgrep as an MCP server so AI agents get its
  code graph, symbol inspection and scanning on demand. JetBrains has no API for a
  plugin to register one directly, so this writes the config file where the location
  is known and copies the JSON for you where it is not.

## Supported languages

Python, Go, Java, JavaScript, TypeScript (including React), Ruby, Rust, C, C++, C#,
Kotlin, Scala, PHP, Lua, shell scripts, HTML, JSON and YAML.

## Installation and troubleshooting

The plugin finds the scanner in this order: the **xgrep path** setting, its own
managed install, `xgrep` on your `PATH`, then common locations including Go's
`~/go/bin` for developers who build it themselves.

When nothing is found the plugin offers to download a release from Mondoo and tells
you which version; nothing is fetched until you accept. Choose **Install** and it
downloads, verifying the published SHA-256 before unpacking; **Not now** asks again
next time; **Never** turns the offer off. **Set Up Scanner** installs without asking,
because asking for it is what that action is.

You are asked again when a new version appears — agreeing to one release is not
agreement to every later one. Turn the offer off in advance by clearing **Offer to download and
update the scanner**, or disable the scanner entirely with **Enable the xgrep
security scanner**.

If the status bar shows **xgrep: set up**, the scanner could not be located. Click it,
or run **Set Up Scanner**. A bar also appears at the top of any file the scanner would
have checked, offering the same thing; dismiss it for good with **Don't show again**.
**Show xgrep Path** reports which binary is in use.

## Changing rules or settings

The rules path, scan parallelism and scan scope are read when the scanner starts, so
changing them prompts you to reload. **Reload Rules** does the same on demand, which is
what picks up edits to a rule file you are working on.

## Scope and limitations

Editor scanning analyses one file at a time, which is what keeps it fast enough to run
on every keystroke. Findings that require following data across files are the job of
`xgrep ci` in your pipeline. The editor and CI share the same rules and the same
suppression comments, so what you fix or dismiss here carries over.
