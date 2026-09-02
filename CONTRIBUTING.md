# Contributing

Thanks for helping improve the Mondoo plugin for JetBrains IDEs.

Questions and ideas are welcome in
[Mondoo discussions](https://github.com/orgs/mondoohq/discussions).

## Before you start

[DEVELOPMENT.md](DEVELOPMENT.md) covers building, running against a local IDE, and the
testing tiers. Two rules there are worth repeating because breaking either is easy and
the failure is silent:

1. **Depend only on `com.intellij.modules.platform`.** Adding a product-specific module
   or a `bundledPlugin` requirement quietly narrows the plugin to a subset of IDEs.
   `./gradlew verifyPlugin` is the guard; CI runs it.
2. **Scope files by name or extension, never by IntelliJ `Language`.** GoLand has no
   Python plugin and Android Studio has no Go plugin, so those files resolve to plain
   text there. A `Language`-keyed lookup works on your machine and fails for users.

## Tests

```bash
./gradlew test          # tier 1: pure, no IDE, fast
./gradlew verifyPlugin -PverifyLocal=true
```

Tier 1 tests take plain data — never `Project`, `VirtualFile` or `Editor`. That
constraint is what keeps the suite fast, so please keep new logic on the right side of
it: put the decision in a pure function and the platform wiring around it.

No test may spawn a real scanner binary, reach the network, or start a language server.

## Commits and pull requests

- `main` is PR-only.
- Conventional commit subjects (`feat(scan): ...`, `fix(ci): ...`).
- Explain *why* in the body. The repository's architecture decisions live in
  [docs/adr/](docs/adr/); if a change contradicts one, update the ADR in the same PR.
- Add a `CHANGELOG.md` entry under `Unreleased` for anything a user would notice. The
  Marketplace release notes are generated from it.

## Verifying against the real scanner

Behaviour shared with the `xgrep` command line — the suppression comment format above
all — should be checked against the real binary rather than assumed, because a silent
disagreement means dismissals stop working with no error anywhere. DEVELOPMENT.md
describes how.

## Licensing

This plugin is Apache 2.0, and every source file carries an SPDX header:

```kotlin
// Copyright Mondoo, Inc. 2026
// SPDX-License-Identifier: Apache-2.0
```

`.copywrite.hcl` records the convention. Note this differs from cnspec, which is
BUSL-1.1 — the plugin is deliberately more permissive, since it is a thin client that
people embed in their own IDE setups.
